package chat.keryx.core

import chat.keryx.core.model.CronFilter
import chat.keryx.core.model.CronHealth
import chat.keryx.core.model.CronJobCard
import chat.keryx.core.model.CronJobFacts
import chat.keryx.core.model.CronRun
import chat.keryx.core.model.CronSection
import chat.keryx.core.model.CronTriage
import chat.keryx.core.model.CronUnread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Runs page's triage, against the shapes the live gateway serves (09-27: 24 jobs, the
 * failing ones half-hourly `no_agent` watchdogs whose failed dispatch writes no session).
 */
class CronTriageTest {

    private val hour = 60L * 60 * 1000
    // 2026-09-27T20:00:00Z
    private val now = 1_790_539_200_000L

    private fun iso(ms: Long): String = kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toString()

    private fun card(name: String, vararg ages: Long) = CronJobCard(
        name = name,
        scheduled = true,
        runs = ages.mapIndexed { i, age -> CronRun("$name-$i", "$name · run $i", now - age) },
    )

    private fun ok(lastAge: Long? = null, schedule: String = "0 7 * * *") = CronJobFacts(
        enabled = true, state = "scheduled", lastStatus = "ok",
        lastRunAt = lastAge?.let { iso(now - it) }, schedule = schedule,
    )

    private fun failing(lastAge: Long, schedule: String = "every 30m") = CronJobFacts(
        enabled = true, state = "scheduled", lastStatus = "error",
        lastError = "Restart-safe cron worker dispatch failed",
        lastRunAt = iso(now - lastAge), schedule = schedule,
    )

    @Test
    fun `a failing job with no transcript leads the page instead of sinking as idle`() {
        // The bug this exists for: no sessions ⇒ CronGrouping calls it "no runs yet" and sorts
        // it last. Its scheduler facts say it fired 10 minutes ago and failed.
        val cards = listOf(card("Daily Brief", 2 * hour), card("Lane run-watch"))
        val facts = mapOf("Daily Brief" to ok(2 * hour), "Lane run-watch" to failing(hour / 6))
        val rows = CronTriage.rows(cards, facts, CronUnread(), now)
        assertEquals(listOf("Lane run-watch", "Daily Brief"), rows.map { it.name })
        assertEquals(CronHealth.FAILED, rows[0].health)
        assertEquals(CronSection.ATTENTION, rows[0].section)
        assertEquals(CronSection.RECENT, rows[1].section)
    }

    @Test
    fun `sections are attention then recent then quiet, and quiet keeps live jobs above off ones`() {
        val cards = listOf(
            card("Monthly check", 24 * 24 * hour),
            card("Paused digest", 10 * 24 * hour),
            card("Never ran"),
            card("Weekly review", 30 * 24 * hour),
            card("Morning", 3 * hour),
        )
        val facts = mapOf(
            "Monthly check" to ok(24 * 24 * hour),
            "Paused digest" to ok(10 * 24 * hour).copy(enabled = false, state = "paused"),
            "Never ran" to CronJobFacts(enabled = true, state = "scheduled"),
            "Weekly review" to failing(30 * 24 * hour, "0 19 * * 0"),
            "Morning" to ok(3 * hour),
        )
        val rows = CronTriage.rows(cards, facts, CronUnread(), now)
        assertEquals(
            listOf("Weekly review", "Morning", "Monthly check", "Paused digest", "Never ran"),
            rows.map { it.name },
        )
        val blocks = CronTriage.sections(rows)
        assertEquals(listOf(CronSection.ATTENTION, CronSection.RECENT, CronSection.QUIET), blocks.map { it.section })
        assertEquals(3, blocks.last().rows.size)
        assertEquals(CronHealth.IDLE, rows.last().health)
        assertEquals(CronHealth.PAUSED, rows[3].health)
    }

    @Test
    fun `switched off beats failed, and a completed one-shot is off`() {
        val c = card("Reminder", 3 * hour)
        assertEquals(CronHealth.PAUSED, CronTriage.health(c, failing(3 * hour).copy(enabled = false)))
        assertEquals(CronHealth.PAUSED, CronTriage.health(c, ok(3 * hour).copy(enabled = false, state = "completed")))
        assertEquals(CronHealth.RUNNING, CronTriage.health(c, ok(3 * hour).copy(state = "running")))
        // Runs that outlived their job: no facts, still a healthy card with history.
        assertEquals(CronHealth.OK, CronTriage.health(c, null))
    }

