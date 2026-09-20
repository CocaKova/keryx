package chat.keryx.core

import chat.keryx.core.model.AlertPolicy
import chat.keryx.core.model.AlertPolicy.Verdict
import chat.keryx.core.model.Message
import chat.keryx.core.model.RunActivities
import chat.keryx.core.model.RunActivity
import chat.keryx.core.model.RunActivity.Phase
import chat.keryx.core.model.RunNotices
import chat.keryx.core.model.RunSubject
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.TodoItem
import chat.keryx.core.model.TodoPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunNoticeTest {
    private val fresh = RunActivity(sessionId = "s", startedAt = 100L)

    private fun subject(id: String, agent: String, a: RunActivity = fresh, session: String = agent, plan: TodoPlan? = null) =
        RunSubject(sessionId = id, agent = agent, session = session, colorKey = agent.lowercase(), activity = a, plan = plan)

    private fun msg(content: String, sender: SenderType = SenderType.HERMES, id: String = "1", streaming: Boolean = false) =
        Message(id = id, roomId = "r", sender = sender, content = content, timestamp = 1L, isStreaming = streaming)

    // --- the reducer ---------------------------------------------------------------------

    @Test fun aStreamOfDeltasIsOneStateNotMany() {
        val writing = RunActivities.reduce(fresh, "message.delta")
        assertEquals(Phase.WRITING, writing.phase)
        // Per-token traffic must hand back the same instance, or the notice repaints per token.
        assertSame(writing, RunActivities.reduce(writing, "message.delta"))
        val thinking = RunActivities.reduce(fresh, "reasoning.delta")
        assertSame(thinking, RunActivities.reduce(thinking, "reasoning.delta"))
    }

    @Test fun aToolIsNamedInTheTranscriptsVocabulary() {
        val a = RunActivities.reduce(fresh, "tool.start", toolId = "t1", toolName = "terminal", toolContext = "npm test")
        assertEquals(Phase.TOOL, a.phase)
        assertEquals("❯ Running npm test", RunActivities.line(a))
    }

    @Test fun generatingThenStartIsOneCall() {
        val gen = RunActivities.reduce(fresh, "tool.generating", toolName = "read_file")
        assertEquals("▤ Reading", RunActivities.line(gen))
        val started = RunActivities.reduce(gen, "tool.start", toolId = "t1", toolName = "read_file", toolContext = "/a/b/notes.md")
        assertEquals(1, started.running.size)
        assertEquals("▤ Reading notes.md", RunActivities.line(started))
    }

    @Test fun oneParallelCallReturningLeavesTheOtherNamed() {
        var a = RunActivities.reduce(fresh, "tool.start", toolId = "t1", toolName = "web_search", toolContext = "kotlin flows")
        a = RunActivities.reduce(a, "tool.start", toolId = "t2", toolName = "terminal", toolContext = "ls")
        a = RunActivities.reduce(a, "tool.complete", toolId = "t2", toolName = "terminal")
        assertEquals(Phase.TOOL, a.phase)
        assertEquals("⌕ Searching web kotlin flows", RunActivities.line(a))
        a = RunActivities.reduce(a, "tool.complete", toolId = "t1", toolName = "web_search")
        assertEquals(Phase.THINKING, a.phase)
        assertEquals(2, a.toolsDone)
        assertEquals("Thinking…", RunActivities.line(a))
    }

    @Test fun proseBesideARunningToolDoesNotFlickerTheNotice() {
        val tool = RunActivities.reduce(fresh, "tool.start", toolId = "t1", toolName = "terminal", toolContext = "make")
        assertSame(tool, RunActivities.reduce(tool, "message.delta"))
    }

    @Test fun unknownEventsChangeNothing() {
        assertSame(fresh, RunActivities.reduce(fresh, "session.info"))
    }

    // --- the notice ----------------------------------------------------------------------

    @Test fun nothingRunningIsNoNotice() {
        assertNull(RunNotices.compose(emptyList()))
    }

    @Test fun theProfilesAgentIsNamedAsTheWorker() {
        val n = RunNotices.compose(listOf(subject("s", "Theo")))!!
        assertEquals("☤ Theo is working", n.title)
        assertEquals("", n.subText) // a Bot Chat: the session is the agent, said once
        assertEquals("Thinking…", n.text)
        assertEquals("s", n.sessionId)
        assertEquals(0, n.planTotal)
    }

    @Test fun aPlainSessionNamesItsProfilesAgentAndTheRoom() {
        val n = RunNotices.compose(listOf(subject("s", "Juno", session = "Fix the deploy")))!!
        assertEquals("☤ Juno is working", n.title)
        assertEquals("Fix the deploy", n.subText)
    }

    @Test fun anUnknownAgentIsNeverGivenAName() {
        // The roster has not answered: the notice names the room, not a guess at who is in it.
        val n = RunNotices.compose(listOf(subject("s", "", session = "Fix the deploy")))!!
        assertEquals("☤ Working · Fix the deploy", n.title)
        assertEquals("", n.subText)
    }

    @Test fun theAgentComesFromTheInstallsOwnRoster() {
        val bots = listOf(
            chat.keryx.core.model.BotProfile(name = "default", title = "Marlowe", isDefault = true),
            chat.keryx.core.model.BotProfile(name = "research-buddy"),
        )
        assertEquals("Marlowe", chat.keryx.core.model.BotRoster.agentFor(bots, null)?.label)
        assertEquals("Research Buddy", chat.keryx.core.model.BotRoster.agentFor(bots, "research-buddy")?.label)
        assertNull(chat.keryx.core.model.BotRoster.agentFor(bots, "gone"))
        assertNull(chat.keryx.core.model.BotRoster.agentFor(emptyList(), null))
    }

    @Test fun thePlanBecomesProgress() {
        val plan = TodoPlan(listOf(
            TodoItem("1", "Read the logs", "completed"),
            TodoItem("2", "Patch the unit", "in_progress"),
            TodoItem("3", "Restart", "pending"),
        ))
        val n = RunNotices.compose(listOf(subject("s", "Theo", plan = plan)))!!
        assertEquals(1, n.planDone)
        assertEquals(3, n.planTotal)
        assertEquals("Step 2 of 3 · Patch the unit", n.lines.first())
    }

    @Test fun aFinishedPlanIsNotProgress() {
        val plan = TodoPlan(listOf(TodoItem("1", "Done thing", "completed")))
        val n = RunNotices.compose(listOf(subject("s", "Theo", plan = plan)))!!
        assertEquals(0, n.planTotal)
    }

    @Test fun severalRunsAreStillOneNotice() {
        val late = fresh.copy(sessionId = "b", startedAt = 500L)
        val n = RunNotices.compose(listOf(subject("b", "Juno", late), subject("a", "Theo")))!!
        assertEquals("☤ 2 agents working", n.title)
        assertNull(n.sessionId)
        assertEquals(listOf("Theo — Thinking…", "Juno — Thinking…"), n.lines)
        assertEquals(100L, n.startedAt)
    }

    // --- the alert -----------------------------------------------------------------------

    @Test fun nothingAlertsMidTurn() {
        assertEquals(Verdict.SILENT_BUSY, AlertPolicy.decide(msg("Let me check that."), busy = true, lastAlertedKey = null))
        assertEquals(Verdict.SILENT_BUSY, AlertPolicy.decide(msg("partial", streaming = true), busy = false, lastAlertedKey = null))
    }

    @Test fun theSameMessageNeverAlertsTwiceWhateverItsId() {
        val live = msg("All done.", id = "live-7")
        val reread = msg("All done.", id = "4312")
        assertEquals(Verdict.ALERT, AlertPolicy.decide(live, busy = false, lastAlertedKey = null))
        assertEquals(Verdict.SILENT_SEEN, AlertPolicy.decide(reread, busy = false, lastAlertedKey = AlertPolicy.keyOf(live)))
    }

    @Test fun aNewMessageAlerts() {
        val first = msg("All done.")
        assertEquals(Verdict.ALERT, AlertPolicy.decide(msg("One more thing."), busy = false, lastAlertedKey = AlertPolicy.keyOf(first)))
    }

    @Test fun myOwnWordsAndEmptyRowsStaySilent() {
        assertEquals(Verdict.SILENT_MINE, AlertPolicy.decide(msg("hi", sender = SenderType.ME), busy = false, lastAlertedKey = null))
        assertEquals(Verdict.SILENT_EMPTY, AlertPolicy.decide(msg("  "), busy = false, lastAlertedKey = null))
        assertTrue(AlertPolicy.keyOf(msg("a")) != AlertPolicy.keyOf(msg("b")))
    }
}
