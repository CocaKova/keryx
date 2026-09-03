package chat.keryx.app.transport.direct

import chat.keryx.core.protocol.MAX_PAGE
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.protocol.sessionMessagesQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The ambient (non-conversational) half of the transport: the dashboard REST surface.
 * TALARIA-PROTOCOL.md §4. Phase-1 scope: connection probe, session list, transcript
 * hydration. Auth = `X-Hermes-Session-Token` (token mode) or a rotating Bearer access
 * token via [DirectAuth] (native mode, gated gateways) — with a rotate-once retry on 401.
 */
class GatewayRest(
    baseUrl: String,
    private val token: String,
    allowInsecure: Boolean = false,
    /** Native-dialect credentials (gated gateways). Null = legacy token-header auth. */
    private val auth: DirectAuth? = null,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    data class SessionRow(
        val id: String,
        val title: String,
        val preview: String,
        val startedAt: Long,      // epoch millis
        val lastActive: Long,     // epoch millis
        val messageCount: Long,
        val isActive: Boolean,
        val archived: Boolean,
        val pinned: Boolean,
        val source: String,
        /** The gateway's read watermark, derived server-side (activity after the last read). */
        val unread: Boolean = false,
    )

    // MessageRow / RestToolCall moved to chat.keryx.core.protocol (:shared) —
    // TranscriptBuilder consumes them from commonMain now.

    data class GatewayStatus(
        val version: String,
        val authRequired: Boolean,
        val gatewayRunning: Boolean,
    )

    /** Public probe — also the onboarding "test connection" call (no auth needed). */
    suspend fun status(): Result<GatewayStatus> = get("/api/status").map { body ->
        val o = json.parseToJsonElement(body).jsonObject
        GatewayStatus(
            version = o.str("version") ?: "?",
            authRequired = o["auth_required"]?.jsonPrimitive?.contentOrNull == "true",
            gatewayRunning = o["gateway_running"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    /** Authenticated probe: proves the token works (status alone is public). */
    suspend fun validateToken(): Result<Unit> = get("/api/system/stats").map { }

    /**
     * Session list. [sources] / [excludeSources] filter SERVER-side (stock query params), which
     * matters more than it looks: on a gateway with busy cron jobs the automated runs are the
     * majority of recent activity, so an unfiltered first page can push every real conversation
     * off it. Filtering at the source means a page of conversations is a page of conversations.
     */
    suspend fun sessions(
        limit: Int = 50,
        offset: Int = 0,
        sources: List<String> = emptyList(),
        excludeSources: List<String> = emptyList(),
        profile: String? = null,
    ): Result<List<SessionRow>> =
        get(
            "/api/sessions?limit=${limit.coerceAtMost(100)}&offset=$offset&order=recent" +
                (if (sources.isEmpty()) "" else "&sources=" + sources.joinToString(",")) +
                (if (excludeSources.isEmpty()) "" else "&exclude_sources=" + excludeSources.joinToString(",")) +
                profileQuery(profile)
        ).map { body ->
            val rows = json.parseToJsonElement(body).jsonObject["sessions"]?.jsonArray ?: JsonArray(emptyList())
            // distinctBy: the drawer (and every other roster consumer) keys rows by session id,
            // and a LazyColumn duplicate key is a crash — id-uniqueness is this parser's
            // contract even when the gateway repeats a row.
            rows.mapNotNull { el ->
                val o = el.jsonObject
                SessionRow(
                    id = o.str("id") ?: return@mapNotNull null,
                    title = o.str("title") ?: "",
                    preview = o.str("preview") ?: "",
                    startedAt = o.epochMs("started_at"),
                    lastActive = o.epochMs("last_active"),
                    messageCount = o["message_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    isActive = o.bool("is_active"),
                    archived = o.bool("archived"),
                    pinned = o.bool("pinned"),
                    source = o.str("source") ?: "",
                    unread = o.bool("unread"),
                )
            }.distinctBy { it.id }
        }

    /**
     * Transcript hydration. The query shape — paging direction, the server cap, and the
     * compaction flag that makes this a display read — lives in [sessionMessagesQuery],
     * shared with the iOS client and held by tests; the rationale for each param is there.
     */
    suspend fun messages(
        sessionId: String,
        limit: Int = MAX_PAGE,
        offset: Int = 0,
        newestFirst: Boolean = true,
        profile: String? = null,
    ): Result<List<MessageRow>> =
        get(
            "/api/sessions/$sessionId/messages?" + sessionMessagesQuery(limit, offset, newestFirst) +
                profileQuery(profile)
        ).map { body ->
            val rows = json.parseToJsonElement(body).jsonObject["messages"]?.jsonArray ?: JsonArray(emptyList())
            rows.mapNotNull { el ->
                val o = el.jsonObject
                MessageRow(
                    id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    role = o.str("role") ?: "assistant",
                    content = o.textContent(),
                    toolName = o.str("tool_name"),
                    timestamp = o.epochMs("timestamp"),
                    reasoning = o.str("reasoning") ?: o.str("reasoning_content"),
                    toolCallId = o.str("tool_call_id"),
                    toolCalls = (o["tool_calls"] as? JsonArray)?.mapNotNull { tc ->
                        val call = tc as? JsonObject ?: return@mapNotNull null
                        val fn = call["function"] as? JsonObject ?: return@mapNotNull null
                        RestToolCall(
                            id = call.str("id") ?: call.str("call_id") ?: return@mapNotNull null,
                            name = fn.str("name") ?: return@mapNotNull null,
                            argumentsJson = fn.str("arguments") ?: "{}",
                        )
                    } ?: emptyList(),
                    displayKind = o.str("display_kind"),
                    // `display_metadata.reactions`: [{emoji, author, at}] — Tapback rows the
                    // gateway persists per author (the desktop's and the react tool's writes
                    // both land here).
                    reactions = ((o["display_metadata"] as? JsonObject)
                        ?.get("reactions") as? JsonArray)
                        ?.mapNotNull { r ->
                            val ro = r as? JsonObject ?: return@mapNotNull null
                            val emoji = ro.str("emoji") ?: return@mapNotNull null
                            chat.keryx.core.model.RawReaction(emoji, ro.str("author") ?: "user")
                        } ?: emptyList(),
                )
            }
        }

    /** One hit from the gateway's FTS index: the session, plus the line that matched. */
    data class SearchHit(
        val sessionId: String,
        val title: String,
        val preview: String,
        /** The matching message text, with the query wrapped in `>>>…<<<` by the server. */
        val snippet: String,
        val role: String,
        val lastActive: Long,
        val messageCount: Long,
    )

    /**
     * Server-side session search: FTS5 over message CONTENT plus session-id prefix matching,
     * deduped by compression lineage (one logical chat that auto-compaction split across
     * several stored ids answers once). The thing a client cannot do for itself — local
     * filtering only ever sees the titles it has already downloaded.
     */
    suspend fun searchSessions(query: String, limit: Int = 20): Result<List<SearchHit>> =
        get("/api/sessions/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=$limit")
            .map { body ->
                val rows = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                    ?: JsonArray(emptyList())
                rows.mapNotNull { el ->
                    val o = el.jsonObject
                    SearchHit(
                        sessionId = o.str("id") ?: o.str("session_id") ?: return@mapNotNull null,
                        title = o.str("title") ?: "",
                        preview = o.str("preview") ?: "",
                        snippet = o.str("snippet") ?: "",
                        role = o.str("role") ?: "",
                        lastActive = o.epochMs("last_active"),
                        messageCount = o["message_count"]?.jsonPrimitive?.longOrNull ?: 0,
                    )
                }
            }

    /** One PATCH covers rename + archive + pin + read state (server merges non-null fields).
     *  `unread` is the gateway's own name for the toggle: true = mark explicitly unread,
     *  false = read up to now. */
    suspend fun patchSession(
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
        unread: Boolean? = null,
        profile: String? = null,
    ): Result<Unit> =
        send("PATCH", "/api/sessions/$sessionId", buildString {
            append("{")
            val parts = mutableListOf<String>()
            title?.let { parts += "\"title\":${kotlinx.serialization.json.JsonPrimitive(it)}" }
            archived?.let { parts += "\"archived\":$it" }
            pinned?.let { parts += "\"pinned\":$it" }
            unread?.let { parts += "\"unread\":$it" }
            // The PATCH names its profile in the BODY (the router's SessionRename model),
            // where GET/DELETE take it as a query — the gateway's own asymmetry, mirrored.
            profile?.takeIf { it.isNotBlank() }?.let { parts += "\"profile\":${kotlinx.serialization.json.JsonPrimitive(it)}" }
            append(parts.joinToString(","))
            append("}")
        }).map { }

    suspend fun deleteSession(sessionId: String, profile: String? = null): Result<Unit> =
        send("DELETE", "/api/sessions/$sessionId" + profileQuery(profile, first = true), null).map { }

    /**
     * Bot Mode (2.8): a Bot Chat lives in ITS profile's state.db, and the dashboard serves
     * every local profile from one process — `?profile=<name>` on the session routes opens
     * that profile's store instead of the launch profile's. Blank/null = the launch profile,
     * exactly the old request.
     */
    private fun profileQuery(profile: String?, first: Boolean = false): String {
        val p = profile?.trim().orEmpty()
        if (p.isEmpty()) return ""
        return (if (first) "?" else "&") + "profile=" + java.net.URLEncoder.encode(p, "UTF-8")
    }

    /**
     * Gateway-native STT (protocol §5.1): the recorded take, base64'd into a data URL.
     * Zero-config on a stock install — the gateway lazy-installs local faster-whisper on
     * FIRST use, so the first call can sit for a while (long read timeout, caller shows a
     * warming state). An empty transcript is SUCCESS (silence), not an error.
     */
    suspend fun transcribe(audio: ByteArray, mime: String = "audio/mp4"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // minSdk 24: android.util.Base64 (java.util.Base64 needs API 26).
                val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
                val body = """{"data_url":"data:$mime;base64,$b64"}"""
                val hdr = authHeader()
                val req = Request.Builder()
                    .url("$base/api/audio/transcribe")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .apply { hdr?.let { header(it.first, it.second) } }
                    .build()
                // Not the shared client: its 30s read timeout is tuned for list calls, and a
                // cold faster-whisper install/first-load can legitimately take minutes.
                val sttClient = client.newBuilder()
                    .readTimeout(240, TimeUnit.SECONDS)
                    .build()
                sttClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error(
                        "HTTP ${resp.code} for /api/audio/transcribe" +
                            raw.take(160).let { if (it.isBlank()) "" else " — $it" }
                    )
                    val o = json.parseToJsonElement(raw).jsonObject
                    if (o.bool("ok")) o.str("transcript").orEmpty()
                    else error("transcribe not ok: ${raw.take(160)}")
                }
            }
        }

    /**
     * `POST /api/audio/speak` — the gateway's own one-shot TTS (protocol §5.2). JSON `{text}`
     * in, `{ok, data_url: "data:audio/mpeg;base64,…"}` out; returns the decoded audio bytes.
     * Zero-config on stock (edge TTS, keyless). No voice params on the wire — config-driven.
     */
    suspend fun speak(text: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(text))
            }.toString()
            val hdr = authHeader()
            val req = Request.Builder()
                .url("$base/api/audio/speak")
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { hdr?.let { header(it.first, it.second) } }
                .build()
            // Synthesis is compute/network-bound server-side; the list-call timeout is too tight.
            val ttsClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
            ttsClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(
                    "HTTP ${resp.code} for /api/audio/speak" +
                        raw.take(160).let { if (it.isBlank()) "" else " — $it" }
                )
                val o = json.parseToJsonElement(raw).jsonObject
                if (!o.bool("ok")) error("speak not ok: ${raw.take(160)}")
                val dataUrl = o.str("data_url") ?: error("speak reply had no data_url")
                val b64 = dataUrl.substringAfter("base64,", "")
                if (b64.isEmpty()) error("speak data_url not base64: ${dataUrl.take(40)}")
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
    }

    /**
     * `GET /api/files/download?path=` — bytes of a file the agent wrote on the gateway host
     * (protocol §4.2 managed files; the `MEDIA:<path>` hand-off resolves through here, same
     * as Desktop's `#media:` links). Auth is the session-token header; on a stock local install
     * any path under the gateway user's home is servable, hosted installs lock to their root.
     * 100 MB server cap; a long read timeout because a PDF over the tailnet is not a list call.
     */
    suspend fun downloadFile(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$base/api/files/download?path=" + java.net.URLEncoder.encode(path, "UTF-8")
            val hdr = authHeader()
            val req = Request.Builder()
                .url(url)
                .apply { hdr?.let { header(it.first, it.second) } }
                .build()
            val dl = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
            dl.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    error("HTTP ${resp.code} for /api/files/download" + raw.take(160).let { if (it.isBlank()) "" else " — $it" })
                }
                resp.body?.bytes() ?: error("empty body for /api/files/download")
            }
        }
    }

    /** The auth header for one request: native bearer (rotating) or the legacy token. */
    private suspend fun authHeader(): Pair<String, String>? =
        auth?.header(base)
            ?: token.takeIf { it.isNotBlank() }?.let { "X-Hermes-Session-Token" to it }

    private suspend fun send(method: String, path: String, jsonBody: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            fun run(hdr: Pair<String, String>?): Pair<Int, String> {
                val req = Request.Builder()
                    .url(base + path)
                    .method(method, jsonBody?.toRequestBody("application/json".toMediaType()))
                    .apply { hdr?.let { header(it.first, it.second) } }
                    .build()
                return client.newCall(req).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            }
            var (code, body) = run(authHeader())
            // A native-mode 401 = the access token died server-side (restart, clock skew) —
            // rotate through the refresh token once and retry once; a second 401 is real.
            if (code == 401 && auth?.nativeMode == true && auth.bearer(base, force = true) != null) {
                val retry = run(authHeader()); code = retry.first; body = retry.second
            }
            // Carry the server's own words into the failure: "HTTP 404" alone sent us
            // hunting the transport when the gateway was already explaining itself.
            if (code !in 200..299) error(
                "HTTP $code for $path" + body.take(160).let { if (it.isBlank()) "" else " — $it" }
            )
            body
        }
    }

    private suspend fun get(path: String): Result<String> = send("GET", path, null)

    // The Shipyard moved to ShipyardRest (Hermes Link base) — this base never mounts the
    // git routes (2.6.0 device walk, 08-31).

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.contentOrNull == "true"
    /** Server timestamps are REAL epoch seconds (may be fractional); the app runs on millis. */
    private fun JsonObject.epochMs(key: String): Long =
        ((this[key]?.jsonPrimitive?.doubleOrNull) ?: 0.0).let { (it * 1000).toLong() }

    /** `content` is a plain string OR a multimodal part array — flatten to displayable text. */
    private fun JsonObject.textContent(): String {
        val el = this["content"] ?: return ""
        return when (el) {
            is JsonArray -> el.joinToString("\n") { part ->
                val p = (part as? JsonObject) ?: return@joinToString ""
                p["text"]?.jsonPrimitive?.contentOrNull
                    ?: p["content"]?.jsonPrimitive?.contentOrNull
                    ?: if (p["type"]?.jsonPrimitive?.contentOrNull == "image_url") "🖼 image" else ""
            }.trim()
            else -> el.jsonPrimitive.contentOrNull ?: ""
        }
    }
}