    @Test
    fun `filters count what they would show and only offer chips that match`() {
        val cards = listOf(card("A", hour), card("B", 2 * hour), card("C"))
        val facts = mapOf(
            "A" to failing(hour),
            "B" to ok(2 * hour),
            "C" to CronJobFacts(enabled = false, state = "paused"),
        )
        val unread = CronUnread(
            runs = listOf(cards[1].runs[0]),
            byJob = mapOf("B" to 1),
            ids = setOf("B-0"),
        )
        val rows = CronTriage.rows(cards, facts, unread, now)
        val counts = CronTriage.counts(rows)
        assertEquals(3, counts[CronFilter.ALL])
        assertEquals(1, counts[CronFilter.UNREAD])
        assertEquals(1, counts[CronFilter.FAILING])
        assertEquals(1, counts[CronFilter.PAUSED])
        assertEquals(0, counts[CronFilter.NEVER_RUN])
        assertEquals(
            listOf(CronFilter.ALL, CronFilter.UNREAD, CronFilter.FAILING, CronFilter.PAUSED),
            CronTriage.offeredFilters(counts, CronFilter.ALL),
        )
        // The chip you're standing on survives dropping to zero.
        assertTrue(CronFilter.NEVER_RUN in CronTriage.offeredFilters(counts, CronFilter.NEVER_RUN))
        assertEquals(listOf("B"), CronTriage.visible(rows, CronFilter.UNREAD, "", emptyMap()).map { it.name })
    }

    @Test
    fun `search ANDs tokens across name, headline and schedule words`() {
        val cards = listOf(card("Weekly Review", hour), card("Daily Brief", hour))
        val facts = mapOf(
            "Weekly Review" to ok(hour, "0 19 * * 0"),
            "Daily Brief" to ok(hour, "0 7 * * *"),
        )
        val rows = CronTriage.rows(cards, facts, CronUnread(), now)
        val headlines = mapOf("Daily Brief" to "Three meetings and a dentist")
        fun hits(q: String) = CronTriage.visible(rows, CronFilter.ALL, q, headlines).map { it.name }.sorted()
        assertEquals(listOf("Daily Brief", "Weekly Review"), hits(""))
        assertEquals(listOf("Weekly Review"), hits("review"))
        assertEquals(listOf("Daily Brief"), hits("DENTIST"))
        assertEquals(listOf("Weekly Review"), hits("sun"))          // "Sun 19:00"
        assertEquals(listOf("Daily Brief"), hits("daily 07:00"))    // both tokens, one job
        assertEquals(emptyList<String>(), hits("daily sun"))
    }

    @Test
    fun `strip reads oldest to newest, capped, with the verdict on the newest`() {
        val c = card("Brief", hour, 25 * hour, 49 * hour)
        val ticks = CronTriage.strip(c, failing(hour / 2).copy(lastRunAt = iso(now - 2 * hour)), setOf("Brief-0"))
        // lastRunAt (2h ago) is before the newest run's activity (1h ago): the failure IS that run.
        assertEquals(listOf("Brief-2", "Brief-1", "Brief-0"), ticks.map { it.runId })
        assertEquals(listOf(false, false, true), ticks.map { it.failed })
        assertEquals(listOf(false, false, true), ticks.map { it.unread })

        val many = card("Many", *LongArray(20) { (it + 1) * hour })
        assertEquals(CronTriage.STRIP_MAX, CronTriage.strip(many, ok(hour), emptySet()).size)
        assertEquals("Many-0", CronTriage.strip(many, ok(hour), emptySet()).last().runId)
        assertTrue(CronTriage.strip(many, ok(hour), emptySet()).none { it.failed })
    }

