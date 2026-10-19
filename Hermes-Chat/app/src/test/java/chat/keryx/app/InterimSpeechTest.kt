package chat.keryx.app

import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.protocol.TranscriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the agent says between its tools on the direct door (Hermes' interim messages) stays
 * on screen (3.0). The owner, 10-16, on an agent run setting up REA: those lines were "caught into the
 * reasoning and or disappear". Live they folded into the run's collapsed thought; reloaded, a
 * line that carried its own reasoning kept only the reasoning and lost the words.
 *
 * The shape below is that run's (14 lines between tools, most with their own thought, most
 * under 240 characters), with stand-in words.
 */
class InterimSpeechTest {

    /** (words, thought, calls) for each assistant row of the run, then the answer. */
    private val run = listOf(
        Triple(98, 552, 2), Triple(109, 138, 1), Triple(0, 127, 1), Triple(166, 707, 1),
        Triple(269, 597, 1), Triple(143, 1282, 2), Triple(177, 918, 1), Triple(0, 0, 1),
        Triple(221, 3382, 2), Triple(0, 0, 1), Triple(133, 607, 1), Triple(101, 331, 1),
        Triple(0, 0, 1), Triple(82, 102, 1), Triple(0, 116, 1), Triple(98, 1029, 1),
        Triple(0, 1688, 1), Triple(124, 874, 1), Triple(224, 1287, 1), Triple(0, 249, 1),
        Triple(86, 1623, 2), Triple(0, 0, 2),
    )

    private fun words(n: Int, k: Int) = "line $k ".padEnd(n, 'w')

    private fun rows(): List<MessageRow> {
        var id = 1L
        val out = mutableListOf(MessageRow(id++, "user", "Set up REA", null, id * 1000, null))
        run.forEachIndexed { k, (w, t, calls) ->
            val callIds = (0 until calls).map { "c$k-$it" }
            out += MessageRow(
                id++, "assistant", if (w > 0) words(w, k) else "", null, id * 1000,
                if (t > 0) "thought $k ".padEnd(t, 't') else null,
                toolCalls = callIds.map { RestToolCall(it, "terminal", """{"command":"ls $it"}""") },
            )
            callIds.forEach { out += MessageRow(id++, "tool", "ok", "terminal", id * 1000, null, toolCallId = it) }
        }
        out += MessageRow(id++, "assistant", "Done and verified. ".padEnd(1736, 'a'), null, id * 1000, "wrap".padEnd(604, 't'))
        return out
    }

    private fun group(msgs: List<Message>) = groupChatItems(msgs.asReversed()).asReversed()

    @Test
    fun `every line said between tools is a visible bubble after reload`() {
        val items = group(TranscriptBuilder.build("s", rows()))
        val shown = items.filterIsInstance<ChatRenderItem.Single>().map { it.message.content }
        val said = run.mapIndexedNotNull { k, (w, _, _) -> if (w > 0) words(w, k) else null }
        assertEquals(14, said.size)
        for (line in said) assertTrue("missing: ${line.take(8)}", line in shown)
        // Each keeps its own thought, above its words.
        val line1 = items.filterIsInstance<ChatRenderItem.Single>().first { it.message.content == words(109, 1) }
        assertTrue(line1.message.reasoning!!.startsWith("thought 1"))
        // No words leak into a run's thought block.
        for (r in items.filterIsInstance<ChatRenderItem.ToolRun>()) {
            assertTrue(r.reasoning.orEmpty().lines().none { it.startsWith("line ") })
        }
        // Every call is still in a run, and the answer closes the turn.
        assertEquals(run.sumOf { it.third }, items.filterIsInstance<ChatRenderItem.ToolRun>().sumOf { it.callCount })
        assertTrue((items.last() as ChatRenderItem.Single).message.content.startsWith("Done and verified"))
    }

    @Test
    fun `a thought with no words still folds into the run`() {
        val items = group(TranscriptBuilder.build("s", rows()))
        val thoughts = items.filterIsInstance<ChatRenderItem.ToolRun>().mapNotNull { it.reasoning }.joinToString("\n")
        // Row 2 (index 2) had a thought and calls but no words.
        assertTrue(thoughts.contains("thought 2 "))
        assertTrue(items.filterIsInstance<ChatRenderItem.Single>().none { it.message.reasoning?.startsWith("thought 2 ") == true })
    }

    @Test
    fun `live, a line sealed between tools is a bubble, not part of the thought`() {
        // The live overlay's rows: sealed text, then its tool, then the streaming tail.
        val live = listOf(
            Message("me", "s", SenderType.ME, "go", 1),
            Message("t0", "s", SenderType.HERMES, "Let me look at the repo first.", 2),
            Message("c0", "s", SenderType.HERMES, "", 3, toolCalls = listOf(ToolCall(name = "terminal", context = "ls"))),
            Message("t1", "s", SenderType.HERMES, "Found it. Reading the install docs now.", 4),
            Message("c1", "s", SenderType.HERMES, "", 5, toolCalls = listOf(ToolCall(name = "web_extract", context = "x"))),
            Message("tail", "s", SenderType.HERMES, "", 6, isStreaming = true, reasoning = "checking arm64"),
        )
        val items = group(live)
        val shown = items.filterIsInstance<ChatRenderItem.Single>().map { it.message.id }
        assertTrue(shown.containsAll(listOf("t0", "t1")))
        assertTrue(items.filterIsInstance<ChatRenderItem.ToolRun>().none { it.reasoning.orEmpty().contains("Found it") })
    }

    @Test
    fun `on the Matrix door a short aside still folds, words and thought both kept`() {
        val items = group(
            listOf(
                Message("me", "!r", SenderType.ME, "go", 1),
                Message("a1", "!r", SenderType.HERMES, "💻 terminal\n```\nls\n```", 2),
                Message("aside", "!r", SenderType.HERMES, "Checking the file real quick", 3, reasoning = "why not"),
                Message("a2", "!r", SenderType.HERMES, "💻 terminal\n```\ncat x\n```", 4),
                Message("ans", "!r", SenderType.HERMES, "Done. ".padEnd(300, 'x'), 5),
            ),
        )
        assertTrue(items.filterIsInstance<ChatRenderItem.Single>().none { it.message.id == "aside" })
        val thought = items.filterIsInstance<ChatRenderItem.ToolRun>().single().reasoning.orEmpty()
        assertTrue(thought.contains("why not"))
        assertTrue(thought.contains("Checking the file"))
    }
}
