package chat.keryx.app.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** When set, every successful GET's raw body is handed here keyed by path — the Agent Hub's
     *  offline cache. Kept as a plain hook so this client stays free of Android storage types. */
    private val snapshotStore: ((path: String, json: String) -> Unit)? = null,
) {

    sealed interface Event {
        /** Incremental assistant text. */
        data class Delta(val text: String) : Event
        /** Incremental reasoning/thinking text (streamed live, before any answer tokens). */
        data class Reasoning(val text: String) : Event
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
                        "reasoning" -> parseText(data)?.let { trySendBlocking(Event.Reasoning(it)) }
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
        /** Matrix room id → agent profile name (the gateway's routing-only multiplex map).
         *  Empty when the gateway predates the field or no map is configured. */
        val roomProfiles: Map<String, String> = emptyMap(),
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
                    roomProfiles = (obj["room_profiles"] as? kotlinx.serialization.json.JsonObject)
                        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                        ?.toMap().orEmpty(),
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

    /** The active petdex mascot (from `GET /keryx/pet`) — spritesheet plus render geometry.
     *  Pets are configured server-side (`display.pet.*`), so the phone shows exactly the pet the
     *  desktop and TUI show. */
    data class PetInfo(
        val enabled: Boolean,
        val slug: String,
        val displayName: String,
        /** `mtime_ns:size` of the sheet on the gateway — cache key for the payload. */
        val revision: String,
        /** Base64 spritesheet (webp/png); empty on `meta=true` probes. */
        val spritesheetBase64: String,
        val frameW: Int,
        val frameH: Int,
        /** Frames one full animation loop steps through (petdex `steps(6)`). */
        val framesPerState: Int,
        /** Duration of one full loop, ms. */
        val loopMs: Int,
        /** Sheet row taxonomy top→bottom (legacy 8-row or Codex 9-row names). */
        val stateRows: List<String>,
        /** Real (padding-trimmed) frame count per concrete row name — ragged sheets pad short
         *  rows with blank frames, and stepping into the padding reads as the pet blinking out. */
        val framesByRow: Map<String, Int>,
    )

    /**
     * Fetch the gateway's active pet. `meta = true` asks for just enabled/slug/revision — a cheap
     * probe so callers can skip re-downloading an unchanged ~2MB spritesheet payload. Gateways
     * without the keryx-stream plugin (or with the pet disabled) come back as failure/enabled=false;
     * callers simply don't show a mascot.
     */
    suspend fun pet(meta: Boolean = false): Result<PetInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/pet" + if (meta) "?meta=1" else "")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            // The full payload carries the whole spritesheet — give it more room than the
            // 5s metadata probes get.
            val probe = client.newBuilder().readTimeout(if (meta) 5 else 20, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                fun str(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
                fun int(key: String, dflt: Int) = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: dflt
                PetInfo(
                    enabled = (obj["enabled"] as? JsonPrimitive)?.content == "true",
                    slug = str("slug"),
                    displayName = str("displayName"),
                    revision = str("spritesheetRevision"),
                    spritesheetBase64 = str("spritesheetBase64"),
                    frameW = int("frameW", 192),
                    frameH = int("frameH", 208),
                    framesPerState = int("framesPerState", 6),
                    loopMs = int("loopMs", 1100),
                    stateRows = (obj["stateRows"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    framesByRow = (obj["framesByRow"] as? kotlinx.serialization.json.JsonObject)
                        ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }
                        ?.toMap().orEmpty(),
                )
            }
        }
    }

    /** One adoptable pet in the picker (installed pets merged with the petdex catalog). */
    data class PetGalleryEntry(
        val slug: String,
        val displayName: String,
        val installed: Boolean,
        /** petdex's hand-picked set — the picker surfaces these first. */
        val curated: Boolean,
        /** Hatched locally on the desktop (AI-generated), not from the catalog. */
        val generated: Boolean,
        /** petdex CDN sheet URL — passed to the thumb endpoint for uninstalled previews. */
        val spritesheetUrl: String,
    )

    data class PetGallery(
        val enabled: Boolean,
        /** Currently active slug ("" when none). */
        val active: String,
        val pets: List<PetGalleryEntry>,
    )

    /**
     * Fetch the adoptable-pets list (`GET /keryx/pets`). `localOnly = true` skips the petdex
     * manifest so installed pets render instantly; callers follow up with a full fetch — the
     * same two-phase load the desktop picker does.
     */
    suspend fun petGallery(localOnly: Boolean = false): Result<PetGallery> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/pets" + if (localOnly) "?localOnly=1" else "")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            // The full list rides on a remote manifest fetch server-side — allow for a slow first hit.
            val probe = client.newBuilder().readTimeout(if (localOnly) 5 else 20, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                PetGallery(
                    enabled = (obj["enabled"] as? JsonPrimitive)?.content == "true",
                    active = (obj["active"] as? JsonPrimitive)?.content.orEmpty(),
                    pets = (obj["pets"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                        val p = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                        val slug = (p["slug"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        PetGalleryEntry(
                            slug = slug,
                            displayName = (p["displayName"] as? JsonPrimitive)?.content ?: slug,
                            installed = (p["installed"] as? JsonPrimitive)?.content == "true",
                            curated = (p["curated"] as? JsonPrimitive)?.content == "true",
                            generated = (p["generated"] as? JsonPrimitive)?.content == "true",
                            spritesheetUrl = (p["spritesheetUrl"] as? JsonPrimitive)?.content.orEmpty(),
                        )
                    }.orEmpty(),
                )
            }
        }
    }

    /**
     * Adopt a pet (`POST /keryx/pet/select`): the gateway installs it from petdex if needed and
     * persists `display.pet.*` — the same path the desktop picker takes. Success returns the
     * display name. Generous timeout: a first adopt downloads the sheet from the CDN server-side.
     */
    suspend fun petSelect(slug: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val payload = "{\"slug\":" + kotlinx.serialization.json.JsonPrimitive(slug).toString() + "}"
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/pet/select")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            val probe = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (!resp.isSuccessful) {
                    val msg = (obj["error"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("message")?.let { (it as? JsonPrimitive)?.content }
                    error(msg ?: "HTTP ${resp.code}")
                }
                (obj["displayName"] as? JsonPrimitive)?.content ?: slug
            }
        }
    }

    /**
     * Small idle-frame PNG for one pet (`GET /keryx/pet/thumb`) — the picker's row preview.
     * [url] is the petdex sheet URL for not-yet-installed pets (the gateway crops + caches
     * server-side). Failure or `ok: false` → Result failure; callers draw a placeholder.
     */
    suspend fun petThumb(slug: String, url: String = ""): Result<ByteArray> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val q = "slug=" + java.net.URLEncoder.encode(slug, "UTF-8") +
                (if (url.isNotBlank()) "&url=" + java.net.URLEncoder.encode(url, "UTF-8") else "")
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/keryx/pet/thumb?" + q)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            // Uninstalled previews make the gateway pull the sheet from the CDN first.
            val probe = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            probe.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if ((obj["ok"] as? JsonPrimitive)?.content != "true") error("no thumb for $slug")
                val b64 = (obj["thumbBase64"] as? JsonPrimitive)?.content ?: error("no thumb payload")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
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

    // --- Kanban (Missions) — /keryx/kanban/* --------------------------------------------------

    /** One task card on the board (summary shape from `GET /keryx/kanban/board`). */
    data class KanbanTask(
        val id: String,
        val title: String,
        val assignee: String,
        val status: String,
        val priority: Int,
        val createdBy: String,
        val createdAt: Long,
        val startedAt: Long?,
        val completedAt: Long?,
        val consecutiveFailures: Int,
        val bodyExcerpt: String,
        /** Full body — only populated on the detail call. */
        val body: String = "",
        val result: String = "",
        val lastFailureError: String = "",
    )

    data class KanbanComment(val author: String, val body: String, val createdAt: Long)

    data class KanbanEvent(val id: Long, val taskId: String, val kind: String, val createdAt: Long)

    /** The whole board: tasks grouped by raw gateway status (column layout is the app's call). */
    data class KanbanBoard(val board: String, val tasks: Map<String, List<KanbanTask>>)

    data class KanbanDetail(
        val task: KanbanTask,
        val comments: List<KanbanComment>,
        val events: List<KanbanEvent>,
    )

    private fun kanbanTaskOf(o: kotlinx.serialization.json.JsonObject): KanbanTask {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun l(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        return KanbanTask(
            id = s("id"),
            title = s("title"),
            assignee = s("assignee"),
            status = s("status"),
            priority = l("priority")?.toInt() ?: 0,
            createdBy = s("created_by"),
            createdAt = l("created_at") ?: 0L,
            startedAt = l("started_at"),
            completedAt = l("completed_at"),
            consecutiveFailures = l("consecutive_failures")?.toInt() ?: 0,
            bodyExcerpt = s("body_excerpt"),
            body = s("body"),
            result = s("result"),
            lastFailureError = s("last_failure_error"),
        )
    }

    private suspend fun kanbanCall(
        path: String,
        post: kotlinx.serialization.json.JsonObject? = null,
    ): kotlinx.serialization.json.JsonObject =
        apiCall(path, method = if (post != null) "POST" else "GET", body = post)

    /**
     * One authenticated JSON round-trip to the gateway. The gateway speaks two error dialects —
     * keryx/openai routes wrap `{"error":{"message":…}}`, the /api/jobs routes return a bare
     * `{"error":"…"}` string — both surface here as the exception message so every caller's
     * `Result.onFailure` shows the gateway's own words instead of an HTTP code.
     */
    private suspend fun apiCall(
        path: String,
        method: String = "GET",
        body: kotlinx.serialization.json.JsonObject? = null,
        snapshotAs: String? = null,
        /** false = never cache this GET (per-run status polls would grow the store forever). */
        snapshot: Boolean = true,
    ): kotlinx.serialization.json.JsonObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
                when (method) {
                    "GET" -> get()
                    "POST" -> post(payload ?: ByteArray(0).toRequestBody(null))
                    "PUT" -> put(payload ?: ByteArray(0).toRequestBody(null))
                    "PATCH" -> patch(payload ?: ByteArray(0).toRequestBody(null))
                    "DELETE" -> if (payload != null) delete(payload) else delete()
                    else -> error("unsupported method $method")
                }
            }
            .build()
        val probe = client.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        probe.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    when (val err = json.parseToJsonElement(text).jsonObject["error"]) {
                        is kotlinx.serialization.json.JsonObject ->
                            (err["message"] as? JsonPrimitive)?.content
                        is JsonPrimitive -> err.contentOrNull
                        else -> null
                    }
                }.getOrNull()
                error(msg ?: "HTTP ${resp.code}")
            }
            // Snapshot plain GETs only: parameterized paths (event cursors, per-id lookups) would
            // grow the cache without ever being re-read. A caller whose query is fixed rather than
            // a cursor opts in via [snapshotAs], which pins the cache key the seeded panels read.
            val snapshotKey = snapshotAs ?: path.takeIf { '?' !in it }
            if (snapshot && method == "GET" && snapshotKey != null) snapshotStore?.invoke(snapshotKey, text)
            json.parseToJsonElement(text).jsonObject
        }
    }

    suspend fun kanbanBoard(): Result<KanbanBoard> = runCatching {
        val obj = kanbanCall("/keryx/kanban/board")
        KanbanBoard(
            board = (obj["board"] as? JsonPrimitive)?.content ?: "default",
            tasks = (obj["tasks"] as? kotlinx.serialization.json.JsonObject)
                ?.mapValues { (_, v) ->
                    (v as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(::kanbanTaskOf) }
                        .orEmpty()
                }.orEmpty(),
        )
    }

    suspend fun kanbanTask(taskId: String): Result<KanbanDetail> = runCatching {
        val obj = kanbanCall("/keryx/kanban/task/$taskId")
        KanbanDetail(
            task = kanbanTaskOf(obj["task"] as kotlinx.serialization.json.JsonObject),
            comments = (obj["comments"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el ->
                    val c = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    KanbanComment(
                        author = (c["author"] as? JsonPrimitive)?.content.orEmpty(),
                        body = (c["body"] as? JsonPrimitive)?.content.orEmpty(),
                        createdAt = (c["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    )
                }.orEmpty(),
            events = (obj["events"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { el ->
                    val e = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    KanbanEvent(
                        id = (e["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                        taskId = taskId,
                        kind = (e["kind"] as? JsonPrimitive)?.content.orEmpty(),
                        createdAt = (e["created_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    )
                }.orEmpty(),
        )
    }

    /** Create a mission. [triage] parks it spec-first; false lets the dispatcher pick it up. */
    suspend fun kanbanCreate(
        title: String,
        assignee: String,
        body: String,
        triage: Boolean,
    ): Result<String> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("assignee", kotlinx.serialization.json.JsonPrimitive(assignee))
            if (body.isNotBlank()) put("body", kotlinx.serialization.json.JsonPrimitive(body))
            put("triage", kotlinx.serialization.json.JsonPrimitive(triage))
        }
        val obj = kanbanCall("/keryx/kanban/task", post = payload)
        (obj["task_id"] as? JsonPrimitive)?.content ?: error("no task_id in response")
    }

    suspend fun kanbanComment(taskId: String, body: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("body", kotlinx.serialization.json.JsonPrimitive(body))
        }
        kanbanCall("/keryx/kanban/task/$taskId/comment", post = payload)
        Unit
    }

    /** One page of the incremental event feed; pass [cursor] back as `since` next poll. */
    data class KanbanEventsPage(val events: List<KanbanEvent>, val cursor: Long)

    /** Poll `GET /keryx/kanban/events?since=` — the mission watcher's feed (≤200 events/page). */
    suspend fun kanbanEvents(since: Long): Result<KanbanEventsPage> = runCatching {
        HubJson.events(kanbanCall("/keryx/kanban/events?since=$since"), since)
    }

    /** One notify subscription — a chat the gateway pushes a native message into when the task
     *  hits a terminal state. The gateway deletes rows itself once the task is genuinely done,
     *  so a subscription vanishing between refreshes means "it fired", not an error. */
    data class KanbanSub(
        val taskId: String,
        val platform: String,
        val chatId: String,
        val threadId: String,
    )

    /** All notify subscriptions on the board (`GET /keryx/kanban/subs`). */
    suspend fun kanbanSubs(): Result<List<KanbanSub>> = runCatching {
        HubJson.subs(kanbanCall("/keryx/kanban/subs"))
    }

    /** Subscribe [roomId] to [taskId]'s terminal events: the gateway notifier then delivers a
     *  real Matrix message into that room when the mission ends — no polling involved. */
    suspend fun kanbanSubscribe(taskId: String, roomId: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("platform", kotlinx.serialization.json.JsonPrimitive("matrix"))
            put("chat_id", kotlinx.serialization.json.JsonPrimitive(roomId))
        }
        kanbanCall("/keryx/kanban/task/$taskId/subscribe", post = payload)
        Unit
    }

    suspend fun kanbanUnsubscribe(
        taskId: String,
        chatId: String,
        platform: String = "matrix",
    ): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("platform", kotlinx.serialization.json.JsonPrimitive(platform))
            put("chat_id", kotlinx.serialization.json.JsonPrimitive(chatId))
        }
        kanbanCall("/keryx/kanban/task/$taskId/unsubscribe", post = payload)
        Unit
    }

    // --- Agent Hub — gateway console (/health/detailed, /api/jobs, /api/sessions, /v1/*) -------

    /** One platform adapter's connection state from `GET /health/detailed`. */
    data class PlatformHealth(
        val name: String,
        /** "connected", "disconnected", "retrying", … — rendered as a status dot, never assumed. */
        val state: String,
        val errorMessage: String,
        val updatedAt: String,
    )

    data class HubHealth(
        val status: String,
        val version: String,
        val gatewayState: String,
        val platforms: List<PlatformHealth>,
        /** In-flight agent turns across all platforms (0 = idle). */
        val activeAgents: Int = 0,
        /** The gateway's own busy verdict (same contract its dashboard uses). */
        val busy: Boolean = false,
        /** Gateway process id — a restart shows as a pid change between refreshes. */
        val pid: Long = 0,
    )

    /** One scheduled job from `GET /api/jobs`. Timestamps stay the gateway's ISO strings — the UI
     *  formats them fail-soft so a schema change degrades to raw text, never a crash. */
    data class HubJob(
        val id: String,
        val name: String,
        val scheduleDisplay: String,
        val enabled: Boolean,
        val state: String,
        val nextRunAt: String?,
        val lastRunAt: String?,
        val lastStatus: String?,
        val lastError: String?,
        val deliver: String,
        val repeatCompleted: Int,
        /** What the agent is told each run — carried so the edit dialog can prefill. */
        val prompt: String = "",
    )

    /** One persisted Hermes session from `GET /api/sessions` (epoch-seconds timestamps). */
    data class HubSession(
        val id: String,
        val source: String,
        val model: String,
        val title: String?,
        val messageCount: Int,
        val toolCallCount: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val apiCallCount: Int,
        val startedAt: Double,
        val lastActive: Double,
        val endedAt: Double?,
        val preview: String,
    )

    /** One transcript entry from `GET /api/sessions/{id}/messages`, preview-shaped. */
    data class HubMessage(
        val role: String,
        val content: String,
        val toolName: String,
        val toolCallCount: Int,
    )

    data class HubSkill(val name: String, val description: String)

    data class HubToolset(
        val name: String,
        val label: String,
        val description: String,
        val enabled: Boolean,
        val configured: Boolean,
        /** Pinned by the gateway operator (can't be flipped from the app) — render disabled. */
        val locked: Boolean = false,
        val tools: List<String>,
    )

    /** The toolset surface plus whether this gateway persists toggles (keryx-stream 1.16+). */
    data class HubToolsets(
        val canToggle: Boolean,
        val toolsets: List<HubToolset>,
    )

    suspend fun healthDetailed(): Result<HubHealth> =
        runCatching { HubJson.health(apiCall("/health/detailed")) }

    /** The gateway omits paused jobs by default; the hub is a management console, so it always
     *  requests the full list and lets [HubJob.enabled] drive the UI. */
    suspend fun jobs(): Result<List<HubJob>> =
        runCatching { HubJson.jobs(apiCall("/api/jobs?include_disabled=true", snapshotAs = "/api/jobs")) }

    /** [action] is one of the gateway's job verbs: "pause", "resume", "run". */
    suspend fun jobAction(jobId: String, action: String): Result<Unit> =
        runCatching { apiCall("/api/jobs/$jobId/$action", method = "POST"); Unit }

    suspend fun jobDelete(jobId: String): Result<Unit> =
        runCatching { apiCall("/api/jobs/$jobId", method = "DELETE"); Unit }

    /** Create a scheduled job. [deliver] is a gateway delivery target ("local" or "matrix:<room>"). */
    suspend fun jobCreate(name: String, schedule: String, prompt: String, deliver: String): Result<Unit> =
        runCatching {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("schedule", kotlinx.serialization.json.JsonPrimitive(schedule))
                put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
                put("deliver", kotlinx.serialization.json.JsonPrimitive(deliver))
            }
            apiCall("/api/jobs", method = "POST", body = payload)
            Unit
        }

    suspend fun sessions(): Result<List<HubSession>> =
        runCatching { HubJson.sessions(apiCall("/api/sessions")) }

    suspend fun sessionMessages(sessionId: String): Result<List<HubMessage>> =
        runCatching { HubJson.messages(apiCall("/api/sessions/$sessionId/messages")) }

    suspend fun skills(): Result<List<HubSkill>> =
        runCatching { HubJson.skills(apiCall("/v1/skills")) }

    /** The toolset surface the agent actually chats with. `/keryx/toolsets` (plugin 1.16+) is
     *  platform-aware (matrix by default) and accepts toggles; older gateways only offer the
     *  read-only, api_server-scoped `/v1/toolsets` — still shown, minus the switches. */
    suspend fun toolsets(): Result<HubToolsets> = runCatching {
        val obj = try {
            apiCall("/keryx/toolsets")
        } catch (_: Exception) {
            apiCall("/v1/toolsets")
        }
        HubJson.toolsets(obj)
    }

    /** Persist one toolset's enablement (PUT /keryx/toolsets/{name}). Failures carry the
     *  gateway's own refusal wording (unknown / locked by the operator). */
    suspend fun setToolsetEnabled(name: String, enabled: Boolean): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("enabled", kotlinx.serialization.json.JsonPrimitive(enabled))
        }
        apiCall(
            "/keryx/toolsets/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"),
            method = "PUT",
            body = payload,
        )
        Unit
    }

    // --- Run Console — vanilla /v1/runs + session resume (1.20) --------------------------------
    // Everything here speaks the STOCK hermes-agent api_server: no /keryx/* plugin routes, so the
    // console works against any gateway the app can reach. Two SSE dialects exist upstream:
    // /v1/runs/{id}/events sends unnamed events (the JSON's own "event" field discriminates),
    // /api/sessions/{id}/chat/stream sends NAMED events with a different vocabulary. Both are
    // normalized into [RunEvent] here so the console renders one stream shape.

    /** One normalized live-run event, from either run-events or session-chat-stream. */
    sealed interface RunEvent {
        /** The SSE channel is connected and live. */
        data object Opened : RunEvent
        /** Incremental assistant text. */
        data class Delta(val text: String) : RunEvent
        /** Reasoning text (arrives in chunks before/between answer tokens). */
        data class Reasoning(val text: String) : RunEvent
        data class ToolStarted(val tool: String, val preview: String) : RunEvent
        data class ToolCompleted(val tool: String, val failed: Boolean) : RunEvent
        /** The agent is blocked on a tool approval; [choices] are the gateway's own verbs
         *  (once/session/always/deny) — answer via [runApproval]. */
        data class ApprovalRequest(val command: String, val tool: String, val choices: List<String>) : RunEvent
        /** Someone (this app or another client) resolved the pending approval. */
        data class ApprovalResponded(val choice: String) : RunEvent
        /** Terminal: the run finished; [output] is the full final response. */
        data class Completed(val output: String) : RunEvent
        /** Terminal: the run failed; [error] is the gateway's redacted message. */
        data class Failed(val error: String) : RunEvent
        /** Terminal: the run was stopped. */
        data object Cancelled : RunEvent
        /** The transport dropped without a terminal event — the run itself may still be going
         *  (poll [runStatus]). [connected] = whether the channel ever opened. */
        data class StreamLost(val reason: String, val connected: Boolean) : RunEvent
    }

    /** Pollable run state (`GET /v1/runs/{id}`) — the fallback surface when SSE is gone. */
    data class RunStatus(
        val runId: String,
        /** queued | running | waiting_for_approval | completed | failed | cancelled */
        val status: String,
        val model: String,
        val sessionId: String,
        val lastEvent: String,
        /** Final response — populated once completed. */
        val output: String,
        val error: String,
    )

    /** One model row from `GET /v1/models` (the gateway's own name + configured route aliases). */
    data class HubModel(val id: String, val resolvesTo: String)

    /** One remembered console run. The gateway has no list-runs route, so the app keeps its own
     *  registry of what it launched (persisted via the hub snapshot store, transcripts capped to
     *  the final output). [kind] "run" = /v1/runs; "session" = a resumed session turn. */
    data class ConsoleRun(
        val id: String,
        val kind: String,
        val prompt: String,
        val startedAt: Long,
        /** running | completed | failed | cancelled | lost */
        val status: String,
        val output: String = "",
        val error: String = "",
    )

    /** Start an agent run (`POST /v1/runs`, answers 202 immediately). Returns the run_id;
     *  subscribe [runEvents] with it right away — the gateway holds an event queue per run, but
     *  only one subscriber may drain it. */
    suspend fun runCreate(prompt: String, model: String? = null): Result<String> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("input", kotlinx.serialization.json.JsonPrimitive(prompt))
            if (!model.isNullOrBlank()) put("model", kotlinx.serialization.json.JsonPrimitive(model))
        }
        val obj = apiCall("/v1/runs", method = "POST", body = payload)
        (obj["run_id"] as? JsonPrimitive)?.contentOrNull ?: error("no run_id in response")
    }

    suspend fun runStatus(runId: String): Result<RunStatus> =
        runCatching { HubJson.runStatus(apiCall("/v1/runs/$runId", snapshot = false)) }

    /** Resolve a pending approval. [choice] is one of the event's `choices` verbs. */
    suspend fun runApproval(runId: String, choice: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("choice", kotlinx.serialization.json.JsonPrimitive(choice))
        }
        apiCall("/v1/runs/$runId/approval", method = "POST", body = payload)
        Unit
    }

    /** Interrupt a running agent (`POST /v1/runs/{id}/stop`). The cancel confirmation arrives as
     *  [RunEvent.Cancelled] on the event stream, not in this response. */
    suspend fun runStop(runId: String): Result<Unit> =
        runCatching { apiCall("/v1/runs/$runId/stop", method = "POST"); Unit }

    /**
     * Live events for a run this app started (`GET /v1/runs/{id}/events`, SSE). Cold flow — the
     * subscription lives exactly as long as the collector. Terminal events close the flow; a
     * transport drop surfaces as [RunEvent.StreamLost] (the run may still be going server-side).
     * NOTE: the gateway pops the run's queue when the subscriber disconnects — re-subscribing
     * after a drop misses everything in between; recover via [runStatus] instead.
     */
    fun runEvents(runId: String): Flow<RunEvent> = callbackFlow {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/runs/$runId/events")
            .header("Accept", "text/event-stream")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        var connected = false
        var terminal = false
        val source = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    connected = true
                    trySendBlocking(RunEvent.Opened)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                    val ev = HubJson.runEvent(obj) ?: return
                    if (ev is RunEvent.Completed || ev is RunEvent.Failed || ev is RunEvent.Cancelled) terminal = true
                    trySendBlocking(ev)
                    if (terminal) close()
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!terminal) trySendBlocking(RunEvent.StreamLost("closed by server", connected))
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (!terminal) {
                        val reason = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "connection failed"
                        trySendBlocking(RunEvent.StreamLost(reason, connected))
                    }
                    close()
                }
            },
        )
        awaitClose { source.cancel() }
    }

    /**
     * One live agent turn INTO an existing session (`POST /api/sessions/{id}/chat/stream`, SSE) —
     * the Sessions tab's Resume. Same normalized [RunEvent] stream as [runEvents]. No stop route
     * exists for this dialect; cancelling the collector closes the connection, which cancels the
     * turn server-side. Approvals never surface here (the vanilla session path has no approval
     * transport) — gateway approval policy decides alone.
     */
    fun sessionChatStream(sessionId: String, message: String): Flow<RunEvent> = callbackFlow {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("message", kotlinx.serialization.json.JsonPrimitive(message))
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + sessionPath(sessionId) + "/chat/stream")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        var connected = false
        var terminal = false
        val source = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    connected = true
                    trySendBlocking(RunEvent.Opened)
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (type == "done") { close(); return }
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
                    val ev = HubJson.sessionEvent(type, obj) ?: return
                    if (ev is RunEvent.Completed || ev is RunEvent.Failed) terminal = true
                    trySendBlocking(ev)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (!terminal) trySendBlocking(RunEvent.StreamLost("closed by server", connected))
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (!terminal) {
                        val reason = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "connection failed"
                        trySendBlocking(RunEvent.StreamLost(reason, connected))
                    }
                    close()
                }
            },
        )
        awaitClose { source.cancel() }
    }

    private fun sessionPath(sessionId: String): String =
        "/api/sessions/" + java.net.URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")

    /** Rename a session (`PATCH`, title only — the gateway whitelists its fields). */
    suspend fun sessionRename(sessionId: String, title: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
        }
        apiCall(sessionPath(sessionId), method = "PATCH", body = payload)
        Unit
    }

    /** Delete a session and its transcript — permanent; callers gate with a destructive confirm. */
    suspend fun sessionDelete(sessionId: String): Result<Unit> =
        runCatching { apiCall(sessionPath(sessionId), method = "DELETE"); Unit }

    /** Branch a session (CLI /branch semantics: source is marked branched, the fork carries the
     *  transcript forward). Returns the new session id. */
    suspend fun sessionFork(sessionId: String, title: String? = null): Result<String> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            if (!title.isNullOrBlank()) put("title", kotlinx.serialization.json.JsonPrimitive(title))
        }
        val obj = apiCall(sessionPath(sessionId) + "/fork", method = "POST", body = payload)
        ((obj["session"] as? kotlinx.serialization.json.JsonObject)
            ?.get("id") as? JsonPrimitive)?.contentOrNull ?: error("no session id in response")
    }

    /** Edit a scheduled job (`PATCH /api/jobs/{id}`) — the missing verb next to the existing
     *  create/pause/run/delete. Only the gateway-whitelisted fields are sent. */
    suspend fun jobUpdate(
        jobId: String,
        name: String,
        schedule: String,
        prompt: String,
        deliver: String,
    ): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(name))
            put("schedule", kotlinx.serialization.json.JsonPrimitive(schedule))
            put("prompt", kotlinx.serialization.json.JsonPrimitive(prompt))
            put("deliver", kotlinx.serialization.json.JsonPrimitive(deliver))
        }
        apiCall("/api/jobs/$jobId", method = "PATCH", body = payload)
        Unit
    }

    suspend fun models(): Result<List<HubModel>> =
        runCatching { HubJson.models(apiCall("/v1/models")) }

    // --- Gateway Controls — /keryx/config, /keryx/reasoning, /keryx/logs, /keryx/brains (1.21) --
    // The plugin's curated config surface: whitelisted non-secret knobs, validated server-side.
    // Gateways without the keryx-stream plugin 404 all of these — panels hide, nothing breaks.

    /** One whitelisted config knob. [kind] is "enum" | "bool" | "int" | "float"; [value] arrives
     *  as the raw JSON text ("true", "queue", "90", "0.7") — [boolValue]/[intValue]/[floatValue]
     *  read it typed. [group] clusters knobs into titled sections (1.23). */
    data class ConfigKnob(
        val key: String,
        val label: String,
        val description: String,
        val kind: String,
        val group: String,
        val value: String,
        val choices: List<String>,
        val min: Int?,
        val max: Int?,
        /** Float-kind bounds — same JSON fields as [min]/[max], kept unrounded. */
        val minF: Double?,
        val maxF: Double?,
        /** When a change lands: "next turn" / "next session" / "gateway restart". */
        val applies: String,
        /** Operator-pinned (KERYX_CONFIG_LOCKED) — render read-only. */
        val locked: Boolean,
    ) {
        val boolValue: Boolean get() = value.toBooleanStrictOrNull() ?: false
        val intValue: Int? get() = value.toIntOrNull()
        val floatValue: Double? get() = value.toDoubleOrNull()
    }

    suspend fun configKnobs(): Result<List<ConfigKnob>> =
        runCatching { HubJson.configKnobs(apiCall("/keryx/config")) }

    /** Persist one knob. [value] must already be the right JSON type for the knob's kind. */
    suspend fun configSet(key: String, value: JsonPrimitive): Result<String> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("key", JsonPrimitive(key))
            put("value", value)
        }
        val obj = apiCall("/keryx/config", method = "PUT", body = payload)
        (obj["applies"] as? JsonPrimitive)?.contentOrNull ?: "saved"
    }

    /** Set the reasoning dial (write side of [capabilities]); the plugin validates against
     *  what the ACTIVE brain accepts. */
    suspend fun reasoningSet(level: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("level", JsonPrimitive(level))
        }
        apiCall("/keryx/reasoning", method = "PUT", body = payload)
        Unit
    }

    /** A redacted tail of the gateway's own log. [source] = "journal" | "file". */
    data class LogsTail(val source: String, val text: String)

    suspend fun logsTail(lines: Int = 120): Result<LogsTail> = runCatching {
        val obj = apiCall("/keryx/logs?lines=$lines", snapshot = false)
        LogsTail(
            source = (obj["source"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    /** The operator-configured brain picker ([] = unconfigured, hide the panel) plus what's
     *  actually serving right now (live-probed, so a finished swap shows up here). */
    data class Brains(val active: String, val brains: List<BrainEntry>)

    data class BrainEntry(val name: String, val description: String)

    suspend fun brains(): Result<Brains> =
        runCatching { HubJson.brains(apiCall("/keryx/brains")) }

    /** Launch the operator's swap command for [name] (202 — the swap runs detached; watch
     *  [brains].active land on the new model). The gateway enforces a cooldown. */
    suspend fun brainSelect(name: String): Result<Unit> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("name", JsonPrimitive(name))
        }
        apiCall("/keryx/brain", method = "POST", body = payload)
        Unit
    }

    // --- Skill Forge — /keryx/skills/* ----------------------------------------------------------

    /** One skill's full SKILL.md. [name] is the CANONICAL directory basename — always use it for
     *  [skillPut], even when the lookup went through a frontmatter display name. [readonly] skills
     *  live in external dirs the gateway refuses to write. */
    data class SkillDetail(
        val name: String,
        val category: String?,
        val content: String,
        val files: List<String>,
        val readonly: Boolean,
    )

    private fun skillPath(name: String): String =
        "/keryx/skills/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    suspend fun skillGet(name: String): Result<SkillDetail> =
        runCatching { HubJson.skillDetail(apiCall(skillPath(name))) }

    /** Full SKILL.md rewrite through the gateway's own validation + security scan. Returns the
     *  server's note ("index refreshes for new sessions"); failures carry the gateway's own
     *  validation message. */
    suspend fun skillPut(name: String, content: String): Result<String> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
        }
        val obj = apiCall(skillPath(name), method = "PUT", body = payload)
        (obj["note"] as? JsonPrimitive)?.contentOrNull ?: "saved"
    }

    suspend fun skillCreate(name: String, content: String, category: String? = null): Result<Unit> =
        runCatching {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("content", kotlinx.serialization.json.JsonPrimitive(content))
                if (!category.isNullOrBlank()) {
                    put("category", kotlinx.serialization.json.JsonPrimitive(category))
                }
            }
            apiCall("/keryx/skills", method = "POST", body = payload)
            Unit
        }

    // --- Session pruner — POST /keryx/sessions/prune ---------------------------------------------

    data class PruneSample(
        val id: String,
        val title: String?,
        val model: String,
        val startedAt: Double,
        val messageCount: Int,
    )

    /** Dry-run answer: true [matched] count with a phone-sized [sample] (server caps it at 50). */
    data class PrunePreview(
        val matched: Int,
        val oldestStartedAt: Double?,
        val newestStartedAt: Double?,
        val sample: List<PruneSample>,
    )

    /** Wet call → [removed] with null [preview]; dry-run → [preview] with removed = 0. */
    data class PruneResult(val removed: Int, val preview: PrunePreview?)

    /** Bulk-delete ENDED sessions (the gateway never touches an active one). Always preview with
     *  [dryRun] first — the wet call is permanent, transcripts included. */
    suspend fun sessionsPrune(
        olderThanDays: Int,
        maxMessages: Int?,
        includeArchived: Boolean,
        dryRun: Boolean,
    ): Result<PruneResult> = runCatching {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("older_than_days", kotlinx.serialization.json.JsonPrimitive(olderThanDays))
            if (maxMessages != null) put("max_messages", kotlinx.serialization.json.JsonPrimitive(maxMessages))
            put("include_archived", kotlinx.serialization.json.JsonPrimitive(includeArchived))
            put("dry_run", kotlinx.serialization.json.JsonPrimitive(dryRun))
        }
        HubJson.pruneResult(apiCall("/keryx/sessions/prune", method = "POST", body = payload))
    }
}

