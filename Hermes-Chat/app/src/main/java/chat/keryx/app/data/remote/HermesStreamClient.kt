package chat.keryx.app.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Tier-1 of the dual-tier streaming architecture: a transient Server-Sent-Events subscription to
 * the Hermes gateway's Keryx side-channel (`GET /keryx/stream` on the OpenAI-compatible API server,
 * see hermes-plugin/keryx-stream/). Tokens render live from here; the Matrix room only ever gets
 * the single final committed message — zero m.replace bloat on the homeserver.
 *
 * The subscription is per-turn and per-room: opened right before the command is sent, closed once
 * the final Matrix event has synced (or the turn is abandoned). If this channel can't connect at
 * all the caller falls back to tier-2 (rendering whatever the Matrix sync delivers, including
 * throttled m.replace edits when the gateway has protocol streaming enabled).
 *
 * Wire format (one JSON object per SSE `data:` line):
 *   event: delta   data: {"text": "…incremental tokens…"}
 *   event: segment data: {"final": false}          — a segment boundary (text → tool → text)
 *   event: stop    data: {"text": "<full final>"}  — turn complete; text is the committed body
 *   event: ping    data: {}                        — keepalive, ignored
 */
class HermesStreamClient(
    private val baseUrl: String,
    private val apiKey: String,
    allowInsecure: Boolean = false,
) {

    sealed interface Event {
        /** Incremental assistant text. */
        data class Delta(val text: String) : Event
        /** A segment boundary — the current text run ended (e.g. a tool call interleaves). */
        data object SegmentBreak : Event
        /** Turn finished. [finalText] is the exact body Hermes commits to Matrix (may be null if
         *  the server couldn't include it; match then falls back to accumulated text). */
        data class Stop(val finalText: String?) : Event
        /** The SSE channel is connected and live. */
        data object Opened : Event
        /** The channel failed or dropped. [connected] tells whether any bytes ever flowed —
         *  a never-connected channel triggers the fallback tier, a dropped one shows an alert. */
        data class Failed(val reason: String, val connected: Boolean) : Event
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        // SSE is a long-lived read: no read timeout, the server pings to keep NATs open.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply {
            if (allowInsecure) {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    /**
     * Subscribe to the side-channel for [roomId]. Cold flow: the SSE connection lives exactly as
     * long as the collector. All terminal states surface as [Event.Failed]/[Event.Stop] rather
     * than exceptions, so the collector's `when` is the single place stream state is decided.
     */
    fun stream(roomId: String): Flow<Event> = callbackFlow {
        val url = baseUrl.trimEnd('/') +
            "/keryx/stream?platform=matrix&chat_id=" + java.net.URLEncoder.encode(roomId, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        var connected = false
        val source = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    connected = true
                    trySendBlocking(Event.Opened)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    when (type) {
                        "delta" -> parseText(data)?.let { trySendBlocking(Event.Delta(it)) }
                        "segment" -> trySendBlocking(Event.SegmentBreak)
                        "stop" -> {
                            trySendBlocking(Event.Stop(parseText(data)))
                            close() // turn done — tear the transient channel down
                        }
                        // "ping" and unknown events: ignore (forward-compatible).
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // Server closed without a stop event: treat as a drop so the UI can recover.
                    trySendBlocking(Event.Failed("closed by server", connected))
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val reason = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "connection failed"
                    trySendBlocking(Event.Failed(reason, connected))
                    close()
                }
            },
        )
        awaitClose { source.cancel() }
    }

    private fun parseText(data: String): String? = try {
        (json.parseToJsonElement(data).jsonObject["text"] as? JsonPrimitive)?.content
    } catch (e: Exception) {
        null
    }

    /** What the active brain's reasoning dial looks like (from `GET /keryx/capabilities`). */
    data class ReasoningCaps(
        val model: String,
        /** "binary" (local enable_thinking switch), "effort" (full scale), or "none". */
        val mode: String,
        /** /reasoning command args in menu order (e.g. ["none","high"] for binary). */
        val levels: List<String>,
        /** Optional display labels per arg (binary: none→Off, high→On). */
        val labels: Map<String, String>,
        /** The currently configured arg. */
        val current: String,
    )

    /**
     * Fetch the gateway's reasoning capabilities for the active brain, so the app's reasoning
     * menu can match what the model actually supports (a local vLLM brain is a binary
     * enable_thinking switch; cloud providers take the full effort scale). Gateways without the
     * keryx-stream plugin 404 here — callers treat failure as "unknown, show the generic menu".
     */
    suspend fun capabilities(): Result<ReasoningCaps> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/capabilities")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val probe = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val reasoning = obj["reasoning"]?.jsonObject ?: error("no reasoning block")
                ReasoningCaps(
                    model = (obj["model"] as? JsonPrimitive)?.content.orEmpty(),
                    mode = (reasoning["mode"] as? JsonPrimitive)?.content ?: "effort",
                    levels = (reasoning["levels"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    labels = (reasoning["labels"] as? kotlinx.serialization.json.JsonObject)
                        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                        ?.toMap().orEmpty(),
                    current = (reasoning["current"] as? JsonPrimitive)?.content.orEmpty(),
                )
            }
        }
    }

    /** One slash command actually installed on the connected gateway (from `GET /keryx/commands`). */
    data class GatewayCommand(
        val cmd: String,
        val description: String,
        val category: String,
        /** Argument placeholder ("<prompt>", "[name]"); blank = command takes no arguments. */
        val argsHint: String,
        val aliases: List<String>,
    )

    /**
     * Fetch the gateway's live slash-command registry (core commands + plugin-registered ones),
     * so the "/" autocomplete reflects the system Keryx is actually pointed at instead of a
     * hardcoded preset. Gateways without the keryx-stream plugin 404 — callers keep the preset.
     */
    suspend fun commands(): Result<List<GatewayCommand>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/commands")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val probe = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                (obj["commands"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { el ->
                        val c = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val cmd = (c["cmd"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        GatewayCommand(
                            cmd = cmd,
                            description = (c["description"] as? JsonPrimitive)?.content.orEmpty(),
                            category = (c["category"] as? JsonPrimitive)?.content.orEmpty(),
                            argsHint = (c["args_hint"] as? JsonPrimitive)?.content.orEmpty(),
                            aliases = (c["aliases"] as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                        )
                    }.orEmpty()
            }
        }
    }

    /**
     * One-shot gateway health probe (`GET /health`) for the Settings "Test link" button. Success
     * returns a short human line ("ok · hermes-agent 0.18.0"); every failure mode comes back as a
     * plain Result failure so the caller can toast the reason.
     */
    suspend fun health(): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/health")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            // The shared client has no read timeout (SSE); a health probe must not hang like that.
            val probe = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val status = (obj["status"] as? JsonPrimitive)?.content ?: "ok"
                val platform = (obj["platform"] as? JsonPrimitive)?.content
                val version = (obj["version"] as? JsonPrimitive)?.content
                listOfNotNull(status, platform, version).joinToString(" · ")
            }
        }
    }
}
