package chat.keryx.core.model

/**
 * The drawer's shelves: a roster read by *when*, not just in one long newest-first run.
 *
 * Today · Yesterday · This week · Older — the same four the Hermes Desktop sidebar keeps, each
 * one a header you can fold. A folded shelf still says how many rows it holds and how many of
 * them are unread, so nothing is hidden, only put away. The split is a pure function of the
 * rows and one number — the local midnight the caller measured — because a `commonMain`
 * object has no clock and no zone, and a shelf boundary that moves with the phone's zone is
 * the phone's to decide.
 */
enum class RosterGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    OLDER("Older"),
    /** No activity stamp at all — a row the wire has not dated. Sinks below everything. */
    UNDATED("Undated"),
}

data class RosterSection(val group: RosterGroup, val rows: List<RoomProfile>) {
    val unread: Int get() = rows.count { it.hasUnread }
}

object RosterGroups {
    const val DAY_MS = 86_400_000L

    /** Which shelf a stamp belongs on, given the local midnight that began today. */
    fun groupOf(timestamp: Long, startOfTodayMs: Long): RosterGroup = when {
        timestamp <= 0L -> RosterGroup.UNDATED
        timestamp >= startOfTodayMs -> RosterGroup.TODAY
        timestamp >= startOfTodayMs - DAY_MS -> RosterGroup.YESTERDAY
        timestamp >= startOfTodayMs - 6 * DAY_MS -> RosterGroup.THIS_WEEK
        else -> RosterGroup.OLDER
    }

    /**
     * Rows shelved in [RosterGroup] order, each shelf keeping the rows' incoming order (the
     * roster is already sorted by activity — a shelf never re-sorts, it only divides). Empty
     * shelves are not returned: a header over nothing is a lie about the list.
     *
     * A nested fork ([RoomProfile.forkDepth] > 0, laid out by [SessionTree.nest]) is shelved
     * with the family it sits under, by the family's newest activity — a fork answered today
     * pulls its week-old parent onto Today rather than splitting the pair across two shelves.
     */
    fun split(rows: List<RoomProfile>, startOfTodayMs: Long): List<RosterSection> {
        if (rows.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<RosterGroup, MutableList<RoomProfile>>()
        for (g in RosterGroup.entries) buckets[g] = mutableListOf()
        var i = 0
        while (i < rows.size) {
            var end = i + 1
            while (end < rows.size && rows[end].forkDepth > 0) end++
            val family = rows.subList(i, end)
            buckets.getValue(groupOf(family.maxOf { it.timestamp }, startOfTodayMs)).addAll(family)
            i = end
        }
        return buckets.entries.filter { it.value.isNotEmpty() }.map { RosterSection(it.key, it.value) }
    }
}
