package chat.keryx.app

import chat.keryx.core.model.CronGrouping
import chat.keryx.core.model.CronUnreadCalc
import chat.keryx.core.model.CronRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badge decides whether a person opens the app to homework or to news, so its rules are
 * pinned: a fresh install has no backlog, only opening a run clears it, and nothing that
 * predates the install can ever be "new".
 */
class CronUnreadTest {

    private fun run(title: String, ts: Long) = CronRun(id = "s$ts", title = title, timestamp = ts)

    private fun cards(vararg runs: CronRun) =
        CronGrouping.group(runs.toList(), listOf("Daily Brief", "ArXiv"))

    @Test
    fun unBaselinedInstallShowsNothing() {
        val c = cards(run("Daily Brief · a", 100), run("ArXiv · a", 90))
        assertEquals(0, CronUnreadCalc.compute(c, emptySet(), baseline = 0L).total)
    }

    @Test
    fun baselineIsTheNewestRunAtFirstSight() {
        val c = cards(run("Daily Brief · a", 100), run("ArXiv · a", 90))
        assertEquals(100L, CronUnreadCalc.baselineOf(c))
        // Everything that existed at first sight is history, the newest one included.
        assertEquals(0, CronUnreadCalc.compute(c, emptySet(), baseline = 100L).total)
    }

    @Test
    fun aGatewayWithNoRunsStaysUnBaselined() {
        // Jobs exist, none has ever run: the FIRST report must still arrive as news.
        val c = CronGrouping.group(emptyList(), listOf("Daily Brief"))
        assertEquals(0L, CronUnreadCalc.baselineOf(c))
    }

    @Test
    fun runsAfterTheBaselineAreNewUntilOpened() {
        val fresh = run("Daily Brief · b", 200)
        val c = cards(fresh, run("Daily Brief · a", 100))
        val unread = CronUnreadCalc.compute(c, emptySet(), baseline = 100L)
        assertEquals(1, unread.total)
        assertEquals(1, unread.countFor("Daily Brief"))
        assertEquals(0, unread.countFor("ArXiv"))
        assertTrue(unread.isNew(fresh.id))

        val afterOpen = CronUnreadCalc.compute(c, setOf(fresh.id), baseline = 100L)
        assertEquals(0, afterOpen.total)
        assertFalse(afterOpen.isNew(fresh.id))
    }

    @Test
    fun newestFirstAcrossJobs() {
        val c = cards(
            run("Daily Brief · b", 300),
            run("ArXiv · b", 400),
            run("Daily Brief · a", 100),
        )
        val unread = CronUnreadCalc.compute(c, emptySet(), baseline = 100L)
        assertEquals(listOf(400L, 300L), unread.runs.map { it.timestamp })
        assertEquals(2, unread.total)
    }

    @Test
    fun seenIdsSurviveUntilTheSetIsBig() {
        // Below the cap the set is kept whole: the cron list is a PAGE, and an id outside
        // this fetch's window can come back into it — dropping it would resurrect a report.
        val seen = setOf("gone", "here")
        assertEquals(seen, CronUnreadCalc.prune(seen, setOf("here")))
        // Past the cap, ids the gateway no longer lists are dropped.
        val big = (1..10).map { "id$it" }.toSet() + "gone"
        assertEquals(
            (1..10).map { "id$it" }.toSet(),
            CronUnreadCalc.prune(big, (1..10).map { "id$it" }.toSet(), cap = 5),
        )
    }

    @Test
    fun knownIdsCoverEveryRunOnTheSurface() {
        val c = cards(run("Daily Brief · b", 300), run("ArXiv · b", 400))
        assertEquals(setOf("s300", "s400"), CronUnreadCalc.knownIds(c))
    }
}
