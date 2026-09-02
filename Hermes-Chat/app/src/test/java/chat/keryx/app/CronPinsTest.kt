package chat.keryx.app

import chat.keryx.core.model.CronGrouping
import chat.keryx.core.model.CronPins
import chat.keryx.core.model.CronRun
import chat.keryx.core.model.CronUnreadCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf is a pure function of the cards, and a pin flip is a value operation — those two
 * facts are what let the delegate move the shelf before the gateway answers and put it back
 * exactly when the gateway refuses. Pinned rules are kept here so neither can drift.
 */
class CronPinsTest {

    private fun run(title: String, ts: Long, pinned: Boolean = false) =
        CronRun(id = "s$ts", title = title, timestamp = ts, pinned = pinned)

    private fun cards(vararg runs: CronRun) =
        CronGrouping.group(runs.toList(), listOf("Daily Brief", "ArXiv"))

    @Test
    fun shelfIsEveryPinnedRunNewestFirstAcrossJobs() {
        val c = cards(
            run("Daily Brief · a", 100, pinned = true),
            run("ArXiv · a", 300, pinned = true),
            run("Daily Brief · b", 200),
            run("ArXiv · b", 50, pinned = true),
        )
        assertEquals(listOf("s300", "s100", "s50"), CronPins.of(c).map { it.id })
        assertEquals(setOf("s300", "s100", "s50"), CronPins.ids(c))
    }

    @Test
    fun noPinsNoShelf() {
        val c = cards(run("Daily Brief · a", 100), run("ArXiv · a", 90))
        assertTrue(CronPins.of(c).isEmpty())
        assertTrue(CronPins.ids(c).isEmpty())
    }

    @Test
    fun withPinFlipsExactlyOneRunAndNothingElseMoves() {
        val c = cards(
            run("Daily Brief · a", 100),
            run("Daily Brief · b", 200, pinned = true),
            run("ArXiv · a", 300),
        )
        val pinned = CronPins.withPin(c, "s100", pinned = true)
        assertEquals(c.map { it.name }, pinned.map { it.name })
        assertEquals(c.map { card -> card.runs.map { it.id } }, pinned.map { card -> card.runs.map { it.id } })
        assertEquals(setOf("s200", "s100"), CronPins.ids(pinned))
        // And back: the revert is the same operation with the other value.
        val reverted = CronPins.withPin(pinned, "s100", pinned = false)
        assertEquals(c, reverted)
    }

    @Test
    fun withPinOnAnUnknownIdLeavesTheCardsUntouched() {
        val c = cards(run("Daily Brief · a", 100))
        val same = CronPins.withPin(c, "nope", pinned = true)
        assertEquals(c, same)
        // Cards holding no such run are handed back as the same object, not a copy.
        assertSame(c[0], same[0])
    }

    @Test
    fun cardCountsItsOwnPins() {
        val c = cards(
            run("Daily Brief · a", 100, pinned = true),
            run("Daily Brief · b", 200, pinned = true),
            run("ArXiv · a", 300),
        )
        assertEquals(2, c.first { it.name == "Daily Brief" }.pinnedCount)
        assertEquals(0, c.first { it.name == "ArXiv" }.pinnedCount)
    }

    @Test
    fun pinningNeverTouchesUnread() {
        // Keeping a report is not reading it: a pinned run that landed after the baseline and
        // was never opened is still news, and pinning an old one doesn't resurrect it as news.
        val c = cards(run("Daily Brief · old", 100), run("Daily Brief · new", 200))
        val before = CronUnreadCalc.compute(c, emptySet(), baseline = 150L)
        val after = CronUnreadCalc.compute(
            CronPins.withPin(CronPins.withPin(c, "s200", true), "s100", true),
            emptySet(), baseline = 150L,
        )
        assertEquals(before.ids, after.ids)
        assertTrue(after.isNew("s200"))
        assertFalse(after.isNew("s100"))
    }
}
