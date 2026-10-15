package chat.keryx.core

import chat.keryx.core.demo.DemoScript
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `whatever is typed, the answer points at connecting a real gateway`() {
        assertTrue(DemoScript.replyTo("rm -rf /").contains("gateway"))
    }
}
