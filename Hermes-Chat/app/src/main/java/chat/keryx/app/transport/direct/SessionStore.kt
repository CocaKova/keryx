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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/** Placeholder id for a tool.generating card, replaced by the real tool.start. */
private const val GHOST_TOOL_ID = "generating"
/** How often a streaming turn repaints the transcript; see [SessionStore.publishThrottled]. */
private const val STREAM_PUBLISH_MS = 100L

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
internal class SessionStore(
    val storedId: String,
    /** The transport's media-tag split; it records remote paths as it goes. */
    private val expandMediaTags: (List<Message>) -> List<Message>,
) {
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val agentTyping = MutableStateFlow(false)
    val history = MutableStateFlow(chat.keryx.core.model.HistoryState())
    var hydrated = false
    /** Open timelines on this store; the foreign-turn follower runs while it is > 0. */
    var followers = 0
    var followJob: Job? = null
    /** The cached page is on screen (a paint, not a hydration — the wire still owes the truth). */
    var painted = false

    /** Honest tok/s (2.16): this turn's streamed characters, timed, and the gateway's
     *  cumulative output/call counters as they stood at `message.start`. */
    val meter = chat.keryx.core.transport.StreamRateMeter()
    var baseOutput: Long? = null
    var baseCalls: Int? = null
    var lastRatePublishAt = 0L

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
    val isStreaming: Boolean get() = streaming
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
        rows.lastOrNull {
            it.role == "tool" && chat.keryx.core.model.TodoPlanParser.isTodoTool(it.toolName) &&
                chat.keryx.core.model.TodoPlanParser.parse(it.content) != null
        }?.let { row ->
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

    /** Newest row id held, or null before the first page (2.17.1). */
    fun newestRowId(): Long? = hydratedRows.maxOfOrNull { it.id }

    /**
     * A newer page, appended (2.17.1): the tail a foreign turn just wrote. Same identity
     * rule as [prependHistory]; rebuilt from ALL rows so a call and its result pair even
     * when the call was held and only the result is new.
     */
    fun appendHistory(newer: List<MessageRow>) {
        val have = hydratedRows.mapTo(HashSet()) { it.id }
        val fresh = newer.filterNot { it.id in have }.sortedBy { it.id }
        if (fresh.isEmpty()) return
        hydratedRows = hydratedRows + fresh
        seedReactions(fresh)
        hydratedMessages = chat.keryx.core.protocol.TranscriptBuilder.build(storedId, hydratedRows)
        history.value = history.value.copy(loading = false, loaded = hydratedRows.size)
        fresh.lastOrNull {
            it.role == "tool" && chat.keryx.core.model.TodoPlanParser.isTodoTool(it.toolName) &&
                chat.keryx.core.model.TodoPlanParser.parse(it.content) != null
        }?.let { row ->
            chat.keryx.core.model.TodoPlanParser.parse(row.content)?.let { todoPlan.value = it }
        }
        publish()
    }

    fun historyLoading(loading: Boolean) {
        history.value = history.value.copy(loading = loading)
    }

    /** Rows fetched so far = the offset the next (older) page starts at. */
    fun historyOffset(): Int = hydratedRows.size

    /** The persisted rows held, oldest first (2.16 branch-from-here counts over them). */
    fun rowsSnapshot(): List<MessageRow> = hydratedRows

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
                    risk = done.risk.ifBlank { prev.risk },
                    riskFindings = done.riskFindings.ifEmpty { prev.riskFindings },
                ),
            )
        } else {
            items += TurnItem.Tool(seq++, done) // start frame lost (reconnect); still show it
        }
        publish()
    }

    /** Hermes flagged a call's output. It arrives just after that call's completion. */
    fun toolRisk(toolId: String, risk: String, findings: List<String>) {
        val i = items.indexOfLast { it is TurnItem.Tool && it.call.toolId == toolId }
        if (i < 0) return
        val call = (items[i] as TurnItem.Tool).call
        items[i] = TurnItem.Tool(items[i].seq, call.copy(risk = risk, riskFindings = findings))
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
    ): String? {
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
        val settledId = (finMsg.lastOrNull() ?: folded.lastOrNull { it.content.isNotBlank() })?.id
        items.clear(); buffer = StringBuilder(); turnTag = 0L
        reasonBuf = StringBuilder(); reasonStartedAt = 0L; reasonEndedAt = 0L
        publish()
        return settledId
    }

    /** Drop the rows this process life added on its own — a re-read is about to replace them. */
    fun clearLocal() { local = emptyList(); publish() }

    /**
     * A persisted page now holds this process's turns: drop their local copies (echoes,
     * folded live turns) or every turn shows twice once a re-read lands. Keeps what only
     * the phone ever had: slash output, review pills, wing landings. No paint; the caller
     * publishes the replacement next.
     */
    fun dropPersistedTwinsQuietly() { local = local.filter { LiveTurnIds.isPhoneOnly(it.id) } }

    /**
     * The turn on screen ended (or was lost) while the link was down, so no complete will
     * come to settle it: without this the bubble streamed forever. A turn still running
     * restarts its overlay with its next frame, and its complete carries the whole reply.
     */
    fun abandonTurnQuietly() {
        if (!streaming) return
        streaming = false
        agentTyping.value = false
        items.clear(); buffer = StringBuilder(); turnTag = 0L
        reasonBuf = StringBuilder(); reasonStartedAt = 0L; reasonEndedAt = 0L
    }

    /** Paint now. A replay applies a burst of deltas inside one throttle window, and its
     *  last words must not wait for the next live frame to show (2.16). */
    fun republish() = publish()

    /**
     * A redirect landed (2.16): the correction joins the live turn where it happened — under
     * the words already streamed (sealed, so the re-asked call's words start a new segment
     * instead of running on into the cancelled one), above whatever comes next. The order
     * the gateway records and Desktop draws. A turn already over gets a plain echo.
     */
    fun correction(text: String) {
        if (!streaming) { localUserMessage(text); return }
        if (buffer.isNotBlank()) sealBuffer()
        items += TurnItem.Said(seq++, text)
        publish()
    }

    /** Echo what I sent; returns its id so a send that fails can take it back. */
    fun localUserMessage(text: String): String {
        val id = "local-${System.currentTimeMillis()}"
        local = local + Message(
            id = id,
            roomId = storedId,
            sender = SenderType.ME,
            content = text,
            timestamp = System.currentTimeMillis(),
        )
        publish()
        return id
    }

    fun dropLocal(id: String) {
        if (local.none { it.id == id }) return
        local = local.filterNot { it.id == id }
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
        is TurnItem.Said -> Message(
            id = id, roomId = storedId, sender = SenderType.ME,
            content = item.text, timestamp = ts,
        )
    }

    /** The row each step was last drawn as, by seq. A step that hasn't changed hands back
     *  the same Message: rebuilt every tick with a fresh timestamp, no finished row of a
     *  live turn could ever skip recomposing (2.19.1). */
    private val itemRows = HashMap<Int, Pair<TurnItem, Message>>()

    private fun rowFor(item: TurnItem): Message {
        val id = LiveTurnIds.item(turnTag, item.seq)
        val prev = itemRows[item.seq]?.takeIf { it.second.id == id }
        if (prev != null && prev.first == item) return prev.second
        val row = itemMessage(item, id, prev?.second?.timestamp ?: System.currentTimeMillis())
        itemRows[item.seq] = item to row
        return row
    }

    private fun publish() {
        if (items.isEmpty()) itemRows.clear()
        val overlay = if (streaming) buildList {
            val now = System.currentTimeMillis()
            // Sealed items get seq-stable ids so Compose keys survive re-publishes — and
            // the fold in [streamComplete], which hands the same rows the same names.
            items.forEach { add(rowFor(it)) }
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
internal sealed interface TurnItem {
    val seq: Int
    data class Text(override val seq: Int, val text: String, val provisional: Boolean) : TurnItem
    data class Tool(override val seq: Int, val call: ToolCall) : TurnItem
    /** The user's own words, inside the turn they redirected (2.16). */
    data class Said(override val seq: Int, val text: String) : TurnItem
}
