package chat.keryx.core

import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.RosterGroup
import chat.keryx.core.model.RosterGroups
import chat.keryx.core.model.RosterGroups.DAY_MS
import kotlin.test.Test
import kotlin.test.assertEquals

class RosterGroupsTest {

    private val midnight = 1_000_000_000_000L
    private fun row(id: String, at: Long, unread: Boolean = false) =
        RoomProfile(id = id, name = id, type = RoomType.DIRECT_MESSAGE, timestamp = at, unread = unread)

    @Test
    fun eachShelfHasItsBoundary() {
        assertEquals(RosterGroup.TODAY, RosterGroups.groupOf(midnight, midnight))
        assertEquals(RosterGroup.TODAY, RosterGroups.groupOf(midnight + 5 * 3_600_000, midnight))
        assertEquals(RosterGroup.YESTERDAY, RosterGroups.groupOf(midnight - 1, midnight))
        assertEquals(RosterGroup.YESTERDAY, RosterGroups.groupOf(midnight - DAY_MS, midnight))
        assertEquals(RosterGroup.THIS_WEEK, RosterGroups.groupOf(midnight - DAY_MS - 1, midnight))
        assertEquals(RosterGroup.THIS_WEEK, RosterGroups.groupOf(midnight - 6 * DAY_MS, midnight))
        assertEquals(RosterGroup.OLDER, RosterGroups.groupOf(midnight - 6 * DAY_MS - 1, midnight))
        assertEquals(RosterGroup.UNDATED, RosterGroups.groupOf(0L, midnight))
    }

    @Test
    fun shelvesComeInOrderAndKeepTheRowsOrderWithin() {
        val rows = listOf(
            row("t1", midnight + 100), row("t2", midnight + 50),
            row("y1", midnight - 10), row("w1", midnight - 3 * DAY_MS),
            row("o1", midnight - 30 * DAY_MS), row("u", 0L),
        )
        val sections = RosterGroups.split(rows, midnight)
        assertEquals(
            listOf(RosterGroup.TODAY, RosterGroup.YESTERDAY, RosterGroup.THIS_WEEK, RosterGroup.OLDER, RosterGroup.UNDATED),
            sections.map { it.group },
        )
        assertEquals(listOf("t1", "t2"), sections.first().rows.map { it.id })
    }

    @Test
    fun anEmptyShelfIsNotReturned() {
        val sections = RosterGroups.split(listOf(row("o1", midnight - 30 * DAY_MS)), midnight)
        assertEquals(listOf(RosterGroup.OLDER), sections.map { it.group })
        assertEquals(emptyList(), RosterGroups.split(emptyList(), midnight))
    }

    @Test
    fun aFoldedShelfStillCountsItsUnread() {
        val sections = RosterGroups.split(
            listOf(row("a", midnight - 2 * DAY_MS, unread = true), row("b", midnight - 2 * DAY_MS), row("c", midnight - 2 * DAY_MS, unread = true)),
            midnight,
        )
        assertEquals(2, sections.single().unread)
        assertEquals(3, sections.single().rows.size)
    }
}
