package chat.keryx.app

import chat.keryx.core.model.CronGrouping
import chat.keryx.core.model.CronRun
import chat.keryx.core.model.CronTile
import chat.keryx.core.model.CronTiles
import chat.keryx.core.model.CronUnreadCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinned job is a tile that follows its newest run; a pinned run is a tile that is that run. */
class CronTilesTest {

    private fun run(title: String, ts: Long, pinned: Boolean = false) =
        CronRun(id = "s$ts", title = title, timestamp = ts, pinned = pinned)

    private fun cards(vararg runs: CronRun) =
        CronGrouping.group(runs.toList(), listOf("Daily Brief", "ArXiv", "Quiet Job"))

    @Test
    fun jobTileFollowsTheNewestRunAndWearsItsUnread() {
        val c = cards(run("Daily Brief · Mon", 100), run("Daily Brief · Tue", 200), run("ArXiv · a", 150))
        val unread = CronUnreadCalc.compute(c, emptySet(), baseline = 150L)
        val tiles = CronTiles.build(c, listOf("Daily Brief"), unread)
        val t = tiles.single()
        assertEquals(CronTile.jobId("Daily Brief"), t.id)
        assertTrue(t.job)
        assertEquals("s200", t.runId)
        assertEquals("Daily Brief · Tue", t.runTitle)
        assertTrue(t.unread)
    }

    @Test
    fun jobsKeepTheirPinnedOrderThenRunsNewestFirst() {
        val c = cards(
            run("Daily Brief · a", 100, pinned = true),
            run("ArXiv · a", 300, pinned = true),
            run("ArXiv · b", 50),
        )
        val tiles = CronTiles.build(c, listOf("ArXiv", "Daily Brief"), CronUnreadCalc.compute(c, emptySet(), 0L))
        assertEquals(
            listOf(CronTile.jobId("ArXiv"), CronTile.jobId("Daily Brief"), "s300", "s100"),
            tiles.map { it.id },
        )
        assertEquals(listOf(true, true, false, false), tiles.map { it.job })
        assertEquals("ArXiv", tiles[2].name)
        assertEquals("ArXiv · a", tiles[2].label)
    }

    @Test
    fun aPinnedJobWithNoVisibleRunStillHasATile() {
        val c = cards(run("Daily Brief · a", 100))
        val t = CronTiles.build(c, listOf("Quiet Job"), CronUnreadCalc.compute(c, emptySet(), 0L)).single()
        assertNull(t.runId)
        assertFalse(t.unread)
        assertEquals("Quiet Job", t.label)
    }

    @Test
    fun nothingPinnedNoTiles() {
        val c = cards(run("Daily Brief · a", 100))
        assertTrue(CronTiles.build(c, emptyList(), CronUnreadCalc.compute(c, emptySet(), 0L)).isEmpty())
    }
}
