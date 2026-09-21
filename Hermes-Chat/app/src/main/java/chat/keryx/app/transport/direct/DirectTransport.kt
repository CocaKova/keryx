package chat.keryx.app.transport.direct

import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.model.MediaTags
import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomInvite
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import chat.keryx.core.model.TypingState
import chat.keryx.core.protocol.ToolText
import chat.keryx.core.model.MessageReactions
import chat.keryx.core.model.RawReaction
import chat.keryx.core.transport.ChatTransport
import chat.keryx.core.transport.GatewayCapabilities
import chat.keryx.core.transport.MatrixCapabilities
import chat.keryx.core.transport.SessionSearchHit
import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RosterOrder
import chat.keryx.core.model.RoomType
import chat.keryx.core.protocol.ForeignFollow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * ChatRepository over a stock hermes-agent gateway (TALARIA-PROTOCOL.md) instead of Matrix.
 *
 * The interface is still Keryx-shaped (rooms/events); the mapping is deliberate scaffolding
 * so every screen keeps compiling while the spine swaps — the domain reshape is a Phase 2
 * item, not a Phase 1 one:
 *   - one pseudo-room ("gateway") represents the connected gateway; profiles→rooms later
 *   - Session = gateway stored session; "eventId" = transcript row id (stringified)
 *   - live turns stream through [GatewayRpc.events] into a per-session overlay message
 *   - typing.agentTyping = a turn is running (message.start .. message.complete)
 *
 * Session ids are DUAL on the wire (protocol §6.1): the durable stored id (REST, session
 * lists) vs the ephemeral live sid (all RPCs, stamped on events). UI only ever sees stored
 * ids; [attach] resolves and caches the mapping via `session.resume`.
 */
