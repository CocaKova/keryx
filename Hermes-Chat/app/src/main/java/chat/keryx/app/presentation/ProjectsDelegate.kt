package chat.keryx.app.presentation

import chat.keryx.core.model.FolderPage
import chat.keryx.core.model.ProjectInfo
import chat.keryx.core.model.ProjectTreeNode
import chat.keryx.core.model.ProjectsCatalog
import chat.keryx.core.model.ProjectsTree
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Projects — the gateway's native workspace grouping, harvested from Talaria (08-28). A
 * project is folders on the host; a session belongs to whichever project claims its cwd. The
 * door exists only where the gateway serves `projects.*` (no dead doors): the probe is the
 * overview fetch itself, sticky for the process once it has answered.
 */
class ProjectsDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val openSession: (id: String, title: String) -> Unit,
) {
    private val scope = deps.scope
    private val gateway get() = transport.gateway

    private val _tree = MutableStateFlow<ProjectsTree?>(null)
    val projectsTree: StateFlow<ProjectsTree?> = _tree.asStateFlow()

    private val _catalog = MutableStateFlow<ProjectsCatalog?>(null)

    /** Explicit, unarchived projects that can CLAIM a session (they have a folder). */
    val projectMoveTargets: StateFlow<List<ProjectInfo>> =
        _catalog.map { cat -> cat?.projects?.filter { !it.archived && it.anchorPath != null } ?: emptyList() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Parents of every path the gateway already treats as a workspace, commonest first —
     *  where a folder browse is worth starting. Derived, never hardcoded. */
    val workspaceRoots: StateFlow<List<String>> =
        _tree.map { tree ->
            (tree?.projects ?: emptyList())
                .mapNotNull { it.path }
                .mapNotNull { it.trimEnd('/').substringBeforeLast('/', "").takeIf { p -> p.isNotBlank() } }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key }
                .take(4)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _hasProjects = MutableStateFlow(false)
    val hasProjects: StateFlow<Boolean> = _hasProjects.asStateFlow()

    private val _detail = MutableStateFlow<ProjectTreeNode?>(null)
    val projectDetail: StateFlow<ProjectTreeNode?> = _detail.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val projectsError: StateFlow<String?> = _error.asStateFlow()

    /** One probe = the door AND the overview. A no-op on Matrix (no gateway seam). */
    fun refreshProjects() {
        val gw = gateway ?: return
        scope.launch {
            gw.projectsTree()
                .onSuccess { _tree.value = it; _hasProjects.value = true; _error.value = null }
                .onFailure { if (_hasProjects.value) _error.value = it.message?.take(120) }
            gw.projectsCatalog().onSuccess { _catalog.value = it }
        }
    }

    fun openProjectDetail(projectId: String) {
        val gw = gateway ?: return
        _detail.value = null
        scope.launch {
            gw.projectSessions(projectId)
                .onSuccess { _detail.value = it }
                .onFailure { _error.value = it.message?.take(120) }
        }
    }

    fun closeProjectDetail() { _detail.value = null }

    fun createProject(name: String, folderPath: String, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the gateway door")
        scope.launch {
            val bad = folderComplaint(folderPath)
            if (bad != null) { onDone(bad); return@launch }
            gw.createProject(name, folderPath.ifBlank { null })
                .onSuccess { refreshProjects(); onDone(null) }
                .onFailure { onDone(it.message?.take(160) ?: "couldn't create the project") }
        }
    }

    /**
     * Why this folder can't anchor a project, or null. `projects.create` stores any string
     * without looking at the disk — a typo yields a project that fails every move later
     * (Talaria live-caught 08-17). The check belongs BEFORE the row exists; a gateway that
     * can't answer the probe is not blocked.
     */
    private suspend fun folderComplaint(folderPath: String): String? {
        val gw = gateway ?: return null
        val path = folderPath.trim()
        if (path.isBlank()) return null
        if (!path.startsWith("/") && !path.startsWith("~")) {
            return "Give the full folder — starting with ~/ or / — so it means one place on the gateway."
        }
        val probe = gw.folderExists(path)
        if (probe.isFailure) return null
        return if (probe.getOrDefault(true)) null
        else "No folder at $path on the gateway — create it there first, or pick one below."
    }

    fun browseFolders(query: String, onResult: (FolderPage?) -> Unit) {
        val gw = gateway ?: return onResult(null)
        scope.launch { onResult(gw.listFolders(query).getOrNull()) }
    }

    fun deleteProject(projectId: String, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the gateway door")
        scope.launch {
            gw.deleteProject(projectId)
                .onSuccess { refreshProjects(); onDone(null) }
                .onFailure { onDone(it.message?.take(160) ?: "couldn't delete the project") }
        }
    }

    fun archiveProject(projectId: String, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the gateway door")
        scope.launch {
            gw.archiveProject(projectId)
                .onSuccess { refreshProjects(); onDone(null) }
                .onFailure { onDone(it.message?.take(160) ?: "couldn't archive the project") }
        }
    }

    /** "Pin" a session into a project = re-home its workspace. The gateway refuses mid-turn;
     *  that refusal is surfaced, not retried. */
    fun moveSessionToProject(sessionId: String, target: ProjectInfo, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the gateway door")
        val cwd = target.anchorPath ?: return onDone("\"${target.name}\" has no folder to move into")
        scope.launch {
            gw.moveSessionToProject(sessionId, cwd)
                .onSuccess { refreshProjects(); onDone(null) }
                .onFailure { e ->
                    val msg = e.message ?: ""
                    onDone(
                        when {
                            "busy" in msg -> "Session is mid-turn — move it when the agent finishes"
                            "does not exist" in msg ->
                                "\"${target.name}\" points at $cwd, and there's no such folder on the gateway — fix or delete the project."
                            else -> msg.take(160).ifBlank { "move failed" }
                        }
                    )
                }
        }
    }

    /** New chat born inside a project's workspace; opens directly (a zero-message session
     *  isn't listed yet, so an id-lookup would park forever). */
    fun newChatInProject(node: ProjectTreeNode, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the gateway door")
        val cwd = node.path ?: return onDone("this project has no folder")
        scope.launch {
            gw.createSessionIn(null, cwd)
                .onSuccess { id -> openSession(id, "New session"); onDone(null) }
                .onFailure { onDone(it.message?.take(120) ?: "couldn't create the session") }
        }
    }
}
