package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.data.remote.HubJson
import chat.keryx.app.notify.MissionAlertsWorker
import chat.keryx.app.presentation.ui.components.NEEDS_YOU
import chat.keryx.app.presentation.ui.components.blockKindLabel
import chat.keryx.app.presentation.ui.components.missionSections
import chat.keryx.app.presentation.ui.components.readableEvents
import chat.keryx.app.presentation.ui.components.runLength
import chat.keryx.app.presentation.ui.components.sameWords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Missions 2.14: a card says what it needs. Fixtures are shaped like the keryx-stream answers
 * for the live Post 1 card (t_7fdf593c, 2026-09-24) — a needs_input block, three runs, one
 * crash streak a later run outlived — plus the fail-soft rule for older gateways.
 */
class MissionBoardTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun task(id: String, status: String, needsYou: Boolean = false) = HermesStreamClient.KanbanTask(
        id = id, title = id, assignee = "theo", status = status, priority = 0, createdBy = "user",
        createdAt = 0L, startedAt = null, completedAt = null, consecutiveFailures = 0, bodyExcerpt = "",
        needsYou = needsYou,
    )

    private val detailJson = """
        {"task":{"id":"t_7fdf593c","title":"Post 1 · draft","assignee":"theo","status":"blocked",
          "priority":0,"created_at":1790270264,"block_kind":"needs_input","block_recurrences":1,
          "ask":"needs your yes: publish Post 1 draft","block_reason":"needs your yes: publish Post 1 draft",
          "latest_summary":"needs your yes: publish Post 1 draft","needs_you":true,"body":"tier: needs-your-yes"},
         "comments":[{"id":1,"author":"default","body":"UNBLOCK: skill linked","created_at":1790293060}],
         "events":[{"id":9,"kind":"heartbeat","payload":null,"created_at":1790294900,"run_id":30},
                   {"id":10,"kind":"blocked","payload":{"reason":"needs your yes","kind":"needs_input"},"created_at":1790294978,"run_id":30}],
         "runs":[{"id":28,"profile":"theo","status":"crashed","outcome":"crashed","summary":null,
                  "error":"Unknown skill(s): lane-autonomy","started_at":1790292921,"ended_at":1790292981,"duration_seconds":60},
                 {"id":30,"profile":"theo","status":"blocked","outcome":"blocked","summary":"Draft complete",
                  "error":null,"started_at":1790293101,"ended_at":1790294978,"duration_seconds":1877}],
         "diagnostics":[{"kind":"repeated_crashes","severity":"error","title":"Agent crashed 2x","detail":"…",
                         "actions":[{"kind":"reassign","label":"Reassign","suggested":false},
                                    {"kind":"cli_hint","label":"Check logs: hermes kanban log t_7fdf593c","suggested":true}],
                         "stale":true}],
         "parents":[{"id":"t_907c9110","title":"Post 1 · summarize","status":"done"}],
         "children":[],
         "attachments":[]}
    """

    @Test
    fun `detail parses the ask, runs, diagnostics and links`() {
        val d = HubJson.kanbanDetail(obj(detailJson), "t_7fdf593c")
        assertEquals("needs_input", d.task.blockKind)
        assertEquals("needs your yes: publish Post 1 draft", d.task.ask)
        assertTrue(d.task.needsYou)
        assertEquals(1, d.task.blockRecurrences)
        assertEquals(listOf(28L, 30L), d.runs.map { it.id })
        assertEquals(1877L, d.runs[1].durationSeconds)
        assertEquals("Unknown skill(s): lane-autonomy", d.runs[0].error)
        assertEquals("", d.runs[0].summary) // null → blank, not "null"
        assertEquals("Check logs: hermes kanban log t_7fdf593c", d.diagnostics.single().suggestedAction)
        assertTrue(d.diagnostics.single().stale)
        // The sheet's badge is derived from the list: one, all history.
        assertEquals(1, d.task.diagCount)
        assertTrue(d.task.diagStale)
        assertEquals("Post 1 · summarize", d.parents.single().title)
        assertEquals("needs your yes", d.events.last().detail)
        assertEquals(30L, d.events.last().runId)
    }

    @Test
    fun `an older gateway's detail degrades to blanks`() {
        val d = HubJson.kanbanDetail(obj("""{"task":{"id":"t_1","title":"old","status":"blocked"},"comments":[],"events":[]}"""), "t_1")
        assertEquals("", d.task.ask)
        assertFalse(d.task.needsYou)
        assertTrue(d.runs.isEmpty() && d.diagnostics.isEmpty() && d.parents.isEmpty())
    }

    @Test
    fun `board card reads the excerpts and the badge`() {
        val t = HubJson.kanbanTask(obj("""
            {"id":"t_1","title":"x","status":"blocked","block_kind":"needs_input","needs_you":true,
             "ask_excerpt":"needs your yes…","latest_summary_excerpt":"Draft…",
             "diagnostics":{"count":2,"severity":"error","stale":false}}
        """))
        assertEquals("needs your yes…", t.ask)
        assertEquals("Draft…", t.latestSummary)
        assertEquals(2, t.diagCount)
        assertEquals("error", t.diagSeverity)
        assertFalse(t.diagStale)
    }

    @Test
    fun `needs-you lane is pinned first and cards never show twice`() {
        val sections = missionSections(
            mapOf(
                "running" to listOf(task("r1", "running")),
                "blocked" to listOf(task("b1", "blocked", needsYou = true), task("b2", "blocked")),
                "review" to listOf(task("v1", "review", needsYou = true)),
                "weird" to listOf(task("w1", "weird")),
            ),
        )
        assertEquals(listOf(NEEDS_YOU, "running", "blocked", "weird"), sections.map { it.key })
        assertEquals(listOf("b1", "v1"), sections[0].cards.map { it.id })
        assertEquals(listOf("b2"), sections[2].cards.map { it.id })
    }

    @Test
    fun `no one waiting means no pinned lane`() {
        val sections = missionSections(mapOf("done" to listOf(task("d1", "done"))))
        assertEquals(listOf("done"), sections.map { it.key })
    }

    @Test
    fun `block kinds say whose move it is`() {
        assertEquals("needs your input", blockKindLabel("needs_input", "blocked"))
        assertEquals("paused — clears itself", blockKindLabel("transient", "blocked"))
        assertEquals("waiting on your review", blockKindLabel("", "review"))
    }

    @Test
    fun `events fold heartbeats and read newest first`() {
        val d = HubJson.kanbanDetail(obj(detailJson), "t_7fdf593c")
        val (rows, beats) = readableEvents(d.events)
        assertEquals(1, beats)
        assertEquals(listOf("blocked"), rows.map { it.kind })
    }

    @Test
    fun `run lengths read at a glance`() {
        assertEquals("45s", runLength(45))
        assertEquals("31m", runLength(1877))
        assertEquals("1h 04m", runLength(3840))
        assertEquals(null, runLength(null))
    }

    @Test
    fun `the ask and a summary that repeats it count as one`() {
        assertTrue(sameWords("needs your yes: publish it. Draft is complete", "needs your yes: publish it…"))
        assertFalse(sameWords("Draft complete", "needs your yes"))
    }

    @Test
    fun `a block alert says why`() {
        val ev = HermesStreamClient.KanbanEvent(1, "t_1", "blocked", 0L, detail = "needs your yes: publish the draft")
        val line = MissionAlertsWorker.alertLine(ev, task("t_1", "blocked").copy(blockKind = "needs_input"))
        assertEquals("Mission blocked — needs you: needs your yes: publish the draft", line)
    }

    @Test
    fun `a self-clearing block does not summon`() {
        val ev = HermesStreamClient.KanbanEvent(1, "t_1", "blocked", 0L, detail = "run still going")
        val line = MissionAlertsWorker.alertLine(ev, task("t_1", "blocked").copy(blockKind = "transient"))
        assertEquals("Mission paused: run still going", line)
    }

    @Test
    fun `an alert with no words keeps the old line`() {
        val ev = HermesStreamClient.KanbanEvent(1, "t_1", "blocked", 0L)
        assertEquals("Mission blocked — it needs something from you", MissionAlertsWorker.alertLine(ev, null))
        val done = HermesStreamClient.KanbanEvent(2, "t_1", "completed", 0L)
        assertEquals("Mission complete", MissionAlertsWorker.alertLine(done, null))
    }
}