    @Test
    fun `a failure that wrote no session gets its own tick with nothing to open`() {
        val c = card("Watchdog", 5 * hour, 6 * hour)
        val ticks = CronTriage.strip(c, failing(hour / 6), emptySet())
        assertEquals(3, ticks.size)
        assertNull(ticks.last().runId)
        assertTrue(ticks.last().failed)
        assertFalse(ticks.first().failed)
        // And with no runs at all, the failure is still visible.
        assertEquals(1, CronTriage.strip(card("Bare"), failing(hour), emptySet()).size)
        assertTrue(CronTriage.strip(card("Bare"), ok(), emptySet()).isEmpty())
    }

    @Test
    fun `recent is a 24 hour window on the newest sign of life`() {
        val c = card("Weekly", 23 * hour)
        assertEquals(CronSection.RECENT, CronTriage.rows(listOf(c), mapOf("Weekly" to ok(23 * hour)), CronUnread(), now)[0].section)
        val old = card("Weekly", 25 * hour)
        assertEquals(CronSection.QUIET, CronTriage.rows(listOf(old), mapOf("Weekly" to ok(25 * hour)), CronUnread(), now)[0].section)
        // The scheduler's fire counts even when the run left no transcript.
        val bare = card("Script")
        assertEquals(CronSection.RECENT, CronTriage.rows(listOf(bare), mapOf("Script" to ok(hour)), CronUnread(), now)[0].section)
    }

    @Test
    fun `an error's gist is a traceback's exception line, else its first line`() {
        val tb = "Restart-safe cron worker dispatch failed: cron external worker exited before ownership " +
            "acknowledgement (exit 1); worker stderr: Traceback (most recent call last):\n" +
            "  File \"/h/cron/__init__.py\", line 6, in <module>\n    from cron.jobs import (\n" +
            "ModuleNotFoundError: No module named 'ruamel'\n"
        assertEquals("ModuleNotFoundError: No module named 'ruamel'", CronTriage.errorGist(tb))
        assertEquals("RuntimeError: Connection error.", CronTriage.errorGist("RuntimeError: Connection error.\nmore"))
        assertEquals("", CronTriage.errorGist("  \n "))
    }

    @Test
    fun `script jobs shelve together unless they are failing`() {
        val facts = mapOf(
            "Watchdog" to ok(hour, schedule = "every 30m").copy(scriptOnly = true),
            "Bridge" to failing(hour).copy(scriptOnly = true, failureStreak = 6),
            "Brief" to ok(hour),
        )
        val rows = CronTriage.rows(listOf(card("Watchdog"), card("Bridge"), card("Brief", hour)), facts, CronUnread(), now)
        val byName = rows.associateBy { it.name }
        assertEquals(CronSection.SCRIPTS, byName.getValue("Watchdog").section)
        // The shelf hides the routine, never the broken.
        assertEquals(CronSection.ATTENTION, byName.getValue("Bridge").section)
        assertEquals(CronSection.RECENT, byName.getValue("Brief").section)
        assertEquals(
            listOf(CronSection.ATTENTION, CronSection.RECENT, CronSection.SCRIPTS),
            CronTriage.sections(rows).map { it.section },
        )
    }

    @Test
    fun `iso parsing takes the gateway's offset stamps and refuses junk`() {
        assertEquals(
            kotlinx.datetime.Instant.parse("2026-09-27T13:00:44.990158Z").toEpochMilliseconds(),
            CronTriage.parseIso("2026-09-27T08:00:44.990158-05:00"),
        )
        assertNull(CronTriage.parseIso(null))
        assertNull(CronTriage.parseIso(""))
        assertNull(CronTriage.parseIso("tomorrow"))
    }

    @Test
    fun `the rail folds past its threshold and ago never lies`() {
        assertFalse(CronTriage.foldRail(CronTriage.RAIL_FOLD_AT))
        assertTrue(CronTriage.foldRail(CronTriage.RAIL_FOLD_AT + 1))
        assertEquals("—", CronTriage.ago(0L, now))
        assertEquals("now", CronTriage.ago(now + hour, now))
        assertEquals("3h ago", CronTriage.ago(now - 3 * hour, now))
        assertEquals("2d ago", CronTriage.ago(now - 49 * hour, now))
    }
}
