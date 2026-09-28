package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.data.remote.HubJson
import chat.keryx.app.notify.MissionAlertsWorker
import chat.keryx.app.presentation.boardNeedsYou
import chat.keryx.app.presentation.bulkArchiveSummary
import chat.keryx.app.presentation.ui.components.MissionSection
import chat.keryx.app.presentation.ui.components.doneLaneIds
import chat.keryx.app.presentation.ui.components.liveSelection
import chat.keryx.app.presentation.ui.components.missionSections
import chat.keryx.app.presentation.ui.components.runSessionMeta
import chat.keryx.app.presentation.ui.components.showAlertsHint
import chat.keryx.app.presentation.ui.components.toggleLane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Missions 2.14.1: the needs-you orb, bulk clean-up, the in-process alert pulse's shared policy,
 * and the run deck's way into a worker's session. Fixtures follow the keryx-stream board and
 * task answers; every new field is checked against an older gateway that never sends it.
 */
class MissionsPulseTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun task(id: String, status: String, needsYou: Boolean = false) = HermesStreamClient.KanbanTask(
        id = id, title = id, assignee = "theo", status = status, priority = 0, createdBy = "user",
        createdAt = 0L, startedAt = null, completedAt = null, consecutiveFailures = 0, bodyExcerpt = "",
        needsYou = needsYou,
    )

    private fun event(id: Long, kind: String) =
        HermesStreamClient.KanbanEvent(id = id, taskId = "t_$id", kind = kind, createdAt = id)

    // --- The orb's count ----------------------------------------------------------------------

    @Test
    fun `the board's own needs_you count is parsed and wins over the cards' flags`() {
        val board = HubJson.kanbanBoard(obj("""
            {"board":"default","needs_you":3,
             "tasks":{"blocked":[{"id":"t_1","title":"a","status":"blocked","needs_you":true}],
                      "review":[{"id":"t_2","title":"b","status":"review","needs_you":true}]}}
        """))
        assertEquals(3, board.needsYou)
        assertEquals(3, boardNeedsYou(board))
        assertEquals(2, board.tasks.values.flatten().size)
    }

    @Test
    fun `an older board without the count falls back to counting flagged cards once each`() {
        val board = HubJson.kanbanBoard(obj("""
            {"board":"default",
             "tasks":{"blocked":[{"id":"t_1","title":"a","status":"blocked","needs_you":true},
                                 {"id":"t_3","title":"c","status":"blocked","block_kind":"transient"}],
                      "review":[{"id":"t_2","title":"b","status":"review","needs_you":true}],
                      "running":[{"id":"t_4","title":"d","status":"running"}]}}
        """))
        assertNull(board.needsYou)
        assertEquals(2, boardNeedsYou(board))
    }

    @Test
    fun `no board yet means no orb, and a nonsense negative count never shows`() {
        assertEquals(0, boardNeedsYou(null))
        assertEquals(0, boardNeedsYou(HermesStreamClient.KanbanBoard("default", emptyMap(), needsYou = -2)))
    }

    // --- Bulk clean-up ------------------------------------------------------------------------

    @Test
    fun `select all toggles a lane in, then back out, leaving other lanes' picks alone`() {
        val lane = listOf(task("t_1", "done"), task("t_2", "done"))
        val once = toggleLane(setOf("t_9"), lane)
        assertEquals(setOf("t_9", "t_1", "t_2"), once)
        assertEquals(setOf("t_9"), toggleLane(once, lane))
        // Partly picked → the tap completes the lane rather than clearing it.
        assertEquals(setOf("t_1", "t_2"), toggleLane(setOf("t_1"), lane))
        assertEquals(emptySet<String>(), toggleLane(emptySet(), emptyList()))
    }

    @Test
    fun `clear done archives exactly the done lane`() {
        val sections = missionSections(
            mapOf(
                "done" to listOf(task("t_1", "done"), task("t_2", "done")),
                "running" to listOf(task("t_3", "running")),
            ),
        )
        assertEquals(listOf("t_1", "t_2"), doneLaneIds(sections))
        assertEquals(emptyList<String>(), doneLaneIds(sections.filter { it.key != "done" }))
    }

    @Test
    fun `a pick that left the board is neither counted nor archived again`() {
        val sections = listOf(MissionSection("done", "Done", listOf(task("t_1", "done"))))
        assertEquals(setOf("t_1"), liveSelection(setOf("t_1", "t_gone"), sections))
        assertTrue(liveSelection(setOf("t_gone"), sections).isEmpty())
    }

    @Test
    fun `a bulk archive ends in one summary toast`() {
        assertEquals("Archived 12 · 1 failed", bulkArchiveSummary(12, 1))
        assertEquals("Archived 5", bulkArchiveSummary(5, 0))
        assertEquals("Archive failed for 1 card", bulkArchiveSummary(0, 1))
        assertEquals("Archive failed for 3 cards", bulkArchiveSummary(0, 3))
        assertEquals("Nothing to archive", bulkArchiveSummary(0, 0))
    }

    // --- Alerts: one policy for the worker and the pulse --------------------------------------

    @Test
    fun `only terminal kinds ring, and a burst rings its newest five`() {
        val events = (1L..8L).map { event(it, "completed") } +
            listOf(event(9, "heartbeat"), event(10, "blocked"), event(11, "created"), event(12, "gave_up"))
        val rung = MissionAlertsWorker.alertsToRing(events, boardOnScreen = false)
        assertEquals(listOf(6L, 7L, 8L, 10L, 12L), rung.map { it.id })
        assertTrue(rung.all { it.kind in setOf("completed", "blocked", "gave_up") })
    }

    @Test
    fun `nothing rings while the board itself is on screen`() {
        val events = listOf(event(1, "completed"), event(2, "blocked"))
        assertTrue(MissionAlertsWorker.alertsToRing(events, boardOnScreen = true).isEmpty())
        assertEquals(2, MissionAlertsWorker.alertsToRing(events, boardOnScreen = false).size)
    }

    @Test
    fun `the ring-me hint shows once, on the gateway door, while alerts are off`() {
        assertTrue(showAlertsHint(direct = true, alertsOn = false, dismissed = false))
        assertFalse(showAlertsHint(direct = true, alertsOn = true, dismissed = false))
        assertFalse(showAlertsHint(direct = true, alertsOn = false, dismissed = true))
        assertFalse(showAlertsHint(direct = false, alertsOn = false, dismissed = false))
    }

    // --- The worker's session -----------------------------------------------------------------

    private fun detailWithRuns(runs: String) = HubJson.kanbanDetail(
        obj("""{"task":{"id":"t_1","title":"x","status":"running"},"comments":[],"events":[],"runs":[$runs]}"""),
        "t_1",
    )

    @Test
    fun `a run carries its worker session id when the gateway names one`() {
        val d = detailWithRuns(
            """{"id":31,"profile":"theo","status":"running","outcome":"","started_at":100,"ended_at":null,
                "session_id":"20260927_101500_ab12cd"}""",
        )
        assertEquals("20260927_101500_ab12cd", d.runs.single().sessionId)
    }

    @Test
    fun `null, blank or missing session ids all read as none — the old deck, unchanged`() {
        val d = detailWithRuns(
            """{"id":1,"profile":"theo","status":"crashed","session_id":null,"started_at":1},
               {"id":2,"profile":"theo","status":"done","session_id":"  ","started_at":2},
               {"id":3,"profile":"theo","status":"done","started_at":3}""",
        )
        assertEquals(listOf(null, null, null), d.runs.map { it.sessionId })
    }

    @Test
    fun `the transcript sheet's meta says who, how it ended, and how long`() {
        val live = HermesStreamClient.KanbanRun(
            id = 31, profile = "theo", status = "running", outcome = "", summary = "", error = "",
            startedAt = 1_000L, endedAt = null, durationSeconds = null, sessionId = "s",
        )
        assertEquals("theo · running · 2m", runSessionMeta(live, nowSeconds = 1_130L))
        val landed = live.copy(outcome = "timed_out", endedAt = 5_000L, durationSeconds = 45L, profile = "")
        assertEquals("timed out · 45s", runSessionMeta(landed, nowSeconds = 9_999L))
    }
}
