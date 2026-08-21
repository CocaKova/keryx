package chat.keryx.app

import chat.keryx.core.model.CronHumanize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Schedule/digest cases are the LIVE roster and a LIVE report tail (probed 08-14),
 *  not invented ideals. */
class CronHumanizeTest {

    @Test
    fun `the live roster's schedules read as words`() {
        assertEquals("daily 08:00", CronHumanize.schedule("0 8 * * *"))
        assertEquals("daily 07:15", CronHumanize.schedule("15 7 * * *"))
        assertEquals("Sun 19:00", CronHumanize.schedule("0 19 * * 0"))
        assertEquals("Sat 10:00", CronHumanize.schedule("0 10 * * 6"))
        assertEquals("every 30 min", CronHumanize.schedule("every 30m"))
        assertEquals("every 14 days", CronHumanize.schedule("every 20160m"))
    }

    @Test
    fun `common shapes beyond the roster`() {
        assertEquals("weekdays 07:15", CronHumanize.schedule("15 7 * * 1-5"))
        assertEquals("weekends 09:00", CronHumanize.schedule("0 9 * * 0,6"))
        assertEquals("Mon/Thu 06:30", CronHumanize.schedule("30 6 * * 1,4"))
        assertEquals("every 30 min", CronHumanize.schedule("*/30 * * * *"))
        assertEquals("every 2 h", CronHumanize.schedule("0 */2 * * *"))
        assertEquals("monthly (day 1) 09:00", CronHumanize.schedule("0 9 1 * *"))
        assertEquals("hourly", CronHumanize.schedule("every 60m"))
        assertEquals("daily", CronHumanize.schedule("every 1440m"))
    }

    @Test
    fun `unparseable schedules pass through raw - never lie, never hide`() {
        assertEquals("0 9 * 2 *", CronHumanize.schedule("0 9 * 2 *")) // month-scoped: raw
        assertEquals("weird", CronHumanize.schedule("weird"))
        assertEquals("", CronHumanize.schedule(""))
    }

    @Test
    fun `nextIn speaks distance, refuses the past`() {
        val now = 1_786_800_000_000L
        assertEquals("next in 45m", CronHumanize.nextIn(iso(now + 45 * 60_000), now))
        assertEquals("next in 11h", CronHumanize.nextIn(iso(now + 11 * 3_600_000), now))
        assertEquals("next in 3d", CronHumanize.nextIn(iso(now + 3 * 86_400_000L + 3_600_000), now))
        assertNull(CronHumanize.nextIn(iso(now - 60_000), now))
        assertNull(CronHumanize.nextIn("not a time", now))
        // The wire's real shapes: offset AND fractional-second forms both parse.
        assertEquals("next in 13h", CronHumanize.nextIn("2026-08-15T08:00:00-05:00", 1_786_752_000_000L))
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    @Test
    fun `a real report tail digests to its own headline`() {
        // The live Daily Brief tail, verbatim shape: framing sentence, rule, heading,
        // section, bold bullet.
        val tail = """
            The brief is written and persisted. Here's the delivery:

            ---

            # DAILY BRIEF 2026-08-14

            ## AI / TECH

            - **OpenAI launches "Ultrafast" mode — GPT-5.6 Sol at 14x speed.** New API tier powered by Cerebras.
        """.trimIndent()
        val d = CronHumanize.digest(tail)
        assertEquals("DAILY BRIEF 2026-08-14", d.title)
        assertEquals(
            "OpenAI launches \"Ultrafast\" mode — GPT-5.6 Sol at 14x speed. New API tier powered by Cerebras.",
            d.lead,
        )
    }

    @Test
    fun `machine lines and fences never become the headline`() {
        val d = CronHumanize.digest(
            "[IMPORTANT: You are running as a scheduled cron job. DELIVER]\n```\ncode noise\n```\nInbox is clear — nothing needed a reply.",
        )
        assertEquals("Inbox is clear — nothing needed a reply.", d.title)
        assertNull(d.lead) // prose first line IS the content
    }

    @Test
    fun `empty and all-noise input degrade to nothing, not garbage`() {
        assertNull(CronHumanize.digest("").title)
        assertNull(CronHumanize.digest("[machine]\n---\n```\nx\n```").title)
    }

    @Test
    fun `a heading later in the message beats narration before it`() {
        // The live AI-News shape: narration, a rule, then the report with its own title —
        // all ONE assistant message.
        val d = CronHumanize.digest(
            "Now I have all the data I need. Let me compile the briefing.\n\n---\n\n" +
                "# 🤖 AI Intelligence Brief — 2026-08-14\n\n- **Top story.** Detail.",
        )
        assertEquals("🤖 AI Intelligence Brief — 2026-08-14", d.title)
        assertEquals("Top story. Detail.", d.lead)
    }

    @Test
    fun `the report is the longest assistant text, not the last`() {
        // The live AI-News tail shape: long delivery, then short wrap-up narration.
        val delivery = "# AI NEWS 2026-08-14\n\n- **Story one.** " + "detail ".repeat(40)
        val picked = CronHumanize.pickReport(
            listOf("Now I have all the data I need. Let me compile the briefing.", delivery, ""),
        )
        assertEquals(delivery, picked)
        assertNull(CronHumanize.pickReport(listOf("", "  ")))
    }

    @Test
    fun `tint index is stable and in range`() {
        val a = CronHumanize.tintIndex("Daily Brief", 8)
        assertEquals(a, CronHumanize.tintIndex("Daily Brief", 8))
        assertEquals(true, a in 0 until 8)
        assertEquals(0, CronHumanize.tintIndex("anything", 0))
    }
}
