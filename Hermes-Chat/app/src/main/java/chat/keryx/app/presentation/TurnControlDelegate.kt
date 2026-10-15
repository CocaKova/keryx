package chat.keryx.app.presentation

import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.SenderType
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What can be done to a turn while it runs, and to the helpers it spawned (split out of
 * [ChatViewModel] in 3.0): steer, queue, stop, redirect, branch, ask aside, the session's
 * standing orders, and the crew sheet's controls. All of it acts on the open room.
 */
class TurnControlDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val currentRoom: StateFlow<RoomProfile?>,
    /** The ViewModel's send: the Matrix door speaks `/steer` and `/queue` as messages. */
    private val sendMessage: (String) -> Unit,
    private val recordCommandUse: (String) -> Unit,
    private val openSessionById: (id: String, title: String) -> Unit,
) {
    private val scope = deps.scope
    private val toast = deps.toast
    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport

    // --- Busy-turn inputs (the Talaria way: the send button IS the submit tree) ----------------
    // Text typed while a turn runs steers it; a long-press queues it for the next turn; an empty
    // composer stops it. The direct door speaks the gateway's RPC verbs. The Matrix door speaks
    // the gateway's own slash verbs, which every platform dispatches while busy (`/steer` and
    // `/queue` carry busy_policy=dispatch) — a plain message there would follow the operator's
    // busy_input_mode instead, which may be "interrupt".

    /** Whether this door can stop a running turn: `session.interrupt` exists on the direct
     *  door only — the Matrix gateway has no interrupt verb a room can send. */
    val canInterruptTurn: Boolean get() = direct != null

    /** Steer the running turn: the text reaches the model on its next step, no interrupt.
     *  Falls back to a queue when the agent declines (turn past its last tool batch, or a
     *  model that can't be steered). */
    fun steerTurn(text: String) {
        val session = currentRoom.value ?: return
        val d = direct
        if (d == null) {
            recordCommandUse("/steer")
            sendMessage("/steer $text")
            return
        }
        scope.launch {
            d.steerTurn(session.id, text)
                .onSuccess { accepted ->
                    if (accepted) {
                        toast("Steered — the agent sees it on its next step")
                    } else {
                        d.queuePrompt(session.id, text)
                            .onSuccess { toast("Agent declined the steer — queued for the next turn") }
                            .onFailure { toast("Queue failed: ${it.message?.take(80)}") }
                    }
                }
                .onFailure { toast("Steer failed: ${it.message?.take(80)}") }
        }
    }

    // ---- 2.16: the gateway's other hands — standing orders, asides, branches, redirect -----

    /** The open session's goal / loop / heartbeat (direct door). Read on open; the gateway's
     *  `session.control.update` keeps it after that. Null = none set, or a gateway without it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionControl: StateFlow<chat.keryx.core.model.SessionControl?> =
        currentRoom.flatMapLatest { r ->
            val d = transport as? chat.keryx.app.transport.direct.DirectTransport
            if (r == null || d == null) flowOf(null)
            else d.sessionControl(r.id).also { scope.launch { d.readControl(r.id) } }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Pause / resume / clear / stop-waiting on the open session's goal. */
    fun goalAction(action: String) {
        val session = currentRoom.value ?: return
        val d = direct ?: return
        scope.launch {
            d.controlAction(session.id, action)
                .onFailure { toast("Goal: ${it.message?.take(80)}") }
        }
    }

    /** A question asked aside (2.16 `prompt.btw`): shown under the chat, never part of it. */
    data class Aside(
        val taskId: String,
        val roomId: String,
        val question: String,
        val answer: String? = null,
        val failed: Boolean = false,
    )

    private val _asides = MutableStateFlow<List<Aside>>(emptyList())
    /** The open room's asides, oldest first. Gone with the process; never in history. */
    val asides: StateFlow<List<Aside>> =
        combine(_asides, currentRoom) { all, room -> all.filter { it.roomId == room?.id } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var asideCollector: kotlinx.coroutines.Job? = null

    /**
     * Ask aside: a side agent answers [text] over a snapshot of this conversation while the turn
     * keeps running. It is NOT a turn — it never touches the ViewModel's awaiting-reply or
     * live-turn state, so nothing after it can be mistaken for a steer; its own pending state
     * lives on the aside.
     */
    fun askAside(text: String) {
        val session = currentRoom.value ?: return
        val d = direct ?: run { toast("Asking aside needs the direct gateway"); return }
        if (asideCollector == null) {
            asideCollector = scope.launch {
                d.asideAnswers().collect { a ->
                    val failed = a.text.startsWith("error:")
                    _asides.value = _asides.value.map {
                        if (it.taskId == a.taskId) it.copy(answer = a.text.removePrefix("error:").trim(), failed = failed) else it
                    }
                }
            }
        }
        scope.launch {
            d.askAside(session.id, text)
                .onSuccess { task ->
                    _asides.value = (_asides.value + Aside(task, session.id, text)).takeLast(MAX_ASIDES)
                }
                .onFailure { toast("Ask aside failed: ${it.message?.take(80)}") }
        }
    }

    fun dismissAside(taskId: String) {
        _asides.value = _asides.value.filterNot { it.taskId == taskId }
    }

    /** Whether redirect is still on offer on this gateway (false after a 4010 / unknown verb). */
    val redirectAvailable: StateFlow<Boolean> =
        (transport as? chat.keryx.app.transport.direct.DirectTransport)?.redirectAvailable()
            ?: MutableStateFlow(false)

    /**
     * Redirect the running turn (2.16): cancel the reply in flight and re-ask with [text], keeping
     * the work done. The turn stays the same turn, so the live-turn flags are left as they are —
     * except when the gateway answers that the turn is over, where it becomes a queued prompt.
     */
    fun redirectTurn(text: String) {
        val session = currentRoom.value ?: return
        val d = direct ?: run { steerTurn(text); return }
        scope.launch {
            d.redirectTurn(session.id, text)
                .onSuccess { outcome ->
                    when (outcome) {
                        chat.keryx.core.model.RedirectOutcome.REDIRECTED ->
                            toast("Redirected — the reply starts over with your correction")
                        chat.keryx.core.model.RedirectOutcome.QUEUED ->
                            toast("Between calls — your correction runs next")
                        chat.keryx.core.model.RedirectOutcome.REJECTED ->
                            d.queuePrompt(session.id, text)
                                .onSuccess { toast("The reply had finished — queued for the next turn") }
                                .onFailure { toast("Queue failed: ${it.message?.take(80)}") }
                        chat.keryx.core.model.RedirectOutcome.UNSUPPORTED -> {
                            toast("This agent can't redirect — steering instead")
                            steerTurn(text)
                        }
                    }
                }
                .onFailure { toast("Redirect failed: ${it.message?.take(80)}") }
        }
    }

    /**
     * Branch from [message] (2.16 `session.branch {count}`): a new session holding the history up
     * to and including it, opened with the room-switch dissolve.
     */
    fun branchFrom(message: Message) {
        val session = currentRoom.value ?: return
        val d = direct ?: return
        scope.launch {
            d.branchFrom(session.id, message.id, message.content, mine = message.sender == SenderType.ME)
                .onSuccess { (stored, title) ->
                    openSessionById(stored, title)
                    toast("Branched — this is a new session from that message")
                }
                .onFailure { toast("Branch failed: ${it.message?.take(80)}") }
        }
    }

    /** Queue a message to run after the current turn finishes. */
    fun queueMessage(text: String) {
        val session = currentRoom.value ?: return
        val d = direct
        if (d == null) {
            recordCommandUse("/queue")
            sendMessage("/queue $text")
            return
        }
        scope.launch {
            d.queuePrompt(session.id, text)
                .onSuccess { toast("Queued — sends when this turn finishes") }
                .onFailure { toast("Queue failed: ${it.message?.take(80)}") }
        }
    }

    /** Stop the running turn (direct door). */
    fun interruptTurn() {
        val session = currentRoom.value ?: return
        val d = direct ?: return
        scope.launch {
            d.interruptTurn(session.id)
                .onSuccess { toast("Stopped") }
                .onFailure { toast("Stop failed: ${it.message?.take(80)}") }
        }
    }

    // ---- the crew (2.13): one helper's mind, and a word in its ear ----------------------

    /** Whether a helper can be steered or stopped from here: the subagent RPCs need the
     *  parent's live transport slot, which only the direct door holds. */
    val canSteerCrew: Boolean get() = direct != null

    /**
     * A flying helper's own stream — its thinking, its words, its tools — through the same
     * store any session uses. Resuming a child's session id opens the gateway's watch window,
     * which mirrors the child's frames as native deltas on that sid; the transcript rules
     * then apply unchanged. Finite empty flow on the Matrix door: the room never carries a
     * child's sid we could resume.
     */
    fun crewMessages(childSessionId: String): kotlinx.coroutines.flow.Flow<List<Message>> {
        val d = direct ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        if (childSessionId.isBlank()) return kotlinx.coroutines.flow.flowOf(emptyList())
        return d.watchChild(childSessionId)
    }

    /** What a helper did before this window opened — the gateway's tail of its live transcript. */
    suspend fun crewTail(parentSessionId: String, subagentId: String): String {
        val d = direct ?: return ""
        return d.crewTail(parentSessionId, subagentId).getOrDefault("")
    }

    /** A word in one helper's ear. It lands on that child's next step; the parent turn and
     *  the other helpers never see it. "Rejected" is honest: the helper answers to a door
     *  that isn't this one, or it is already past its last tool batch. */
    fun steerCrew(parentSessionId: String, subagentId: String, role: String, text: String) {
        val d = direct ?: return
        scope.launch {
            d.steerCrew(parentSessionId, subagentId, text)
                .onSuccess { queued ->
                    if (queued) toast("Steered $role — it sees it on its next step")
                    else toast("$role didn't take it — it answers to another door, or it's already wrapping up")
                }
                .onFailure { toast("Steer failed: ${it.message?.take(80)}") }
        }
    }

    /** The helper sheet's handle on all of the above — null on the Matrix door, where the
     *  sheet keeps its read-only shape. Built per call; it holds nothing but this delegate.
     *
     *  Bound to [parentSessionId], the room the helper was opened from — never read from the
     *  open room at press time: a notice tap that switched rooms under an open sheet sent A's
     *  helper id with B's session, and the gateway's refusal read as "another door". */
    fun crewControls(parentSessionId: String?): chat.keryx.app.presentation.ui.components.CrewControls? {
        if (!canSteerCrew || parentSessionId.isNullOrBlank()) return null
        return chat.keryx.app.presentation.ui.components.CrewControls(
            messages = { id -> crewMessages(id) },
            tail = { id -> crewTail(parentSessionId, id) },
            steer = { id, role, text -> steerCrew(parentSessionId, id, role, text) },
            stop = { id, role -> stopCrew(parentSessionId, id, role) },
        )
    }

    /** Stop one helper; the turn that spawned it keeps going and reads the partial result. */
    fun stopCrew(parentSessionId: String, subagentId: String, role: String) {
        val d = direct ?: return
        scope.launch {
            d.stopCrew(parentSessionId, subagentId)
                .onSuccess { found ->
                    if (found) toast("Stopped $role") else toast("$role had already finished")
                }
                .onFailure { toast("Stop failed: ${it.message?.take(80)}") }
        }
    }

    private companion object {
        const val MAX_ASIDES = 4
    }
}
