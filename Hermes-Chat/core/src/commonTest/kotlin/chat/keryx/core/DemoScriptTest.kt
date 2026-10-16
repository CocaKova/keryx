package chat.keryx.core

import chat.keryx.core.demo.DemoPages
import chat.keryx.core.demo.DemoScript
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demo gateway's script (3.0 herald): every session draws through the real transcript
 * builder, and the drawer it makes has what the tour points at.
 */
class DemoScriptTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `every session draws a transcript that starts with the user and ends on the agent's words`() {
        for (s in DemoScript.sessions) {
            val msgs = DemoScript.transcript(s.id, now)
            assertTrue(msgs.isNotEmpty(), s.id)
            assertEquals(SenderType.ME, msgs.first().sender, s.id)
            val last = msgs.last()
            assertEquals(SenderType.HERMES, last.sender, s.id)
            assertTrue(last.content.isNotBlank(), s.id)
        }
    }

    @Test
    fun `tool calls come back paired with their results, none failed`() {
        val calls = DemoScript.sessions.flatMap { DemoScript.transcript(it.id, now) }.flatMap { it.toolCalls }
        assertTrue(calls.size >= 5)
        for (c in calls) {
            assertEquals(ToolStatus.COMPLETED, c.status, c.name)
            assertTrue(c.result.isNotBlank(), c.name)
            assertTrue(c.context.isNotBlank(), c.name)
        }
    }

    @Test
    fun `message ids are unique across the whole demo, as the Archive keys them`() {
        val ids = DemoScript.sessions.flatMap { DemoScript.transcript(it.id, now) }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the drawer is newest first, opens on a real session, and has one running and one unread`() {
        val rooms = DemoScript.rooms(now)
        assertEquals(rooms.sortedByDescending { it.timestamp }, rooms)
        assertTrue(rooms.any { it.id == DemoScript.FIRST })
        assertEquals(listOf(DemoScript.RUNNING), rooms.filter { it.isActive }.map { it.id })
        assertEquals(listOf(DemoScript.UNREAD), rooms.filter { it.hasUnread }.map { it.id })
        assertTrue(rooms.all { it.timestamp < now && it.name.isNotBlank() })
    }

    private val job get() = DemoScript.sessions.single { it.id == DemoScript.RUNNING }

    /** The importer's transcript after the visitor answered [choice], a minute a row. */
    private fun played(choice: String): Pair<List<chat.keryx.core.model.Message>, List<DemoScript.Row>> {
        val base = DemoScript.stamped(job.id, now)
        val after = job.ask!!.after(choice).mapIndexed { i, row -> DemoScript.Stamped(row, now + (i + 1) * 60_000L) }
        val rows = base + after
        return DemoScript.build(job.id, rows) to rows.map { it.row }
    }

    @Test
    fun `the demo opens on the agent at work, its plan half done and an approval waiting`() {
        assertEquals(DemoScript.RUNNING, DemoScript.FIRST)
        val plan = assertNotNull(DemoScript.planOf(job.rows.toList()))
        assertEquals(4, plan.total)
        assertEquals(2, plan.done)
        assertEquals("Run the tests", plan.active?.content)
        val card = assertNotNull(job.ask).request
        assertEquals(listOf("once", "session", "always", "deny"), card.choices)
        // Hermes's own check would stop on it (tools/approval_detection.py, "recursive delete").
        assertTrue(Regex("""\brm\s+-[^\s]*r""").containsMatchIn(card.command))
        assertEquals("recursive delete", card.description)
        // Only the live job asks.
        assertEquals(listOf(DemoScript.RUNNING), DemoScript.sessions.filter { it.ask != null }.map { it.id })
    }

    @Test
    fun `either answer plays the job to the end, the plan done and a page to open`() {
        for (choice in listOf("once", "deny")) {
            val (msgs, rows) = played(choice)
            assertTrue(assertNotNull(DemoScript.planOf(rows)).allDone, choice)
            val page = msgs.single { it.mediaKind != null }
            assertEquals(MediaKind.FILE, page.mediaKind, choice)
            assertEquals(DemoPages.SPEEDUP, page.mediaUrl, choice)
            assertEquals("speedup.html", page.fileName, choice)
            assertNotNull(DemoPages.page(DemoPages.SPEEDUP))
            // The prose that named the page keeps none of the tag.
            assertFalse(msgs.any { it.content.contains("MEDIA:") }, choice)
        }
        assertTrue(played("once").first.any { it.content.contains("cleared the stale thumbnail cache") })
        assertTrue(played("deny").first.any { it.content.contains("thumbnail cache is untouched") })
    }

    @Test
    fun `a replayed transcript keeps every id it had, so nothing on screen jumps`() {
        val before = DemoScript.transcript(job.id, now).map { it.id }
        val after = played("once").first.map { it.id }
        assertEquals(before, after.take(before.size))
    }

    @Test
    fun `whatever is typed, the answer points at connecting a real gateway`() {
        assertTrue(DemoScript.replyTo("rm -rf /").contains("gateway"))
    }
}
