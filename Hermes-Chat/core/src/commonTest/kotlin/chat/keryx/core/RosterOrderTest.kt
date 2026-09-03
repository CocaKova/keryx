package chat.keryx.core

import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.RosterOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RosterOrderTest {

    private fun row(id: String, at: Long) = RoomProfile(id = id, name = id, type = RoomType.DIRECT_MESSAGE, timestamp = at)

    @Test
    fun newestActivityFirst_whateverOrderTheRowsArrivedIn() {
        val rows = listOf(row("old", 100), row("new", 300), row("mid", 200))
        assertEquals(listOf("new", "mid", "old"), RosterOrder.byActivity(rows).map { it.id })
    }

    @Test
    fun aLocallyMintedRowStampedNowRisesAboveTheServerPage() {
        val server = listOf(row("s1", 900), row("s2", 800))
        val pending = listOf(row("fresh", 1_000))
        assertEquals(listOf("fresh", "s1", "s2"), RosterOrder.byActivity(pending + server).map { it.id })
    }

    @Test
    fun tiesAndUnstampedRowsKeepTheirIncomingOrder() {
        val rows = listOf(row("a", 500), row("z0", 0), row("b", 500), row("z1", 0))
        assertEquals(listOf("a", "b", "z0", "z1"), RosterOrder.byActivity(rows).map { it.id })
    }

    @Test
    fun aRowYouJustSpokeIntoSortsToTheTop() {
        val rows = listOf(row("top", 900), row("spoken", 100), row("other", 500))
        val stamps = RosterOrder.stamp(emptyMap(), "spoken", now = 10_000)
        val ordered = RosterOrder.byActivity(RosterOrder.withLocalStamps(rows, stamps))
        assertEquals(listOf("spoken", "top", "other"), ordered.map { it.id })
        assertEquals(10_000, ordered.first().timestamp)
    }

    @Test
    fun aLocalStampNeverMovesTheServersAnswerBackwards() {
        val rows = listOf(row("ahead", 50_000), row("other", 100))
        val stamped = RosterOrder.withLocalStamps(rows, mapOf("ahead" to 20_000L))
        assertEquals(50_000, stamped.first { it.id == "ahead" }.timestamp)
        // No stamps at all: the very same list, no copy.
        assertSame(rows, RosterOrder.withLocalStamps(rows, emptyMap()))
    }

    @Test
    fun aStampInsideTheSlackIsTheSameMap() {
        val first = RosterOrder.stamp(emptyMap(), "live", now = 10_000)
        assertSame(first, RosterOrder.stamp(first, "live", now = 10_000 + 1_000))
        val later = RosterOrder.stamp(first, "live", now = 10_000 + RosterOrder.TOUCH_SLACK_MS)
        assertEquals(10_000 + RosterOrder.TOUCH_SLACK_MS, later["live"])
    }

    @Test
    fun stampsForRowsTheRosterDoesNotCarryAreHarmless() {
        val rows = listOf(row("a", 1))
        val stamped = RosterOrder.withLocalStamps(rows, mapOf("ghost" to 99L))
        assertEquals(listOf("a"), stamped.map { it.id })
        assertEquals(1, stamped.single().timestamp)
    }
}