class DirectTransport(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Where the last page of each transcript sleeps between process lives (2.10) — the
     *  app's cache dir, evictable by the system, never a source of truth. Null = no cache. */
    private val cacheDir: java.io.File? = null,
) : ChatTransport, GatewayCapabilities {

    /** No Matrix here — the UI's Matrix affordances gate themselves off on exactly this. */
    override val matrix: MatrixCapabilities? get() = null

    /** Rooms ARE gateway sessions here, so the session verbs are live. */
    override val gateway: GatewayCapabilities get() = this

    companion object {
        const val GATEWAY_ROOM_ID = "gateway"

        /** Placeholder id for a tool.generating card, replaced by the real tool.start. */
        private const val STREAM_PUBLISH_MS = 100L
private const val GHOST_TOOL_ID = "generating"

        /**
         * Transcript rows per history page. Well under the server's 500 cap on purpose:
         * opening a session is the most latency-visible thing the app does, and a deep
         * session's older turns are one tap away rather than paid for up front.
         *
         * 120 matches the tail page Hermes Desktop settled on, and a phone has the
         * stronger case for it: every row costs a TranscriptBuilder pass, markdown and
         * syntax-highlight work, and tool-card construction before anything paints.
         */
        private const val HISTORY_PAGE = 120
        /** The roster's page — the REST cap is 100; fifty keeps the first pull light. */
        private const val SESSION_PAGE = 50

        /**
         * The gateway's `source` for a scheduled run. Cron is the one source that is
         * automated BY DEFINITION on every gateway, so it is the only one hard-coded here —
         * everything else (a chat platform, a client, a plugin) is treated as a place a
         * person might be talking, because hiding a real conversation is a far worse
         * failure than listing a machine one.
         */
        const val CRON_SOURCE = "cron"

        /**
         * Sources that are machinery rather than conversation, beyond cron. Deliberately a
         * SHORT known list: an unrecognized source is assumed to be a human somewhere.
         */
        val QUIET_SOURCES = setOf("api_server", "kanban", "hermes_browser")
    }

    private var rpc: GatewayRpc? = null
    private var rest: GatewayRest? = null
    private var pumpJob: Job? = null
    private val attachMutex = Mutex()

    private val _loggedIn = MutableStateFlow(settings.directLoggedIn)
    private val storedToLive = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val liveToStored = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val stores = java.util.concurrent.ConcurrentHashMap<String, SessionStore>()

    /**
     * Bot Mode (2.8): stored id → the profile whose state.db holds it. Only sessions that
     * live OUTSIDE the launch profile are registered (a Bot Chat opened from the roster);
     * every session-scoped call — resume, hydrate, page, PATCH, DELETE — reads its profile
     * here and names it on the wire, so a bot's forever-chat resolves in the right store.
     */
    private val profileOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun profileFor(storedId: String): String? = profileOf[storedId]

    /**
     * The profile that holds [storedId] on the wire, or null for the launch profile's own — for
     * the collaborators outside this class that make session-scoped REST calls of their own (the
     * archive sweep). A Bot Chat lives in ITS profile's store; a call that does not name it asks
     * the wrong store and is answered, truthfully, with nothing.
     */
    internal fun profileForSession(storedId: String): String? = profileFor(storedId)

    /** Roster rows the Bots delegate publishes: canonical chats the session list never carries. */
    private val _botRows = MutableStateFlow<List<RoomProfile>>(emptyList())

    /**
     * Live transcript state for one stored session: REST hydration + a structured streaming
     * overlay. A running turn is an ORDERED list of items — text segments and tool calls — so
     * the theater shows text → tools → text in the order it actually happened:
     *
     *   - `message.delta` accumulates in [buffer] (the segment currently being spoken);
     *   - `message.interim` seals the buffer as its own segment (the gateway emits it exactly
     *     so commentary next to tool calls isn't lost when complete replaces the buffer);
     *   - `tool.start` seals any un-interim'd buffer provisionally, then appends the call
     *     (a later already_streamed interim replaces that provisional text with the
     *     authoritative version instead of duplicating it);
     *   - `tool.generating` shows a ghost card while the model is still typing the args;
     *   - `message.complete` folds the whole turn into [base] and clears the overlay.
     */
    private inner class SessionStore(val storedId: String) {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val agentTyping = MutableStateFlow(false)
        val history = MutableStateFlow(chat.keryx.core.model.HistoryState())
        var hydrated = false
        /** Open timelines on this store; the foreign-turn follower runs while it is > 0. */
        var followers = 0
        var followJob: Job? = null
        /** The cached page is on screen (a paint, not a hydration — the wire still owes the truth). */
        var painted = false

        /** The agent's own `todo` plan — newest tool result wins, live or hydrated
         *  (every call returns the FULL list, so the latest one is the whole truth). */
        val todoPlan = MutableStateFlow<chat.keryx.core.model.TodoPlan?>(null)

        /**
         * The transcript is TWO layers, and they must not be confused: [hydratedRows] is the
         * server's history (rebuilt wholesale every time paging walks further back, so a
         * tool result whose assistant row was on the previous page stops being an orphan),
         * while [local] is everything that happened since — folded live turns, local echoes,
         * slash output. Rebuilding must never take [local] with it.
         */
        private var hydratedRows: List<MessageRow> = emptyList()
        private var hydratedMessages: List<Message> = emptyList()
            set(v) { field = v; baseCache = null }
        private var local: List<Message> = emptyList()
            set(v) { field = v; baseCache = null }
        // A background fan-out can be witnessed twice — once as each wing lands live, and
        // again when its consolidated report arrives in history. Keep the persisted one.
        // Cached (invalidated by the setters above): publish() runs per streaming delta, and
        // the settled transcript must not pay a full dedup walk + media-tag regex scan of
        // every message per token — only the small live overlay changes between deltas.
        private var baseCache: List<Message>? = null
        private val base: List<Message>
            get() = baseCache ?: expandMediaTags(
                chat.keryx.core.model.DelegationReport
                    .withoutSupersededLandings(hydratedMessages + local),
            ).also { baseCache = it }

        private val items = mutableListOf<TurnItem>()
        private var seq = 0
        /** Names this turn's rows, live and folded alike — see [LiveTurnIds]. */
        private var turnTag = 0L
        private var buffer = StringBuilder()
        private var streaming = false
        // The turn's thinking: reasoning.delta/thinking.delta accumulate here (they stream
        // BEFORE answer tokens, so this is often the first life the turn shows). Duration is
        // measured delta-to-delta — the gateway doesn't persist one.
        private var reasonBuf = StringBuilder()
        private var reasonStartedAt = 0L
        private var reasonEndedAt = 0L

        /**
         * Aggregated reactions per event id. Keys are durable row ids (stringified — the same
         * ids TranscriptBuilder stamps on hydrated messages), plus the ephemeral `local-*` /
         * `live-*` id of any message reacted to before it round-tripped through hydration.
         * Seeded from hydration rows; updated from `message.react` round-trips.
         */
        val reactions = MutableStateFlow<Map<String, List<MessageReaction>>>(emptyMap())

        /** Fold a hydration page in — including rows with none, so a reaction someone cleared
         *  on the gateway also clears here on the next open. */
        private fun seedReactions(rows: List<MessageRow>) {
            if (rows.isEmpty()) return
            reactions.value = reactions.value +
                rows.associate { it.id.toString() to MessageReactions.aggregate(it.reactions) }
        }

        fun setReactions(keys: List<String>, aggregated: List<MessageReaction>) {
            reactions.value = reactions.value + keys.associateWith { aggregated }
        }

        /** Newest page: replaces history wholesale (this is the open-a-session path). */
        fun setHistory(rows: List<MessageRow>, more: Boolean) {
            hydratedRows = rows
            seedReactions(rows)
            hydratedMessages = chat.keryx.core.protocol.TranscriptBuilder.build(storedId, rows)
            history.value = history.value.copy(hasMore = more, loading = false, loaded = rows.size)
            // Seed the Flight Plan from the newest persisted `todo` result — rows arrive
            // chronological here, so scan from the end.
            rows.lastOrNull { it.role == "tool" && it.toolName == "todo" }?.let { row ->
                chat.keryx.core.model.TodoPlanParser.parse(row.content)
                    ?.let { todoPlan.value = it }
            }
            publish()
        }

        /** An older page, prepended. Rebuilds from ALL rows so calls and their results
         *  re-pair across the page seam. */
        fun prependHistory(older: List<MessageRow>, more: Boolean) {
            // Row ids are the gateway's own AUTOINCREMENT keys, so this is an exact
            // identity check, not a heuristic — and it makes a double-fetch of the same
            // offset (two taps racing the loading flag) harmless instead of a doubled page.
            val have = hydratedRows.mapTo(HashSet()) { it.id }
            hydratedRows = older.filterNot { it.id in have } + hydratedRows
            seedReactions(older)
            hydratedMessages = chat.keryx.core.protocol.TranscriptBuilder.build(storedId, hydratedRows)
            history.value = history.value.copy(
                hasMore = more, loading = false, loaded = hydratedRows.size,
            )
            publish()
        }

        fun historyLoading(loading: Boolean) {
            history.value = history.value.copy(loading = loading)
        }

        /** Rows fetched so far = the offset the next (older) page starts at. */
        fun historyOffset(): Int = hydratedRows.size

        fun streamStart() {
            streaming = true
            items.clear(); buffer = StringBuilder(); seq = 0
            turnTag = System.currentTimeMillis()
            reasonBuf = StringBuilder(); reasonStartedAt = 0L; reasonEndedAt = 0L
            agentTyping.value = true
            publish()
        }

        // ---- E1: the per-token publish throttle -------------------------------------
        // publish() rewrites the whole messages list and re-groups the trailing block; at
        // ~50 tok/s the unthrottled append→publish was ~50 full republishes a second against
        // the Matrix door's ~10 (STREAM_DISPATCH_MS, since 1.18.3) — measured 08-24 at
        // 3.9-5.1% janky frames on a long direct stream. Gate-only, no trailing job: every
        // non-delta path (interim, tool events, completion, handoff) still publishes
        // immediately and seals the buffer, so the held tail always lands within one event.
        private var lastStreamPublishAt = 0L

        private fun publishThrottled() {
            val now = System.currentTimeMillis()
            if (now - lastStreamPublishAt < STREAM_PUBLISH_MS) return
            lastStreamPublishAt = now
            publish()
        }

        fun streamDelta(text: String) { if (!streaming) streamStart(); buffer.append(text); publishThrottled() }

        fun streamReasoning(text: String) {
            if (!streaming) streamStart()
            if (reasonStartedAt == 0L) reasonStartedAt = System.currentTimeMillis()
            reasonEndedAt = System.currentTimeMillis()
            reasonBuf.append(text)
            publishThrottled()
        }

        /** Post-hoc full reasoning (`reasoning.available`) — authoritative, replaces. */
        fun reasoningAvailable(text: String) {
            if (!streaming) streamStart()
            if (text.isNotBlank()) { reasonBuf = StringBuilder(text) }
            publish()
        }

        /** Delta-to-delta span — the answer streaming after the thought must not inflate it. */
        private fun reasonSeconds(): Int? =
            if (reasonStartedAt == 0L) null
            else (((reasonEndedAt - reasonStartedAt).coerceAtLeast(0L)) / 1000L).toInt()

        fun interim(text: String, alreadyStreamed: Boolean) {
            if (!streaming) streamStart()
            if (alreadyStreamed) {
                val last = items.lastOrNull()
                if (buffer.isBlank() && last is TurnItem.Text && last.provisional) {
                    // tool.start already sealed this segment; adopt the authoritative text.
                    items[items.lastIndex] = last.copy(
                        text = text.ifBlank { last.text }, provisional = false,
                    )
                } else {
                    sealBuffer(finalText = text.ifBlank { null })
                }
            } else if (text.isNotBlank()) {
                items += TurnItem.Text(seq++, text.trim(), provisional = false)
            }
            publish()
        }

        private fun sealBuffer(finalText: String? = null, provisional: Boolean = false) {
            val t = (finalText ?: buffer.toString()).trim()
            buffer = StringBuilder()
            if (t.isNotBlank()) items += TurnItem.Text(seq++, t, provisional)
        }

        fun toolGenerating(name: String) {
            if (!streaming) streamStart()
            if (buffer.isNotBlank()) sealBuffer(provisional = true)
            val ghost = ToolCall(toolId = GHOST_TOOL_ID, name = name, context = "preparing…")
            val gi = items.indexOfLast { it is TurnItem.Tool && it.call.toolId == GHOST_TOOL_ID }
            if (gi >= 0) items[gi] = TurnItem.Tool(items[gi].seq, ghost)
            else items += TurnItem.Tool(seq++, ghost)
            publish()
        }

        fun toolStart(incoming: ToolCall) {
            if (!streaming) streamStart()
            if (buffer.isNotBlank()) sealBuffer(provisional = true)
            // A call that starts while another is STILL RUNNING genuinely overlaps it —
            // the only honest evidence of concurrency available, and the reason `concurrent`
            // is set here and nowhere else. History can group by dispatch but must not claim
            // parallelism: the runtime segments each batch (parallel-safe runs vs barriers).
            val running = items.lastOrNull {
                it is TurnItem.Tool && it.call.status == ToolStatus.EXECUTING &&
                    it.call.toolId != GHOST_TOOL_ID
            } as? TurnItem.Tool
            val call = incoming.copy(
                batchId = running?.call?.batchId?.takeIf { it.isNotBlank() } ?: "live-batch-$seq",
                concurrent = running != null,
            )
            val gi = items.indexOfLast { it is TurnItem.Tool && it.call.toolId == GHOST_TOOL_ID }
            if (gi >= 0) items[gi] = TurnItem.Tool(items[gi].seq, call)
            else items += TurnItem.Tool(seq++, call)
            publish()
        }

        fun toolComplete(done: ToolCall) {
            if (!streaming) streamStart()
            val i = items.indexOfLast {
                it is TurnItem.Tool &&
                    (it.call.toolId == done.toolId || it.call.toolId == GHOST_TOOL_ID)
            }
            if (i >= 0) {
                val prev = (items[i] as TurnItem.Tool).call
                items[i] = TurnItem.Tool(
                    items[i].seq,
                    done.copy(
                        context = prev.context.ifBlank { done.context },
                        // Completion frames carry neither the batch nor the overlap evidence.
                        batchId = prev.batchId,
                        concurrent = prev.concurrent,
                    ),
                )
            } else {
                items += TurnItem.Tool(seq++, done) // start frame lost (reconnect); still show it
            }
            publish()
        }

        /**
         * Subagents currently in flight, in dispatch order.
         *
         * ⚠ These deliberately do NOT live in [items] with the turn's tool calls, because a
         * delegation OUTLIVES the turn that dispatched it: on stock 0.20.1 a top-level
         * `delegate_task` always runs in the BACKGROUND (verified live — the model doesn't
         * get to choose), so the parent turn completes at dispatch and the children report
         * back minutes later, in their own time. Folding them into the turn overlay would
         * either lose them at `message.complete` or hold a phantom turn open — a "working"
         * chip spinning over a session where nothing is streaming.
         *
         * So: while a wing flies it rides a live trailing card; when it lands it becomes an
         * ordinary transcript entry at the moment it actually finished.
         */
        private val flying = LinkedHashMap<String, chat.keryx.core.model.Delegation>()

        /**
         * Stop claiming a subagent is in flight. Called when the socket comes back: a wing's
         * completion event may have been lost while we were away, and a spinner that can
         * never resolve is worse than one that quietly stands down — the transcript still
         * holds every landing we did see, and the batch's own result message still arrives.
         */
        fun releaseFlyingWings() {
            if (flying.isEmpty()) return
            flying.clear()
            publish()
        }

        fun delegation(
            key: String,
            update: (chat.keryx.core.model.Delegation) -> chat.keryx.core.model.Delegation,
        ) {
            val prev = flying[key] ?: chat.keryx.core.model.Delegation(key = key)
            val next = update(prev)
            if (next.running) {
                flying[key] = next
            } else {
                // Record every landing, including one whose flight we missed (a reconnect
                // mid-delegation still gets the completion, and it is still news).
                flying.remove(key)
                local = local + Message(
                    id = "wing-$key",
                    roomId = storedId,
                    sender = SenderType.HERMES,
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    delegations = listOf(next),
                )
            }
            publish()
        }

        fun streamComplete(
            finalText: String,
            error: Boolean,
            finalReasoning: String? = null,
            /** The gateway's account of a failed turn (2.10) — rides the final message. */
            failure: chat.keryx.core.model.TurnFailure? = null,
        ) {
            streaming = false
            agentTyping.value = false
            val now = System.currentTimeMillis()
            // A turn that completes without a start frame (reconnect mid-turn) still needs a name.
            if (turnTag == 0L) turnTag = now
            // Same ids the overlay wore while it streamed: the fold is the SAME rows settling,
            // and a row that changes its key is a row the list fades out beside its own copy.
            val folded = items.map { itemMessage(it, LiveTurnIds.item(turnTag, it.seq), now) }
            val fin = finalText.ifBlank { buffer.toString() }.trim()
            val lastSealed = (items.lastOrNull() as? TurnItem.Text)?.text
            val finMsg = if (fin.isNotBlank() && fin != lastSealed) listOf(
                Message(
                    id = LiveTurnIds.answer(turnTag),
                    roomId = storedId,
                    sender = if (error) SenderType.SYSTEM else SenderType.HERMES,
                    content = fin,
                    timestamp = now,
                    failure = failure,
                )
            ) else emptyList()
            // The thought led the turn, so it folds in FIRST — its own scaffold row, exactly
            // where the live disclosure sat. Wire text (message.complete.reasoning) wins over
            // our accumulation when both exist.
            val thought = (finalReasoning?.trim()?.takeIf { it.isNotEmpty() }
                ?: reasonBuf.toString().trim().takeIf { it.isNotEmpty() })
            val thoughtMsg = if (thought != null) listOf(
                Message(
                    id = LiveTurnIds.thought(turnTag),
                    roomId = storedId,
                    sender = SenderType.HERMES,
                    content = "",
                    timestamp = now,
                    reasoning = thought,
                    reasoningSeconds = reasonSeconds(),
                )
            ) else emptyList()
            local = local + thoughtMsg + folded + finMsg
            items.clear(); buffer = StringBuilder(); turnTag = 0L
            reasonBuf = StringBuilder(); reasonStartedAt = 0L; reasonEndedAt = 0L
            publish()
        }

        /** Drop the rows this process life added on its own — a re-read is about to replace them. */
        fun clearLocal() { local = emptyList(); publish() }

        fun localUserMessage(text: String) {
            local = local + Message(
                id = "local-${System.currentTimeMillis()}",
                roomId = storedId,
                sender = SenderType.ME,
                content = text,
                timestamp = System.currentTimeMillis(),
            )
            publish()
        }

        /** A quiet system line (slash-command output, local notices). */
        fun localSystemMessage(text: String) {
            local = local + Message(
                id = "local-sys-${System.currentTimeMillis()}",
                roomId = storedId,
                sender = SenderType.SYSTEM,
                content = text,
                timestamp = System.currentTimeMillis(),
            )
            publish()
        }

        /** The self-improvement review's "💾 …" summary, as an AGENT row.
         *
         *  Sender matters: the review renders through the telemetry path, and that path is
         *  keyed on [SenderType.HERMES] (ChatScreen's `isTelem` gate) — which is also the only
         *  way it reaches [MessageParser] with agent chrome on, and therefore the only way its
         *  skill items become pills instead of a wall of " · "-joined prose. A SYSTEM row would
         *  parse as human text and lose them. It is also the shape the rest of the app already
         *  assumes: ChatViewModel's turn-tail settles the working banner early on exactly this
         *  message arriving as the agent.
         *
         *  Not persisted by the gateway (it rides a `review.summary` event, never a message
         *  row), so nothing in history can duplicate it — and nothing restores it after a
         *  process death either. Same lifetime the desktop's own row has. */
        fun localReviewSummary(text: String) {
            local = local + Message(
                id = "local-review-${System.currentTimeMillis()}",
                roomId = storedId,
                sender = SenderType.HERMES,
                content = text,
                timestamp = System.currentTimeMillis(),
            )
            publish()
        }

        /** Local echo for an uploaded image; returns the message id (bytes key). */
        fun localUserImage(caption: String, fileName: String): String {
            val id = "local-img-${System.currentTimeMillis()}"
            local = local + Message(
                id = id,
                roomId = storedId,
                sender = SenderType.ME,
                content = caption,
                timestamp = System.currentTimeMillis(),
                mediaKind = chat.keryx.core.model.MediaKind.IMAGE,
                fileName = fileName,
            )
            publish()
            return id
        }

        /** Local echo for a staged non-image file (renders as the ⎘ file row). */
        fun localUserFile(caption: String, fileName: String) {
            local = local + Message(
                id = "local-file-${System.currentTimeMillis()}",
                roomId = storedId,
                sender = SenderType.ME,
                content = caption,
                timestamp = System.currentTimeMillis(),
                mediaKind = chat.keryx.core.model.MediaKind.FILE,
                fileName = fileName,
            )
            publish()
        }

        private fun itemMessage(item: TurnItem, id: String, ts: Long): Message = when (item) {
            is TurnItem.Text -> Message(
                id = id, roomId = storedId, sender = SenderType.HERMES,
                content = item.text, timestamp = ts,
            )
            is TurnItem.Tool -> Message(
                id = id, roomId = storedId, sender = SenderType.HERMES,
                content = "", timestamp = ts, toolCalls = listOf(item.call),
            )
        }

        private fun publish() {
            val overlay = if (streaming) buildList {
                val now = System.currentTimeMillis()
                // Sealed items get seq-stable ids so Compose keys survive re-publishes — and
                // the fold in [streamComplete], which hands the same rows the same names.
                items.forEach { add(itemMessage(it, LiveTurnIds.item(turnTag, it.seq), now)) }
                add(
                    Message(
                        id = LiveTurnIds.answer(turnTag),
                        roomId = storedId,
                        sender = SenderType.HERMES,
                        content = buffer.toString(),
                        timestamp = now,
                        isStreaming = true,
                        // The live thought rides the streaming message: the disclosure shows
                        // "Thinking…" with the accumulating text while answer tokens are
                        // still to come. Seconds tick because reasoning deltas re-publish.
                        reasoning = reasonBuf.toString().takeIf { it.isNotBlank() },
                        reasoningSeconds = reasonSeconds(),
                    )
                )
            } else emptyList()
            // Wings in flight ride the very bottom: they are the one thing still happening,
            // and they belong under whatever the turn last said rather than pinned to where
            // they were dispatched.
            val wings = if (flying.isEmpty()) emptyList() else listOf(
                Message(
                    id = "wings",
                    roomId = storedId,
                    sender = SenderType.HERMES,
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    delegations = flying.values.toList(),
                )
            )
            // base is already media-expanded (cached); only the live overlay + wings — a
            // handful of rows — get the per-publish expansion pass.
            messages.value = base + expandMediaTags(overlay + wings)
        }
    }

    /** One step of a live turn, in arrival order. [seq] is the append-stable overlay key. */
    private sealed interface TurnItem {
        val seq: Int
        data class Text(override val seq: Int, val text: String, val provisional: Boolean) : TurnItem
        data class Tool(override val seq: Int, val call: ToolCall) : TurnItem
    }

    // ---- connection lifecycle -------------------------------------------------------

    /** (Re)build clients from current settings and open the WS. Idempotent. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        // Only dial when NO client exists — an existing GatewayRpc self-heals (its own
        // backoff + the network callback), and rebuilding it here would drop every live
        // session lease this process holds, including the one a respond is aimed at.
        if (rpc == null) connectIfConfigured()
        if (rpc == null) return false // not configured
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            _linkState.first { it == chat.keryx.core.model.LinkState.CONNECTED }
            true
        } ?: (_linkState.value == chat.keryx.core.model.LinkState.CONNECTED)
    }

    fun connectIfConfigured() {
        if (!settings.directLoggedIn) return
        val url = settings.directGatewayUrl
        val token = settings.directApiKey
        if (url.isBlank()) return
        rpc?.close()
        pumpJob?.cancel()
        stateJob?.cancel()
        storedToLive.clear(); liveToStored.clear()
        // One DirectAuth for both halves: REST bearer rotation and per-connect WS tickets
        // share a refresh mutex, so one expiry rotates once for everyone.
        val auth = DirectAuth(settings, settings.allowInsecure)
        rest = GatewayRest(url, token, settings.allowInsecure, auth)
        rpc = GatewayRpc(url, { auth.wsCredentialQuery(url) }, settings.allowInsecure).also { r ->
            r.connect(scope)
            pumpJob = scope.launch { r.events.collect(::onEvent) }
            stateJob = scope.launch {
                r.state.collect { st ->
                    _linkState.value = when (st) {
                        is GatewayRpc.ConnState.Ready -> chat.keryx.core.model.LinkState.CONNECTED
                        is GatewayRpc.ConnState.Connecting -> chat.keryx.core.model.LinkState.CONNECTING
                        else -> chat.keryx.core.model.LinkState.DISCONNECTED
                    }
                    if (st is GatewayRpc.ConnState.Failed &&
                        st.wsCloseCode in GatewayRpc.TERMINAL_CREDENTIAL_CODES
                    ) onCredentialRejected()
                }
            }
        }
        scope.launch { refreshSessions() }
    }

    /**
     * The gateway rejected our credential TERMINALLY (an HTTP 401/403 upgrade reject — the
     * shape a gated gateway actually sends, measured on the wire — or a post-accept
     * 4401/4403 close): the reconnect loop has already stopped, and no retry can heal it — a GATED gateway rejects the
     * legacy token by design (hermes ≥0.20.5), and a native refresh the server won't rotate
     * is a dead sign-in. Sign the door out so HermesApp falls back to the login screen,
     * whose Connect speaks BOTH dialects (the probe picks; gated fires the browser sign-in).
     * Without this a door that walked in on the token dialect sat "logged in" forever
     * showing disconnected, with the browser sign-in unreachable — device-caught 2026-08-24,
     * the first gated gateway this app met. URL and sealed credentials stay: the login
     * screen prefills from them, and a fresh sign-in overwrites what matters.
     */
    private fun onCredentialRejected() {
        if (!settings.directLoggedIn) return
        android.util.Log.w("KeryxGw", "gateway credential rejected — signing the door out to re-onboard")
        settings.directLoggedIn = false
        _loggedIn.value = false
    }

    private var stateJob: Job? = null
    private val _linkState = MutableStateFlow(chat.keryx.core.model.LinkState.DISCONNECTED)
    fun linkState(): Flow<chat.keryx.core.model.LinkState> = _linkState

    fun reconnect() = connectIfConfigured()

    /**
     * The route may have changed — stop waiting out the backoff and try now.
     *
     * Distinct from [reconnect], which tears the client down and rebuilds it from settings.
     * This is the cheap "the network came back" nudge: if a socket is already up it costs
     * nothing, and if one is pending it skips up to 30 s of dead waiting.
     */
    fun networkMayHaveChanged() {
        val r = rpc
        if (r == null) { connectIfConfigured(); return }
        if (r.state.value !is GatewayRpc.ConnState.Ready) r.retryNow()
    }

    val connectionState: StateFlow<GatewayRpc.ConnState>?
        get() = rpc?.state

    private fun onEvent(ev: GatewayRpc.GatewayEvent) = runCatching { handleEvent(ev) }
        .onFailure { android.util.Log.e("KeryxGw", "event ${ev.type} mishandled", it) }
        .let { }

    // A malformed or unexpected payload shape must never escape the event pump — the pump
    // runs in appScope, and the stream must survive whatever a future gateway emits.
    private fun handleEvent(ev: GatewayRpc.GatewayEvent) {
        // Global broadcasts carry no session id.
        if (ev.type == "sessions.changed") { scope.launch { refreshSessions(); reconcileRuntimes() }; return }
        // A fresh socket. Everything we know may be stale, so this is the resync point.
        if (ev.type == "gateway.ready") { onGatewayReady(); return }
        // ⚠ The gateway can take a live session BACK — idle TTL, LRU eviction, or the
        // WS-orphan reaper after a network blip (server.py `_RECLAIM_END_REASONS`). This is
        // a GLOBAL broadcast (frame session_id is empty; the ids are in the payload),
        // precisely because the orphan case has already lost the transport it would target.
        //
        // Caught live: a reclaimed session goes SILENT — event routing keys off
        // `_sessions[sid].transport`, so once the entry is gone no session event reaches us
        // again, and the next prompt.submit fails 4007 against an id the backend forgot.
        // Phones background constantly, so mobile meets the orphan reaper far more than
        // desktop does. Dropping the cached sid is the whole fix: the next send re-resumes.
        if (ev.type == "session.reclaimed") { onSessionReclaimed(ev.payload); return }
        // Compaction forks the stored session (protocol: /compress ends the parent and
        // continues under a NEW stored id, same live sid). session.info carries the
        // authoritative stored id as `stored_session_id` (server-side that's the
        // session_key) — re-anchor the moment it changes, or every later event lands in
        // the dead parent while the UI highlights nothing.
        if (ev.type == "session.info") {
            val newStored = ev.payload?.get("stored_session_id")?.jsonPrimitive?.contentOrNull
            if (!newStored.isNullOrBlank()) maybeRotateStored(ev.sessionId, newStored)
        }
        val storedId = liveToStored[ev.sessionId] ?: return
        val store = stores[storedId] ?: return
        val p = ev.payload
        fun pStr(key: String) = p?.get(key)?.jsonPrimitive?.contentOrNull
        // A failed turn's descriptor (2.10): `error_surface` {layer, code, retryable} beside the
        // failure's words. A gateway without it yields a layer-less failure — still a card.
        fun pFailure(): chat.keryx.core.model.TurnFailure? {
            if (pStr("status") != "error" && ev.type != "error") return null
            val surface = p?.get("error_surface") as? kotlinx.serialization.json.JsonObject
            val words = pStr("error") ?: pStr("message") ?: pStr("text").orEmpty()
            return chat.keryx.core.model.TurnFailure.fromWire(
                layer = surface?.get("layer")?.jsonPrimitive?.contentOrNull,
                code = surface?.get("code")?.jsonPrimitive?.contentOrNull,
                retryable = surface?.get("retryable")?.jsonPrimitive?.booleanOrNull,
                message = words.removePrefix("Error: ").trim(),
            )
        }
        // The gateway only continues the turn once an approval resolves (any client, or
        // timeout), so ANY further turn traffic for this session means the card is stale.
        if (ev.type.startsWith("message.") || ev.type.startsWith("tool.")) {
            setApproval(storedId, null)
            // Any turn traffic proves a turn is in flight — not just message.start. This is
            // what re-marks a session busy after a reconnect that happened mid-turn (start
            // fired before the socket died; the deltas are the only signal left).
            markBusy(storedId, true)
            touchSession(storedId) // the agent is speaking here: newest activity, locally known
        }
        when (ev.type) {
            // ---- blocking requests: the agent is STOPPED until one of these is answered ----
            // Unlike approvals these carry a request_id and can expire server-side, so they
            // clear on their own `.expire` twin (or when the turn moves on) — never on the
            // blanket message.*/tool.* rule above, which would wipe the card mid-question.
            "clarify.request" -> setBlocking(
                storedId,
                chat.keryx.core.model.BlockingRequest(
                    kind = chat.keryx.core.model.BlockingKind.CLARIFY,
                    requestId = pStr("request_id") ?: "",
                    prompt = pStr("question") ?: "",
                    choices = (p?.get("choices") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    multiSelect = pStr("multi_select") == "true",
                ),
            )
            "sudo.request" -> setBlocking(
                storedId,
                chat.keryx.core.model.BlockingRequest(
                    kind = chat.keryx.core.model.BlockingKind.SUDO,
                    requestId = pStr("request_id") ?: "",
                    prompt = "A command on the host needs your sudo password.",
                ),
            )
            "secret.request" -> setBlocking(
                storedId,
                chat.keryx.core.model.BlockingRequest(
                    kind = chat.keryx.core.model.BlockingKind.SECRET,
                    requestId = pStr("request_id") ?: "",
                    prompt = pStr("prompt") ?: "",
                    envVar = pStr("env_var") ?: "",
                ),
            )
            "clarify.expire", "sudo.expire", "secret.expire" -> {
                val rid = pStr("request_id")
                // Only the request that actually expired: a late twin must not clear a
                // newer question the agent has already asked in its place.
                if (rid != null && blockingFlow(storedId).value?.requestId == rid) {
                    setBlocking(storedId, null)
                }
            }
            "approval.request" -> setApproval(
                storedId,
                chat.keryx.core.model.ApprovalRequest(
                    command = pStr("command") ?: "",
                    description = pStr("description") ?: "",
                    choices = (p?.get("choices") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.ifEmpty { null }
                        ?: listOf("once", "deny"),
                ),
            )
            "message.start" -> { store.streamStart(); noteRun(storedId, ev.type) }
            "message.delta" -> {
                val t = pStr("text") ?: ""
                store.streamDelta(t)
                noteRun(storedId, ev.type)
                _turnEvents.tryEmit(chat.keryx.core.model.TurnEvent.Delta(storedId, t))
            }
            // The model's thinking, streamed before (and between) answer tokens. `.available`
            // is the post-hoc full text a provider hands over only at the end — authoritative,
            // replaces. ⚠️ `thinking.delta` is NOT a second spelling of this: it carries the
            // KawaiiSpinner status tick ("(¬_¬) pondering...") the agent emits before EVERY
            // API call (conversation_loop.py → gateway thinking_callback). Desktop ignores it
            // outright (gateway-event.ts) — folding it in here stamped a kaomoji into the
            // reasoning disclosure once per call (Jonny's live-caught report, 08-15).
            "reasoning.delta" -> { store.streamReasoning(pStr("text") ?: ""); noteRun(storedId, ev.type) }
            "thinking.delta" -> { /* spinner status, not thought — working chip covers it */ }
            "reasoning.available" -> store.reasoningAvailable(pStr("text") ?: "")
            "message.interim" -> {
                val t = pStr("text") ?: ""
                val streamed = (p?.get("already_streamed") as? JsonPrimitive)?.contentOrNull == "true"
                store.interim(text = t, alreadyStreamed = streamed)
                // A segment the stream never carried (tier-2 provider) is new words for a
                // listener; a sealed already-streamed segment is a boundary between prose runs.
                if (!streamed && t.isNotBlank()) _turnEvents.tryEmit(chat.keryx.core.model.TurnEvent.Delta(storedId, t))
                _turnEvents.tryEmit(chat.keryx.core.model.TurnEvent.Break(storedId))
            }
            "message.complete" -> {
                // The turn is over — anything it was waiting on is answered, expired or moot.
                markBusy(storedId, false)
                setBlocking(storedId, null)
                store.streamComplete(
                    finalText = pStr("text") ?: "",
                    error = pStr("status") == "error",
                    // complete carries the turn's reasoning too — authoritative over our
                    // accumulation when present (protocol §3: message.complete {…, reasoning?}).
                    finalReasoning = pStr("reasoning"),
                    failure = pFailure(),
                )
                // After the fold, not before: the shade's end-of-turn alert re-reads the
                // transcript on this event and must find the finished message there.
                _turnEvents.tryEmit(chat.keryx.core.model.TurnEvent.End(
                    storedId, pStr("text") ?: "", error = pStr("status") == "error",
                ))
                applyMeta(storedId, p) // usage (incl. context_percent) rides on complete
                // A turn that died on a refused reasoning level lands here too, with
                // status:"error" and the model's own words (measured live: "HTTP 400:
                // Unexpected reasoning effort max. Supported types are xhigh (default),
                // medium, and low."). Most reliable of the three carriers — cloud providers
                // that DO emit `error` and local ones that only manage this both hit it.
                if (pStr("status") == "error") {
                    val why = pStr("error") ?: pStr("text").orEmpty()
                    if (chat.keryx.core.model.ReasoningEffort.isLevelRejection(why)) {
                        _reasoningRejections.tryEmit(why)
                    }
                }
                // Auto-compaction may have forked the session during this turn without
                // announcing it — reconcile now so the next turn doesn't address a
                // session that no longer exists.
                scope.launch { reconcileStoredId(ev.sessionId) }
            }
            "session.info" -> applyMeta(storedId, p)
            // The gateway narrates long lifecycle work here — notably compaction
            // ("compressing"/"compacting" with its own progress text). "ready" clears.
            "status.update" -> {
                val kind = pStr("kind").orEmpty()
                val text = pStr("text").orEmpty()
                // `of`, not the constructor: the gateway re-tags only the one line carrying its
                // COMPACTION marker; a pre-API / preflight compression arrives as plain
                // "lifecycle" and used to be invisible here too (2.5.7).
                statusFlow(storedId).value =
                    if (kind == "ready" || text.isBlank()) null
                    else chat.keryx.core.model.SessionStatus.of(kind, text)
                // Failure-shaped status lines are the agent's OWN error report ("❌
                // Non-retryable error (HTTP 400): …" — run_agent buffers retry chatter and
                // replays it via status_callback("lifecycle", …) only when the turn actually
                // failed) and often the ONLY signal a dying turn sends: the dedicated
                // `error` event doesn't fire on that path (live-caught 2026-08-15: a vision
                // session hit vLLM's image cap and the phone showed dead air). Persist them
                // as a quiet SYSTEM row; benign statuses (⏳ spinners, compaction — its own
                // kind) stay off the transcript.
                if (kind in setOf("lifecycle", "status") &&
                    (text.startsWith("❌") || text.startsWith("⚠️") || text.startsWith("⚠"))
                ) {
                    store.localSystemMessage(text)
                    // ⚠️ This is the ONLY channel a refused reasoning level travels on for a
                    // local model: measured live — `reasoning_effort: max` → HTTP 400 from the
                    // chat template → "Non-retryable error … Aborting" → NO `error` event, no
                    // message.complete, the turn just stops. run_agent BUFFERS the line, so it
                    // lands here (often on the next turn). Republish it so the level that
                    // killed the turn can be walked back.
                    if (chat.keryx.core.model.ReasoningEffort.isLevelRejection(text)) {
                        _reasoningRejections.tryEmit(text)
                    }
                }
            }
            // The post-turn background review saved something to memory or skills and says so
            // ("💾 Self-improvement review: 📝 Skill 'x' patched: … · Memory ➕ …"). The CLI
            // prints this; the messaging gateway sends it as an ordinary message row, which is
            // why it has always been visible in Keryx. On THIS transport it is event-only
            // (tui_gateway/server.py `_emit("review.summary", …)`) — so without this branch the
            // skill/memory write happens silently and the parser that turns those items into
            // pills never sees a thing. Desktop surfaces it the same way, as a persistent
            // transcript row rather than a toast that can be missed.
            //
            // ⚠️ The leading 💾 is LOAD-BEARING and must survive verbatim: it is what
            // MessageParser.REVIEW_PREFIX anchors on (and what classifies the row as telemetry
            // at all). Desktop strips its own leading glyph because its row draws a fresh one;
            // copying that here would silently demote the review to plain prose.
            "review.summary" -> pStr("text")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { store.localReviewSummary(it) }
            "tool.generating" -> {
                store.toolGenerating(pStr("name") ?: "tool")
                noteRun(storedId, ev.type, toolName = pStr("name") ?: "tool")
            }
            "tool.start" -> {
                val name = pStr("name") ?: "tool"
                val args = p?.get("args") as? kotlinx.serialization.json.JsonObject
                noteRun(storedId, ev.type, pStr("tool_id").orEmpty(), name, pStr("context") ?: ToolText.contextPreview(name, args))
                store.toolStart(
                    ToolCall(
                        toolId = pStr("tool_id") ?: "tool-${System.nanoTime()}",
                        name = name,
                        context = pStr("context")
                            ?: ToolText.contextPreview(name, args),
                        argsJson = args?.toString() ?: "",
                    )
                )
            }
            "tool.complete" -> {
                // The blocking bridges live INSIDE a tool call (clarify, terminal sudo, a
                // skill's secret capture): that tool returning means the question resolved,
                // here or on another client. Interactive tools are dispatch barriers on the
                // gateway, so no unrelated tool can complete while one is waiting.
                blockingFlow(storedId).value = null
                val name = pStr("name") ?: "tool"
                val args = p?.get("args") as? kotlinx.serialization.json.JsonObject
                val result = p?.get("result")
                val resultDisplay = ToolText.resultElementToDisplay(result)
                store.toolComplete(
                    ToolCall(
                        toolId = pStr("tool_id") ?: "tool-${System.nanoTime()}",
                        name = name,
                        context = ToolText.contextPreview(name, args),
                        argsJson = args?.toString() ?: "",
                        status = if (ToolText.elementLooksFailed(result)) ToolStatus.FAILED
                        else ToolStatus.COMPLETED,
                        result = resultDisplay,
                        summary = pStr("summary") ?: "",
                        durationS = (p?.get("duration_s") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                        inlineDiff = pStr("inline_diff") ?: "",
                    )
                )
                // The agent updated its plan — every `todo` result is the full list, so
                // the Flight Plan strip repaints from this alone.
                if (name == "todo") {
                    chat.keryx.core.model.TodoPlanParser.parse(resultDisplay)
                        ?.let { store.todoPlan.value = it }
                }
                // After the plan: the tally moving is what makes the run notice re-read it.
                noteRun(storedId, ev.type, pStr("tool_id").orEmpty(), name)
            }
            // ---- delegation ------------------------------------------------------------
            // Every subagent.* event carries the same identity block and adds what only it
            // knows, so identity folds in once and the type decides state + activity line.
            "subagent.spawn_requested", "subagent.start", "subagent.thinking",
            "subagent.tool", "subagent.progress", "subagent.complete",
            -> {
                // subagent_id is optional on the wire (older emitters omit it); the task
                // index is the stable fallback within one dispatch.
                val key = pStr("subagent_id")?.takeIf { it.isNotBlank() }
                    ?: "task-${pStr("task_index") ?: "0"}"
                fun pInt(k: String) = pStr(k)?.toDoubleOrNull()?.toInt()
                fun pList(k: String) = (p?.get(k) as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                val text = pStr("text").orEmpty().trim()
                store.delegation(key) { prev ->
                    val withIdentity = prev.copy(
                        goal = pStr("goal")?.takeIf { it.isNotBlank() } ?: prev.goal,
                        taskIndex = pInt("task_index") ?: prev.taskIndex,
                        taskCount = pInt("task_count") ?: prev.taskCount,
                        model = pStr("model")?.takeIf { it.isNotBlank() } ?: prev.model,
                        sessionId = pStr("child_session_id")?.takeIf { it.isNotBlank() }
                            ?: prev.sessionId,
                        depth = pInt("depth") ?: prev.depth,
                        toolCount = pInt("tool_count") ?: prev.toolCount,
                        // The first frame about a child is when this client learned it exists.
                        // Stamped once, never moved — see Delegation.elapsedSeconds.
                        startedAtMs = if (prev.startedAtMs == 0L) System.currentTimeMillis()
                            else prev.startedAtMs,
                    )
                    when (ev.type) {
                        "subagent.spawn_requested" -> withIdentity.copy(
                            state = chat.keryx.core.model.DelegationState.SPAWNING,
                        )
                        "subagent.start" -> withIdentity.copy(
                            state = chat.keryx.core.model.DelegationState.RUNNING,
                            activity = "",
                        )
                        // The child's own tool: name it, with its preview as the object.
                        // Kept twice — once as the live line, once in the record that
                        // outlives the flight (Theater.trailWith bounds both doors alike).
                        "subagent.tool" -> {
                            val beat = chat.keryx.core.model.DelegationBeat(
                                kind = "tool",
                                name = pStr("tool_name")?.takeIf { it.isNotBlank() }.orEmpty(),
                                text = (pStr("tool_preview") ?: text).takeIf { it.isNotBlank() }
                                    .orEmpty(),
                            )
                            withIdentity.copy(
                                state = chat.keryx.core.model.DelegationState.RUNNING,
                                activity = beat.line,
                                trail = chat.keryx.core.model.Theater
                                    .trailWith(withIdentity.trail, beat),
                            )
                        }
                        // A progress note is something the child chose to say about its work,
                        // so it is kept; a thinking fragment is texture and is not.
                        "subagent.progress" -> withIdentity.copy(
                            state = chat.keryx.core.model.DelegationState.RUNNING,
                            activity = text.ifBlank { prev.activity },
                            trail = chat.keryx.core.model.Theater.trailWith(
                                withIdentity.trail,
                                chat.keryx.core.model.DelegationBeat("progress", text = text),
                            ),
                        )
                        "subagent.thinking" -> withIdentity.copy(
                            state = chat.keryx.core.model.DelegationState.RUNNING,
                            activity = text.ifBlank { prev.activity },
                        )
                        else -> withIdentity.copy(
                            state = chat.keryx.core.model.DelegationState
                                .fromWire(pStr("status")),
                            activity = "",
                            summary = pStr("summary")?.takeIf { it.isNotBlank() } ?: text,
                            durationSeconds = pStr("duration_seconds")?.toDoubleOrNull()
                                ?: prev.durationSeconds,
                            inputTokens = pInt("input_tokens") ?: prev.inputTokens,
                            outputTokens = pInt("output_tokens") ?: prev.outputTokens,
                            reasoningTokens = pInt("reasoning_tokens") ?: prev.reasoningTokens,
                            apiCalls = pInt("api_calls") ?: prev.apiCalls,
                            filesRead = pList("files_read") ?: prev.filesRead,
                            filesWritten = pList("files_written") ?: prev.filesWritten,
                        )
                    }
                }
            }
            "error" -> {
                markBusy(storedId, false)
                val text = pStr("message") ?: ""
                // The turn ended, however it ended: a listener that only hears `message.complete`
                // waits forever on the turns that die (the Call's channel never closes).
                store.streamComplete(finalText = text, error = true, failure = pFailure())
                _turnEvents.tryEmit(chat.keryx.core.model.TurnEvent.End(storedId, text, error = true))
                // A turn can die because the MODEL refused the reasoning level (a local
                // template's supported set is narrower than Hermes' scale, and nothing knows
                // that until a real turn runs). Republish those so the level that killed the
                // turn can be walked back instead of killing the next one too.
                if (chat.keryx.core.model.ReasoningEffort.isLevelRejection(text)) {
                    _reasoningRejections.tryEmit(text)
                }
            }
        }
    }

    /**
     * A socket just went live. Re-sync everything, because a reconnect is the one moment
     * where what we hold is guaranteed suspect.
     *
     * The morning case this exists for: the phone slept, the VPN was down at launch, so the
     * session list fetch failed and the drawer came up EMPTY. Reconnecting later fixed the
     * socket and nothing else — no list, no transcript, no live events — because the only
     * `refreshSessions()` ran at client construction. The app looked broken until it was
     * force-reopened, which rebuilt the client and did the fetch that was missing.
     *
     * Live session ids do not survive this boundary either: a gateway that restarted (or
     * reaped us as an orphan while we were away) has forgotten every sid we cached, so
     * holding them means the next send addresses a session that no longer exists.
     */
    private fun onGatewayReady() {
        storedToLive.clear()
        liveToStored.clear()
        stores.values.forEach { it.releaseFlyingWings() }
        // Busy marks don't survive the boundary: a turn that ended while we were away will
        // never send its message.complete, and one still running re-marks itself with its
        // next delta (the blanket turn-traffic rule above). Shade entries stay — a pending
        // approval may still be inside its server-side wait, and its notification carries
        // its own honest timeout.
        _runs.value = emptyMap()
        _busyStored.value = emptySet()
        scope.launch {
            refreshSessions()
            // Re-open what the user actually has open. Sessions are only in `stores` once
            // something opened them, so this is bounded by what this run has touched.
            stores.entries.toList().forEach { (storedId, st) ->
                runCatching { rehydrate(storedId, st) }
                    .onFailure { android.util.Log.w("KeryxGw", "resync failed for $storedId", it) }
                // Re-lease so live events flow again without waiting for the user to type.
                runCatching { attach(storedId) }
            }
        }
    }

    /**
     * Re-fetch a session's transcript after a reconnect, keeping roughly as much history as
     * the user had already paged in — dropping them back to one page would silently undo a
     * deliberate scroll into the past.
     */
    private suspend fun rehydrate(storedId: String, st: SessionStore) {
        val rest = rest ?: return
        val want = st.history.value.loaded.coerceIn(HISTORY_PAGE, 500)
        val body = rest.messagesRaw(storedId, limit = want).getOrThrow()
        val rows = rest.parseMessages(body)
        st.setHistory(rows, more = rows.size >= want)
        st.hydrated = true
        // The re-read is the freshest page there is; it is what the next cold open should paint.
        if (want == HISTORY_PAGE) writeCachedPage(storedId, body)
    }

    /**
     * Forget a live session the backend reclaimed. The stored session itself is untouched —
     * its transcript is on disk and reopening it is just another `session.resume` — so this
     * only invalidates the ephemeral sid, plus the live state that was describing a turn
     * which is now definitively over.
     */
    private fun onSessionReclaimed(p: kotlinx.serialization.json.JsonObject?) {
        val live = p?.get("session_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        // Trust our own mapping over the payload's stored id: ours is what the UI keys on.
        val stored = liveToStored[live]
            ?: p?.get("stored_session_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (live.isBlank() && stored.isBlank()) return
        if (live.isNotBlank()) liveToStored.remove(live)
        if (stored.isNotBlank()) {
            // Only drop the forward mapping if it still points at the reclaimed sid — a
            // reclaim notice arriving after we already re-resumed must not undo that.
            if (live.isBlank() || storedToLive[stored] == live) storedToLive.remove(stored)
            stores[stored]?.let { st ->
                st.agentTyping.value = false
                st.releaseFlyingWings()
            }
            statusFlows[stored]?.value = null
            setApproval(stored, null)
            setBlocking(stored, null)
            markBusy(stored, false)
        }
        android.util.Log.i(
            "KeryxGw",
            "session reclaimed by gateway (${p?.get("reason")?.jsonPrimitive?.contentOrNull}): $stored",
        )
        // A session we have OPEN must not sit deaf until the user happens to type: take a
        // fresh lease now so live events resume on their own. Sessions we merely know about
        // stay unresumed — re-leasing every reclaimed session would fight the very reaper
        // that just freed it.
        if (stored.isNotBlank() && stores.containsKey(stored)) {
            scope.launch { runCatching { attach(stored) } }
        }
    }

    // Raw REST rows kept alongside the domain list: the drawer's meta line (msgs · source)
    // and the live shimmer read facts the slim Session model deliberately doesn't carry.
    private val _sessionRows = MutableStateFlow<List<GatewayRest.SessionRow>>(emptyList())

    /** Cron sessions, kept apart from the conversation list (see [CRON_SOURCE]). */
    private val _cronRows = MutableStateFlow<List<GatewayRest.SessionRow>>(emptyList())

    /**
     * The runs you have answered ([SettingsRepository.joinedCronSessions], read once per
     * process — a fleet switch relaunches the app). A report you typed into stops being
     * machinery: [serverRooms] publishes it as a conversation, so the session list carries
     * it like any other, and keeps carrying it across cold starts.
     */
    private val _joinedCron = MutableStateFlow(settings.joinedCronSessions)

    suspend fun refreshSessionList() {
        refreshSessions()
    }

    /** More pages behind the roster (2.10): the last page the list holds came back full. */
    private val _hasMoreSessions = MutableStateFlow(false)
    fun hasMoreSessions(): Flow<Boolean> = _hasMoreSessions
    private val _loadingMore = MutableStateFlow(false)
    fun loadingMoreSessions(): Flow<Boolean> = _loadingMore

    /**
     * The next page of the roster, appended (2.10). The drawer's "show older" row: the first
     * page is fifty, and a phone that talks to one gateway for weeks has hundreds — a list
     * that stopped at fifty made every older session unreachable except by search.
     */
    suspend fun loadMoreSessions(): Result<Int> {
        val r = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        if (_loadingMore.value) return Result.success(0)
        _loadingMore.value = true
        try {
            val have = _sessionRows.value
            return r.sessions(limit = SESSION_PAGE, offset = have.size, excludeSources = listOf(CRON_SOURCE))
                .map { rows ->
                    val known = have.mapTo(HashSet()) { it.id }
                    val fresh = rows.filter { !it.archived && it.id !in known }
                    _sessionRows.value = have + fresh
                    _hasMoreSessions.value = rows.size >= SESSION_PAGE
                    fresh.size
                }
        } finally {
            _loadingMore.value = false
        }
    }

    /**
     * A session's runtime can change under us without a `session.reclaimed`: an interrupted
     * turn that Hermes auto-continues comes back as a NEW live sid on the same stored id
     * (device-caught 2026-09-06 — the phone kept listening on the old sid and its transcript
     * froze at the last event it had received). `sessions.changed` is the gateway's only
     * word on it, so on every one we compare what `session.active_list` says a stored session
     * is running under with what we hold; a mismatch drops the stale lease, re-attaches, and
     * re-hydrates so the rows that streamed while we were deaf land in the timeline.
     */
    private suspend fun reconcileRuntimes() {
        val rpc = rpc ?: return
        if (storedToLive.isEmpty()) return
        val res = runCatching {
            rpc.request("session.active_list", buildJsonObject { }, timeoutMs = 15_000)
        }.getOrNull() ?: return
        val rows = res["sessions"] as? kotlinx.serialization.json.JsonArray ?: return
        val current = HashMap<String, String>()
        for (el in rows) {
            val o = el as? kotlinx.serialization.json.JsonObject ?: continue
            val stored = o.strOrNull("session_key") ?: continue
            val live = o.strOrNull("id") ?: continue
            current[stored] = live
        }
        for ((stored, held) in storedToLive.entries.toList()) {
            val now = current[stored] ?: continue // gone entirely: the reclaim path owns that
            if (now == held) continue
            android.util.Log.w("KeryxGw", "runtime rotated for $stored: $held -> $now — re-attaching")
            storedToLive.remove(stored)
            liveToStored.remove(held)
            val st = stores[stored]
            runCatching { attach(stored) }
                .onFailure { android.util.Log.w("KeryxGw", "re-attach after rotation failed for $stored", it) }
            if (st != null) runCatching { rehydrate(stored, st) }
                .onFailure { android.util.Log.w("KeryxGw", "re-hydrate after rotation failed for $stored", it) }
        }
    }

    private suspend fun refreshSessions() {
        val r = rest ?: return
        // Two fetches, not one filtered locally: scheduled runs can outnumber conversations
        // several to one, so a single page would be mostly machinery either way.
        r.sessions(limit = SESSION_PAGE, excludeSources = listOf(CRON_SOURCE)).onSuccess { rows ->
            val live = rows.filter { !it.archived }
            // A refresh re-reads the first page; pages the drawer already walked past stay
            // (2.10) — a `sessions.changed` must not fold the list back to fifty under you.
            val liveIds = live.mapTo(HashSet()) { it.id }
            val oldest = live.minOfOrNull { it.lastActive } ?: 0L
            val tail = _sessionRows.value.filter { it.id !in liveIds && it.lastActive < oldest }
            _sessionRows.value = live + tail
            if (tail.isEmpty()) _hasMoreSessions.value = rows.size >= SESSION_PAGE
        }
        r.sessions(limit = 100, sources = listOf(CRON_SOURCE)).onSuccess { rows ->
            _cronRows.value = rows.filter { !it.archived }
            // Late recognition: a run adopted before this page landed (a cold start that
            // opens straight into a report, a run adopted the minute it was written) was
            // stamped as a conversation. Now that the gateway has named it, the row is
            // corrected in place — the source is the drawer's whole basis for leaving
            // machinery out of the session list, and it must not depend on call order.
            val cronIds = rows.mapTo(HashSet()) { it.id }
            cronIds -= _joinedCron.value // a run you answered is a conversation, not machinery
            val pending = _pendingNew.value
            if (pending.any { it.id in cronIds && it.source != CRON_SOURCE }) {
                _pendingNew.value = pending.map {
                    if (it.id in cronIds && it.source != CRON_SOURCE) it.copy(source = CRON_SOURCE) else it
                }
            }
        }
    }

    fun observeCronSessions(): Flow<List<RoomProfile>> =
        _cronRows.map { rows -> rows.map(::toProfile) }
            .onStart { scope.launch { refreshSessions() } }

    private fun toProfile(r: GatewayRest.SessionRow) = RoomProfile(
        id = r.id,
        name = r.title.ifBlank { r.preview.ifBlank { r.id } },
        type = RoomType.DIRECT_MESSAGE,
        timestamp = r.lastActive,
        messageCount = r.messageCount,
        source = r.source,
        isActive = r.isActive,
        preview = r.preview,
        pinned = r.pinned,
        unread = r.unread,
    )

    /**
     * The drawer's second line, at zero gateway cost. A row you have opened this process life
     * already holds its transcript, so its newest line is the honest "last thing said"; a row
     * you have not opened answers with the gateway's own recognition preview (the session's
     * first user line, from the list call that already happened). What this never does is
     * hydrate: the old path fetched a transcript page AND `session.resume`d every row the
     * drawer laid out — a drawer of fifty sessions was fifty live agents on the gateway.
     */
    fun peekPreview(sessionId: String): Message? =
        stores[sessionId]?.takeIf { it.hydrated }?.messages?.value?.lastOrNull()

    /** Resolve stored id → live sid, resuming the session on the gateway if needed. */
    private suspend fun attach(storedId: String): String {
        storedToLive[storedId]?.let { return it }
        return attachMutex.withLock {
            storedToLive[storedId]?.let { return it }
            val rpc = rpc ?: error("gateway not connected")
            val res = rpc.request("session.resume", buildJsonObject {
                put("session_id", JsonPrimitive(storedId))
                put("cols", JsonPrimitive(100))
                put("omit_messages", JsonPrimitive(true))
                profileFor(storedId)?.let { put("profile", JsonPrimitive(it)) }
            })
            val live = res["session_id"]?.jsonPrimitive?.contentOrNull ?: error("resume returned no sid")
            storedToLive[storedId] = live
            liveToStored[live] = storedId
            // The ack's `info` IS a session.info for a session the gateway holds live — its
            // `usage` is the ring's reading. Only session.info EVENTS fed the meta before, and
            // those arrive at turn end: a room opened in a fresh process sat dark until the
            // next turn completed (device: "the context ring sometimes disappears", 2026-09-15).
            // The lazy shape (`lazy: true`, no agent) names the gateway's default model, not
            // this session's — it carries no usage either, so it is skipped whole.
            val info = res["info"] as? kotlinx.serialization.json.JsonObject
            if (info?.get("lazy")?.jsonPrimitive?.booleanOrNull != true) applyMeta(storedId, info)
            // Seed the model NOW: session.info only arrives after a turn completes, so an
            // untouched session had a blank model — which is why the composer's model pill
            // never appeared on a freshly opened chat.
            scope.launch {
                seedModelFromActiveList(live, storedId)
                seedGaugeFromBreakdown(live, storedId)
            }
            live
        }
    }

    /**
     * Light a ring the resume left dark. A cold-resumed agent (gateway restarted since) has no
     * prompt-token measurement until its first API call, so its session.info carries no
     * `context_used` at all — but the gateway's anchored figure (`session.context_breakdown`,
     * the payload the ring's tap sheet already reads) knows the window from the stored usage
     * anchor. One call, once per attach, only when nothing else lit the gauge first.
     */
    private suspend fun seedGaugeFromBreakdown(liveSid: String, storedId: String) {
        if (meta(storedId).value.contextGauge != null) return
        val rpc = rpc ?: return
        val res = runCatching {
            rpc.request("session.context_breakdown", buildJsonObject {
                put("session_id", JsonPrimitive(liveSid))
            }, timeoutMs = 15_000)
        }.getOrNull() ?: return
        val flow = meta(storedId)
        flow.value = flow.value.seedGauge(
            used = res["context_used"]?.jsonPrimitive?.longOrNull ?: 0L,
            max = res["context_max"]?.jsonPrimitive?.longOrNull ?: 0L,
            percent = res["context_percent"]?.jsonPrimitive?.intOrNull ?: 0,
            model = res.strOrNull("model").orEmpty(),
        )
    }

    /** Fill in a session's model from `session.active_list` (its rows carry `model`). */
    private suspend fun seedModelFromActiveList(liveSid: String, storedId: String) {
        if (meta(storedId).value.model.isNotBlank()) return
        val rpc = rpc ?: return
        val res = runCatching {
            rpc.request("session.active_list", buildJsonObject { }, timeoutMs = 15_000)
        }.getOrNull() ?: return
        val rows = res["sessions"] as? kotlinx.serialization.json.JsonArray ?: return
        for (el in rows) {
            val o = el as? kotlinx.serialization.json.JsonObject ?: continue
            if (o.strOrNull("id") != liveSid) continue
            o.strOrNull("model")?.takeIf { it.isNotBlank() }?.let { model ->
                val flow = meta(storedId)
                if (flow.value.model.isBlank()) flow.value = flow.value.copy(model = model)
            }
            return
        }
    }

    private fun store(storedId: String): SessionStore =
        stores.getOrPut(storedId) { SessionStore(storedId) }

    private suspend fun hydrate(storedId: String) {
        val st = store(storedId)
        if (st.hydrated) return
        val rest = rest ?: return
        // The open's clock (2.10): `adb logcat -s KeryxPerf` reads it on a release build —
        // Log.w survives R8 where Log.i does not. "Chat load time" is these two lines.
        val t0 = android.os.SystemClock.uptimeMillis()
        // Paint first (2.10): the page this phone saw last time, off disk, before the wire
        // answers — a session opens on its words, not on a blank. The Desktop's transcript-tail
        // cache ("bot wakes paint at ~0 ms"). The wire's page replaces it the moment it lands,
        // so a message undone or archived elsewhere shows for one round trip, then goes.
        if (!st.painted) readCachedPage(storedId)?.let { rows ->
            st.setHistory(rows, more = true)
            st.painted = true
            android.util.Log.w("KeryxPerf", "open ${storedId.take(8)}: painted ${rows.size} rows from cache in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        }
        rest.messagesRaw(storedId, limit = HISTORY_PAGE, profile = profileFor(storedId)).onSuccess { body ->
            val rows = rest.parseMessages(body)
            // A full page means there is almost certainly more behind it. Being wrong here is
            // cheap and self-correcting: the next page comes back empty and the affordance
            // retires itself.
            st.setHistory(rows, more = rows.size >= HISTORY_PAGE)
            st.hydrated = true
            android.util.Log.w("KeryxPerf", "open ${storedId.take(8)}: wire page ${rows.size} rows in ${android.os.SystemClock.uptimeMillis() - t0} ms" +
                (if (st.painted) " (cache painted first)" else " (no cache — first open on this phone)"))
            writeCachedPage(storedId, body)
        }.onFailure {
            android.util.Log.w("KeryxPerf", "open ${storedId.take(8)}: wire page failed after ${android.os.SystemClock.uptimeMillis() - t0} ms: ${it.message}")
        }
    }

    private fun cacheFile(storedId: String): java.io.File? =
        cacheDir?.resolve(storedId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".json")

    private suspend fun readCachedPage(storedId: String): List<MessageRow>? {
        val f = cacheFile(storedId) ?: return null
        val rest = rest ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (f.isFile) rest.parseMessages(f.readText()).takeIf { it.isNotEmpty() } else null }.getOrNull()
        }
    }

    private suspend fun writeCachedPage(storedId: String, body: String) {
        val f = cacheFile(storedId) ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(body)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }.onFailure { android.util.Log.w("KeryxGw", "transcript cache write failed for $storedId: ${it.message}") }
        }
    }

    fun historyState(sessionId: String): Flow<chat.keryx.core.model.HistoryState> =
        store(sessionId).history

    /**
     * Walk one page further into the past. Offset counts BACKWARDS from the newest message
     * (`order=latest`), so the offset for the next page is simply "how many rows we hold".
     */
    suspend fun loadEarlier(sessionId: String): Result<Unit> = runCatching {
        val st = store(sessionId)
        if (st.history.value.loading || !st.history.value.hasMore) return@runCatching
        val rest = rest ?: error("gateway not connected")
        st.historyLoading(true)
        val rows = try {
            rest.messages(sessionId, limit = HISTORY_PAGE, offset = st.historyOffset(), profile = profileFor(sessionId)).getOrThrow()
        } catch (e: Exception) {
            st.historyLoading(false)
            throw e
        }
        st.prependHistory(rows, more = rows.size >= HISTORY_PAGE)
    }

    // ---- ChatRepository -------------------------------------------------------------

    override fun isLoggedIn(): Flow<Boolean> = _loggedIn

    override fun currentUserId(): Flow<String?> = _loggedIn.map { ok ->
        if (!ok) null else "you@" + (runCatching { URI(settings.directGatewayUrl).host }.getOrNull() ?: "gateway")
    }

    // Sessions ARE the top-level items: the gateway has no rooms, so each gateway session
    // surfaces as one drawer row. The UI's Session(room.id, ...) convention then hands real
    // session ids to attach/hydrate — the old single pseudo-room fed its own id into
    // session.resume, which the gateway rightly 4007'd.
    // A session with zero messages is NOT in /api/sessions (verified live: the gateway
    // only lists sessions that have history). A brand-new chat therefore has no row to
    // select or highlight until its first turn — so we carry it locally until the
    // server list catches up. Without this, "New chat" silently went nowhere.
    private val _pendingNew = MutableStateFlow<List<RoomProfile>>(emptyList())

    /**
     * The phone's own activity stamps, by stored id: the moment you send into a session or
     * the agent's turn traffic arrives in one. The server's `last_active` catches up on the
     * next list pull (`sessions.changed` is floored to one broadcast per 2 s, then a REST
     * round trip), and a pull can be missed outright while the phone is backgrounded — so the
     * roster orders by the newest stamp EITHER side knows (see [RosterOrder]) rather than
     * waiting on the wire to say what the phone just did.
     */
    private val _localStamps = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun touchSession(storedId: String) {
        val next = RosterOrder.stamp(_localStamps.value, storedId, System.currentTimeMillis())
        if (next !== _localStamps.value) _localStamps.value = next
    }

    override fun getRooms(): Flow<List<RoomProfile>> =
        combine(serverRooms(), _pendingNew, _botRows, _localStamps) { server, pending, bots, stamps ->
            // A Bot Chat row wins over a server row with the same id (the default profile's
            // forever-chat can be a visible row when it predates the hidden-at-birth rule):
            // one id, one row, and it wears the bot's name rather than "Bot Chat".
            val botIds = bots.mapTo(HashSet()) { it.id }
            val known = server.map { it.id }.toSet() + botIds
            // Bots keep the roster's head (the Bots door orders them itself); everything else
            // is one list, newest activity first — a chat minted here and not yet listed, a
            // row just spoken into, and the gateway's page, in the one order that matters.
            val conversations = pending.filterNot { it.id in known } + server.filterNot { it.id in botIds }
            bots + RosterOrder.byActivity(RosterOrder.withLocalStamps(conversations, stamps))
        }

    override fun publishBotRows(rows: List<RoomProfile>) {
        _botRows.value = rows
    }

    override fun busySessionIds(): Flow<Set<String>> = _busyStored

    private fun serverRooms(): Flow<List<RoomProfile>> =
        combine(_loggedIn, _sessionRows, _cronRows, _joinedCron) { ok, rows, cron, joined ->
            if (!ok) emptyList()
            else {
                // The roster page is cron-free by construction (`exclude_sources`), so a run
                // you have answered has to be published from the cron page instead — with
                // the source cleared, because that source is precisely what the session list
                // filters on. Same row, same id, now a conversation.
                val listed = rows.mapTo(HashSet()) { it.id }
                val promoted = cron
                    .filter { it.id in joined && it.id !in listed }
                    .map { toProfile(it).copy(source = "") }
                rows.map(::toProfile) + promoted
            }
        }.onStart { scope.launch { refreshSessions() } }

    override fun getMessages(sessionId: String, limit: Int): Flow<List<Message>> {
        // Legacy guard: installs from the pseudo-room era may still have "gateway" persisted as
        // the last-open id. It is not a session; hand back a finite empty flow, never resume it.
        if (sessionId == GATEWAY_ROOM_ID) return flowOf(emptyList())
        val st = store(sessionId)
        return st.messages.onStart {
            startFollowing(sessionId, st)
            scope.launch {
                hydrate(sessionId)
                // Attach eagerly so live events for a turn started elsewhere still render.
                runCatching { attach(sessionId) }
                // Keryx's timeline pages by growing `limit`; each growth past what's loaded
                // pulls ONE more page — the ViewModel's own grew/timeout logic does the rest.
                val h = st.history.value
                if (limit > st.historyOffset() && h.hasMore && !h.loading) {
                    loadEarlier(sessionId)
                }
            }
        }.onCompletion { stopFollowing(st) }
    }

    /**
     * Follow a turn another door started (2.11.1). `attach` only streams what the runtime THIS
     * phone attached produces; a turn that came in through the browser extension, the terminal
     * or cron runs in the gateway process and lands rows in the session store with nothing
     * pushing them here. So while a room is open, its session row is polled — one small GET —
     * and when the row count grows past what the transcript reflects, with no local turn to
     * explain it, the newest page is re-read. The timeline then lights the working banner on
     * its own, exactly as it does for a run it opened onto mid-flight.
     */
    private fun startFollowing(storedId: String, st: SessionStore) {
        st.followers++
        if (st.followJob?.isActive == true) return
        st.followJob = scope.launch { followElsewhere(storedId, st) }
    }

    private fun stopFollowing(st: SessionStore) {
        st.followers = (st.followers - 1).coerceAtLeast(0)
        if (st.followers == 0) {
            st.followJob?.cancel()
            st.followJob = null
        }
    }

    private suspend fun followElsewhere(storedId: String, st: SessionStore) {
        var state = ForeignFollow.State()
        var delayMs = ForeignFollow.WARM_POLL_MS
        while (currentCoroutineContext().isActive) {
            delay(delayMs)
            val rest = rest
            val busy = storedId in _busyStored.value
            val pulse = if (rest == null || busy) null
                else rest.sessionPulse(storedId, profileFor(storedId)).getOrNull()
            val (next, action) = ForeignFollow.step(state, pulse, busyLocally = busy)
            state = next
            val now = System.currentTimeMillis()
            // The typing signal is the banner's authoritative "busy". A local turn owns it
            // while it runs; between local turns the gateway's own mid-turn label drives it,
            // so a foreign turn's long silent tool call keeps the banner lit — and a session
            // that ended (or a label gone stale) lets it settle.
            if (!busy && pulse != null) st.agentTyping.value = ForeignFollow.isLive(pulse, now)
            if (action == ForeignFollow.Action.REHYDRATE) {
                android.util.Log.w("KeryxGw", "follow ${storedId.take(8)}: gateway holds ${pulse?.messageCount} rows, re-reading")
                runCatching { rehydrate(storedId, st) }
                    .onFailure { android.util.Log.w("KeryxGw", "follow ${storedId.take(8)}: re-read failed", it) }
            }
            delayMs = ForeignFollow.nextDelayMs(pulse, now, action)
        }
    }

    /** The REST client while the door is open — the Archive's producer reads through it. */
    internal val restClient: GatewayRest? get() = rest

    /**
     * The newest [n] messages of a session WITHOUT opening it: a REST page, built through the
     * same transcript rules the timeline uses, no store, no `session.resume`. What the
     * notification watcher reads — it must never cost the gateway a live agent per row. A
     * store that is already hydrated answers from memory instead.
     */
    suspend fun peekLatest(sessionId: String, n: Int): List<Message> {
        stores[sessionId]?.takeIf { it.hydrated }?.messages?.value?.let { held ->
            if (held.isNotEmpty()) return held.takeLast(n)
        }
        val client = rest ?: return emptyList()
        val rows = client.messages(sessionId, limit = (n * 4).coerceAtLeast(8), profile = profileFor(sessionId))
            .getOrNull() ?: return emptyList()
        return chat.keryx.core.protocol.TranscriptBuilder.build(sessionId, rows).takeLast(n)
    }

    /**
     * The Archive's context view: the transcript around one row. Pages newest-first until the
     * row is in hand with [before] rows of ground beneath it, builds the whole stretch through
     * [TranscriptBuilder] so calls re-pair with their results across page seams, then cuts the
     * window. A row id that never turns up (deleted, compacted away) yields an empty view.
     */
    override suspend fun messagesAround(sessionId: String, eventId: String, before: Int, after: Int): List<Message> {
        val client = rest ?: return emptyList()
        val target = TranscriptPages.rowIdOf(eventId) ?: return emptyList()
        val walk = TranscriptPages.pageUntil(
            pageSize = HISTORY_PAGE,
            fetch = { offset -> client.messages(sessionId, limit = HISTORY_PAGE, offset = offset, profile = profileFor(sessionId)).getOrThrow() },
            // Enough once the target is in hand and there is at least a page of older rows
            // beneath it to draw the "before" side from.
            enough = { rows ->
                val i = rows.indexOfFirst { it.id == target }
                i >= 0 && i >= before
            },
        )
        val built = chat.keryx.core.protocol.TranscriptBuilder.build(sessionId, walk.rows)
        val at = built.indexOfFirst { it.id == eventId }
            .takeIf { it >= 0 }
            ?: built.indexOfFirst { TranscriptPages.rowIdOf(it.id) == target }
        return TranscriptPages.window(built, at, before, after)
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        val live = attach(sessionId)
        touchSession(sessionId) // you spoke here: this row is the newest thing you know of
        joinCronRun(sessionId)  // ... and if this was a report, it is a conversation now
        // Slash commands are CONSOLE verbs, not conversation — the TUI and desktop both
        // intercept them client-side and run slash.exec. Shipping "/compress" to the model
        // as chat text just gets a polite paragraph about compression. Long timeout:
        // /compress on a deep session legitimately takes a while.
        val trimmed = content.trim()
        // Compaction gets its own RPC — slash.exec would compact fine but only announces
        // the new session id through an event we might miss; session.compress returns it.
        val compressVerb = trimmed.substringBefore(' ')
        if (compressVerb == "/compress" || compressVerb == "/compact") {
            store(sessionId).localUserMessage(content)
            val note = compressSession(sessionId, trimmed.substringAfter(' ', "").trim())
            store(liveToStored[live] ?: sessionId).localSystemMessage(note)
            return
        }
        if (trimmed.startsWith("/") && trimmed.length > 1 && !trimmed.startsWith("//")) {
            val rpc = rpc ?: error("gateway not connected")
            store(sessionId).localUserMessage(content)
            // Compression runs the model over the whole context — on a deep session with a
            // local brain that is legitimately many minutes, not a hang.
            val slashTimeout =
                if (trimmed.startsWith("/compress") || trimmed.startsWith("/compact")) 600_000L
                else 180_000L
            val res = rpc.request("slash.exec", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("command", JsonPrimitive(trimmed))
            }, timeoutMs = slashTimeout)
            val out = res["output"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            // Several console verbs END this session and continue under a new stored id —
            // /new, /reset, /clear, /handoff all do. Nothing announces that, so reconcile
            // before echoing, or the output (and every later turn) addresses a dead session.
            reconcileStoredId(live)
            store(liveToStored[live] ?: sessionId).localSystemMessage(out.ifBlank { "✓ $trimmed" })
            return
        }
        store(sessionId).localUserMessage(content)
        rpc?.request("prompt.submit", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("text", JsonPrimitive(content))
        }) ?: error("gateway not connected")
    }

    override suspend fun sendReply(sessionId: String, content: String, replyToEventId: String) {
        // The gateway has no quote-reply concept; prepend context like the TUI does.
        sendMessage(sessionId, content)
    }

    // Uploaded-image bytes by local message id — serves the echo bubble's loader. History
    // images (a reloaded session) have no byte source yet; they render as file chips.
    private val localMediaBytes = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override suspend fun mediaBytes(sessionId: String, eventId: String): ByteArray? {
        localMediaBytes[eventId]?.let { return it }
        val path = remoteMediaPaths[eventId] ?: recoverRemoteMediaPath(sessionId, eventId) ?: return null
        val r = rest ?: return null
        // A MEDIA: value is either a path on the gateway host or an absolute URL the agent
        // linked. They are fetched two different ways, and asking the file endpoint for a URL
        // is an HTTP error, not a file.
        val fetch = if (isAbsoluteUrl(path)) r.downloadUrl(path) else r.downloadFile(path)
        return fetch.getOrElse { e ->
            android.util.Log.w("KeryxGw", "media download failed for $path: ${e.message}"); null
        }
    }

    /**
     * The path table is in-memory; a kept media bubble opened from the Archive after a
     * process restart arrives with a `<row>#media:<n>` id and no path. Re-read that
     * session's transcript, re-split, and the table fills back in — the same derivation the
     * live path made, so the id resolves to the same file.
     */
    private suspend fun recoverRemoteMediaPath(sessionId: String, eventId: String): String? {
        if (!eventId.contains(MEDIA_ID_SEP)) return null
        val rows = rest?.messages(sessionId, limit = HISTORY_PAGE)?.getOrNull() ?: return null
        expandMediaTags(chat.keryx.core.protocol.TranscriptBuilder.build(sessionId, rows))
        return remoteMediaPaths[eventId]
    }

    // ---- MEDIA:<path> hand-offs ------------------------------------------------------
    // The agent's way of handing over a file it wrote (a PDF, a rendered chart, an audio
    // clip): a `MEDIA:<path>` line in its prose. Desktop renders it as a download link; we
    // lift it out of the text into a media bubble of its own, keyed by a synthetic id whose
    // path is remembered here so mediaBytes() can fetch it over /api/files/download.
    private val remoteMediaPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val MEDIA_ID_SEP = "#media:"

    /** `http://` or `https://` — anything else is a path on the gateway host. */
    private fun isAbsoluteUrl(v: String): Boolean =
        v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)

    /** The transcript row a (possibly synthetic media) id belongs to — what the gateway knows. */
    private fun rowIdOf(eventId: String): String = eventId.substringBefore(MEDIA_ID_SEP)

    /**
     * Split every finished agent message that carries MEDIA tags into its prose plus one
     * media message per file. Streaming text is left alone (a half-typed path is not a file
     * yet); user/system rows never carry the convention. Ids are stable per (message, index)
     * so Compose keys survive re-publishes and the bitmap cache hits.
     */
    private fun expandMediaTags(list: List<Message>): List<Message> {
        if (list.none { it.sender == SenderType.HERMES && !it.isStreaming && it.mediaKind == null && MediaTags.hasTag(it.content) }) return list
        return buildList(list.size + 4) {
            for (m in list) {
                if (m.sender != SenderType.HERMES || m.isStreaming || m.mediaKind != null || !MediaTags.hasTag(m.content)) { add(m); continue }
                val split = MediaTags.split(m.content)
                if (split.refs.isEmpty()) { add(m); continue }
                if (split.text.isNotBlank() || m.toolCalls.isNotEmpty() || m.reasoning != null) add(m.copy(content = split.text))
                split.refs.forEachIndexed { i, ref ->
                    val id = "${m.id}$MEDIA_ID_SEP$i"
                    remoteMediaPaths[id] = ref.path
                    add(
                        Message(
                            id = id, roomId = m.roomId, sender = SenderType.HERMES,
                            content = "", timestamp = m.timestamp,
                            senderId = m.senderId, senderName = m.senderName,
                            mediaUrl = ref.path, mediaKind = ref.kind, fileName = ref.name,
                            replyToId = m.replyToId,
                        )
                    )
                }
            }
        }
    }

    /**
     * Image upload, desktop's remote-client path: `image.attach_bytes` queues the image on
     * the session, then `prompt.submit` fires the turn that carries it. A blank caption
     * submits empty text (an image-only turn is legitimate vision input); if the gateway
     * rejects that, the attach note ("[User attached image: …]") goes instead.
     */
    override suspend fun sendAttachment(sessionId: String, bytes: ByteArray, fileName: String, contentType: String, caption: String?) {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        if (contentType.startsWith("image/")) {
            val res = rpc.request("image.attach_bytes", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("content_base64", JsonPrimitive(b64))
                if (fileName.isNotBlank()) put("filename", JsonPrimitive(fileName))
            }, timeoutMs = 60_000)
            val attachNote = res["text"]?.jsonPrimitive?.contentOrNull ?: "[image attached]"
            val echoId = store(sessionId).localUserImage(caption.orEmpty(), fileName)
            localMediaBytes[echoId] = bytes
            val text = caption?.takeIf { it.isNotBlank() } ?: ""
            try {
                rpc.request("prompt.submit", buildJsonObject {
                    put("session_id", JsonPrimitive(live))
                    put("text", JsonPrimitive(text))
                })
            } catch (e: Exception) {
                if (text.isEmpty()) rpc.request("prompt.submit", buildJsonObject {
                    put("session_id", JsonPrimitive(live))
                    put("text", JsonPrimitive(attachNote))
                }) else throw e
            }
        } else {
            // Non-image files (C5): `file.attach` stages the bytes into the session
            // workspace and answers with a `@file:` ref the agent's file tools can read —
            // the prompt carries that ref (plus any caption) so the turn knows the file
            // exists. This branch used to be a bare require() that ESCAPED the launch and
            // took the process down when someone picked a PDF.
            val res = rpc.request("file.attach", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("data_url", JsonPrimitive("data:$contentType;base64,$b64"))
                if (fileName.isNotBlank()) put("name", JsonPrimitive(fileName))
            }, timeoutMs = 120_000)
            val refText = res["ref_text"]?.jsonPrimitive?.contentOrNull
                ?: error("file.attach answered without a ref")
            store(sessionId).localUserFile(caption.orEmpty(), fileName.ifBlank {
                res["name"]?.jsonPrimitive?.contentOrNull ?: "file"
            })
            rpc.request("prompt.submit", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put(
                    "text",
                    JsonPrimitive(
                        listOfNotNull(caption?.takeIf { it.isNotBlank() }, refText)
                            .joinToString("\n\n"),
                    ),
                )
            })
        }
    }

    // The gateway's read state is a watermark (`last_read_at`), not a per-event receipt: one
    // stamp says "read up to now". The chat screen asks once per newest message while a
    // session is open, so this is one small PATCH per turn — and it is what makes a session
    // the agent touches later (a cron continuation, another client) read as unread in the
    // drawer. Optimistic on the local row: the list call that confirms it is not worth the
    // round trip for a flag we just set.
    override suspend fun markRead(sessionId: String, eventId: String) {
        // One stamp per newest message: the screen re-asks on every recomposition of the
        // same tail, and the gateway's answer to a repeat is a write that changes nothing.
        if (readStamps[sessionId] == eventId) return
        readStamps[sessionId] = eventId
        markSessionRead(sessionId, read = true)
    }

    private val readStamps = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun markSessionRead(sessionId: String, read: Boolean): Result<Unit> {
        val rest = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        val row = _sessionRows.value.firstOrNull { it.id == sessionId }
        if (!read) readStamps.remove(sessionId) // an explicit unread mark re-arms the next stamp
        if (row != null) {
            _sessionRows.value = _sessionRows.value.map { if (it.id == sessionId) it.copy(unread = !read) else it }
        }
        return rest.patchSession(sessionId, unread = !read, profile = profileFor(sessionId))
            .onFailure { android.util.Log.w("KeryxGw", "read mark failed for $sessionId: ${it.message}") }
    }

    override suspend fun pinSession(sessionId: String, pinned: Boolean): Result<Unit> {
        val rest = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        // Optimistic: the row moves NOW; the refresh that follows is the gateway agreeing.
        _sessionRows.value = _sessionRows.value.map { if (it.id == sessionId) it.copy(pinned = pinned) else it }
        return rest.patchSession(sessionId, pinned = pinned, profile = profileFor(sessionId))
            .onSuccess { refreshSessions() }
            .onFailure {
                android.util.Log.e("KeryxGw", "pin failed for $sessionId", it)
                refreshSessions() // the gateway's answer wins back over the optimistic row
            }
    }

    override suspend fun react(sessionId: String, eventId: String, emoji: String) {
        val rpc = rpc ?: return
        runCatching {
            val live = attach(sessionId)
            val key = rowIdOf(eventId)
            val rowId = key.toLongOrNull()
            val res = rpc.request("message.react", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("emoji", JsonPrimitive(emoji))
                if (rowId != null) put("row_id", JsonPrimitive(rowId))
                // A live message hasn't round-tripped through hydration, so it has no durable
                // row id yet — name the role whose newest row we mean instead (the wire's own
                // fallback for exactly this case; local-* ids are this user's sends).
                else put("newest_role", JsonPrimitive(if (key.startsWith("local-")) "user" else "assistant"))
            })
            val raw = (res["reactions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { r ->
                val o = r as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val e = o["emoji"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RawReaction(e, o["author"]?.jsonPrimitive?.contentOrNull ?: "user")
            } ?: emptyList()
            // Write under the key the bubble subscribed with AND the durable id the message
            // will wear after its next hydration, so the state survives the id handover.
            val landed = res["row_id"]?.jsonPrimitive?.contentOrNull
            store(sessionId).setReactions(
                listOfNotNull(key, landed).distinct(),
                MessageReactions.aggregate(raw),
            )
        }.onFailure { android.util.Log.w("KeryxGw", "react failed for $eventId", it) }
    }

    override fun reactionsFlow(sessionId: String, eventId: String): Flow<List<MessageReaction>> {
        val key = rowIdOf(eventId)
        return store(sessionId).reactions
            .map { it[key] ?: emptyList() }
            .distinctUntilChanged()
    }

    override fun typing(sessionId: String): Flow<TypingState> =
        store(sessionId).agentTyping.map { TypingState(agentTyping = it) }

    fun todoPlan(sessionId: String): Flow<chat.keryx.core.model.TodoPlan?> =
        store(sessionId).todoPlan

    // "Leaving" a session = archiving it: it drops out of the list but stays recoverable
    // (and searchable) on the gateway. Deletion (below) is the real, destructive verb.
    override suspend fun archiveSession(sessionId: String): Result<Unit> {
        // Drop the local pending row FIRST. A session created this run but never messaged
        // exists only here (the gateway doesn't list message-less sessions), so archiving it
        // server-side removed nothing while this list kept re-showing the row — the archive
        // looked like it silently failed.
        val wasPending = _pendingNew.value.any { it.id == sessionId }
        _pendingNew.value = _pendingNew.value.filterNot { it.id == sessionId }
        if (wasPending && _sessionRows.value.none { it.id == sessionId }) {
            // Never persisted: nothing to archive on the gateway. Local removal IS the archive.
            return Result.success(Unit)
        }
        val rest = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        // Every session-scoped call names its profile (2.8): a Bot Chat lives in ITS profile's
        // store, and a PATCH without the name edits the launch profile's store instead.
        return rest.patchSession(sessionId, archived = true, profile = profileFor(sessionId))
            .onSuccess { refreshSessions() }
            .onFailure { android.util.Log.e("KeryxGw", "archive failed for $sessionId", it) }
    }

    override suspend fun unarchiveSession(sessionId: String): Result<Unit> {
        val rest = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        return rest.patchSession(sessionId, archived = false, profile = profileFor(sessionId))
            .onSuccess { refreshSessions() }
            .onFailure { android.util.Log.e("KeryxGw", "unarchive failed for $sessionId", it) }
    }

    override suspend fun archivedSessions(): Result<List<RoomProfile>> {
        val rest = rest ?: return Result.failure(IllegalStateException("gateway not connected"))
        return rest.sessions(limit = 100, excludeSources = listOf(CRON_SOURCE), archived = "only")
            .map { rows -> rows.map(::toProfile) }
    }

    override suspend fun renameSession(sessionId: String, title: String): Result<Unit> =
        (rest?.patchSession(sessionId, title = title, profile = profileFor(sessionId)) ?: Result.failure(IllegalStateException("gateway not connected")))
            .onSuccess { refreshSessions() }

    override suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        val rest = rest ?: error("gateway not connected")
        rest.deleteSession(sessionId, profile = profileFor(sessionId)).getOrThrow()
        // Drop any live mapping + store; then re-pull the list so the row leaves the drawer.
        _pendingNew.value = _pendingNew.value.filterNot { it.id == sessionId }
        profileOf.remove(sessionId)
        storedToLive.remove(sessionId)?.let { liveToStored.remove(it) }
        stores.remove(sessionId)
        refreshSessions()
    }

    override suspend fun searchSessions(query: String, limit: Int): Result<List<SessionSearchHit>> =
        (rest?.searchSessions(query, limit) ?: Result.failure(IllegalStateException("gateway not connected")))
            .map { hits ->
                hits.map { SessionSearchHit(it.sessionId, it.title, it.snippet, it.role, it.lastActive) }
            }

    // ---- busy-turn inputs (C6: steer / queue) ---------------------------------------

    override suspend fun modelOptions(sessionId: String): Result<chat.keryx.core.model.ModelCatalog> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        // Long-handler pool on the gateway (pricing, tier, custom-endpoint probes): seconds.
        // explicit_only is the desktop picker's dialect: rows the user signed into or
        // configured in Hermes. Without it the gateway also lists providers it borrows
        // ambient credentials for (a `gh` CLI token seeding Copilot) — routes nobody chose.
        val res = rpc.request("model.options", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("explicit_only", JsonPrimitive(true))
            put("include_unconfigured", JsonPrimitive(false))
            put("refresh", JsonPrimitive(false))
        }, timeoutMs = 45_000)
        chat.keryx.core.model.ModelCatalog.parse(res)
    }

    override suspend fun selectModel(
        sessionId: String,
        model: String,
        provider: String?,
        confirm: Boolean,
    ): Result<chat.keryx.core.model.ModelSwitchOutcome> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        // There is no model.set — the setter is config.set with the raw /model grammar.
        val value = buildString {
            append(model)
            if (!provider.isNullOrBlank()) append(" --provider ").append(provider)
            append(" --session")
        }
        val res = rpc.request("config.set", buildJsonObject {
            put("key", JsonPrimitive("model"))
            put("value", JsonPrimitive(value))
            put("session_id", JsonPrimitive(live))
            put("confirm_expensive_model", JsonPrimitive(confirm))
        }, timeoutMs = 60_000)
        chat.keryx.core.model.ModelSwitchOutcome.parse(res)
    }

    // The Shipyard moved off this seam to ShipyardRest (Hermes Link base) — this door's
    // REST base never mounts the git routes (2.6.0 device walk, 08-31).

    // ---- Projects (harvested from Talaria 08-28; shapes fixture-captured live 08-15) ----
    // A mid-turn session refuses a move server-side (4009) rather than yanking the
    // workspace out from under its tools.

    override suspend fun projectsTree(): Result<chat.keryx.core.model.ProjectsTree> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        chat.keryx.core.protocol.ProjectsParser.parseTree(
            rpc.request("projects.tree", buildJsonObject { put("preview_limit", JsonPrimitive(3)) })
        ) ?: error("unrecognized projects.tree payload")
    }

    override suspend fun projectSessions(projectId: String): Result<chat.keryx.core.model.ProjectTreeNode?> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("projects.project_sessions", buildJsonObject {
            put("project_id", JsonPrimitive(projectId))
        })
        chat.keryx.core.protocol.ProjectsParser.parseTreeNode(res["project"])
    }

    override suspend fun projectsCatalog(): Result<chat.keryx.core.model.ProjectsCatalog> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        chat.keryx.core.protocol.ProjectsParser.parseCatalog(rpc.request("projects.list"))
            ?: error("unrecognized projects.list payload")
    }

    override suspend fun createProject(name: String, folderPath: String?): Result<chat.keryx.core.model.ProjectInfo> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("projects.create", buildJsonObject {
            put("name", JsonPrimitive(name))
            if (!folderPath.isNullOrBlank()) {
                put("primary_path", JsonPrimitive(folderPath))
                put("folders", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(folderPath)) })
            }
        })
        chat.keryx.core.protocol.ProjectsParser.parseProject(res["project"]) ?: error("create returned no project")
    }

    override suspend fun deleteProject(projectId: String): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        rpc.request("projects.delete", buildJsonObject { put("id", JsonPrimitive(projectId)) })
        Unit
    }

    override suspend fun archiveProject(projectId: String, restore: Boolean): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        rpc.request("projects.archive", buildJsonObject {
            put("id", JsonPrimitive(projectId))
            if (restore) put("restore", JsonPrimitive(true))
        })
        Unit
    }

    // `complete.path` in `@folder:` mode is the only stock directories-only listing. Its
    // `text` is rebased on the gateway's completion cwd — only `display` is a usable name,
    // so the caller keeps the prefix. Hard 30-item cap, unannounced (FolderPage.truncated).
    override suspend fun listFolders(query: String): Result<chat.keryx.core.model.FolderPage> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        chat.keryx.core.protocol.FolderCompletionParser.parse(
            rpc.request("complete.path", buildJsonObject { put("word", JsonPrimitive("@folder:$query")) })
        )
    }

    override suspend fun folderExists(path: String): Result<Boolean> = runCatching {
        val clean = path.trim().trimEnd('/')
        if (clean.isBlank()) return@runCatching false
        // Ask the PARENT for a child named exactly this — a listing of the folder itself
        // can't tell "empty" from "absent".
        val name = clean.substringAfterLast('/')
        listFolders(clean).getOrThrow().names.any { it == name }
    }

    override suspend fun moveSessionToProject(sessionId: String, cwd: String): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        // Keys on the STORED id (session_key) — no live agent needed.
        rpc.request("session.workspace.move", buildJsonObject {
            put("session_key", JsonPrimitive(sessionId))
            put("cwd", JsonPrimitive(cwd))
        })
        refreshSessions()
    }

    override suspend fun createSessionIn(title: String?, cwd: String): Result<String> =
        createSession(title?.ifBlank { null }, cwd)

    override fun adoptSession(sessionId: String, title: String) {
        if (_pendingNew.value.any { it.id == sessionId }) return
        _pendingNew.value = _pendingNew.value + RoomProfile(
            id = sessionId,
            name = title.ifBlank { "Session" },
            type = RoomType.DIRECT_MESSAGE,
            timestamp = System.currentTimeMillis(),
            // An adopted row wears the source the GATEWAY gave the session, not a blank one.
            // The roster's own page is already cron-free ([CRON_SOURCE] is excluded server
            // side), so a scheduled run only ever reaches the drawer by being adopted — and
            // a row with no source reads as a conversation, which is how reading one report
            // put it in the session list and left it there for the rest of the process life.
            source = adoptedSource(sessionId),
        )
    }

    /**
     * What the gateway calls the session being adopted, as far as this transport can tell.
     * The cron page ([_cronRows], server truth) is the only thing asked: every door into a
     * run — the drawer's tile, the Runs place, a notification tap, a project drill-in —
     * adopts through here, so recognising the run once beats stamping it at four call sites.
     * A miss (the cron page has not landed yet on a cold start, or the run is newer than the
     * last pull) is corrected by the next [refreshSessions].
     */
    private fun adoptedSource(sessionId: String): String =
        if (sessionId !in _joinedCron.value && _cronRows.value.any { it.id == sessionId }) CRON_SOURCE
        else ""

    /**
     * You sent into [sessionId]: if the gateway calls it a scheduled run, the session list
     * carries it from now on — you are talking to it, and the reason a run is kept out of
     * that list (a report is machinery you read in the Runs door) stopped being true the
     * moment you answered.
     *
     * Asked of the same oracle [adoptedSource] uses — the cron page — so the promotion covers
     * exactly the rows the hiding covers: a run too old to be on that page was never stamped
     * cron and was never hidden, and must not be written into the ledger either.
     *
     * Whether a slash verb counts is deliberate: it does. `/compress` or `/model` in a run is
     * work you are doing in that session, not a report you glanced at.
     */
    private fun joinCronRun(sessionId: String) {
        if (sessionId in _joinedCron.value) return
        if (_cronRows.value.none { it.id == sessionId }) return
        val next = _joinedCron.value + sessionId
        _joinedCron.value = next
        settings.joinedCronSessions = next
        // The adopted row is a conversation row as of now — the list must not wait on a pull.
        _pendingNew.value = _pendingNew.value.map {
            if (it.id == sessionId && it.source == CRON_SOURCE) it.copy(source = "") else it
        }
    }

    // ---- Bot Mode (2.8) -----------------------------------------------------------------

    override suspend fun botRoster(): Result<chat.keryx.core.model.BotRosterSnapshot> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        // Per-profile skill walks on the gateway side — a slow-ish call, so callers poll it
        // on a place's cadence, never per keystroke.
        val res = rpc.request("profiles.list", buildJsonObject {}, timeoutMs = 45_000)
        chat.keryx.core.model.BotsJson.snapshot(res, System.currentTimeMillis()).also { _agents.value = it.bots }
    }

    /** The last roster anyone fetched — the shade names a session's agent from it. */
    private val _agents = MutableStateFlow<List<chat.keryx.core.model.BotProfile>>(emptyList())
    fun agents(): StateFlow<List<chat.keryx.core.model.BotProfile>> = _agents

    /** The profile agent behind [storedId] as this install named it, or null if not known yet. */
    fun agentFor(storedId: String): chat.keryx.core.model.BotProfile? =
        chat.keryx.core.model.BotRoster.agentFor(_agents.value, profileFor(storedId))

    /** Name the profile on the wire only when it is not the launch profile's own. */
    private fun JsonObjectBuilder.putProfile(bot: chat.keryx.core.model.BotProfile) {
        if (!bot.isDefault) put("profile", JsonPrimitive(bot.name))
    }

    /** Remember which store holds [storedId] so every later call names it. */
    private fun registerProfile(storedId: String, bot: chat.keryx.core.model.BotProfile) {
        if (!bot.isDefault) profileOf[storedId] = bot.name
    }

    override suspend fun openBotChat(
        bot: chat.keryx.core.model.BotProfile,
        kickoff: Boolean,
    ): Result<chat.keryx.core.model.BotChatRef> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        // THE identity lookup: the profile's session titled exactly "Bot Chat", hidden rows
        // included (canonical chats are born hidden). The gateway answers this with an
        // indexed WHERE title = ? — window-free, so a busy profile cannot push its
        // forever-chat past a recency window. A failure here is an ERROR: a swallowed miss
        // would mint a second Bot Chat while the real one (data intact, hidden) still held
        // the title — the "my bot lost all context" report, avoided by construction.
        val listed = rpc.request("session.list", buildJsonObject {
            putProfile(bot)
            put("title", JsonPrimitive(chat.keryx.core.model.BotRoster.CANONICAL_TITLE))
            put("include_hidden", JsonPrimitive(true))
            put("limit", JsonPrimitive(50))
        })
        if (listed["sessions"] !is kotlinx.serialization.json.JsonArray) {
            error("could not check ${bot.label}'s Bot Chat registry — not starting a new chat")
        }
        chat.keryx.core.model.BotsJson.canonicalFromList(listed)?.let { found ->
            registerProfile(found.id, bot)
            registerProfile(found.openId, bot)
            return@runCatching found
        }
        // A genuine miss: mint the ONE forever chat. Hidden from the global list at birth;
        // follow_profile_config so a resume never restores a stale model pin from the row.
        val created = rpc.request("session.create", buildJsonObject {
            putProfile(bot)
            put("title", JsonPrimitive(chat.keryx.core.model.BotRoster.CANONICAL_TITLE))
            put("hidden", JsonPrimitive(true))
            put("follow_profile_config", JsonPrimitive(true))
            put("cols", JsonPrimitive(100))
        })
        val live = created["session_id"]?.jsonPrimitive?.contentOrNull ?: error("create returned no sid")
        val stored = created["stored_session_id"]?.jsonPrimitive?.contentOrNull ?: live
        storedToLive[stored] = live
        liveToStored[live] = stored
        registerProfile(stored, bot)
        // session.create is lazy — no DB row until the first prompt — and the auto-titler can
        // beat the deferred title. Writing the title NOW materializes the row under the
        // canonical name, so a second tap during the intro turn adopts it instead of
        // minting a twin. "already in use" means another writer won the race: adopt theirs.
        val titled = runCatching {
            rpc.request("session.title", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("title", JsonPrimitive(chat.keryx.core.model.BotRoster.CANONICAL_TITLE))
            })
        }
        titled.exceptionOrNull()?.let { e ->
            if (e.message?.contains("already in use", ignoreCase = true) == true) {
                val again = rpc.request("session.list", buildJsonObject {
                    putProfile(bot)
                    put("title", JsonPrimitive(chat.keryx.core.model.BotRoster.CANONICAL_TITLE))
                    put("include_hidden", JsonPrimitive(true))
                })
                chat.keryx.core.model.BotsJson.canonicalFromList(again)?.let { winner ->
                    registerProfile(winner.id, bot); registerProfile(winner.openId, bot)
                    return@runCatching winner
                }
            }
        }
        store(stored).hydrated = true // nothing to hydrate yet; the intro turn streams in live
        if (kickoff || titled.isFailure) {
            // A newborn introduces itself; and on a gateway without the eager title the
            // prompt is what persists the row at all.
            store(stored).localUserMessage(chat.keryx.core.model.BotRoster.KICKOFF)
            rpc.request("prompt.submit", buildJsonObject {
                put("session_id", JsonPrimitive(live))
                put("text", JsonPrimitive(chat.keryx.core.model.BotRoster.KICKOFF))
            })
        }
        chat.keryx.core.model.BotChatRef(id = stored, resolvedId = stored, lastActive = System.currentTimeMillis())
    }

    override suspend fun configureBot(
        name: String,
        meta: kotlinx.serialization.json.JsonObject?,
        description: String?,
    ): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("profiles.configure", buildJsonObject {
            put("name", JsonPrimitive(name))
            if (meta != null) put("ui_meta", buildJsonObject { put("hermes-bots", meta) })
            if (description != null) put("description", JsonPrimitive(description))
        })
        val applied = res["applied"] as? kotlinx.serialization.json.JsonObject
        if (meta != null && applied?.get("ui_meta")?.jsonPrimitive?.contentOrNull == "false") {
            error("the gateway refused the bot's metadata" +
                (applied["ui_meta_conflicts"]?.let { " (edited elsewhere — try again)" } ?: ""))
        }
    }

    override suspend fun createBot(name: String, description: String?, cloneFrom: String?): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        rpc.request("profiles.create", buildJsonObject {
            put("name", JsonPrimitive(name))
            description?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
            cloneFrom?.takeIf { it.isNotBlank() }?.let { put("clone_from", JsonPrimitive(it)) }
        }, timeoutMs = 90_000)
        Unit
    }

    override suspend fun botAvatar(name: String): Result<ByteArray?> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("profiles.get_asset", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("asset", JsonPrimitive("avatar"))
        })
        val data = res["data"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val b64 = data.substringAfter(",", "")
        if (b64.isBlank()) null
        else runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }

    suspend fun steerTurn(sessionId: String, text: String): Result<Boolean> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        // session.steer lands the text on the LAST tool result of the next tool batch —
        // no interrupt, no new turn, no alternation violation (mirrors AIAgent.steer()).
        val res = rpc.request("session.steer", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("text", JsonPrimitive(text))
        })
        val accepted = res.strOrNull("status") == "queued"
        if (accepted) {
            // The gateway records the correction on the inflight turn server-side; echo it
            // locally the same way slash sends do, so the user sees their words land NOW.
            store(sessionId).localUserMessage(text)
        }
        accepted
    }

    suspend fun queuePrompt(sessionId: String, text: String): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        // queued=true is load-bearing: a mid-turn prompt.submit WITHOUT it interrupts the
        // live turn by default (verified in methods_prompt.py) — the exact silent damage
        // this path exists to prevent.
        rpc.request("prompt.submit", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("text", JsonPrimitive(text))
            put("queued", JsonPrimitive(true))
        }, timeoutMs = 30_000)
        Unit
    }

    /**
     * Raw JSON-RPC passthrough for the wake lease (`wake.start/stop/pause/resume/status/feed`).
     * The lease is TRANSPORT-bound on the server (owner = this socket), which is why the ear
     * controller must ride the repository's one socket rather than dial its own: a second
     * socket would be a second owner. Throws when the socket is down.
     */
    suspend fun gatewayRequest(
        method: String,
        params: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
    ): kotlinx.serialization.json.JsonObject {
        val rpc = rpc ?: error("gateway not connected")
        return rpc.request(method, params, timeoutMs)
    }

    // Reasoning effort (config key `reasoning`) — desktop's model-menu wire, probed live:
    // `config.get` answers {value, display} and resolves the SESSION's override before the
    // profile default when a session_id rides along; `config.set` echoes the authoritative
    // value and rejects an unknown level with 4002 rather than landing it silently.
    //
    // ⚠️ The session id on this wire is the LIVE sid (`_sessions[…]` on the gateway), not the
    // stored one — so it goes through attach() like every other session-scoped call.
    suspend fun reasoningEffort(sessionId: String?): Result<String> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("config.get", buildJsonObject {
            put("key", JsonPrimitive("reasoning"))
            sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", JsonPrimitive(attach(it))) }
        })
        res.strOrNull("value").orEmpty().trim().lowercase()
    }

    suspend fun setReasoningEffort(
        sessionId: String?,
        effort: String,
        global: Boolean,
    ): Result<String> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("config.set", buildJsonObject {
            put("key", JsonPrimitive("reasoning"))
            put("value", JsonPrimitive(effort))
            // Scope is decided by what we send: a session id makes it session-scoped, and
            // `scope: global` overrides that to write config.yaml. Sending the session id even
            // for a global write is what desktop does — the live agent adopts the level in the
            // same call instead of waiting for the next session.
            sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", JsonPrimitive(attach(it))) }
            if (global) put("scope", JsonPrimitive("global"))
        })
        res.strOrNull("value").orEmpty().trim().lowercase()
    }

    /** String-ish field reader: JSON booleans/numbers arrive as primitives too. */
    private fun kotlinx.serialization.json.JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // --- session rotation (compaction fork) ------------------------------------------

    /** Turn-fatal errors whose text says the model refused the reasoning level. */
    private val _reasoningRejections = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun reasoningRejections(): Flow<String> = _reasoningRejections

    private val _turnEvents = MutableSharedFlow<chat.keryx.core.model.TurnEvent>(extraBufferCapacity = 256)

    fun turnEvents(): Flow<chat.keryx.core.model.TurnEvent> = _turnEvents

        /** (oldStoredId, newStoredId) each time compaction re-anchors a live session. */
    private val _sessionRotations = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    fun sessionRotations(): Flow<Pair<String, String>> = _sessionRotations

    /** new stored id → the parent it was forked from (notification baseline inheritance). */
    private val rotationOrigins = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rotationOrigin(sessionId: String): String? = rotationOrigins[sessionId]

    private fun maybeRotateStored(liveSid: String, newStored: String) {
        val old = liveToStored[liveSid] ?: return
        if (old == newStored) return
        liveToStored[liveSid] = newStored
        storedToLive.remove(old)
        storedToLive[newStored] = liveSid
        rotationOrigins[newStored] = old
        // Carry live runtime state across; the transcript re-hydrates fresh under the new
        // id (REST serves the compaction summary + carried turns — exactly what happened).
        metaFlows[old]?.let { metaFlows.putIfAbsent(newStored, it) }
        approvalFlows[old]?.let { approvalFlows.putIfAbsent(newStored, it) }
        blockingFlows[old]?.let { blockingFlows.putIfAbsent(newStored, it) }
        // The shade entry + busy mark follow the fork: a respond keyed to the dead parent
        // would resume a session the gateway forgot.
        _shadePending.value[old]?.let { entry ->
            _shadePending.value = _shadePending.value.toMutableMap().apply {
                remove(old); put(newStored, entry)
            }
        }
        _runs.value[old]?.let { run ->
            _runs.value = _runs.value - old + (newStored to run.copy(sessionId = newStored))
        }
        if (old in _busyStored.value) {
            _busyStored.value = _busyStored.value - old + newStored
        }
        val oldStore = stores.remove(old)
        // Auto-compaction fires MID-TURN: the continuation store must know the agent is
        // still working, or the typing/working animation dies at the fork (device-caught).
        if (oldStore?.agentTyping?.value == true) store(newStored).agentTyping.value = true
        scope.launch { refreshSessions() }
        _sessionRotations.tryEmit(old to newStored)
        android.util.Log.i("KeryxGw", "session rotated (compaction): $old -> $newStored")
    }

    /**
     * Re-resolve a live sid's CURRENT stored id from `session.active_list` (rows carry
     * `id` = live sid, `session_key` = stored id).
     *
     * Auto-compaction inside a turn rotates the stored session and — unlike manual
     * /compress — emits NO `session.info`, so the client is never told (verified against
     * 0.20.1: the post-turn call site re-anchors then goes straight to message.complete).
     * Reconciling after each completed turn is the only honest way to notice.
     */
    private suspend fun reconcileStoredId(liveSid: String) {
        val rpc = rpc ?: return
        val res = runCatching {
            rpc.request("session.active_list", buildJsonObject { }, timeoutMs = 15_000)
        }.getOrNull() ?: return
        val rows = res["sessions"] as? kotlinx.serialization.json.JsonArray ?: return
        for (el in rows) {
            val o = el as? kotlinx.serialization.json.JsonObject ?: continue
            if (o.strOrNull("id") != liveSid) continue
            o.strOrNull("session_key")?.takeIf { it.isNotBlank() }
                ?.let { maybeRotateStored(liveSid, it) }
            return
        }
    }

    /**
     * Manual compaction, the deterministic path: `session.compress` returns the new
     * `info.stored_session_id` in its OWN response, so the handoff never depends on
     * catching an event. Returns the human summary line for the transcript.
     */
    /** The window itemised (2.10) — `session.context_breakdown` on the live session. */
    suspend fun contextBreakdown(sessionId: String): Result<chat.keryx.core.model.ContextBreakdown> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val res = rpc.request("session.context_breakdown", buildJsonObject {
            put("session_id", JsonPrimitive(live))
        })
        val cats = (res["categories"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            chat.keryx.core.model.ContextCategory(
                id = o.strOrNull("id").orEmpty(),
                label = o.strOrNull("label") ?: o.strOrNull("id").orEmpty(),
                tokens = o["tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
        chat.keryx.core.model.ContextBreakdown(
            categories = cats,
            used = res["context_used"]?.jsonPrimitive?.longOrNull ?: 0L,
            max = res["context_max"]?.jsonPrimitive?.longOrNull ?: 0L,
            percent = res["context_percent"]?.jsonPrimitive?.intOrNull ?: 0,
            model = res.strOrNull("model").orEmpty(),
        )
    }

    /**
     * Take back the last exchange (2.10): `session.undo` drops the last user turn and
     * everything after it from the gateway's history. The gateway refuses under a running
     * turn (4009) — the caller reads that as the answer, never retries. Returns how many
     * messages left; the transcript is re-read so the phone shows what the gateway now holds.
     */
    suspend fun undoLastTurn(sessionId: String): Result<Int> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val res = rpc.request("session.undo", buildJsonObject {
            put("session_id", JsonPrimitive(live))
        })
        val removed = res["removed"]?.jsonPrimitive?.intOrNull ?: 0
        val st = store(sessionId)
        st.clearLocal()
        rehydrate(sessionId, st)
        removed
    }

    private suspend fun compressSession(sessionId: String, focusTopic: String): String {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val res = rpc.request("session.compress", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            if (focusTopic.isNotBlank()) put("focus_topic", JsonPrimitive(focusTopic))
        }, timeoutMs = 600_000)
        (res["info"] as? kotlinx.serialization.json.JsonObject)
            ?.strOrNull("stored_session_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { maybeRotateStored(live, it) }
        val status = res.strOrNull("status") ?: "compressed"
        val removed = res.strOrNull("removed")?.toIntOrNull() ?: 0
        val before = res.strOrNull("before_tokens")?.toIntOrNull()
        val after = res.strOrNull("after_tokens")?.toIntOrNull()
        return when {
            status == "aborted" -> "Compression aborted — history unchanged."
            removed <= 0 -> "Nothing to compress yet."
            before != null && after != null ->
                "Compacted $removed messages · ~${before / 1000}k → ~${after / 1000}k tokens."
            else -> "Compacted $removed messages."
        }
    }

    // --- lifecycle status (compaction progress) ---------------------------------------
    private val statusFlows =
        java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<chat.keryx.core.model.SessionStatus?>>()

    private fun statusFlow(storedId: String) =
        statusFlows.getOrPut(storedId) { MutableStateFlow(null) }

    fun sessionStatus(sessionId: String): Flow<chat.keryx.core.model.SessionStatus?> =
        statusFlow(sessionId)

    // --- approvals -------------------------------------------------------------------
    private val approvalFlows =
        java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<chat.keryx.core.model.ApprovalRequest?>>()

    private fun approvalFlow(storedId: String) =
        approvalFlows.getOrPut(storedId) { MutableStateFlow(null) }

    fun pendingApproval(sessionId: String): Flow<chat.keryx.core.model.ApprovalRequest?> =
        approvalFlow(sessionId)

    // --- the shade's merged view -------------------------------------------------------
    // One map across all sessions so the notification layer never has to know which
    // per-session flows exist. Every write to approvalFlow/blockingFlow goes through
    // setApproval/setBlocking so the two views cannot drift; rotation moves entries to the
    // continuation id (responding to the DEAD parent id after a compaction fork would
    // resume a session the gateway forgot — the 0.5.2 lesson, now load-bearing here too).
    private val _shadePending =
        MutableStateFlow<Map<String, chat.keryx.core.model.ShadePendingEntry>>(emptyMap())

    fun shadePending(): Flow<Map<String, chat.keryx.core.model.ShadePendingEntry>> =
        _shadePending

    private fun updateShade(
        storedId: String,
        fn: (chat.keryx.core.model.ShadePendingEntry) -> chat.keryx.core.model.ShadePendingEntry,
    ) {
        _shadePending.value = _shadePending.value.toMutableMap().apply {
            val next = fn(this[storedId] ?: chat.keryx.core.model.ShadePendingEntry())
            if (next.isEmpty) remove(storedId) else put(storedId, next)
        }
    }

    private fun setApproval(storedId: String, value: chat.keryx.core.model.ApprovalRequest?) {
        // The blanket clear runs on EVERY turn event (deltas included) — skip the map churn
        // when nothing changes, which is almost always.
        if (approvalFlow(storedId).value == value) return
        approvalFlow(storedId).value = value
        updateShade(storedId) { it.copy(approval = value) }
    }

    private fun setBlocking(storedId: String, value: chat.keryx.core.model.BlockingRequest?) {
        if (blockingFlow(storedId).value == value) return
        blockingFlow(storedId).value = value
        updateShade(storedId) { it.copy(blocking = value) }
    }

    // --- any-turn-in-flight (drives the background keep-alive) -------------------------
    private val _busyStored = MutableStateFlow<Set<String>>(emptySet())

    fun anyAgentBusy(): Flow<Boolean> = _busyStored.map { it.isNotEmpty() }

    private fun markBusy(storedId: String, busy: Boolean) {
        val cur = _busyStored.value
        if (busy == storedId in cur) return // already in the right state; runs per delta
        // The run's activity is born and dies with the busy mark, activity first: a reader that
        // wakes on the busy set must already find what the session is doing.
        _runs.value = if (busy) _runs.value + (storedId to chat.keryx.core.model.RunActivity(storedId, System.currentTimeMillis()))
            else _runs.value - storedId
        _busyStored.value = if (busy) cur + storedId else cur - storedId
    }

    // --- what each running turn is doing (drives the shade's run notice) ---------------
    private val _runs = MutableStateFlow<Map<String, chat.keryx.core.model.RunActivity>>(emptyMap())

    /** Every turn in flight on this socket, by stored id: phase, the tool that is out, the tally. */
    fun runActivities(): StateFlow<Map<String, chat.keryx.core.model.RunActivity>> = _runs

    /** The agent's own plan for [storedId], if it has written one — the run notice's progress. */
    fun todoPlanOf(storedId: String): chat.keryx.core.model.TodoPlan? = stores[storedId]?.todoPlan?.value

    private fun noteRun(storedId: String, eventType: String, toolId: String = "", toolName: String = "", toolContext: String = "") {
        val prev = _runs.value[storedId] ?: return
        val next = chat.keryx.core.model.RunActivities.reduce(prev, eventType, toolId, toolName, toolContext)
        if (next !== prev) _runs.value = _runs.value + (storedId to next)
    }

    /**
     * The gateway's OWN command registry (`commands.catalog`) — 246 commands here, versus
     * the 11 hardcoded guesses the palette shipped with (which is why `/stop` was missing).
     * `pairs` is [[name, description]]; the description carries "(usage: …)" for the ones
     * that take arguments, which is exactly the fill-vs-send signal the palette needs.
     */
    suspend fun commandCatalog(): Result<List<chat.keryx.core.model.GatewayCommand>> =
        runCatching {
            val rpc = rpc ?: error("gateway not connected")
            val res = rpc.request("commands.catalog", buildJsonObject { }, timeoutMs = 30_000)
            val pairs = res["pairs"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            pairs.mapNotNull { el ->
                val row = el as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                val name = (row.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val desc = (row.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
                chat.keryx.core.model.GatewayCommand(
                    cmd = name,
                    description = desc.substringBefore(" (usage:").trim(),
                    takesArgs = desc.contains("usage:"),
                )
            }
        }

    suspend fun interruptTurn(sessionId: String): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        rpc.request("session.interrupt", buildJsonObject {
            put("session_id", JsonPrimitive(live))
        })
        Unit
    }

    // --- blocking requests (clarify / sudo / secret) ----------------------------------
    private val blockingFlows =
        java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<chat.keryx.core.model.BlockingRequest?>>()

    private fun blockingFlow(storedId: String) =
        blockingFlows.getOrPut(storedId) { MutableStateFlow(null) }

    fun pendingBlocking(sessionId: String): Flow<chat.keryx.core.model.BlockingRequest?> =
        blockingFlow(sessionId)

    /**
     * Answer whatever the agent is blocked on. The respond methods key off `request_id`
     * alone (no session scope), and each reads its answer from its own parameter — hence
     * [BlockingKind.answerKey]. An empty [answer] is the wire's "skipped", which the
     * gateway handles gracefully, so Skip is just a blank answer rather than a silent drop.
     *
     * A stale card resolves as `{"status":"expired"}` instead of erroring (every one of
     * these sets allow_expired), so answering late is safe — it is simply ignored.
     */
    suspend fun respondBlocking(
        sessionId: String,
        requestId: String,
        kind: chat.keryx.core.model.BlockingKind,
        answer: String,
    ): Result<Unit> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        rpc.request("${kind.wire}.respond", buildJsonObject {
            put("request_id", JsonPrimitive(requestId))
            put(kind.answerKey, JsonPrimitive(answer))
        })
        if (blockingFlow(sessionId).value?.requestId == requestId) setBlocking(sessionId, null)
    }

    suspend fun respondApproval(sessionId: String, choice: String): Result<Boolean> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val res = rpc.request("approval.respond", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("choice", JsonPrimitive(choice))
        })
        setApproval(sessionId, null)
        // resolved=0 means the wait already failed closed (approvals.timeout) — the caller
        // must say "expired", not "approved".
        (res["resolved"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) > 0
    }

    suspend fun runSlash(sessionId: String, command: String): Result<String> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val live = attach(sessionId)
        val res = rpc.request("slash.exec", buildJsonObject {
            put("session_id", JsonPrimitive(live))
            put("command", JsonPrimitive(command))
        })
        res["output"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    // --- per-session runtime meta (model, context meter) -----------------------------
    private val metaFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<chat.keryx.core.model.SessionMeta>>()
    private fun meta(storedId: String) =
        metaFlows.getOrPut(storedId) { MutableStateFlow(chat.keryx.core.model.SessionMeta()) }

    fun sessionMeta(sessionId: String): Flow<chat.keryx.core.model.SessionMeta> = meta(sessionId)

    /** Fold a `session.info` / `message.complete` payload's runtime facts into the meta flow.
     *  Absent fields keep their last value — events carry different subsets. */
    private fun applyMeta(storedId: String, p: kotlinx.serialization.json.JsonObject?) {
        p ?: return
        val usage = p["usage"] as? kotlinx.serialization.json.JsonObject
        val flow = meta(storedId)
        val cur = flow.value
        flow.value = cur.copy(
            model = (p["model"] ?: usage?.get("model"))?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: cur.model,
            contextPercent = usage?.get("context_percent")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.toInt() ?: cur.contextPercent,
            contextUsed = usage?.get("context_used")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.toLong() ?: cur.contextUsed,
            contextMax = usage?.get("context_max")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.toLong() ?: cur.contextMax,
            // A level picked on ANY surface (desktop's model menu, the TUI's /reasoning) rides
            // home on session.info — so the pill tells the truth about a session this app
            // didn't configure. Blank means "the gateway didn't say", which must not erase a
            // level we already know.
            reasoningEffort = p["reasoning_effort"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: cur.reasoningEffort,
        )
    }

    override suspend fun setTyping(sessionId: String, typing: Boolean) { /* not surfaced */ }

    /**
     * Gateway "login": credentials already live in settings (LoginScreen writes them);
     * validate the token against an authed endpoint, then bring the WS up.
     */
    /** Gateway "login": credentials already live in settings (the login screen's direct door
     *  writes them); validate the token against an authed endpoint, then bring the WS up. */
    suspend fun login(): Result<Unit> {
        val probe = GatewayRest(
            settings.directGatewayUrl, settings.directApiKey, settings.allowInsecure,
            // Native mode authenticates this probe with the rotating bearer; without it a
            // gated gateway 401s the health check and the door looks dead while fine.
            DirectAuth(settings, settings.allowInsecure),
        )
        return probe.validateToken()
            .onSuccess {
                settings.directLoggedIn = true
                _loggedIn.value = true
                connectIfConfigured()
            }
            .map { }
    }

    override suspend fun logout() {
        rpc?.close(); rpc = null
        pumpJob?.cancel()
        stateJob?.cancel()
        _linkState.value = chat.keryx.core.model.LinkState.DISCONNECTED
        settings.directLoggedIn = false
        _loggedIn.value = false
    }

    override suspend fun createSession(title: String?): Result<String> = createSession(title, null)

    /** Create a fresh gateway session and return its stored id (used by New Chat UI).
     *  [cwd] anchors the session in a workspace (a project's folder); absent = the gateway's
     *  launch directory, i.e. the Home bucket. Kept as a separate overload so the seam stays
     *  workspace-free until the Projects surface lands and gives cwd a real picker. */
    suspend fun createSession(title: String?, cwd: String?): Result<String> = runCatching {
        val rpc = rpc ?: error("gateway not connected")
        val res = rpc.request("session.create", buildJsonObject {
            put("cols", JsonPrimitive(100))
            if (!title.isNullOrBlank()) put("title", JsonPrimitive(title))
            if (!cwd.isNullOrBlank()) put("cwd", JsonPrimitive(cwd))
        })
        val live = res["session_id"]?.jsonPrimitive?.contentOrNull ?: error("create returned no sid")
        val stored = res["stored_session_id"]?.jsonPrimitive?.contentOrNull ?: live
        storedToLive[stored] = live
        liveToStored[live] = stored
        // Carry it locally so it's selectable and visible before its first message.
        _pendingNew.value = _pendingNew.value.filterNot { it.id == stored } + RoomProfile(
            id = stored,
            name = title?.takeIf { it.isNotBlank() } ?: "New session",
            type = RoomType.DIRECT_MESSAGE,
            timestamp = System.currentTimeMillis(),
            messageCount = 0,
            source = "",
            isActive = true,
        )
        store(stored).hydrated = true // nothing to hydrate; skip the REST round trip
        refreshSessions()
        stored
    }
}
