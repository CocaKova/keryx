package chat.keryx.app

import chat.keryx.app.presentation.tapin.TapIn
import chat.keryx.app.presentation.tapin.TurnSlice
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.withLiveTheater
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.SessionStatus
import chat.keryx.core.model.Theater
import chat.keryx.core.model.TheaterEvent
import chat.keryx.core.model.TheaterState
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap-In (2.12) is a projection, not a producer: everything on the screen is a pure function of
 * what the chat already holds. These pin the arithmetic — which line is the headline, how the
 * crew sorts, where the turn is cut out of the transcript — so the screen can be trusted to say
 * exactly what the quiet rows say, only larger.
 */
class TapInTest {

    private fun call(name: String, status: ToolStatus = ToolStatus.COMPLETED, ctx: String = "a.txt") =
        ToolCall(name = name, context = ctx, status = status)

    private fun wing(key: String, goal: String, state: DelegationState = DelegationState.RUNNING, idx: Int = 0, n: Int = 1) =
        Delegation(key = key, goal = goal, taskIndex = idx, taskCount = n, state = state)

    private fun project(
        running: Boolean = true,
        status: SessionStatus? = null,
        label: String = "Working",
        calls: List<ToolCall> = emptyList(),
        crew: List<Delegation> = emptyList(),
        reasoning: String = "",
        answer: String = "",
        startedAt: Long? = 1_000L,
        now: Long = 61_000L,
    ) = TapIn.project(running, status, label, calls, crew, reasoning, answer, startedAt, now)

    // --- headline precedence -------------------------------------------------------------------

    @Test
    fun `a compaction is the headline while it runs`() {
        val s = project(status = SessionStatus("compressing", "Compressing context ~92,000 tokens"), calls = listOf(call("read_file", ToolStatus.EXECUTING)))
        assertEquals("Compressing context (~92k tokens)", s.headline)
    }

    @Test
    fun `a status that is not a compaction does not take the headline`() {
        val s = project(status = SessionStatus("heartbeat", "♥ heartbeat #3 firing…"), calls = listOf(call("read_file", ToolStatus.EXECUTING)))
        assertEquals("Reading a.txt", s.headline)
        val w = project(status = SessionStatus.of("compacting", "⚠ Compression model x context is 229,376 tokens. Auto-lowered this session's threshold"), calls = listOf(call("read_file", ToolStatus.EXECUTING)))
        assertEquals("Reading a.txt", w.headline)
    }

    @Test
    fun `else the open call, in the present tense of the shared grammar`() {
        val s = project(calls = listOf(call("terminal", ctx = "ls"), call("read_file", ToolStatus.EXECUTING)))
        assertEquals("Reading a.txt", s.headline)
    }

    @Test
    fun `else waiting on flying helpers, else the working label`() {
        assertEquals("Waiting on 2 helpers", project(crew = listOf(wing("a", "x"), wing("b", "y"))).headline)
        assertEquals("Reasoning", project(label = "Reasoning").headline)
    }

    @Test
    fun `a settled turn says it landed and how many failed`() {
        assertEquals("Landed", project(running = false, calls = listOf(call("terminal"))).headline)
        val s = project(running = false, calls = listOf(call("terminal", ToolStatus.FAILED)), crew = listOf(wing("a", "x", DelegationState.FAILED)))
        assertEquals("Landed · 2 failed", s.headline)
    }

    // --- the subline and the instruments -------------------------------------------------------

    @Test
    fun `the subline counts tools, open calls, failures and landed helpers`() {
        val s = project(
            calls = listOf(call("terminal"), call("read_file", ToolStatus.EXECUTING), call("patch", ToolStatus.FAILED)),
            crew = listOf(wing("a", "x", DelegationState.DONE, 0, 3), wing("b", "y", DelegationState.RUNNING, 1, 3), wing("c", "z", DelegationState.RUNNING, 2, 3)),
        )
        assertEquals("3 tools · 1 open · 1 failed · 1 of 3 helpers landed", s.subline)
        assertEquals(3, s.toolCount); assertEquals(1, s.openCount); assertEquals(1, s.failedCount)
        assertEquals(1, s.crewLanded)
    }

    @Test
    fun `elapsed runs from the work start and never goes negative`() {
        assertEquals(60_000L, project().elapsedMs)
        assertEquals(0L, project(startedAt = null).elapsedMs)
        assertEquals(0L, project(startedAt = 90_000L, now = 61_000L).elapsedMs)
        assertEquals("1:00", TapIn.clock(60_000L))
        assertEquals("1:02:03", TapIn.clock(3_723_000L))
    }

    // --- the crew ------------------------------------------------------------------------------

    @Test
    fun `flying helpers come first, each half in dispatch order`() {
        val crew = TapIn.crewOf(listOf(
            wing("a", "one", DelegationState.DONE, 0, 4),
            wing("b", "two", DelegationState.RUNNING, 1, 4),
            wing("c", "three", DelegationState.FAILED, 2, 4),
            wing("d", "four", DelegationState.SPAWNING, 3, 4),
        ))
        assertEquals(listOf("b", "d", "a", "c"), crew.map { it.run.key })
        assertEquals(listOf(2, 4, 1, 3), crew.map { it.ordinal })
        assertTrue(crew[3].failed)
    }

