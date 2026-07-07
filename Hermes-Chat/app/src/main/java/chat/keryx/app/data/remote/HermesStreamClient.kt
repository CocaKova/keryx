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
            // grow the cache without ever being re-read.
            if (method == "GET" && '?' !in path) snapshotStore?.invoke(path, text)
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
        val tools: List<String>,
    )

    suspend fun healthDetailed(): Result<HubHealth> =
        runCatching { HubJson.health(apiCall("/health/detailed")) }

    suspend fun jobs(): Result<List<HubJob>> =
        runCatching { HubJson.jobs(apiCall("/api/jobs")) }

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

    /** The api_server platform's toolset surface — the gateway offers no per-platform view. */
    suspend fun toolsets(): Result<List<HubToolset>> =
        runCatching { HubJson.toolsets(apiCall("/v1/toolsets")) }

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

    fun toolsets(obj: kotlinx.serialization.json.JsonObject): List<HermesStreamClient.HubToolset> =
        obj.objs("data").map { t ->
            HermesStreamClient.HubToolset(
                name = t.str("name"),
                label = t.str("label"),
                description = t.str("description"),
                enabled = t.bool("enabled"),
                configured = t.bool("configured"),
                tools = t.arr("tools")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            )
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
