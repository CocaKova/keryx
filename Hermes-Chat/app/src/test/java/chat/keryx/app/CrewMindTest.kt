package chat.keryx.app

import chat.keryx.app.presentation.tapin.CrewMind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crew mind (2.13) is read off the child's own session through the gateway's watch window.
 * These pin what the sheet shows from a given store: the live thought, the words so far, every
 * tool in order, and the fold of what ran before the window opened.
 */
class CrewMindTest {

    private fun agent(
        id: String,
        content: String = "",
        reasoning: String? = null,
        streaming: Boolean = false,
        tools: List<ToolCall> = emptyList(),
    ) = Message(
        id = id, roomId = "child", sender = SenderType.HERMES, content = content, timestamp = 1L,
        isStreaming = streaming, reasoning = reasoning, toolCalls = tools,
    )

    private fun me(id: String, content: String) =
        Message(id = id, roomId = "child", sender = SenderType.ME, content = content, timestamp = 0L)

    @Test fun emptyStoreIsEmptyMind() {
        val mind = CrewMind.of(emptyList())
        assertTrue(mind.empty)
        assertFalse(mind.streaming)
    }

    @Test fun liveThoughtWinsOverEarlierOnes() {
        val mind = CrewMind.of(listOf(
            agent("a", content = "first pass", reasoning = "old thought"),
            agent("b", reasoning = "reading the diff now", streaming = true),
        ))
        assertEquals("reading the diff now", mind.thinking)
        assertTrue(mind.streaming)
        assertFalse(mind.empty)
    }

    @Test fun lastThoughtStaysWhenNothingStreams() {
        val mind = CrewMind.of(listOf(
            agent("a", reasoning = "plan"),
            agent("b", content = "done", tools = listOf(ToolCall(name = "read_file", context = "x.kt", status = ToolStatus.COMPLETED))),
        ))
        assertEquals("plan", mind.thinking)
        assertEquals("done", mind.saying)
        assertEquals(listOf("read_file"), mind.tools.map { it.name })
    }

    @Test fun toolsFlattenInOrderAndUserRowsAreSkipped() {
        val mind = CrewMind.of(listOf(
            me("u", "Review the PR"),
            agent("a", tools = listOf(ToolCall(name = "grep"), ToolCall(name = "read_file"))),
            agent("b", tools = listOf(ToolCall(name = "terminal"))),
        ))
        assertEquals(listOf("grep", "read_file", "terminal"), mind.tools.map { it.name })
        assertEquals("", mind.saying)
    }

    @Test fun earlierFoldsToTheTailOnALineBoundary() {
        val lines = (1..1000).joinToString("\n") { "line $it is here" }
        val mind = CrewMind.of(emptyList(), earlier = lines)
        assertTrue(mind.earlier.length <= CrewMind.EARLIER_MAX_CHARS)
        assertTrue(mind.earlier.startsWith("line "))
        assertTrue(mind.earlier.endsWith("line 1000 is here"))
        assertEquals("short", CrewMind.trimEarlier("  short \n"))
    }
}
