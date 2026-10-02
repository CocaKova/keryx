package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.presentation.MissionEditForm
import chat.keryx.app.presentation.MissionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.16 board tools: find a card, filter by owner, edit only what changed. */
class MissionBoardToolsTest {

    private fun task(id: String, title: String, assignee: String, priority: Int = 0, body: String = "") =
        HermesStreamClient.KanbanTask(
            id = id, title = title, assignee = assignee, status = "todo", priority = priority, createdBy = "user",
            createdAt = 0L, startedAt = null, completedAt = null, consecutiveFailures = 0, bodyExcerpt = body,
        )

    private val board = mapOf(
        "todo" to listOf(task("t1", "Fix the parser", "theo"), task("t2", "Write docs", "")),
        "running" to listOf(task("t3", "Parser benchmarks", "sy", body = "compare against main")),
    )

    @Test
    fun everyTermMustAppearSomewhereOnTheCard() {
        assertEquals(listOf("t1", "t3"), MissionFilter.apply(board, "parser", null).values.flatten().map { it.id })
        assertEquals(listOf("t3"), MissionFilter.apply(board, "parser main", null).values.flatten().map { it.id })
        assertEquals(board, MissionFilter.apply(board, "  ", null))
    }

    @Test
    fun theOwnerFilterNarrowsAndEmptyLanesGo() {
        val sy = MissionFilter.apply(board, "", "sy")
        assertEquals(setOf("running"), sy.keys)
        val nobody = MissionFilter.apply(board, "", MissionFilter.UNASSIGNED)
        assertEquals(listOf("t2"), nobody.values.flatten().map { it.id })
        val owners = MissionFilter.assignees(board)
        assertEquals(MissionFilter.UNASSIGNED, owners.last().first)
        assertEquals(3, owners.size)
    }

    @Test
    fun anEditSendsOnlyWhatChanged() {
        val t = task("t1", "Fix the parser", "theo", priority = 1)
        assertTrue(MissionEditForm.changes(t, "Fix the parser", "", 1).isEmpty())
        assertEquals(mapOf("title" to "Fix the lexer"), MissionEditForm.changes(t, " Fix the lexer ", "", 1))
        assertEquals(mapOf("priority" to 3), MissionEditForm.changes(t, "Fix the parser", "", 3))
        // A blank title is never sent, and the form says why.
        assertTrue(MissionEditForm.changes(t, "  ", "", 1).isEmpty())
        assertEquals("A mission needs a title", MissionEditForm.problem(" "))
        assertNull(MissionEditForm.problem("ok"))
        assertEquals(listOf("default", "sy"), MissionEditForm.parseAssignees("""{"assignees":[{"name":"default"},{"name":"sy"}]}"""))
        assertNull(MissionEditForm.parseAssignees("""{"detail":"Not Found"}"""))
    }

    @Test
    fun sinceYouLookedIsWhatChangedOnTheCardsFace() {
        val before = chat.keryx.app.presentation.MissionSeen.prints(board)
        assertTrue(chat.keryx.app.presentation.MissionSeen.changed(before, board).isEmpty())
        val moved = board + ("done" to listOf(task("t4", "New card", "sy")))
        val now = moved.mapValues { (lane, cards) ->
            if (lane == "todo") cards.map { if (it.id == "t1") it.copy(status = "running") else it } else cards
        }
        assertEquals(setOf("t1", "t4"), chat.keryx.app.presentation.MissionSeen.changed(before, now))
    }

    @Test
    fun sandPoursWithTheStreamAndStopsOnAStall() {
        val ui = chat.keryx.app.presentation.ui.components.SAND_REFERENCE_TPS
        assertEquals(1f, chat.keryx.app.presentation.ui.components.sandPour(null, 0L))
        val rate = chat.keryx.core.model.LiveRate(cps = ui * 4f, lastAtMs = 1_000L, charsPerToken = 4f)
        assertEquals(1f, chat.keryx.app.presentation.ui.components.sandPour(rate, 1_200L))
        assertEquals(0f, chat.keryx.app.presentation.ui.components.sandPour(rate, 30_000L))
    }
}