/**
 * Pure JSON→model mapping for the Agent Hub endpoints, split out of the client so unit tests can
 * feed it fixture JSON without a network. Every accessor is null-tolerant: a missing or retyped
 * field degrades to a blank/zero, never a parse crash (the gateway's schema is not ours to pin).
 */
internal object HubJson {
    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun kotlinx.serialization.json.JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

    private fun kotlinx.serialization.json.JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun kotlinx.serialization.json.JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
        this[key] as? kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonObject.objs(key: String): List<kotlinx.serialization.json.JsonObject> =
        arr(key)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()

    fun health(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.HubHealth =
        HermesStreamClient.HubHealth(
            status = obj.str("status"),
            version = obj.str("version"),
            gatewayState = obj.str("gateway_state"),
            platforms = (obj["platforms"] as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (name, v) ->
                    val p = v as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    HermesStreamClient.PlatformHealth(
                        name = name,
                        state = p.str("state"),
                        errorMessage = p.str("error_message"),
                        updatedAt = p.str("updated_at"),
                    )
                }
                ?.sortedBy { it.name }
                .orEmpty(),
            activeAgents = obj.long("active_agents").toInt(),
            busy = obj.bool("gateway_busy"),
            pid = obj.long("pid"),
        )

    fun jobs(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubJob> =
        obj.objs("jobs").map { j ->
            HermesStreamClient.HubJob(
                id = j.str("id"),
                name = j.str("name"),
                scheduleDisplay = j.str("schedule_display").ifBlank {
                    (j["schedule"] as? kotlinx.serialization.json.JsonObject)?.str("display").orEmpty()
                },
                enabled = j.bool("enabled"),
                state = j.str("state"),
                nextRunAt = j.strOrNull("next_run_at"),
                lastRunAt = j.strOrNull("last_run_at"),
                lastStatus = j.strOrNull("last_status"),
                lastError = j.strOrNull("last_error"),
                deliver = j.str("deliver"),
                repeatCompleted = (j["repeat"] as? kotlinx.serialization.json.JsonObject)
                    ?.long("completed")?.toInt() ?: 0,
                prompt = j.str("prompt"),
            )
        }

    fun sessions(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubSession> =
        obj.objs("data").map { s ->
            HermesStreamClient.HubSession(
                id = s.str("id"),
                source = s.str("source"),
                model = s.str("model"),
                title = s.strOrNull("title"),
                messageCount = s.long("message_count").toInt(),
                toolCallCount = s.long("tool_call_count").toInt(),
                inputTokens = s.long("input_tokens"),
                outputTokens = s.long("output_tokens"),
                apiCallCount = s.long("api_call_count").toInt(),
                startedAt = s.dbl("started_at") ?: 0.0,
                lastActive = s.dbl("last_active") ?: 0.0,
                endedAt = s.dbl("ended_at"),
                preview = s.str("preview"),
            )
        }

    fun messages(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubMessage> =
        obj.objs("data").map { m ->
            HermesStreamClient.HubMessage(
                role = m.str("role"),
                content = m.str("content"),
                toolName = m.str("tool_name"),
                toolCallCount = m.arr("tool_calls")?.size ?: 0,
            )
        }

    fun skills(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubSkill> =
        obj.objs("data").map { s ->
            HermesStreamClient.HubSkill(name = s.str("name"), description = s.str("description"))
        }

    /** The mission watcher's feed page. [since] is the caller's cursor, kept when the gateway's
     *  answer omits one so the watcher never accidentally rewinds to 0 and re-alerts history. */
    fun events(obj: kotlinx.serialization.json.JsonObject, since: Long): HermesStreamClient.KanbanEventsPage =
        HermesStreamClient.KanbanEventsPage(
            events = obj.objs("events").map { e ->
                HermesStreamClient.KanbanEvent(
                    id = e.long("id"),
                    taskId = e.str("task_id"),
                    kind = e.str("kind"),
                    createdAt = e.long("created_at"),
                )
            },
            cursor = obj.dbl("cursor")?.toLong() ?: since,
        )

    fun pruneResult(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.PruneResult =
        HermesStreamClient.PruneResult(
            removed = obj.long("removed").toInt(),
            // "sessions" only appears on dry-run answers — its presence is the discriminator.
            preview = if (obj["sessions"] != null) HermesStreamClient.PrunePreview(
                matched = obj.long("matched").toInt(),
                oldestStartedAt = obj.dbl("oldest_started_at"),
                newestStartedAt = obj.dbl("newest_started_at"),
                sample = obj.objs("sessions").map { s ->
                    HermesStreamClient.PruneSample(
                        id = s.str("id"),
                        title = s.strOrNull("title"),
                        model = s.str("model"),
                        startedAt = s.dbl("started_at") ?: 0.0,
                        messageCount = s.long("message_count").toInt(),
                    )
                },
            ) else null,
        )

    fun skillDetail(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.SkillDetail =
        HermesStreamClient.SkillDetail(
            name = obj.str("name"),
            category = obj.strOrNull("category"),
            content = obj.str("content"),
            files = obj.arr("files")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            readonly = obj.bool("readonly"),
        )

    fun subs(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.KanbanSub> =
        obj.objs("subs").map { s ->
            HermesStreamClient.KanbanSub(
                taskId = s.str("task_id"),
                platform = s.str("platform"),
                chatId = s.str("chat_id"),
                threadId = s.str("thread_id"),
            )
        }

    fun toolsets(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.HubToolsets =
        HermesStreamClient.HubToolsets(
            // Absent on /v1/toolsets answers (and old cached snapshots) → read-only view.
            canToggle = obj.bool("canToggle"),
            toolsets = obj.objs("data").map { t ->
                HermesStreamClient.HubToolset(
                    name = t.str("name"),
                    label = t.str("label"),
                    description = t.str("description"),
                    enabled = t.bool("enabled"),
                    configured = t.bool("configured"),
                    locked = t.bool("locked"),
                    tools = t.arr("tools")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                )
            },
        )

    // --- Run Console (1.20) -----------------------------------------------------------------

    /** One `/v1/runs/{id}/events` SSE payload (unnamed events; the JSON's "event" field is the
     *  discriminator) → normalized [HermesStreamClient.RunEvent]. Null = structural/unknown event,
     *  skipped (forward-compatible). */
    fun runEvent(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.RunEvent? =
        when (obj.str("event")) {
            "message.delta" -> HermesStreamClient.RunEvent.Delta(obj.str("delta"))
            "reasoning.available" -> HermesStreamClient.RunEvent.Reasoning(obj.str("text"))
            "tool.started" -> HermesStreamClient.RunEvent.ToolStarted(obj.str("tool"), obj.str("preview"))
            "tool.completed" -> HermesStreamClient.RunEvent.ToolCompleted(obj.str("tool"), obj.bool("error"))
            "approval.request" -> HermesStreamClient.RunEvent.ApprovalRequest(
                command = obj.str("command"),
                tool = obj.str("tool"),
                choices = obj.arr("choices")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty().ifEmpty { listOf("once", "deny") },
            )
            "approval.responded" -> HermesStreamClient.RunEvent.ApprovalResponded(obj.str("choice"))
            "run.completed" -> HermesStreamClient.RunEvent.Completed(obj.str("output"))
            "run.failed" -> HermesStreamClient.RunEvent.Failed(obj.str("error").ifBlank { "run failed" })
            "run.cancelled" -> HermesStreamClient.RunEvent.Cancelled
            else -> null
        }

    /** One `/api/sessions/{id}/chat/stream` SSE payload (NAMED events — [name] is the SSE event
     *  line) → the same normalized [HermesStreamClient.RunEvent]. The session dialect signals
     *  reasoning as `tool.progress` with tool_name "_thinking", and the final text rides on
     *  `assistant.completed` rather than run.completed. Null = structural/unknown, skipped. */
    fun sessionEvent(name: String?, obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.RunEvent? =
        when (name) {
            "assistant.delta" -> HermesStreamClient.RunEvent.Delta(obj.str("delta"))
            "tool.progress" -> HermesStreamClient.RunEvent.Reasoning(obj.str("delta"))
            "tool.started" -> HermesStreamClient.RunEvent.ToolStarted(obj.str("tool_name"), obj.str("preview"))
            "tool.completed" -> HermesStreamClient.RunEvent.ToolCompleted(obj.str("tool_name"), false)
            "tool.failed" -> HermesStreamClient.RunEvent.ToolCompleted(obj.str("tool_name"), true)
            "assistant.completed" -> HermesStreamClient.RunEvent.Completed(obj.str("content"))
            "error" -> HermesStreamClient.RunEvent.Failed(obj.str("message").ifBlank { "run failed" })
            // run.started / message.started / run.completed / done are structural framing.
            else -> null
        }

    fun runStatus(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.RunStatus =
        HermesStreamClient.RunStatus(
            runId = obj.str("run_id"),
            status = obj.str("status"),
            model = obj.str("model"),
            sessionId = obj.str("session_id"),
            lastEvent = obj.str("last_event"),
            output = obj.str("output"),
            error = obj.str("error"),
        )

    fun models(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubModel> =
        obj.objs("data").map { m ->
            HermesStreamClient.HubModel(id = m.str("id"), resolvesTo = m.str("root"))
        }

    fun configKnobs(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.ConfigKnob> =
        obj.objs("knobs").map { k ->
            HermesStreamClient.ConfigKnob(
                key = k.str("key"),
                label = k.str("label"),
                description = k.str("description"),
                kind = k.str("kind"),
                group = k.str("group").ifBlank { "Gateway" },
                value = k.str("value"),
                choices = k.arr("choices")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                min = k.long("min").toInt().takeIf { k["min"] != null && k["min"] !is kotlinx.serialization.json.JsonNull },
                max = k.long("max").toInt().takeIf { k["max"] != null && k["max"] !is kotlinx.serialization.json.JsonNull },
                minF = k.dbl("min"),
                maxF = k.dbl("max"),
                applies = k.str("applies"),
                locked = k.bool("locked"),
            )
        }

    fun brains(obj: kotlinx.serialization.json.JsonObject): HermesStreamClient.Brains =
        HermesStreamClient.Brains(
            active = obj.str("active"),
            brains = obj.objs("brains").map { b ->
                HermesStreamClient.BrainEntry(name = b.str("name"), description = b.str("description"))
            },
        )

    /** The console's persisted run registry (app-local, rides the hub snapshot store). */
    fun consoleRuns(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.ConsoleRun> =
        obj.objs("runs").map { r ->
            HermesStreamClient.ConsoleRun(
                id = r.str("id"),
                kind = r.str("kind").ifBlank { "run" },
                prompt = r.str("prompt"),
                startedAt = r.long("started_at"),
                status = r.str("status").ifBlank { "lost" },
                output = r.str("output"),
                error = r.str("error"),
            )
        }

    fun buildConsoleRuns(runs: List<HermesStreamClient.ConsoleRun>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("runs", kotlinx.serialization.json.buildJsonArray {
                runs.forEach { r ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("id", JsonPrimitive(r.id))
                        put("kind", JsonPrimitive(r.kind))
                        put("prompt", JsonPrimitive(r.prompt))
                        put("started_at", JsonPrimitive(r.startedAt))
                        put("status", JsonPrimitive(r.status))
                        put("output", JsonPrimitive(r.output))
                        put("error", JsonPrimitive(r.error))
                    })
                }
            })
        }

    /**
     * Parse the gateway's ISO-8601 offset timestamps ("2026-07-07T07:00:00-05:00", optionally with
     * fractional seconds) to epoch millis without java.time (minSdk 24, no desugaring). Null on any
     * surprise — callers fall back to showing the raw string.
     */
    fun isoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        // SimpleDateFormat's X can't take fractional seconds and a colon offset together reliably;
        // strip the fraction, it never matters for schedule display.
        val cleaned = iso.replace(Regex("\\.\\d+"), "")
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .parse(cleaned)?.time
        }.getOrNull()
    }
}
