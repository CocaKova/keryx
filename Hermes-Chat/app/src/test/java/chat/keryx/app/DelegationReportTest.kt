package chat.keryx.app

import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationReport
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.DelegationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background-delegation report arrives as a role:"user" row, so every one of these is
 * ultimately about the same thing: it is NOT something the user said.
 *
 * The fixtures are built to the two builders in the gateway's
 * `_format_delegation_completion` (tools/process_registry.py), not to guesses.
 */
class DelegationReportTest {

    private val batch = """
        [ASYNC DELEGATION BATCH COMPLETE — deleg_7]
        A background fan-out of 2 subagent(s) you dispatched earlier has finished. All ran in parallel and waited on each other; their consolidated results are below. You may have moved on since dispatching — act on these or re-dispatch if things have changed.

        Dispatched: 2026-08-14 07:15:02 (12m ago)
        Role: leaf   Model: qwen3.6-27b-vision   Total duration: 84.2s

        --- ✓ TASK 1/2: audit the login flow  (status=completed, api_calls=6, 41.5s) ---
        Found two unguarded redirects in session setup.
        Both are reachable without a token.

        --- ✗ TASK 2/2: benchmark the parser  (status=failed, api_calls=2, 8.1s) ---
        (failed: worker exited)
        Partial output:
        Only the warmup pass completed.
        Full live transcript (complete tool/assistant trace): /tmp/deleg_7_2.jsonl
    """.trimIndent()

    private val single = """
        [ASYNC DELEGATION COMPLETE — deleg_3]
        A background subagent you dispatched earlier has finished. You may have moved on since dispatching it; the full task source is below so you can act on the result or re-dispatch if things have changed.

        Dispatched: 2026-08-14 09:00:00 (4m ago)
        Original goal: summarise the release notes
        Role: leaf   Model: qwen3.6-27b-vision
        Status: completed   API calls: 3   Duration: 22.0s
        --- RESULT ---
        Three user-visible changes, one of them breaking.
    """.trimIndent()

    // ---- attribution: the actual bug -------------------------------------------------

    @Test
    fun `both report shapes are recognised as machinery`() {
        assertTrue(DelegationReport.isReport(batch))
        assertTrue(DelegationReport.isReport(single))
    }

    @Test
    fun `an ordinary message is never mistaken for a report`() {
        assertFalse(DelegationReport.isReport("can you check the async delegation batch complete thing?"))
        assertFalse(DelegationReport.isReport(""))
        assertFalse(DelegationReport.isReport("[CONTEXT COMPACTION — REFERENCE ONLY] …"))
    }

    @Test
    fun `a mangled report is still recognised, so attribution never falls back to the user`() {
        // Parsing may yield nothing; isReport is what decides whose voice it speaks in.
        val mangled = "[ASYNC DELEGATION BATCH COMPLETE — deleg_9]\nnothing else survived truncation"
        assertTrue(DelegationReport.isReport(mangled))
        assertTrue(DelegationReport.parse(mangled).isEmpty())
    }

    // ---- batch ------------------------------------------------------------------------

    @Test
    fun `a batch parses into one wing per task, in order`() {
        val wings = DelegationReport.parse(batch)
        assertEquals(2, wings.size)
        assertEquals("audit the login flow", wings[0].goal)
        assertEquals("benchmark the parser", wings[1].goal)
        assertEquals(0, wings[0].taskIndex)
        assertEquals(1, wings[1].taskIndex)
        assertEquals(2, wings[0].taskCount)
    }

    @Test
    fun `task status, cost and duration come off the header`() {
        val wings = DelegationReport.parse(batch)
        assertEquals(DelegationState.DONE, wings[0].state)
        assertEquals(DelegationState.FAILED, wings[1].state)
        assertEquals(6, wings[0].apiCalls)
        assertEquals(41.5, wings[0].durationSeconds!!, 0.001)
        assertEquals(8.1, wings[1].durationSeconds!!, 0.001)
    }

    @Test
    fun `the model is carried onto every wing`() {
        assertTrue(DelegationReport.parse(batch).all { it.model == "qwen3.6-27b-vision" })
    }

    @Test
    fun `each task keeps its own result text`() {
        val wings = DelegationReport.parse(batch)
        assertTrue(wings[0].summary.startsWith("Found two unguarded redirects"))
        assertTrue(wings[0].summary.contains("reachable without a token"))
        // A task's summary must not bleed into its neighbour's.
        assertFalse(wings[0].summary.contains("warmup"))
        assertTrue(wings[1].summary.contains("Only the warmup pass completed."))
    }

