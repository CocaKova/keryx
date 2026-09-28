package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.notify.MissionAlertsWorker
import chat.keryx.app.presentation.ui.components.CardMove
import chat.keryx.core.model.RoomProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many cards on [board] wait on their owner. The gateway's own top-level `needs_you` wins
 * when the plugin sends it (it counts with the same rule the lane uses, server side); an older
 * plugin gets the cards' flags counted here, once per card. Pure for the orb's tests.
 */
internal fun boardNeedsYou(board: HermesStreamClient.KanbanBoard?): Int {
    if (board == null) return 0
    board.needsYou?.let { return it.coerceAtLeast(0) }
    return board.tasks.values.flatten().filter { it.needsYou }.distinctBy { it.id }.size
}

/** The one toast a bulk archive ends with — a count, never twelve toasts. */
internal fun bulkArchiveSummary(archived: Int, failed: Int): String = when {
    archived == 0 && failed == 0 -> "Nothing to archive"
    failed == 0 -> "Archived $archived"
    archived == 0 -> "Archive failed for $failed card${if (failed == 1) "" else "s"}"
    else -> "Archived $archived · $failed failed"
}

/** Missions: the kanban board over the gateway, plus the mission-alert toggle. */
class MissionsDelegate(
    deps: GatewayDeps,
    /** The live room list — alert room names resolve against it. */
    private val rooms: () -> List<RoomProfile>,
) {
    private val scope = deps.scope
    private val settings = deps.settings
    private val client = deps.client
    private val toast = deps.toast

    private val _kanbanBoard =
        MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.KanbanBoard?>(null)
    val kanbanBoard: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.KanbanBoard?> =
        _kanbanBoard.asStateFlow()

    /**
     * Cards waiting on you, board-wide (2.14.1) — the drawer's Missions orb, and the chat top
     * bar's dot. Derived from whatever board was fetched last, by the Missions screen's own
     * poll or by [pulse] while the app is on screen with the board closed; zero until the
     * first fetch, never a stale guess from a cache.
     */
    val needsYouCount: StateFlow<Int> =
        _kanbanBoard.map { boardNeedsYou(it) }.stateIn(scope, SharingStarted.Eagerly, 0)
    private val _kanbanRefreshing = MutableStateFlow(false)
    val kanbanRefreshing: StateFlow<Boolean> = _kanbanRefreshing.asStateFlow()
    private val _kanbanError = MutableStateFlow<String?>(null)
    val kanbanError: StateFlow<String?> = _kanbanError.asStateFlow()

    fun refreshKanban() {
        val client = client() ?: run {
            _kanbanError.value = "Hermes Link is off — enable it in Settings"
            return
        }
        _kanbanRefreshing.value = true
        scope.launch {
            client.kanbanBoard()
                .onSuccess { _kanbanBoard.value = it; _kanbanError.value = null }
                .onFailure { _kanbanError.value = it.message?.take(120) ?: "board unavailable" }
            _kanbanRefreshing.value = false
        }
        refreshKanbanSubs()
    }

    /**
     * The board refreshed without the screen's spinner or its error line (2.14.1): the pulse
     * and the drawer opening use it to keep [needsYouCount] honest while Missions is closed.
     * A failure keeps the last board — an orb that blinks out on one dropped request would
     * read as "nothing needs you", which is the one wrong thing it can say.
     */
    fun refreshKanbanQuietly() {
        val client = client() ?: return
        scope.launch {
            client.kanbanBoard().onSuccess { _kanbanBoard.value = it; _kanbanError.value = null }
        }
    }

    /**
     * The foreground pulse (2.14.1). Runs while — and only while — the caller's lifecycle keeps
     * it running: MainActivity starts it under `repeatOnLifecycle(STARTED)`, so it is born when
     * Keryx comes on screen and cancelled the moment it leaves. Every [PULSE_MS] it does two
     * cheap things over the gateway client:
     *
     *  - refreshes the board quietly, so the drawer's orb counts cards that started needing you
     *    while you were in a chat — skipped while the Missions screen is up, which polls itself;
     *  - runs the mission watcher's own check ([MissionAlertsWorker.checkAndNotify]) when alerts
     *    are on, so a completion rings within a minute instead of at the worker's 15-minute
     *    floor. Same function, same cursor, same quiet-while-the-board-is-open rule.
     *
     * No client (Hermes Link off) = each beat is a no-op; nothing is scheduled beyond this loop.
     * [context] is only for posting notifications; the application context is taken from it.
     */
    suspend fun pulse(context: android.content.Context) {
        val app = context.applicationContext
        while (true) {
            client()?.let { c ->
                if (!MissionAlertsWorker.boardOnScreen) {
                    c.kanbanBoard().onSuccess { _kanbanBoard.value = it; _kanbanError.value = null }
                }
                if (settings.missionAlertsEnabled) MissionAlertsWorker.checkAndNotify(app, c, settings)
            }
            delay(PULSE_MS)
        }
    }

    /** The Missions screen tells the watchers it is on screen (see [MissionAlertsWorker.boardOnScreen]). */
    fun setBoardOnScreen(onScreen: Boolean) {
        MissionAlertsWorker.boardOnScreen = onScreen
    }

    /**
     * A card to open, asked for from outside the board — a mission notification's tap (2.14.1).
     * A request, not an event: the Missions screen may not be composed when the tap lands (the
     * app may not even be past its lock screen); the host navigates to Missions on seeing it,
     * and the screen opens the card's sheet and [consumeOpenTask]s it.
     */
    private val _openTaskRequest = MutableStateFlow<String?>(null)
    val openTaskRequest: StateFlow<String?> = _openTaskRequest.asStateFlow()

    fun requestOpenTask(taskId: String) {
        if (taskId.isNotBlank()) _openTaskRequest.value = taskId
    }

    fun consumeOpenTask() { _openTaskRequest.value = null }

    /** A bulk archive in flight: how far along. Null = none running. */
    data class BulkProgress(val done: Int, val total: Int, val failed: Int = 0)

    private val _bulkProgress = MutableStateFlow<BulkProgress?>(null)
    val bulkProgress: StateFlow<BulkProgress?> = _bulkProgress.asStateFlow()

    /**
     * Archive many cards (2.14.1 — clean-up after a swarm). The gateway has no bulk route, so
     * it is one `action: archive` per card, [BULK_PARALLEL] at a time: a burst of forty POSTs
     * would queue behind each other on the gateway's DB lock anyway, and a few in flight keeps
     * the progress bar honest. One toast at the end, one board refresh, never one per card.
     * A second bulk while one runs is refused — the bar would show two jobs as one.
     */
    fun kanbanArchiveMany(taskIds: Collection<String>, onDone: () -> Unit = {}) {
        val client = client() ?: return
        val ids = taskIds.distinct()
        if (ids.isEmpty() || _bulkProgress.value != null) return
        _bulkProgress.value = BulkProgress(0, ids.size)
        scope.launch {
            val gate = Semaphore(BULK_PARALLEL)
            val results = coroutineScope {
                ids.map { id ->
                    async {
                        gate.withPermit {
                            val ok = client.kanbanAction(id, CardMove.ARCHIVE.verb).isSuccess
                            _bulkProgress.update { p ->
                                p?.copy(done = p.done + 1, failed = p.failed + if (ok) 0 else 1)
                            }
                            ok
                        }
                    }
                }.awaitAll()
            }
            val archived = results.count { it }
            toast(bulkArchiveSummary(archived, results.size - archived))
            _bulkProgress.value = null
            onDone()
            refreshKanban()
        }
    }

    /** task_id → its notify subscriptions. Bell state on cards + the detail-sheet toggle. The
     *  gateway notifier deletes rows itself once a task genuinely ends, so a subscription
     *  vanishing between refreshes means "it fired", never an error. */
    private val _kanbanSubs =
        MutableStateFlow<Map<String, List<chat.keryx.app.data.remote.HermesStreamClient.KanbanSub>>>(emptyMap())
    val kanbanSubs: StateFlow<Map<String, List<chat.keryx.app.data.remote.HermesStreamClient.KanbanSub>>> =
        _kanbanSubs.asStateFlow()

    private fun refreshKanbanSubs() {
        val client = client() ?: return
        scope.launch {
            // Failure keeps the last known map: stale bells beat a board-wide flicker-off.
            client.kanbanSubs().onSuccess { subs -> _kanbanSubs.value = subs.groupBy { it.taskId } }
        }
    }

    /** The chat mission alerts land in: whichever the user has (last) open, on either door.
     *  Matrix: the room, reached by the gateway's kanban notifier as a native message. Gateway:
     *  the SESSION — the gateway's per-session notification poller (`session_notifications.py`)
     *  reads subscriptions keyed `platform="tui", chat_id=<session id>` and hands the event to
     *  that session as a turn, so the agent reports it in the chat. Until 2.13.8 the direct door
     *  returned null here on the belief that no adapter could reach a session; the poller can. */
    fun alertRoom(): String? {
        val last = settings.lastRoomId
        if (settings.transportMode != "direct") return last
        // The last-open row on the gateway door may be machinery, not a conversation: the
        // worker transcript of the very mission being watched, a cron report, a bot's chat.
        // 2.13.8 subscribed whatever was last open, and Jonny's first alert bound to the card's
        // own worker session ("Post 1 · summarize … #2") because he had just read it — a report
        // that would land in a transcript nobody reopens. A machine row is skipped for the
        // newest conversation instead; an unknown row (not in the list yet) is trusted.
        val list = rooms()
        val lastRow = list.firstOrNull { it.id == last }
        if (last != null && (lastRow == null || lastRow.isConversation())) return last
        return list.filter { it.isConversation() }.maxByOrNull { it.timestamp }?.id
    }

    private fun RoomProfile.isConversation(): Boolean = source.lowercase() !in MACHINE_SOURCES

    /** The notifier platform for [alertRoom] on this door. */
    fun alertPlatform(): String = if (settings.transportMode == "direct") "tui" else "matrix"

    /**
     * A per-mission alert on the gateway door reports into a chat — which only reaches the
     * phone's shade while that chat is open. The background mission watcher
     * ([chat.keryx.app.notify.MissionAlertsWorker], Settings ▸ Mission alerts) is what rings
     * with the app closed; switching a card's alert on arms it too, so "alert me" means the
     * phone, not just the transcript. Needs a Context to schedule the work, hence the caller.
     */
    fun armPhoneAlerts(context: android.content.Context) {
        if (settings.transportMode != "direct" || settings.missionAlertsEnabled) return
        setAlertsEnabled(true)
        chat.keryx.app.notify.MissionAlertsWorker.setEnabled(context, true)
        toast("Mission alerts on — the phone rings when a mission ends (Settings ▸ Mission alerts)")
    }

    /** Why alerts are unavailable, in the door's own words. */
    val alertUnavailableReason: String
        get() = if (settings.transportMode == "direct") "Open a session first — alerts land in the chat you last had open"
        else "Open a room first — alerts land in a Matrix room"

    fun alertRoomName(): String? =
        alertRoom()?.let { id -> rooms().firstOrNull { it.id == id }?.name ?: id }

    /** Toggle "alert when this ends". On subscribes the current alert chat; off removes every
     *  subscription the app can see for the task — they may point at chats opened earlier. */
    fun kanbanSetAlert(taskId: String, enabled: Boolean) {
        val client = client() ?: return
        scope.launch {
            if (enabled) {
                val room = alertRoom() ?: run {
                    toast(alertUnavailableReason)
                    return@launch
                }
                client.kanbanSubscribe(taskId, room, alertPlatform())
                    .onFailure { toast("Alert failed: ${it.message?.take(80)}") }
            } else {
                _kanbanSubs.value[taskId].orEmpty().forEach { sub ->
                    client.kanbanUnsubscribe(taskId, sub.chatId, sub.platform.ifBlank { "matrix" })
                }
            }
            refreshKanbanSubs()
        }
    }

    suspend fun kanbanTaskDetail(taskId: String): Result<chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail> =
        client()?.kanbanTask(taskId)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    /** Create a mission and refresh the board; toasts the outcome either way. [notify] chains a
     *  terminal-event subscription for the current alert room onto the fresh task. */
    fun kanbanCreate(title: String, assignee: String, body: String, triage: Boolean, notify: Boolean = false) {
        val client = client() ?: return
        scope.launch {
            client.kanbanCreate(title, assignee, body, triage)
                .onSuccess { taskId ->
                    toast("Mission created${if (triage) " (triage)" else ""}")
                    val room = alertRoom()
                    if (notify && room != null) client.kanbanSubscribe(taskId, room, alertPlatform())
                    refreshKanban()
                }
                .onFailure { toast("Create failed: ${it.message?.take(80)}") }
        }
    }

    fun kanbanComment(taskId: String, body: String, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanComment(taskId, body)
                .onSuccess { onDone() }
                .onFailure { toast("Comment failed: ${it.message?.take(80)}") }
        }
    }

    /** Answer a card that asked for something; [unblock] sends it back to work. Toasts where
     *  the card landed so "did that do anything?" never needs a refresh to answer. */
    fun kanbanReply(taskId: String, body: String, unblock: Boolean, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanReply(taskId, body, unblock)
                .onSuccess { r ->
                    toast(
                        when {
                            r.unblocked -> "Answered — back to ${r.status.ifBlank { "work" }}"
                            unblock -> "Answered — the card stays put (gateway can't unblock yet)"
                            else -> "Answered"
                        },
                    )
                    onDone()
                    refreshKanban()
                }
                .onFailure { toast("Reply failed: ${it.message?.take(80)}") }
        }
    }

    /** The review verdict, yes: the card completes. [note] is optional. */
    fun kanbanApprove(taskId: String, note: String, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanApprove(taskId, note)
                .onSuccess { toast("Approved — mission done"); onDone(); refreshKanban() }
                .onFailure { toast(verdictFailure("Approve", it)) }
        }
    }

    /** The review verdict, no: back to the implementer with [reason]. */
    fun kanbanRequestChanges(taskId: String, reason: String, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanRequestChanges(taskId, reason)
                .onSuccess { status -> toast("Changes requested — back to ${status.ifBlank { "the implementer" }}"); onDone(); refreshKanban() }
                .onFailure { toast(verdictFailure("Request changes", it)) }
        }
    }

    /** An owner move on a card ([CardMove]); toasts where it landed, or the gateway's refusal. */
    fun kanbanAction(
        taskId: String,
        move: chat.keryx.app.presentation.ui.components.CardMove,
        note: String = "",
        assignee: String = "",
        onDone: () -> Unit = {},
    ) {
        val client = client() ?: return
        scope.launch {
            client.kanbanAction(taskId, move.verb, note, assignee)
                .onSuccess { r ->
                    toast(move.landed(r.status, r.assignee))
                    onDone()
                    refreshKanban()
                }
                .onFailure { toast(verdictFailure(move.label, it)) }
        }
    }

    /** A 404 on a verdict route is a gateway plugin older than 2.14, not a missing card. */
    private fun verdictFailure(what: String, e: Throwable): String =
        if ((e as? chat.keryx.app.data.remote.HermesStreamClient.GatewayError)?.httpStatus == 404 &&
            e.message?.contains("unknown task") != true
        ) "$what needs the 2.14 gateway plugin"
        else "$what failed: ${e.message?.take(80)}"

    /** Pin (blank = clear) the mission's model override; takes effect on its next dispatch. */
    fun kanbanSetModel(taskId: String, model: String, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanSetModel(taskId, model)
                .onSuccess { onDone() }
                .onFailure { toast("Model pin failed: ${it.message?.take(80)}") }
        }
    }

    /** Pin the mission's thinking depth ("" inherits; "none" pins thinking OFF). */
    fun kanbanSetReasoning(taskId: String, effort: String, onDone: () -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            client.kanbanSetReasoning(taskId, effort)
                .onSuccess { onDone() }
                .onFailure { toast("Depth pin failed: ${it.message?.take(80)}") }
        }
    }


    // --- Mission alerts ---

    private val _missionAlertsEnabled = MutableStateFlow(settings.missionAlertsEnabled)
    val alertsEnabled: StateFlow<Boolean> = _missionAlertsEnabled.asStateFlow()

    /** Persist the toggle; the caller schedules/cancels the actual worker (it needs a Context).
     *  Enabling resets the event cursor so the first check baselines quietly instead of dumping
     *  every historical completion as a notification. */
    fun setAlertsEnabled(enabled: Boolean) {
        settings.missionAlertsEnabled = enabled
        if (enabled) settings.missionEventsCursor = -1L
        _missionAlertsEnabled.value = enabled
    }

    companion object {
        /** The foreground pulse's beat. A minute: the orb and a completion alert are "soon", not
         *  "live" — and the board fetch is one small GET, so this is ~60 requests an hour on
         *  screen and none off it. */
        const val PULSE_MS = 60_000L

        /** Archive calls in flight at once during a bulk clean-up. */
        const val BULK_PARALLEL = 3

        /** Gateway session `source` values that are machinery, never a chat to report into:
         *  kanban worker transcripts, scheduled runs, subagents, bots, one-shot tool runs. */
        val MACHINE_SOURCES: Set<String> = setOf(
            "kanban", "cron", "subagent", "bot", "oneshot", "tool",
            chat.keryx.app.transport.direct.DirectTransport.CRON_SOURCE,
            BotsDelegate.BOT_SOURCE,
        )
    }
}
