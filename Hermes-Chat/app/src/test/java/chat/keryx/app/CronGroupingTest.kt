package chat.keryx.app

import chat.keryx.core.model.CronGrouping
import chat.keryx.core.model.CronRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping scheduled runs by job is the whole point of the Cron place, and it runs against
 * whatever job names a stranger's gateway happens to use — so the rules that keep it honest
 * (longest match wins, unknown jobs still group, nothing is ever dropped) are pinned here.
 */
class CronGroupingTest {

    private var t = 1_000_000L
    private fun run(title: String, ts: Long = t++) = CronRun(id = "s$ts", title = title, timestamp = ts)

    @Test
    fun runsCollapseIntoOneCardPerJob() {
        // The real shape from a live gateway: "<job name> · <when>".
        val cards = CronGrouping.group(
            listOf(
                run("Daily Brief · Aug 14 07:06"),
                run("Daily Brief · Aug 13 07:04"),
                run("Localpeer inbox scan · Aug 14 01:01"),
            ),
            listOf("Daily Brief", "Localpeer inbox scan"),
        )
        assertEquals(2, cards.size)
        assertEquals(2, cards.first { it.name == "Daily Brief" }.runCount)
    }

    @Test
    fun longestJobNameWins() {
        // A shorter job name that prefixes a longer one must not steal its runs.
        val cards = CronGrouping.group(
            listOf(run("Weekly Review — Finance · Aug 10 19:00")),
            listOf("Weekly Review", "Weekly Review — Finance"),
        )
        val owner = cards.single { it.runCount == 1 }
        assertEquals("Weekly Review — Finance", owner.name)
    }

    @Test
    fun aJobNameMustMatchWholeSegments_notArbitraryPrefixes() {
        // "Daily Brief" must not claim "Daily Briefing Extra": the separator is the boundary.
        val cards = CronGrouping.group(
            listOf(run("Daily Briefing Extra · Aug 14 07:06")),
            listOf("Daily Brief"),
        )
        val orphan = cards.single { it.runCount == 1 }
        assertEquals("Daily Briefing Extra", orphan.name)
        assertTrue("no real job matched, so the card is unscheduled", !orphan.scheduled)
        // And the real job is still listed — it simply has no runs of its own.
        assertTrue(cards.single { it.name == "Daily Brief" }.neverRun)
    }

    @Test
    fun runsOfADeletedJobStillGroup() {
        // The job is gone from the registry; its history is still real work.
        val cards = CronGrouping.group(
            listOf(
                run("Retired Sweep · Aug 10 06:00"),
                run("Retired Sweep · Aug 09 06:00"),
            ),
            emptyList(),
        )
        assertEquals(1, cards.size)
        assertEquals(2, cards.single().runCount)
        assertTrue(!cards.single().scheduled)
    }

    @Test
    fun scheduledButNeverRunJobsStillAppear() {
        val cards = CronGrouping.group(emptyList(), listOf("Weekly Docker Image Refresh"))
        assertEquals(1, cards.size)
        assertTrue(cards.single().neverRun)
        assertTrue(cards.single().scheduled)
    }

    @Test
    fun neverRunJobsSortBelowActiveOnes() {
        val cards = CronGrouping.group(
            listOf(run("Daily Brief · Aug 14 07:06")),
            listOf("Daily Brief", "Aardvark Job"),
        )
        // Alphabetically "Aardvark" would come first; recency has to outrank the alphabet.
        assertEquals("Daily Brief", cards.first().name)
    }

    @Test
    fun runsWithinACardAreNewestFirst() {
        val cards = CronGrouping.group(
            listOf(
                run("Daily Brief · Aug 12", ts = 100),
                run("Daily Brief · Aug 14", ts = 300),
                run("Daily Brief · Aug 13", ts = 200),
            ),
            listOf("Daily Brief"),
        )
        assertEquals(listOf(300L, 200L, 100L), cards.single().runs.map { it.timestamp })
        assertEquals(300L, cards.single().latest?.timestamp)
    }

    @Test
    fun aTitleWithNoSeparatorKeepsItsWholeName() {
        val cards = CronGrouping.group(listOf(run("one-off maintenance")), emptyList())
        assertEquals("one-off maintenance", cards.single().name)
    }

    @Test
    fun everySessionSurvivesGrouping() {
        // The invariant that matters most: grouping hides nothing.
        val runs = listOf(
            run("Daily Brief · Aug 14"),
            run("Unknown Job · Aug 14"),
            run("no separator here"),
            run(""),
        )
        val cards = CronGrouping.group(runs, listOf("Daily Brief"))
        assertEquals(runs.size, cards.sumOf { it.runCount })
    }
}
