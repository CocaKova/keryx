package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import chat.keryx.app.presentation.ui.components.CardMove
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.core.protocol.CronRestParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Editing a mission after it was made (2.16). Until now a card's words were fixed the moment
 * "Create" was tapped: a typo in the title, a brief that needed one more line, a priority set
 * wrong — the only fix was archive and re-create.
 *
 * The Keryx gateway plugin's kanban routes have no edit verb (create, comment, settings, the
 * owner moves). The dashboard's own kanban plugin does — `PATCH /api/plugins/kanban/tasks/{id}`
 * takes title, body and priority — so the words are edited there, on the direct door, and the
 * owner changes through the plugin's reassign move, which stops a running worker first (the
 * PATCH refuses a running card). Where the dashboard's kanban answers nothing (the Matrix door,
 * a dashboard without the plugin) the editor is simply not offered; "Hand to…" still is.
 */
class MissionEditDelegate(
    deps: GatewayDeps,
    private val missions: MissionsDelegate,
    /** The direct door's dashboard REST client; null on the Matrix door. */
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val toast = deps.toast

    /** Null until asked; false = this gateway cannot edit a card's words from the phone. */
    private val _canEdit = MutableStateFlow<Boolean?>(null)
    val canEdit: StateFlow<Boolean?> = _canEdit.asStateFlow()

    /** Every profile the dashboard knows, for the owner picker — a fresh profile shows before
     *  it has a card. Empty until probed. */
    private val _assignees = MutableStateFlow<List<String>>(emptyList())
    val assignees: StateFlow<List<String>> = _assignees.asStateFlow()

    private var probedFor: GatewayRest? = null

    /** One GET per client: the dashboard's assignee list doubles as the "is the kanban API here"
     *  probe. A 404 or a body that is not the list reads as "no editor" — no toast, no retry. */
    fun probe() {
        val r = rest()
        if (r == null) {
            _canEdit.value = false
            probedFor = null
            return
        }
        if (r === probedFor && _canEdit.value != null) return
        probedFor = r
        scope.launch {
            val names = r.call("GET", "/api/plugins/kanban/assignees").getOrNull()
                ?.let(MissionEditForm::parseAssignees)
            _assignees.value = names.orEmpty()
            _canEdit.value = names != null
        }
    }

    /**
     * Save an edit of [task] (the detail call's copy — it carries the full brief). Words and
     * priority first, in one PATCH; then the owner, if it changed. [onDone] true = the gateway
     * took all of it.
     */
    fun save(
        task: KanbanTask,
        board: String?,
        title: String,
        body: String,
        priority: Int,
        assignee: String,
        onDone: (Boolean) -> Unit,
    ) {
        val fields = MissionEditForm.changes(task, title, body, priority)
        val reassign = assignee.isNotBlank() && assignee != task.assignee
        if (fields.isEmpty() && !reassign) { onDone(true); return }
        scope.launch {
            if (fields.isNotEmpty()) {
                val r = rest() ?: run { onDone(false); return@launch }
                val payload = buildJsonObject {
                    fields.forEach { (k, v) ->
                        put(k, if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString()))
                    }
                }.toString()
                val q = board?.takeIf { it.isNotBlank() }?.let { "?board=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
                val res = r.call("PATCH", "/api/plugins/kanban/tasks/${java.net.URLEncoder.encode(task.id, "UTF-8")}$q", payload)
                if (res.isFailure) {
                    toast("Edit refused: ${CronRestParser.errorDetail(res.exceptionOrNull()?.message).take(100)}")
                    onDone(false)
                    return@launch
                }
            }
            if (reassign) {
                // The move toasts where the card landed and refreshes the board itself.
                missions.kanbanAction(task.id, CardMove.REASSIGN, assignee = assignee) { onDone(true) }
            } else {
                toast("Mission updated")
                missions.refreshKanban()
                onDone(true)
            }
        }
    }
}
