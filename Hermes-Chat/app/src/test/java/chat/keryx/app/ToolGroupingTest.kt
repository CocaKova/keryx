package chat.keryx.app

import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.ToolRunEntry
import chat.keryx.app.presentation.ui.components.groupChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the tool-run grouping bugs: visually doubled calls (accumulate-mode
 *  re-emission) and runs breaking apart when automated telemetry lands mid-turn. */
class ToolGroupingTest {

    private var ts = 0L
    private fun msg(sender: SenderType, content: String) =
        Message(id = "e${ts}", sessionId = "room", sender = sender, content = content, timestamp = ts++)

    /** groupChatItems takes newest-first (the reversed list the UI uses). */
    private fun group(vararg chrono: Message) = groupChatItems(chrono.toList().asReversed())

    private fun runOf(items: List<ChatRenderItem>) =
        items.filterIsInstance<ChatRenderItem.ToolRun>().single()

    @Test
    fun accumulateModeRepeats_dedupedAcrossMessages() {
        // Hermes' tool_progress_grouping=accumulate re-sends every previous call with each step.
        val items = group(
            msg(SenderType.ME, "do the thing"),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\""),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n⚙️ terminal: \"cat x\""),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n⚙️ terminal: \"cat x\"\n🔍 session_search: \"y\""),
            msg(SenderType.HERMES, "All done."),
        )
        assertEquals(3, runOf(items).callCount)
    }

    @Test
    fun genuineRetry_withinOneMessage_survivesDedup() {
        val items = group(
            msg(SenderType.ME, "go"),
            msg(SenderType.HERMES, "⚙️ terminal: \"make\"\nbuild failed\n⚙️ terminal: \"make\""),
        )
        assertEquals(2, runOf(items).callCount)
    }

    @Test
    fun telemetryMidRun_doesNotFragmentTheRun() {
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step one\""),
            msg(SenderType.HERMES, "[status] gateway healthy, background sync ok"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step two\""),
            msg(SenderType.HERMES, "Finished — both steps green."),
        )
        // ONE run containing both calls + the telemetry entry, then the answer bubble.
        val run = runOf(items)
        assertEquals(2, run.callCount)
        assertTrue(run.entries.any { it is ToolRunEntry.Telemetry })
        assertEquals(
            "Finished — both steps green.",
            (items.first() as ChatRenderItem.Single).message.content,
        )
    }

    @Test
    fun telemetryAfterLastTool_absorbedIntoRun_answerStaysBubble() {
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"work\""),
            msg(SenderType.HERMES, "⏰ Cron: heartbeat ok"),
            msg(SenderType.HERMES, "Here's the result you wanted."),
        )
        val singles = items.filterIsInstance<ChatRenderItem.Single>()
        // Only my message and the answer stay as bubbles; the heartbeat lives inside the run.
        assertEquals(2, singles.size)
        assertTrue(runOf(items).entries.any { it is ToolRunEntry.Telemetry })
    }

    @Test
    fun standaloneRuntimeFooter_attachesToPreviousAnswerBubble() {
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "Here's the result you wanted."),
            msg(SenderType.HERMES, "qwen3.5-122b · 42% · ~/workspace/keryx"),
        )
        val bubbles = items.filterIsInstance<ChatRenderItem.Single>()
        assertEquals(2, bubbles.size)
        val answer = bubbles.first().message.content
        assertTrue(answer.contains("Here's the result you wanted."))
        assertTrue(answer.contains("qwen3.5-122b · 42% · ~/workspace/keryx"))
    }

    @Test
    fun standaloneRuntimeFooterAfterToolAnswer_attachesToAnswerNotToolRun() {
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"work\""),
            msg(SenderType.HERMES, "Here's the result you wanted."),
            msg(SenderType.HERMES, "qwen3.5-122b · 42% · ~/workspace/keryx"),
        )
        val bubbles = items.filterIsInstance<ChatRenderItem.Single>()
        assertEquals(2, bubbles.size)
        assertTrue(runOf(items).entries.none { it is ToolRunEntry.Telemetry })
        assertTrue(bubbles.first().message.content.contains("qwen3.5-122b · 42% · ~/workspace/keryx"))
    }

    @Test
    fun plainReply_noRun() {
        val items = group(
            msg(SenderType.ME, "hi"),
            msg(SenderType.HERMES, "Hello! Nothing to run here."),
        )
        assertTrue(items.none { it is ChatRenderItem.ToolRun })
    }
}
