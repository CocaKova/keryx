package chat.keryx.app

import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.Theater
import chat.keryx.core.model.TheaterEvent
import chat.keryx.core.model.TheaterState
import chat.keryx.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool theater's state machine (2.4). The hard part is ORDER: the gateway sends no call ids
 * (`tool.completed` doesn't carry one) and a subagent never says when one of its own calls
 * ended, so every closing rule here is inferred and worth pinning down.
 */
class TheaterTest {

    private fun start(name: String, preview: String = "") =
        TheaterEvent(phase = "start", name = name, preview = preview)

    private fun end(name: String, ok: Boolean = true, ms: Long = 0L, result: String = "") =
        TheaterEvent(phase = "end", name = name, ok = ok, ms = ms, result = result)

    private fun sub(kind: String, child: String = "c1", name: String = "", preview: String = "") =
        TheaterEvent(phase = "sub", kind = kind, child = child, name = name, preview = preview)

    private fun run(vararg events: TheaterEvent): TheaterState =
        events.fold(TheaterState()) { acc, e -> Theater.reduce(acc, e) }

    private fun beats(vararg events: TheaterEvent): List<ToolCall> = run(*events).beats

    // --- the ordinary shape --------------------------------------------------------------------

    @Test
    fun `a start opens a running row`() {
        val b = beats(start("terminal", "ls -la"))
        assertEquals(1, b.size)
        assertEquals("terminal", b[0].name)
        assertEquals("ls -la", b[0].context)
        assertTrue(b[0].running)
        assertNull(b[0].verdictOk)
    }

    @Test
    fun `an end closes it and carries the outcome`() {
        val b = beats(start("terminal"), end("terminal", ok = true, ms = 412, result = "hi"))
        assertEquals(1, b.size)
        assertFalse(b[0].running)
        assertEquals(true, b[0].verdictOk)
        assertEquals(0.412, b[0].durationS!!, 1e-9)
        assertEquals("hi", b[0].result)
    }

    @Test
    fun `a failure is kept as a failure`() {
        assertEquals(false, beats(start("web_search"), end("web_search", ok = false))[0].verdictOk)
    }

    @Test
    fun `sequential calls stay in order and close independently`() {
        val b = beats(start("read"), end("read"), start("write"), end("write"))
        assertEquals(listOf("read", "write"), b.map { it.name })
        assertTrue(b.none { it.running })
    }

    @Test
    fun `an end with nothing open is ignored rather than inventing a row`() {
        assertEquals(emptyList<ToolCall>(), beats(end("terminal")))
    }

    @Test
    fun `an unknown phase changes nothing`() {
        assertEquals(1, beats(start("read"), TheaterEvent(phase = "wat")).size)
    }

    @Test
    fun `a blank tool name never renders as an empty row`() {
        assertEquals("tool", beats(start(""))[0].name)
    }

    // --- correlation ---------------------------------------------------------------------------

    @Test
    fun `an end prefers the open row with the same name`() {
        val b = beats(start("read"), start("terminal"), end("read"))
        assertFalse(b[0].running)
        assertTrue(b[1].running)
    }

    @Test
    fun `an end with an unknown name still closes the oldest open row`() {
        // Better to close something than to leave a row spinning forever, and completions
        // arrive in start order, so the oldest open row is the one that just finished.
        val b = beats(start("read"), start("terminal"), end("something_else"))
        assertFalse(b[0].running)
        assertTrue(b[1].running)
    }

    @Test
    fun `batched calls of the SAME tool keep their own outcomes`() {
        // The trace that motivated FIFO closing, captured off the live side-channel: a model
        // opened two read_file calls before either finished, then they landed in start order.
        // Closing newest-first gave the successful read the failure and vice versa.
        val b = beats(
            start("read_file", "SOUL.md"),
            start("read_file", "nope.md"),
            end("read_file", ok = true, ms = 113),
            end("read_file", ok = false, ms = 85, result = "File not found"),
        )
        assertEquals("SOUL.md", b[0].context)
        assertEquals(true, b[0].verdictOk)
        assertEquals(0.113, b[0].durationS!!, 1e-9)
        assertEquals("nope.md", b[1].context)
        assertEquals(false, b[1].verdictOk)
        assertEquals("File not found", b[1].result)
    }

    // --- parallel batching ---------------------------------------------------------------------

    @Test
    fun `calls that never overlapped are not a batch`() {
        val b = beats(start("read"), end("read"), start("write"), end("write"))
        assertTrue(b.all { it.batchId.isBlank() })
        assertEquals(listOf(1, 1), Theater.batches(b).map { it.size })
    }

    @Test
    fun `an overlap joins BOTH ends of it into one dispatch, not just the newcomer`() {
        val b = beats(start("read"), start("write"))
        assertTrue(b[0].batchId.isNotBlank())
        assertEquals(b[0].batchId, b[1].batchId)
        // ⚠️ Announced together is NOT observed concurrency — this channel never claims it (§6).
        assertTrue(b.none { it.concurrent })
        assertEquals(listOf(2), Theater.batches(b).map { it.size })
    }

    @Test
    fun `a batch does not swallow the solitary call that follows it`() {
        val b = beats(
            start("a"), start("b"), end("a"), end("b"),
            start("c"), end("c"),
        )
        assertEquals(listOf(2, 1), Theater.batches(b).map { it.size })
    }

    @Test
    fun `an empty beat list groups into nothing`() {
        assertEquals(emptyList<List<ToolCall>>(), Theater.batches(emptyList()))
    }

    // --- delegations ---------------------------------------------------------------------------

    @Test
    fun `a subagent is a wing, not a tool row`() {
        val s = run(start("delegate"), sub("start", preview = "research the API"))
        assertEquals(1, s.beats.size)
        assertEquals(1, s.delegations.size)
        assertTrue(s.delegations[0].running)
    }

    @Test
    fun `identity folds in once and is kept when a later event omits it`() {
        val s = run(
            TheaterEvent(
                phase = "sub", kind = "start", child = "s1",
                goal = "audit the logs", model = "qwen3.8-27b",
                taskIndex = 1, taskCount = 3, depth = 1,
            ),
            sub("tool", child = "s1", name = "read_file", preview = "app.log"),
        )
        val d = s.delegations.single()
        assertEquals("audit the logs", d.goal)
        assertEquals("qwen3.8-27b", d.model)
        assertEquals(1, d.taskIndex)
        assertEquals(3, d.taskCount)
        assertEquals(1, d.depth)
        assertEquals("read_file app.log", d.activity)
    }

    @Test
    fun `the wing's activity is its newest line`() {
        val s = run(
            sub("start", preview = "goal"),
            sub("tool", name = "web_search", preview = "trixnity"),
            sub("thinking", preview = "weighing the options"),
        )
        assertEquals("weighing the options", s.delegations[0].activity)
    }

    @Test
    fun `a blank thinking line does not erase what the wing was last doing`() {
        val s = run(sub("tool", name = "read_file", preview = "a.kt"), sub("thinking"))
        assertEquals("read_file a.kt", s.delegations[0].activity)
    }

    @Test
    fun `completion swaps the activity line for the rollup`() {
        val s = run(
            sub("start", preview = "goal"),
            sub("tool", name = "read_file"),
            TheaterEvent(
                phase = "sub", kind = "complete", child = "c1", status = "completed",
                summary = "Found three offenders.", durationSeconds = 42.5,
                inputTokens = 8000, outputTokens = 2000, reasoningTokens = 1000,
                apiCalls = 4, filesRead = 6, filesWritten = 2, toolCount = 9,
            ),
        )
        val d = s.delegations.single()
        assertEquals(DelegationState.DONE, d.state)
        assertFalse(d.running)
        assertEquals("", d.activity)
        assertEquals("Found three offenders.", d.summary)
        assertEquals(42.5, d.durationSeconds!!, 0.001)
        assertEquals(11000, d.totalTokens)
        assertEquals(9, d.toolCount)
        assertEquals(2, d.filesWrittenN)
    }

    @Test
    fun `a dead subagent is never marked successful`() {
        listOf("failed", "error", "timeout").forEach { status ->
            val s = run(sub("start"), TheaterEvent(phase = "sub", kind = "complete", child = "c1", status = status))
            assertEquals(status, DelegationState.FAILED, s.delegations[0].state)
        }
        assertEquals(
            DelegationState.INTERRUPTED,
            run(TheaterEvent(phase = "sub", kind = "complete", child = "c1", status = "interrupted"))
                .delegations[0].state,
        )
    }

    @Test
    fun `an unknown completion status means it finished, not that it finished well or badly`() {
        val s = run(TheaterEvent(phase = "sub", kind = "complete", child = "c1", status = "wat"))
        assertEquals(DelegationState.DONE, s.delegations[0].state)
        assertFalse(s.delegations[0].running)
    }

    @Test
    fun `a fan-out keeps its wings apart and in dispatch order`() {
        val s = run(
            sub("start", child = "a", preview = "alpha"),
            sub("start", child = "b", preview = "beta"),
            sub("tool", child = "b", name = "write_file"),
            TheaterEvent(phase = "sub", kind = "complete", child = "a", status = "completed", summary = "done"),
        )
        assertEquals(listOf("a", "b"), s.delegations.map { it.key })
        assertFalse(s.delegations[0].running)
        assertTrue(s.delegations[1].running)
        assertEquals("write_file", s.delegations[1].activity)
    }

    @Test
    fun `a wing with no subagent_id falls back to its task index`() {
        val s = run(TheaterEvent(phase = "sub", kind = "start", taskIndex = 2))
        assertEquals("task-2", s.delegations.single().key)
    }

    @Test
    fun `a spawn request is a wing before it has started`() {
        val s = run(sub("spawn_requested", preview = "go read the docs"))
        assertEquals(DelegationState.SPAWNING, s.delegations[0].state)
        assertTrue(s.delegations[0].running)
    }

    @Test
    fun `a kind this client does not know still folds identity and cannot blank a wing`() {
        val s = run(
            TheaterEvent(phase = "sub", kind = "start", child = "c1", goal = "the goal"),
            sub("tool", child = "c1", name = "read_file"),
            TheaterEvent(phase = "sub", kind = "invented_later", child = "c1", model = "new-brain"),
        )
        val d = s.delegations.single()
        assertEquals("the goal", d.goal)
        assertEquals("new-brain", d.model)
        assertEquals("read_file", d.activity)
        assertEquals(DelegationState.RUNNING, d.state)
    }

    // --- bounds --------------------------------------------------------------------------------

    @Test
    fun `the beat list is capped at the tail, so a marathon turn cannot grow it forever`() {
        var s = TheaterState()
        repeat(Theater.MAX_BEATS + 12) { i ->
            s = Theater.reduce(s, start("tool$i"))
            s = Theater.reduce(s, end("tool$i"))
        }
        assertEquals(Theater.MAX_BEATS, s.beats.size)
        assertEquals("tool${Theater.MAX_BEATS + 11}", s.beats.last().name)
    }

    @Test
    fun `a turn with nothing in it stays empty`() {
        assertTrue(TheaterState().isEmpty)
        assertFalse(run(sub("start")).isEmpty)
    }

    // --- the failure reason ---------------------------------------------------------------------

    @Test
    fun `a failure shows its reason, not the envelope it arrived in`() {
        val raw = """{"content": "", "total_lines": 0, "file_size": 0, "truncated": false, """ +
            """"is_binary": false, "error": "File not found: /home/j/missing.txt"}"""
        assertEquals("File not found: /home/j/missing.txt", Theater.reason(raw))
    }

    @Test
    fun `an envelope with nothing that reads as a reason still shows its first line`() {
        assertEquals("""{"content": "", "ok": false}""", Theater.reason("""{"content": "", "ok": false}"""))
    }

    @Test
    fun `a plain-text failure keeps its first line`() {
        assertEquals("bash: nope: command not found", Theater.reason("bash: nope: command not found\n\nexit 127"))
    }

    @Test
    fun `an escaped newline in the reason does not break the one line it gets`() {
        assertEquals(
            "Permission denied and then some",
            Theater.reason("""{"error": "Permission denied\nand then some"}"""),
        )
    }
}
