package chat.keryx.core.model

/**
 * One conversation, however many ids it has worn (2.19).
 *
 * Hermes gives a session two kinds of children, and the session list treats them differently:
 *  - **Compaction** ends a session and continues it under a new id. That is the same
 *    conversation, so the gateway lists only the live tip, naming every id it has had in
 *    `_lineage_ids` (and the first in `_lineage_root_id`). A tip never sits beside its own
 *    root: [dropSuperseded] removes a held row that a fresh row's lineage has replaced.
 *  - **A fork** (`/branch`, Keryx's own branch) is a new conversation that began as a copy
 *    of another. The gateway marks it `_branched_from`. [nest] draws it under the
 *    conversation it came from, so the family reads together without merging into one row.
 *
 * A `/new` reset also keeps a parent link (`_reset_from`), but it is a fresh conversation by
 * design, so it is never nested.
 */
object SessionTree {

    /** Indentation stops here: a fork of a fork of a fork still reads as "under that one". */
    const val MAX_DEPTH = 2

    /**
     * [rows] without the ones another row's lineage has replaced: a root or middle segment
     * held from an earlier read, once its continuation shows up. Order is kept.
     */
    fun <T> dropSuperseded(rows: List<T>, id: (T) -> String, lineage: (T) -> Collection<String>): List<T> {
        val replaced = HashSet<String>()
        for (r in rows) {
            val self = id(r)
            for (old in lineage(r)) if (old != self) replaced += old
        }
        if (replaced.isEmpty()) return rows
        return rows.filter { id(it) !in replaced }
    }

    /**
     * Forks under the conversation they came from, depth set on each row.
     *
     * Each family moves as one: it is placed by the newest activity anywhere in it, so a
     * busy fork carries its parent up rather than leaving it behind on an older shelf.
     * Within a family, forks keep newest-first. A fork whose parent is not in [rows] (an
     * older page, pinned to the deck, filtered out) stands on its own at depth 0 and keeps
     * [RoomProfile.forkOf], so the row can still say where it came from. Pure, and it
     * recomputes depth from scratch, so it is safe to run again after filtering.
     */
    fun nest(rows: List<RoomProfile>): List<RoomProfile> {
        if (rows.none { it.forkOf != null }) {
            return if (rows.any { it.forkDepth != 0 }) rows.map { it.copy(forkDepth = 0) } else rows
        }
        val ids = rows.mapTo(HashSet()) { it.id }
        val children = LinkedHashMap<String, MutableList<RoomProfile>>()
        val roots = ArrayList<RoomProfile>()
        for (r in rows) {
            val parent = r.forkOf?.takeIf { it != r.id && it in ids }
            if (parent == null) roots += r else children.getOrPut(parent) { mutableListOf() } += r
        }
        // A fork cycle in the data has no root in it; its rows would never be reached, so
        // they join the roots instead of disappearing.
        val reachable = HashSet<String>()
        fun mark(id: String) {
            if (!reachable.add(id)) return
            children[id]?.forEach { mark(it.id) }
        }
        roots.forEach { mark(it.id) }
        rows.filterTo(roots) { it.id !in reachable }

        val newest = HashMap<String, Long>()
        fun familyStamp(r: RoomProfile, seen: MutableSet<String>): Long = newest.getOrPut(r.id) {
            if (!seen.add(r.id)) return r.timestamp
            maxOf(r.timestamp, children[r.id]?.maxOfOrNull { familyStamp(it, seen) } ?: 0L)
        }
        val out = ArrayList<RoomProfile>(rows.size)
        val placed = HashSet<String>()
        fun emit(r: RoomProfile, depth: Int) {
            if (!placed.add(r.id)) return
            out += r.copy(forkDepth = depth.coerceAtMost(MAX_DEPTH))
            children[r.id]
                ?.sortedByDescending { familyStamp(it, HashSet()) }
                ?.forEach { emit(it, depth + 1) }
        }
        roots.sortedByDescending { familyStamp(it, HashSet()) }.forEach { emit(it, 0) }
        return out
    }
}