    @Test
    fun `a role is the goal's first clause, capitalised, never a paragraph`() {
        assertEquals("Review the diff for correctness bugs", TapIn.roleOf("review the diff for correctness bugs; report each with file and line"))
        assertEquals("Find every caller of undoLastTurn", TapIn.roleOf("Find every caller of undoLastTurn. Then list them."))
        assertEquals("Delegated task", TapIn.roleOf("   "))
        val long = TapIn.roleOf("Investigate why the gateway refuses the undo under a running turn and what the client should do about it")
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= TapIn.ROLE_MAX + 1)
    }

    @Test
    fun `the glyph reads the kind of helper off its goal`() {
        assertEquals("⚖", TapIn.glyphOf(wing("a", "Review the diff")))
        assertEquals("⌕", TapIn.glyphOf(wing("a", "Research prior art")))
        assertEquals("⚒", TapIn.glyphOf(wing("a", "Implement the retry")))
        assertEquals("⑂", TapIn.glyphOf(wing("a", "Do the thing")))
    }

    // --- the mind ------------------------------------------------------------------------------

    @Test
    fun `the mind keeps the last six non-blank lines`() {
        val thought = (1..10).joinToString("\n\n") { "line $it" }
        assertEquals((5..10).joinToString("\n") { "line $it" }, TapIn.tailLines(thought, TapIn.MIND_LINES))
        assertEquals("", TapIn.tailLines("  \n ", 6))
    }

    // --- the slice: where the turn is cut out of the transcript --------------------------------

    private var ts = 0L
    private fun msg(sender: SenderType, content: String, reasoning: String? = null, delegations: List<Delegation> = emptyList()) =
        Message(id = "e${ts}", roomId = "room", sender = sender, content = content, timestamp = ts++, reasoning = reasoning, delegations = delegations)

    private fun items(vararg chrono: Message): List<ChatRenderItem> = groupChatItems(chrono.toList().asReversed())

    @Test
    fun `the slice stops at the last thing the user said`() {
        val slice = TurnSlice.of(items(
            msg(SenderType.HERMES, "⚙️ terminal: \"old\""),
            msg(SenderType.ME, "now do this"),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n📖 read_file: \"a.txt\""),
            msg(SenderType.HERMES, "Here is the answer", reasoning = "thought one\nthought two"),
        ))
        assertEquals(listOf("terminal", "read_file"), slice.calls.map { it.name })
        assertEquals("Here is the answer", slice.answer)
        assertEquals("thought one\nthought two", slice.reasoning)
    }

    @Test
    fun `the slice is empty before the agent has done anything`() {
        assertEquals(TurnSlice.EMPTY, TurnSlice.of(items(msg(SenderType.ME, "go"))))
        assertTrue(TurnSlice.of(emptyList()).calls.isEmpty())
    }

    @Test
    fun `helpers folded in from the theater reach the slice once each`() {
        var theater = TheaterState()
        theater = Theater.reduce(theater, TheaterEvent(phase = "sub", kind = "start", child = "c1", goal = "review it"))
        theater = Theater.reduce(theater, TheaterEvent(phase = "sub", kind = "tool", child = "c1", name = "read_file", preview = "x"))
        val folded = withLiveTheater(
            items(msg(SenderType.ME, "go"), msg(SenderType.HERMES, "⚙️ terminal: \"ls\"")),
            beats = Theater.reduce(TheaterState(), TheaterEvent(phase = "start", name = "terminal", preview = "ls")).beats,
            delegations = theater.delegations,
        )
        val slice = TurnSlice.of(folded)
        assertEquals(1, slice.delegations.size)
        assertEquals("review it", slice.delegations.single().goal)
        assertEquals(1, slice.calls.size)
    }

    @Test
    fun `a landed wing on its own is a run, not a dropped message`() {
        // The direct door records a landing as its own message, minutes after the turn, with
        // nothing else in its block. The grouper used to require a call or a note to emit a run.
        val late = items(
            msg(SenderType.ME, "go"),
            msg(SenderType.HERMES, "Dispatched.", delegations = emptyList()),
            msg(SenderType.HERMES, "", delegations = listOf(wing("w1", "write the doc", DelegationState.DONE))),
        )
        val runs = late.filterIsInstance<ChatRenderItem.ToolRun>()
        assertEquals(1, runs.size)
        assertEquals("w1", TurnSlice.of(late).delegations.single().key)
    }

    @Test
    fun `the direct door's wings message lands its helpers in the slice`() {
        val slice = TurnSlice.of(items(
            msg(SenderType.ME, "go"),
            msg(SenderType.HERMES, "", delegations = listOf(wing("w1", "test the build"), wing("w2", "write the doc", DelegationState.DONE))),
        ))
        assertEquals(listOf("w1", "w2"), slice.delegations.map { it.key })
    }

    @Test
    fun `structured beats enrich the rail by position and never by guess`() {
        val items = items(msg(SenderType.ME, "go"), msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n📖 read_file: \"a.txt\""))
        val beats = listOf(
            ToolCall(name = "terminal", context = "ls", status = ToolStatus.COMPLETED, durationS = 0.4),
            ToolCall(name = "patch", context = "b.kt", status = ToolStatus.COMPLETED, durationS = 0.9),
        )
        val slice = TurnSlice.of(items, structured = beats)
        assertEquals(0.4, slice.calls[0].durationS!!, 0.0)
        assertEquals("the name disagreed at position 1, so it stays un-enriched", null, slice.calls[1].durationS)
    }

    // --- frozen equals last live ---------------------------------------------------------------

    @Test
    fun `a frozen state is the last live one with running off`() {
        val calls = listOf(call("terminal"), call("read_file"))
        val live = project(calls = calls, reasoning = "a\nb", answer = "done")
        val frozen = project(running = false, calls = calls, reasoning = "a\nb", answer = "done")
        assertEquals(live.copy(running = false, headline = "Landed", subline = frozen.subline), frozen)
        assertFalse(frozen.isEmpty)
    }
}
