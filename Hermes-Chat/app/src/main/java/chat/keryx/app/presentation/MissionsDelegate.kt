package chat.keryx.app.presentation

import chat.keryx.core.model.RoomProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** The room mission alerts land in: whichever the user has (last) open. */
    fun alertRoom(): String? = settings.lastRoomId

    fun alertRoomName(): String? =
        alertRoom()?.let { id -> rooms().firstOrNull { it.id == id }?.name ?: id }

    /** Toggle "alert when this ends". On subscribes the current alert room; off removes every
     *  subscription the app can see for the task — they may point at rooms opened earlier. */
    fun kanbanSetAlert(taskId: String, enabled: Boolean) {
        val client = client() ?: return
        scope.launch {
            if (enabled) {
                val room = alertRoom() ?: run {
                    toast("Open a room first — alerts land in a Matrix room")
                    return@launch
                }
                client.kanbanSubscribe(taskId, room)
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
                    if (notify && room != null) client.kanbanSubscribe(taskId, room)
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

}
