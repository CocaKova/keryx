package chat.keryx.core

import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.RosterGroup
import chat.keryx.core.model.RosterGroups
import chat.keryx.core.model.RosterGroups.DAY_MS
import chat.keryx.core.model.SessionTree
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTreeTest {

    private fun row(id: String, at: Long, forkOf: String? = null) =
        RoomProfile(id = id, name = id, type = RoomType.DIRECT_MESSAGE, timestamp = at, forkOf = forkOf)

    private fun List<RoomProfile>.shape() = map { "  ".repeat(it.forkDepth) + it.id }

    @Test
    fun `a fork sits under its parent`() {
        val rows = listOf(row("a", 50), row("p", 40), row("f", 30, forkOf = "p"), row("b", 20))
        assertEquals(listOf("a", "p", "  f", "b"), SessionTree.nest(rows).shape())
    }

    @Test
    fun `a busy fork carries its family up`() {
        val rows = listOf(row("f", 90, forkOf = "p"), row("a", 50), row("p", 10))
        assertEquals(listOf("p", "  f", "a"), SessionTree.nest(rows).shape())
    }

    @Test
    fun `forks of forks step in, newest first, and stop at the cap`() {
        val rows = listOf(
            row("p", 10), row("f1", 20, forkOf = "p"), row("f2", 30, forkOf = "p"),
            row("g", 25, forkOf = "f1"), row("h", 24, forkOf = "g"),
        )
        assertEquals(listOf("p", "  f2", "  f1", "    g", "    h"), SessionTree.nest(rows).shape())
    }

    @Test
    fun `a fork without its parent stands alone and still says it is one`() {
        val out = SessionTree.nest(listOf(row("f", 30, forkOf = "gone"), row("a", 20)))
        assertEquals(listOf("f", "a"), out.shape())
        assertEquals("gone", out.first().forkOf)
    }

    @Test
    fun `a cycle loses nothing`() {
        val out = SessionTree.nest(listOf(row("x", 20, forkOf = "y"), row("y", 10, forkOf = "x"), row("a", 5)))
        assertEquals(setOf("x", "y", "a"), out.map { it.id }.toSet())
        assertEquals(3, out.size)
    }

    @Test
    fun `nesting again after a filter recomputes depth`() {
        val nested = SessionTree.nest(listOf(row("p", 40), row("f", 30, forkOf = "p")))
        assertEquals(listOf("f"), SessionTree.nest(nested.filter { it.id != "p" }).shape())
    }

    @Test
    fun `a family shelves together by its newest row`() {
        val midnight = 1_000_000_000_000L
        val rows = SessionTree.nest(
            listOf(row("p", midnight - 5 * DAY_MS), row("f", midnight + 1000, forkOf = "p"), row("a", midnight - 10)),
        )
        val shelves = RosterGroups.split(rows, midnight)
        assertEquals(RosterGroup.TODAY, shelves[0].group)
        assertEquals(listOf("p", "f"), shelves[0].rows.map { it.id })
        assertEquals(listOf("a"), shelves[1].rows.map { it.id })
    }

    @Test
    fun `a compacted root leaves once its tip is listed`() {
        data class R(val id: String, val lineage: List<String> = emptyList())
        val rows = listOf(R("tip", listOf("root", "mid", "tip")), R("root"), R("other"), R("mid"))
        assertEquals(
            listOf("tip", "other"),
            SessionTree.dropSuperseded(rows, { it.id }, { it.lineage }).map { it.id },
        )
    }

    @Test
    fun `a typed branch command is read with its name`() {
        assertEquals("", chat.keryx.core.model.BranchCommand.parse("/branch"))
        assertEquals("try plan B", chat.keryx.core.model.BranchCommand.parse("/fork  try plan B"))
        assertEquals("alt", chat.keryx.core.model.BranchCommand.parse("/branch --here alt"))
        assertEquals(null, chat.keryx.core.model.BranchCommand.parse("/branches"))
        assertEquals(null, chat.keryx.core.model.BranchCommand.parse("let's branch out"))
    }
}
