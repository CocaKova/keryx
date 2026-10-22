package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail
import chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent
import chat.keryx.app.data.remote.HermesStreamClient.KanbanRun
import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import chat.keryx.app.presentation.MissionStory
import chat.keryx.app.presentation.MissionStory.Mood
import chat.keryx.app.presentation.MissionStory.Primary
import chat.keryx.app.transport.demo.DemoBoard
import chat.keryx.core.model.BotProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mission sheet as a story (3.0): one plain sentence per state, the thing to act on, the
 * move that fits, and a history in words. Gateway-shaped data (epoch seconds, Hermes's event
 * kinds); the agent names come from the roster, never from the app.
 */
class MissionStoryTest {
    private val now = 1_800_000_000L

    private fun task(
        status: String, assignee: String = "atlas", needsYou: Boolean = false, blockKind: String = "",
        ask: String = "", result: String = "", summary: String = "", body: String = "", error: String = "",
        started: Long? = now - 25 * 60, completed: Long? = null,
    ) = KanbanTask(
        id = "t_1", title = "A mission", assignee = assignee, status = status, priority = 0,
        createdBy = "you", createdAt = now - 3600, startedAt = started, completedAt = completed,
        consecutiveFailures = if (error.isBlank()) 0 else 1, bodyExcerpt = body.take(40), body = body,
        result = result, lastFailureError = error, blockKind = blockKind, ask = ask,
        latestSummary = summary, needsYou = needsYou,
    )

    private fun run(id: Long, ended: Long?, outcome: String = "", session: String? = "s$id") = KanbanRun(
        id = id, profile = "atlas", status = outcome.ifBlank { "running" }, outcome = outcome,
        summary = "", error = "", startedAt = now - 25 * 60, endedAt = ended,
        durationSeconds = ended?.let { it - (now - 25 * 60) }, sessionId = session,
    )

    private fun ev(id: Long, kind: String, at: Long) = KanbanEvent(id, "t_1", kind, at)

    private fun detail(t: KanbanTask, events: List<KanbanEvent> = emptyList(), runs: List<KanbanRun> = emptyList()) =
        KanbanDetail(t, emptyList(), events, runs)

    @Test
    fun `the agent is named the way the install names it`() {
        val roster = listOf(
            BotProfile(name = "default", isDefault = true),
            BotProfile(name = "atlas", title = "Atlas the Archivist"),
            BotProfile(name = "scout", displayName = "Scout"),
        )
        assertNull(MissionStory.agentName("default", roster))             // "default" is not a name
        assertNull(MissionStory.agentName("default", emptyList()))
        assertNull(MissionStory.agentName("", roster))
        assertEquals("Atlas the Archivist", MissionStory.agentName("atlas", roster))
        assertEquals("Scout", MissionStory.agentName("scout", roster))
        assertEquals("Research buddy", MissionStory.agentName("research-buddy", roster))
        assertEquals("Your agent", MissionStory.subject(null))
        assertEquals("Muse", MissionStory.agentName("default", listOf(BotProfile(name = "default", title = "Muse", isDefault = true))))
    }

    @Test
    fun `each state reads as one plain sentence`() {
        fun h(t: KanbanTask, d: KanbanDetail? = null) = MissionStory.headline(t, d, "Atlas", now)
        val review = task("review", needsYou = true, ask = "Read it over.")
        assertEquals(MissionStory.Headline("Atlas finished and wants your OK · 35 min ago", Mood.NEEDS_YOU),
            h(review, detail(review, listOf(ev(3, "review_requested", now - 35 * 60)))))
        assertEquals("Atlas has a question for you · 20 min ago",
            h(task("blocked", needsYou = true, blockKind = "needs_input"), detail(task("blocked"), listOf(ev(2, "blocked", now - 20 * 60)))).text)
        assertEquals("Atlas is working on this · started 25 min ago · last sign of life 1 min ago",
            h(task("running"), detail(task("running"), listOf(ev(9, "heartbeat", now - 60)))).text)
        assertEquals("Atlas is working on this · started 25 min ago", h(task("running")).text)
        val failed = task("ready", error = "Couldn't reach the NAS.")
        assertEquals(Mood.STUCK, h(failed).mood)
        assertTrue(h(failed).text.startsWith("Atlas hit a problem and will try again"))
        assertEquals("Done · 3h ago", h(task("done", completed = now - 3 * 3600)).text)
        assertEquals("Waiting for another mission to finish first", h(task("blocked", blockKind = "dependency")).text)
        assertEquals("Waiting for a plan before anyone starts", h(task("triage", started = null)).text)
        // No name: the sentence still reads.
        assertEquals("Your agent is working on this · started 25 min ago", MissionStory.headline(task("running"), null, null, now).text)
    }

