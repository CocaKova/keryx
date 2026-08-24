package chat.keryx.app

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.protocol.MessageParser
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.groupChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1 §B — one reasoning grammar. Pins the transport-side lift (`MessageParser.reasoningOf`,
 * the API `MatrixTransport.toMessage` fills `Message.reasoning` from), the reasoning-only
 * judgment the render path uses instead of blankness, and the grouping rule that the run's
 * consolidated block prefers the field over re-gathering segments (one owner per fact).
 */
class ReasoningLiftTest {

    // ---- reasoningOf: the lift itself ----

    @Test
    fun `think tags lift and prose does not`() {
        assertEquals("plan the fix", MessageParser.reasoningOf("<think>plan the fix</think>The answer."))
        assertNull(MessageParser.reasoningOf("Just an answer, no thought."))
        assertNull(MessageParser.reasoningOf(""))
    }

    @Test
    fun `hermes reasoning prelude lifts`() {
        val thought = "💭 **Reasoning:**\n```\nweighing options\n```\n\nThe answer."
        assertEquals("weighing options", MessageParser.reasoningOf(thought))
    }

    @Test
    fun `a quoted tag mid-prose is literal text, not thought`() {
        assertNull(MessageParser.reasoningOf("the adapter wraps <think> and </think> markers inline"))
    }

    @Test
    fun `two thoughts join with a blank line`() {
        val r = MessageParser.reasoningOf("<think>first</think>middle prose\n<think>second</think>end")
        assertEquals("first\n\nsecond", r)
    }

    // ---- isReasoningOnly: the empty-bubble gate ----

    @Test
    fun `pure thought is reasoning-only, mixed and blank are not`() {
        assertTrue(MessageParser.isReasoningOnly("<think>only thought here</think>"))
        assertFalse(MessageParser.isReasoningOnly("<think>thought</think>and an answer"))
        assertFalse(MessageParser.isReasoningOnly(""))
        assertFalse(MessageParser.isReasoningOnly("plain prose"))
    }

    // ---- grouping: the run's block reads the field first ----

    private var ts = 0L
    private fun me(id: String, text: String) =
        Message(id, "!r", SenderType.ME, text, ++ts)
    private fun agent(id: String, text: String, reasoning: String? = null) =
        Message(id, "!r", SenderType.HERMES, text, ++ts, reasoning = reasoning)

    private fun group(vararg chrono: Message) = groupChatItems(chrono.toList().asReversed())
    private fun List<ChatRenderItem>.run() = filterIsInstance<ChatRenderItem.ToolRun>().single()

    private val longProse = "x".repeat(300)

    @Test
    fun `run reasoning prefers the lifted field`() {
        // The Matrix door post-B1: content keeps its think lines, the field carries the lift.
        // The run must read the field, not gather the segments a second time.
        val items = group(
            me("q", "go"),
            agent("tool1", "<think>checking the file</think>📖 Reading /tmp/a.txt", reasoning = "checking the file"),
            agent("answer", "All good. $longProse"),
        )
        assertEquals("checking the file", items.run().reasoning)
    }

    @Test
    fun `run reasoning falls back to segments when no producer lifted it`() {
        // A producer that never set the field (a raw fixture, an older store): the segment
        // gather still works, so nothing vanishes.
        val items = group(
            me("q", "go"),
            agent("tool1", "<think>fallback thought</think>📖 Reading /tmp/a.txt"),
            agent("answer", "Done. $longProse"),
        )
        assertEquals("fallback thought", items.run().reasoning)
    }

    @Test
    fun `mid-run aside with a lifted field folds the field into the run`() {
        val items = group(
            me("q", "go"),
            agent("tool1", "📖 Reading /tmp/a.txt"),
            agent("aside", "<think>the orphan logic looks off</think>", reasoning = "the orphan logic looks off"),
            agent("tool2", "📖 Reading /tmp/b.txt"),
            agent("answer", "Fixed. $longProse"),
        )
        val r = items.run().reasoning ?: ""
        assertTrue(r.contains("the orphan logic looks off"))
    }
}
