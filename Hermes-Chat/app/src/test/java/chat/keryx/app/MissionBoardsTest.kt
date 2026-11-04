package chat.keryx.app

import chat.keryx.app.presentation.MissionBoards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 boards: the dashboard's list read right, and a new board named the gateway's way. */
class MissionBoardsTest {
    @Test fun parsesTheDashboardsBoardList() {
        val body = """{"boards":[
            {"slug":"default","name":"Default","total":2,"is_current":true},
            {"slug":"ops","name":"","total":5,"is_current":false},
            {"name":"no slug"}
        ],"current":"default"}"""
        val boards = MissionBoards.parse(body)!!
        assertEquals(listOf("default", "ops"), boards.map { it.slug })
        assertEquals("a blank name reads as the slug", "ops", boards[1].name)
        assertEquals(5, boards[1].total)
        assertTrue(boards[0].isCurrent)
    }

    @Test fun currentFallsBackToTheTopLevelField() {
        val boards = MissionBoards.parse("""{"boards":[{"slug":"a"},{"slug":"b"}],"current":"b"}""")!!
        assertEquals(listOf(false, true), boards.map { it.isCurrent })
    }

    @Test fun notTheListShapeIsNull() {
        assertNull(MissionBoards.parse("""{"error":"nope"}"""))
        assertNull(MissionBoards.parse("<html>"))
    }

    @Test fun slugsFollowTheGatewaysRule() {
        assertEquals("house-projects", MissionBoards.slugFor("  House Projects! ", emptySet()))
        assertEquals("q3-launch", MissionBoards.slugFor("Q3 — launch", emptySet()))
        assertEquals("ops-2", MissionBoards.slugFor("Ops", setOf("ops")))
        assertEquals("ops-3", MissionBoards.slugFor("ops", setOf("ops", "ops-2")))
        assertNull("nothing a slug can keep", MissionBoards.slugFor("🚀🚀", emptySet()))
        val long = MissionBoards.slugFor("x".repeat(100), emptySet())!!
        assertTrue(long.length <= 64)
    }
}

/** The board's lanes: travel order, standing lanes kept, a filter shows only what matched. */
class BoardLanesTest {
    private fun lane(key: String) = chat.keryx.app.presentation.ui.components.MissionSection(key, key, emptyList())
    private fun lanes(vararg keys: String, filtering: Boolean = false) =
        chat.keryx.app.presentation.ui.components.boardLanes(keys.map(::lane), filtering).map { it.key }

    @Test fun standingLanesShowEvenWhenEmpty() {
        assertEquals(listOf("triage", "todo", "ready", "running", "done"), lanes())
    }

    @Test fun travelOrderWithNeedsYouFirstAndUnknownLast() {
        assertEquals(
            listOf("needs_you", "triage", "todo", "ready", "running", "blocked", "done", "parked"),
            lanes("done", "parked", "blocked", "needs_you"),
        )
    }

    @Test fun filteringKeepsOnlyMatchingLanes() {
        assertEquals(listOf("running"), lanes("running", filtering = true))
    }
}

/** Archiving (3.0): the gateway refuses to remove its first board, so the app never offers it. */
class MissionBoardArchiveTest {
    @Test fun theDefaultBoardIsTheGatewaysOwnSlug() {
        assertEquals("default", MissionBoards.DEFAULT)
        val boards = MissionBoards.parse("""{"boards":[{"slug":"default"},{"slug":"ops"}],"current":"default"}""")!!
        assertEquals(listOf("ops"), boards.filter { it.slug != MissionBoards.DEFAULT }.map { it.slug })
    }
}
