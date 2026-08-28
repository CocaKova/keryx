package chat.keryx.core.model

/**
 * Stock hermes Projects — the gateway's native session-grouping surface (`projects.*` RPCs,
 * per-profile projects.db). Membership is WORKSPACE-derived: a project owns folders, and a
 * session belongs to whichever project claims its cwd/git root. "Pinning a session into a
 * project" therefore means re-homing its workspace (`session.workspace.move`) — there is no
 * per-session project column. Times leave the parser in epoch MILLIS (the wire speaks seconds).
 */
data class ProjectFolder(
    val path: String,
    val label: String?,
    val isPrimary: Boolean,
)

/** One row of `projects.list` — an EXPLICIT project (projects.db), archived ones included. */
data class ProjectInfo(
    val id: String,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val color: String?,
    val boardSlug: String?,
    val primaryPath: String?,
    val archived: Boolean,
    val createdAtMs: Long,
    val folders: List<ProjectFolder>,
) {
    /** The folder a session lands in when "moved into" this project — primary first. A
     *  folder-less project can never claim a session (membership is cwd-based), so null here
     *  means "not a move target". */
    val anchorPath: String?
        get() = primaryPath
            ?: folders.firstOrNull { it.isPrimary }?.path
            ?: folders.firstOrNull()?.path
}

/** `projects.list` payload: every explicit project + which one is active (new-chat default). */
data class ProjectsCatalog(
    val projects: List<ProjectInfo>,
    val activeId: String?,
)

/**
 * One page of the gateway's folder listing (`complete.path` in its `@folder:` mode).
 *
 * [names] are the child directory NAMES under whatever parent the query addressed — the
 * caller owns the prefix, because the wire's own `text` field is rebased on the gateway's
 * completion cwd and is therefore not a usable path for anyone else.
 *
 * The gateway caps a completion at 30 items and says nothing about it, so [truncated]
 * carries that fact to the UI rather than letting a partial listing read as the whole
 * folder (no silent caps).
 */
data class FolderPage(
    val names: List<String>,
    val truncated: Boolean,
) {
    companion object { val EMPTY = FolderPage(emptyList(), false) }
}

/** One lane inside a repo node (a branch checkout, a kanban worktree bucket…). Sessions are
 *  full rows only in hydrated (`projects.project_sessions`) payloads; the overview empties them. */
data class ProjectLane(
    val id: String,
    val label: String,
    val isMain: Boolean,
    val isKanban: Boolean,
    val sessions: List<RoomProfile>,
)

data class ProjectRepo(
    val id: String,
    val label: String,
    val path: String?,
    val sessionCount: Long,
    val lanes: List<ProjectLane>,
)

/** One project node of `projects.tree` / `projects.project_sessions` — explicit (p_…), auto
 *  (a discovered repo root; id = the path), or the synthetic no-project "Home" bucket. */
data class ProjectTreeNode(
    val id: String,
    val label: String,
    val path: String?,
    val color: String?,
    val icon: String?,
    val isAuto: Boolean,
    val isNoProject: Boolean,
    val sessionCount: Long,
    val lastActiveMs: Long,
    val totalTokens: Long,
    val repos: List<ProjectRepo>,
    val previewSessions: List<RoomProfile>,
) {
    /** Hydrated drill-in rows, flattened chronologically (newest first) with their lane label —
     *  the mobile rendering: lanes become quiet section captions, not a tree. */
    fun flatSessions(): List<Pair<RoomProfile, String>> =
        repos.flatMap { repo ->
            repo.lanes.flatMap { lane -> lane.sessions.map { it to lane.label } }
        }.sortedByDescending { it.first.timestamp }
}

/** `projects.tree` payload. [scopedSessionIds] = every session some project claimed, so a flat
 *  recents list can exclude them (the desktop's rule). */
data class ProjectsTree(
    val projects: List<ProjectTreeNode>,
    val activeId: String?,
    val scopedSessionIds: Set<String>,
)