    @Test
    fun `the box shows the thing to act on, and the ask is not said twice`() {
        val review = task("review", needsYou = true, ask = "Read it over before I post it.", result = "Drafted the notes.")
        val f = MissionStory.focus(review, detail(review))!!
        assertEquals("For your OK", f.label)
        assertEquals("Drafted the notes.", f.text)
        assertEquals("Read it over before I post it.", MissionStory.askQuote(review, f))
        // The ask that only repeats the draft is not quoted.
        val same = review.copy(ask = "Drafted the notes.")
        assertNull(MissionStory.askQuote(same, MissionStory.focus(same, detail(same))))
        assertEquals("The question", MissionStory.focus(task("blocked", needsYou = true, blockKind = "needs_input", ask = "Which evening?"), null)!!.label)
        val err = MissionStory.focus(task("ready", error = "Timed out."), null)!!
        assertTrue(err.error)
        assertEquals("The task", MissionStory.focus(task("running", body = "Prune the backups."), null)!!.label)
        assertNull(MissionStory.focus(task("running"), null))
    }

    @Test
    fun `one move fits each state`() {
        val live = run(1, ended = null)
        assertEquals(Primary.APPROVE, MissionStory.primary(task("review", needsYou = true), null))
        assertEquals(Primary.REPLY_UNBLOCK, MissionStory.primary(task("blocked", needsYou = true), null))
        assertEquals(Primary.WATCH, MissionStory.primary(task("running"), live))
        assertEquals(Primary.NONE, MissionStory.primary(task("running"), live.copy(sessionId = null)))
        assertEquals(Primary.START, MissionStory.primary(task("todo", started = null), null))
        assertEquals(Primary.UNBLOCK, MissionStory.primary(task("blocked", blockKind = "dependency"), null))
        assertEquals(Primary.NONE, MissionStory.primary(task("done"), null))
    }

    @Test
    fun `a run is live only while its card runs`() {
        val open = run(1, ended = null)
        assertTrue(MissionStory.isLive(open, task("running")))
        // A run the gateway never stamped an end on, on a card already waiting on review.
        assertFalse(MissionStory.isLive(open, task("review", needsYou = true)))
        assertNull(MissionStory.liveRun(detail(task("review"), runs = listOf(open)), task("review")))
        assertEquals(1L, MissionStory.liveRun(detail(task("running"), runs = listOf(open)), task("running"))?.id)
    }

    @Test
    fun `what happened is told in words, without the plumbing`() {
        val t = task("review", needsYou = true)
        val d = detail(t, listOf(
            ev(1, "created", now - 3600), ev(2, "claimed", now - 3000), ev(3, "spawned", now - 2999),
            ev(4, "heartbeat", now - 2900), ev(5, "owner_admitted", now - 2899), ev(6, "commented", now - 2500),
            ev(7, "commented", now - 2400), ev(8, "review_requested", now - 2100),
        ))
        assertEquals(
            listOf("Created", "Atlas started on it", "Note added", "Finished and asked for your OK"),
            MissionStory.timeline(d, "Atlas").map { it.text },
        )
        assertEquals("3 days ago", MissionStory.ago(now - 3 * 86_400, now))
        assertEquals("just now", MissionStory.ago(now - 5, now))
        assertNull(MissionStory.ago(null, now))
    }

    @Test
    fun `every demo card tells a story, and only the running one has a live run`() {
        val nowMs = now * 1000
        for (c in DemoBoard.board(nowMs).tasks.values.flatten()) {
            val d = DemoBoard.detail(c.id, nowMs).getOrThrow()
            val h = MissionStory.headline(d.task, d, null, now)
            assertTrue(c.id, h.text.isNotBlank() && !h.text.contains("_"))
            for (r in d.runs) assertEquals(c.id, d.task.status == "running", MissionStory.isLive(r, d.task))
            assertEquals(c.id, d.task.status == "running", r(d))
        }
    }

    private fun r(d: KanbanDetail) = d.runs.any { it.endedAt == null }
}
