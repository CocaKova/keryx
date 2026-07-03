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
    fun reasoningMessageMidRun_foldsIntoRun_notALooseBubble() {
        // The reported old-version flow: "Ran 1 tool" → a standalone reasoning message → edited
        // tool re-emissions → answer. Everything before the answer must collapse into ONE run,
        // with the reasoning inside the run's thinking block, not intertwined full-size bubbles.
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"probe the service\""),
            msg(SenderType.HERMES, "💭 **Reasoning:**\n```\nThe probe timed out, I should retry with a longer deadline.\n```\n\n"),
            msg(SenderType.HERMES, "⚙️ terminal: \"probe the service\"\n⚙️ terminal: \"probe --timeout 30\""),
            msg(SenderType.HERMES, "Service is healthy after the retry."),
        )
        val run = runOf(items)
        // Accumulate-mode re-emission deduped: probe once + the retry variant.
        assertEquals(2, run.callCount)
        // The standalone reasoning message lives inside the run, not as its own bubble.
        assertTrue(run.reasoning?.contains("longer deadline") == true)
        val singles = items.filterIsInstance<ChatRenderItem.Single>()
        assertEquals(2, singles.size) // my message + the answer, nothing else loose
        assertEquals("Service is healthy after the retry.", (items.first() as ChatRenderItem.Single).message.content)
    }

    @Test
    fun steerCommandMidRun_doesNotSplitTheRun() {
        // Live-observed 2026-07-03: a /steer + its ⏩ ack in the middle of a run cut the tool log
        // in two. The command stays a visible bubble; the ack folds in as telemetry.
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step one\""),
            msg(SenderType.ME, "/steer change the LLM to ornith 35b"),
            msg(SenderType.HERMES, "⏩ Steer queued — arrives after the next tool call: 'change the LLM'"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step two\""),
            msg(SenderType.HERMES, "Done — switched."),
        )
        val run = runOf(items) // exactly ONE run despite my /steer in the middle
        assertEquals(2, run.callCount)
        assertTrue(run.entries.any { it is ToolRunEntry.Telemetry })
        // My /steer stays visible as its own bubble.
        assertTrue(items.filterIsInstance<ChatRenderItem.Single>()
            .any { it.message.content.startsWith("/steer") })
    }

    @Test
    fun substantialInterimAnswer_staysABubble_notSwallowedIntoReasoning() {
        // A steered turn: real answer lands mid-block, then MORE tools follow. The answer must
        // stay a bubble (it used to vanish into the next run's 💭 reasoning block).
        val answer = buildString {
            append("Here's what I found:\n\n")
            append("The graph is healthy — 266 entities, 77 orphans, proportional growth, no corruption. ")
            append("Consolidate is safe to run: backup, dedup, distill, decay are all reversible. ")
            append("Want me to run it, or will you clean up the noise orphans first?")
        }
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"inspect\""),
            msg(SenderType.HERMES, answer),
            msg(SenderType.HERMES, "⚙️ terminal: \"apply the change\""),
            msg(SenderType.HERMES, "Switched."),
        )
        val runs = items.filterIsInstance<ChatRenderItem.ToolRun>()
        assertEquals(2, runs.size) // run before the answer, fresh run after it
        val singles = items.filterIsInstance<ChatRenderItem.Single>()
        assertTrue(singles.any { it.message.content == answer })
        assertTrue(runs.none { it.reasoning?.contains("Here's what I found") == true })
    }

    @Test
    fun bareCodeFenceMidRun_becomesANoteStep_notReasoning() {
        // A tool output that lost its header (just fenced blocks) is machine output, not thought.
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step one\""),
            msg(SenderType.HERMES, "```\ntail -5 ~/.hermes/health.log\n```"),
            msg(SenderType.HERMES, "⚙️ terminal: \"step two\""),
            msg(SenderType.HERMES, "All good."),
        )
        val run = runOf(items)
        assertTrue(run.entries.any { it is ToolRunEntry.Note && (it as ToolRunEntry.Note).text.contains("tail -5") })
        assertTrue(run.reasoning?.contains("tail -5") != true)
    }

    @Test
    fun gerundProgressLines_groupAsTools() {
        // `📖 Reading consolidate.py L80-89` (human-readable progress, no colon) must join the
        // run — it floated loose as a bubble and fragmented the log (live 2026-07-03).
        val items = group(
            msg(SenderType.ME, "task"),
            msg(SenderType.HERMES, "📖 Reading consolidate.py L80-89"),
            msg(SenderType.HERMES, "🔧 Editing /home/cocakova/.hermes/hermes-agent/p... (×2)"),
            msg(SenderType.HERMES, "Fixed."),
        )
        assertEquals(2, runOf(items).callCount)
        // But a sentence with a leading emoji is NOT a tool.
        val prose = group(
            msg(SenderType.ME, "hi"),
            msg(SenderType.HERMES, "✨ Generating magic is what I do best!"),
        )
        assertTrue(prose.none { it is ChatRenderItem.ToolRun })
        // Nor is a markdown heading that happens to start with a gerund (live-caught: a streamed
        // essay's `## Enduring Legacy` heading became a phantom "Running 1 tool" group).
        val heading = group(
            msg(SenderType.ME, "hi"),
            msg(SenderType.HERMES, "## Enduring Legacy of the Greek Alphabet"),
        )
        assertTrue(heading.none { it is ChatRenderItem.ToolRun })
    }

    @Test
    fun headerlessFencesAfterInterimStatus_keepTheRunTogether() {
        // Live-caught 2026-07-03 (consolidate.py turn): long terminal run → interim status →
        // header-LESS fence-only progress messages → longer interim → final answer. The fences
        // weren't recognized as tool activity, so the first status "closed" the run and the
        // fences floated as loose code bubbles.
        val longInterim = "The script timed out again, and the backup wasn't created. The LLM is " +
            "responding fine, but the dedup step calls the LLM in a loop for each candidate " +
            "cluster — with 266 entities, that's a lot of slow thinking calls. " +
            "Let me bump the timeout and try once more:"
        val finalAnswer = "The consolidation is now running in the background with a 10-minute " +
            "timeout. It's going to be slow because the dedup step alone needs multiple LLM " +
            "calls for each candidate cluster of similar entities. I'll notify you when it " +
            "finishes. In the meantime we could clean up orphan noise first. What would you prefer?"
        val items = group(
            msg(SenderType.ME, "Yes please"),
            msg(SenderType.HERMES, "💻 terminal\n```\ncd ~/.hermes && venv/bin/python consolidate.py\n```"),
            msg(SenderType.HERMES, "⏳ Working — 3 min — iteration 1/90, terminal"),
            msg(SenderType.HERMES, "\n\n\n\nThe script timed out at 5 minutes — let me check what happened."),
            msg(SenderType.HERMES, "```\nls -la ~/.hermes/graphiti-backups/\n```\n```\n# Test if the model is responding\n```"),
            msg(SenderType.HERMES, longInterim),
            msg(SenderType.HERMES, finalAnswer),
        )
        val run = runOf(items) // ONE run — the fences didn't split it
        assertEquals(1, run.callCount)
        assertTrue(run.entries.any { it is ToolRunEntry.Note && (it as ToolRunEntry.Note).text.contains("ls -la") })
        assertTrue(run.entries.any { it is ToolRunEntry.Telemetry })
        // The short status folds into the run's reasoning; the substantial interim + final stay bubbles.
        assertTrue(run.reasoning?.contains("timed out at 5 minutes") == true)
        val agentBubbles = items.filterIsInstance<ChatRenderItem.Single>()
            .filter { it.message.sender == SenderType.HERMES }
        assertEquals(listOf(finalAnswer, longInterim), agentBubbles.map { it.message.content })
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