    @Test
    fun `the live transcript path is dropped — it is a pointer for the agent, not a result`() {
        assertFalse(DelegationReport.parse(batch)[1].summary.contains("Full live transcript"))
        assertFalse(DelegationReport.parse(batch)[1].summary.contains(".jsonl"))
    }

    @Test
    fun `the prose addressed to the model never becomes a wing summary`() {
        val wings = DelegationReport.parse(batch)
        assertTrue(wings.none { it.summary.contains("re-dispatch if things have changed") })
        assertTrue(wings.none { it.summary.contains("A background fan-out") })
    }

    @Test
    fun `a cross mark overrides a status word we do not recognise`() {
        val odd = """
            [ASYNC DELEGATION BATCH COMPLETE — deleg_1]
            --- ✗ TASK 1/1: do a thing  (status=weird_new_word, api_calls=1, 2.0s) ---
            it did not go well
        """.trimIndent()
        assertEquals(DelegationState.FAILED, DelegationReport.parse(odd).single().state)
    }

    @Test
    fun `a timeout is a failure, not a success`() {
        val t = """
            [ASYNC DELEGATION BATCH COMPLETE — deleg_2]
            --- ✗ TASK 1/1: slow thing  (status=timeout, api_calls=9, 600.0s) ---
            gave up
        """.trimIndent()
        assertEquals(DelegationState.FAILED, DelegationReport.parse(t).single().state)
    }

    // ---- single -----------------------------------------------------------------------

    @Test
    fun `a single completion parses into one wing`() {
        val wings = DelegationReport.parse(single)
        assertEquals(1, wings.size)
        val w = wings.single()
        assertEquals("summarise the release notes", w.goal)
        assertEquals("qwen3.6-27b-vision", w.model)
        assertEquals(DelegationState.DONE, w.state)
        assertEquals(3, w.apiCalls)
        assertEquals(22.0, w.durationSeconds!!, 0.001)
        assertEquals("Three user-visible changes, one of them breaking.", w.summary)
        // A lone subagent must not label itself "[1] " like a fan-out member.
        assertEquals(1, w.taskCount)
    }

    @Test
    fun `a single report with no RESULT block still yields a wing rather than nothing`() {
        val noResult = """
            [ASYNC DELEGATION COMPLETE — deleg_4]
            Original goal: do the thing
            Role: leaf   Model: m1
            Status: interrupted   API calls: 1   Duration: 3.0s
        """.trimIndent()
        val w = DelegationReport.parse(noResult).single()
        assertEquals(DelegationState.INTERRUPTED, w.state)
        assertEquals("", w.summary)
    }

    // ---- superseded live landings -----------------------------------------------------

    private fun landed(key: String, goal: String, running: Boolean = false) = Message(
        id = "${DelegationReport.LANDED_ID_PREFIX}$key",
        roomId = "s", sender = SenderType.HERMES, content = "", timestamp = 0L,
        delegations = listOf(
            Delegation(
                key = key, goal = goal,
                state = if (running) DelegationState.RUNNING else DelegationState.DONE,
            )
        ),
    )

    private fun reportRow() = Message(
        id = "${DelegationReport.ROW_ID_PREFIX}42",
        roomId = "s", sender = SenderType.HERMES, content = "", timestamp = 0L,
        delegations = DelegationReport.parse(batch),
    )

    @Test
    fun `a live landing is dropped once its report covers it`() {
        val msgs = listOf(landed("sub_a", "audit the login flow"), reportRow())
        val kept = DelegationReport.withoutSupersededLandings(msgs)
        assertEquals(1, kept.size)
        assertTrue(kept.single().id.startsWith(DelegationReport.ROW_ID_PREFIX))
    }

    @Test
    fun `a landing the report does not mention survives`() {
        val msgs = listOf(landed("sub_z", "something else entirely"), reportRow())
        assertEquals(2, DelegationReport.withoutSupersededLandings(msgs).size)
    }

    @Test
    fun `a wing still in flight is never dropped`() {
        val msgs = listOf(landed("sub_a", "audit the login flow", running = true), reportRow())
        assertEquals(2, DelegationReport.withoutSupersededLandings(msgs).size)
    }

    @Test
    fun `with no report present nothing is touched`() {
        val msgs = listOf(landed("sub_a", "audit the login flow"))
        assertEquals(msgs, DelegationReport.withoutSupersededLandings(msgs))
    }

    @Test
    fun `ordinary messages are never dropped`() {
        val mine = Message(id = "m1", roomId = "s", sender = SenderType.ME,
            content = "audit the login flow", timestamp = 0L)
        val kept = DelegationReport.withoutSupersededLandings(listOf(mine, reportRow()))
        assertEquals(2, kept.size)
    }
}
