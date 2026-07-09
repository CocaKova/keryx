package chat.keryx.app

import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.groupChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the 2026-07-03 timeline-grouping fixes, pinned against the live-caught
 * "rigorous test" exchange (create → think → read → reason → delete):
 *
 *  1. The gateway reply-threads EVERY prose chunk of a multi-part turn to the triggering message,
 *     which re-quoted the user's own last message above each chunk. Those quotes are suppressed;
 *     a quote reaching further back (a genuine reference) is not.
 *  2. Turn-opening prose before the first tool call ("No worries — let's do it again.
 *     **1. Create file**") is dialogue and must stay a visible bubble — it used to be folded
 *     invisibly into the collapsed run's reasoning block.
 */
class GroupChatItemsTest {

    private var ts = 0L
    private fun me(id: String, text: String) =
        Message(id, "!r", SenderType.ME, text, ++ts)
    private fun agent(id: String, text: String, replyTo: String? = null) =
        Message(id, "!r", SenderType.HERMES, text, ++ts, replyToId = replyTo)

    /** groupChatItems takes newest-first (reverseLayout order) and returns newest-first. */
    private fun group(vararg chrono: Message) = groupChatItems(chrono.toList().asReversed())

    private fun List<ChatRenderItem>.single(id: String): ChatRenderItem.Single =
        filterIsInstance<ChatRenderItem.Single>().first { it.message.id == id }

    private val longProse = "x".repeat(300)

    @Test
    fun `turn intro before first tool stays a visible bubble`() {
        val items = group(
            me("trigger", "do the test again"),
            agent("intro", "No worries — let's do it again.\n\n**1. Create file**", replyTo = "trigger"),
            agent("tool1", "✍️ Writing /tmp/x.txt"),
            agent("answer", "Done. $longProse", replyTo = "trigger"),
        )
        // Intro must be present as its own bubble, not swallowed by the run.
        val intro = items.single("intro")
        assertTrue(intro.message.content.startsWith("No worries"))
        // The run still exists and still counts its tool.
        val run = items.filterIsInstance<ChatRenderItem.ToolRun>().single()
        assertEquals(1, run.callCount)
        // Chronological order (items are newest-first): intro before run before answer.
        val keys = items.map { it.key }.asReversed()
        assertTrue(keys.indexOf("intro") < keys.indexOf(run.key))
        assertTrue(keys.indexOf(run.key) < keys.indexOf("answer"))
    }

    @Test
    fun `quotes of the user's most recent message are suppressed, older references are not`() {
        val items = group(
            me("old", "something from way back"),
            agent("earlier", "an earlier answer"),
            me("trigger", "do the test again"),
            agent("chunk1", "**2. Thinking:** $longProse", replyTo = "trigger"),
            agent("tool1", "💻 terminal\n```\nrm /tmp/x.txt\n```"),
            agent("final", "Done. $longProse", replyTo = "trigger"),
            agent("callback", "About that old thing: $longProse", replyTo = "old"),
        )
        // Every chunk reply-threaded to the trigger is suppressed.
        assertTrue(items.single("chunk1").suppressQuote)
        assertTrue(items.single("final").suppressQuote)
        // A genuine reference to an older message keeps its quote.
        assertFalse(items.single("callback").suppressQuote)
    }

    @Test
    fun `mid-run asides still fold into the run, not into visible bubbles`() {
        val items = group(
            me("trigger", "go"),
            agent("tool1", "✍️ Writing /tmp/x.txt"),
            agent("aside", "Now checking the file real quick", replyTo = "trigger"),
            agent("tool2", "📖 Reading x.txt"),
            agent("answer", "All good. $longProse", replyTo = "trigger"),
        )
        // The aside between two tools must NOT be a visible bubble.
        assertTrue(items.filterIsInstance<ChatRenderItem.Single>().none { it.message.id == "aside" })
        val run = items.filterIsInstance<ChatRenderItem.ToolRun>().single()
        assertEquals(2, run.callCount)
        assertTrue(run.reasoning?.contains("checking the file") == true)
    }

    /**
     * Pinned against the live 2026-07-04 steered .tffs turn: after a /steer, interim answers
     * (≥ ANSWER_PROSE_MIN narration) split the block into several runs, but Hermes only headers
     * the FIRST progress send of its grouping — every later run held only bare ``` fences and
     * short asides. closeRun used to drop any run without a headered Call, so all of that output
     * and its folded reasoning silently vanished on room reload.
     */
    @Test
    fun `header-less fence continuation runs survive an interim-answer split`() {
        val items = group(
            me("trigger", "style my termux"),
            agent("tool1", "💻 terminal\n```\nadb connect 192.168.1.42\n```"),
            me("steer", "/steer look to verify where it goes first"),
            agent("interim", "Good findings so far. $longProse", replyTo = "trigger"),
            agent("fence1", "```\nadb shell \"ls /sdcard/\"\n```"),
            agent("aside", "The phone's an S24 Ultra. Let me push the file.", replyTo = "trigger"),
            agent("fence2", "```\nadb push cyber-noir.tffs /sdcard/Download/\n```"),
            agent("final", "The .tffs file is on the phone and verified. $longProse", replyTo = "trigger"),
        )
        val runs = items.filterIsInstance<ChatRenderItem.ToolRun>()
        // Two runs: the headered one before the interim answer, and the fence-only continuation.
        assertEquals(2, runs.size)
        val continuation = runs.first { it.callCount == 0 }
        // Both fence outputs survive as Note steps…
        assertEquals(
            2,
            continuation.entries.count {
                it is chat.keryx.app.presentation.ui.components.ToolRunEntry.Note
            },
        )
        // …and the aside folded between them is still reachable in the run's reasoning.
        assertTrue(continuation.reasoning?.contains("S24 Ultra") == true)
        // The interim answer and final answer stay visible bubbles.
        items.single("interim"); items.single("final")
        // Nothing from the continuation leaked out as loose Singles.
        assertTrue(
            items.filterIsInstance<ChatRenderItem.Single>()
                .none { it.message.id in setOf("fence1", "fence2", "aside") },
        )
    }

    @Test
    fun `day headers appear once per local day, before that day's first item`() {
        val day = 86_400_000L
        val monday = 1_751_500_800_000L // some midday epoch; exact date irrelevant
        val items = group(
            Message("m1", "!r", SenderType.ME, "hey", monday),
            Message("m2", "!r", SenderType.HERMES, "hello!", monday + 60_000L),
            Message("m3", "!r", SenderType.ME, "next-day question", monday + day),
            Message("m4", "!r", SenderType.HERMES, "next-day answer", monday + day + 60_000L),
        )
        val chrono = items.asReversed()
        val headers = chrono.filterIsInstance<ChatRenderItem.DayHeader>()
        assertEquals(2, headers.size)
        // First item overall is a day header; the second header sits right before m3.
        assertTrue(chrono.first() is ChatRenderItem.DayHeader)
        val m3At = chrono.indexOfFirst { it is ChatRenderItem.Single && it.message.id == "m3" }
        assertTrue(chrono[m3At - 1] is ChatRenderItem.DayHeader)
        // Same-day messages never repeat a header, and keys are unique per day.
        assertEquals(headers.map { it.dayKey }.distinct().size, headers.size)
    }
}
