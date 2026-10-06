package chat.keryx.app.presentation

import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.GroupApproval
import chat.keryx.core.model.GroupChats
import chat.keryx.core.model.GroupDriver
import chat.keryx.core.model.GroupEvent
import chat.keryx.core.model.GroupLine
import chat.keryx.core.model.GroupRoom
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Group chats (2.18), the direct door: the gateway's hosted rooms of 2–6 bots. The gateway owns
 * the room — roster, log, and the driver that runs each member's turn even with the phone shut —
 * so this delegate only lists rooms, follows the open one by polling its log (there is no push
 * for room events), and appends what the user says. Fast while members are working or right
 * after a send; slow when the room is quiet; stopped when the room is closed.
 */
class GroupsDelegate(
    private val deps: GatewayDeps,
    private val transport: ChatTransport,
) {
    private val scope get() = deps.scope
    private val gateway get() = transport.gateway

    val available: Boolean get() = gateway != null

    data class RoomRow(val room: GroupRoom, val preview: String?)

    private val _rooms = MutableStateFlow<HubDelegate.PanelState<List<RoomRow>>>(HubDelegate.PanelState())
    val rooms: StateFlow<HubDelegate.PanelState<List<RoomRow>>> = _rooms.asStateFlow()

    /** Everything the open room shows. */
    data class RoomView(
        val roomId: String,
        val room: GroupRoom? = null,
        val events: List<GroupEvent> = emptyList(),
        val lines: List<GroupLine> = emptyList(),
        val working: Set<String> = emptySet(),
        val driver: GroupDriver = GroupDriver(),
        val loading: Boolean = true,
        val error: String? = null,
    ) {
        /** Member names with a turn in flight, for the "… is thinking" line. */
        val workingNames: List<String> get() = working.map { id -> room?.member(id)?.label ?: id }
        val busy: Boolean get() = working.isNotEmpty() || driver.working
    }

    private val _view = MutableStateFlow<RoomView?>(null)
    val view: StateFlow<RoomView?> = _view.asStateFlow()

    private var follow: Job? = null
    private var lastSendAt = 0L
    /** Wakes the follow loop early — after a send, a stop, an approval — instead of on its tick. */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun poke() { wake.trySend(Unit) }

    fun refresh() {
        val gw = gateway ?: return
        scope.launch {
            _rooms.value = _rooms.value.copy(refreshing = true)
            gw.groupRooms()
                .onSuccess { rooms ->
                    // The row's second line is the newest thing said: read each room's tail.
                    val rows = rooms.sortedByDescending { it.updatedAt }.map { room ->
                        val since = (room.latestSeq - 12).coerceAtLeast(0)
                        val tail = gw.groupLog(room.roomId, since, 12).getOrNull()?.events.orEmpty()
                        RoomRow(room, GroupChats.preview(GroupChats.lines(tail, room)))
                    }
                    _rooms.value = HubDelegate.PanelState(data = rows)
                }
                .onFailure { e ->
                    _rooms.value = _rooms.value.copy(error = e.message?.take(120) ?: "rooms unavailable", refreshing = false)
                }
        }
    }

    fun create(name: String, bots: List<BotProfile>, onDone: (GroupRoom?, String?) -> Unit) {
        val gw = gateway ?: return onDone(null, "not connected to a gateway")
        scope.launch {
            gw.createGroup(name, bots)
                .onSuccess { room -> refresh(); onDone(room, null) }
                .onFailure { e -> onDone(null, e.message?.take(160) ?: "couldn't create the room") }
        }
    }

    /** Follow [roomId]: read its whole log once, then poll for what is new until [close]. */
    fun open(roomId: String) {
        val gw = gateway ?: return
        if (_view.value?.roomId != roomId) _view.value = RoomView(roomId)
        follow?.cancel()
        follow = scope.launch {
            var cursor = 0L
            var events = emptyList<GroupEvent>()
            while (isActive) {
                val state = gw.groupState(roomId)
                val snapshot = state.getOrNull()
                if (snapshot == null) {
                    _view.value = _view.value?.copy(loading = false, error = state.exceptionOrNull()?.message?.take(160))
                    kotlinx.coroutines.withTimeoutOrNull(5_000) { wake.receive() }
                    continue
                }
                val (room, driver) = snapshot
                // Drain every page past the cursor (a long room's first read is several).
                var more = true
                while (more) {
                    val page = gw.groupLog(roomId, cursor).getOrNull() ?: break
                    if (page.events.isNotEmpty()) {
                        events = events + page.events
                        cursor = page.events.last().seq
                    }
                    more = page.hasMore && page.events.isNotEmpty()
                }
                val working = GroupChats.working(events)
                _view.value = RoomView(
                    roomId = roomId,
                    room = room,
                    events = events,
                    lines = GroupChats.lines(events, room),
                    working = working,
                    driver = driver,
                    loading = false,
                )
                val hot = working.isNotEmpty() || driver.working || System.currentTimeMillis() - lastSendAt < 20_000
                kotlinx.coroutines.withTimeoutOrNull(if (hot) 1_500L else 5_000L) { wake.receive() }
            }
        }
    }

    fun close() {
        follow?.cancel()
        follow = null
        _view.value = null
        refresh()
    }

    /** Say [text] in the open room: in [threadId] to continue that topic, else a new one. */
    fun send(text: String, threadId: String? = null) {
        val gw = gateway ?: return
        val roomId = _view.value?.roomId ?: return
        val body = text.trim().ifEmpty { return }
        lastSendAt = System.currentTimeMillis()
        scope.launch {
            gw.sendToGroup(roomId, body, threadId ?: GroupChats.newId("thread"), GroupChats.newId("keryx"))
                .onSuccess { poke() } // read it back now rather than on the next tick
                .onFailure { e -> deps.toast("Couldn't send: ${e.message?.take(100) ?: "try again"}") }
        }
    }

    fun stop() = act("Couldn't stop the room") { gw, id -> gw.stopGroup(id) }

    fun approve(approval: GroupApproval, choice: String) =
        act("Couldn't answer the approval") { gw, id -> gw.approveInGroup(id, approval, choice) }

    fun retry(taskId: String) = act("Couldn't retry") { gw, id -> gw.retryInGroup(id, taskId) }

    fun rename(roomId: String, name: String) {
        val gw = gateway ?: return
        scope.launch {
            gw.renameGroup(roomId, name)
                .onSuccess { refresh(); poke() }
                .onFailure { e -> deps.toast("Couldn't rename: ${e.message?.take(100) ?: "try again"}") }
        }
    }

    fun disband(roomId: String, onDone: () -> Unit = {}) {
        val gw = gateway ?: return
        scope.launch {
            gw.disbandGroup(roomId)
                .onSuccess {
                    if (_view.value?.roomId == roomId) { follow?.cancel(); _view.value = null }
                    refresh()
                    onDone()
                }
                .onFailure { e -> deps.toast("Couldn't disband: ${e.message?.take(100) ?: "try again"}") }
        }
    }

    private fun act(failure: String, call: suspend (chat.keryx.core.transport.GatewayCapabilities, String) -> Result<Unit>) {
        val gw = gateway ?: return
        val roomId = _view.value?.roomId ?: return
        scope.launch {
            call(gw, roomId)
                .onSuccess { poke() }
                .onFailure { e -> deps.toast("$failure: ${e.message?.take(100) ?: "try again"}") }
        }
    }
}
