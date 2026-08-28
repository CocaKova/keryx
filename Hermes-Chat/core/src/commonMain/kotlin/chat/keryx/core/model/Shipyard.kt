package chat.keryx.core.model

/**
 * The Shipyard — git review over the direct door (`/keryx/git/…`, a confined layer over the
 * gateway's own `web_git` library). Read shapes here mirror the wire; nothing is derived.
 */

/** One repo the gateway lets a phone review: a project folder or a discovered repo. */
data class ShipyardRepo(
    val path: String,
    val label: String,
    /** "project" (an explicit project's folder) or "discovered". */
    val source: String,
    /** Checked-out branch; null when detached. */
    val branch: String?,
)

/** `repo_status`: the working tree at a glance. */
data class ShipyardStatus(
    val branch: String?,
    val defaultBranch: String?,
    val detached: Boolean,
    val ahead: Int,
    val behind: Int,
    val staged: Int,
    val unstaged: Int,
    val untracked: Int,
    val conflicted: Int,
    val changed: Int,
    val added: Int,
    val removed: Int,
) {
    val clean: Boolean get() = changed == 0 && untracked == 0
}

/** One changed file in a review scope. [status] is git's letter: M A D R ? U … */
data class ShipyardFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val status: String,
    val staged: Boolean,
) {
    val untracked: Boolean get() = status == "?"
}

data class ShipyardReview(
    val scope: String,
    /** The merge-base for `branch` scope; null for uncommitted. */
    val base: String?,
    val files: List<ShipyardFile>,
)

/** A unified diff, clipped server-side when large — [clipped] is honest, never silent. */
data class ShipyardDiff(
    val file: String,
    val diff: String,
    val clipped: Boolean,
    val omittedLines: Int,
    val totalLines: Int,
)

/** What a commit message should be written against: the staged (or whole) diff + recent subjects. */
data class ShipyardCommitContext(
    val diff: String,
    val recentSubjects: List<String>,
)

data class ShipyardCommitResult(
    val sha: String?,
    val pushed: Boolean,
)

/** `review_ship_info`: is `gh` usable, and is this branch already a PR? */
data class ShipyardShipInfo(
    val ghReady: Boolean,
    val prUrl: String?,
    val prState: String?,
    val prNumber: Int?,
)
