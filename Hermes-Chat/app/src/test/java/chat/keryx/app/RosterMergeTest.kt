package chat.keryx.app

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.mergeRecent
import org.junit.Assert.assertEquals
import org.junit.Test

class RosterMergeTest {

    private fun row(
        id: String, last: Long, title: String = id, archived: Boolean = false, lineage: List<String> = emptyList(),
    ) = GatewayRest.SessionRow(
        id = id, title = title, preview = "", startedAt = 0, lastActive = last, messageCount = 1,
        isActive = false, archived = archived, pinned = false, source = "tui", lineage = lineage,
    )

    @Test
    fun `a moved session updates in place and rises to the top`() {
        val held = listOf(row("a", 30), row("b", 20), row("c", 10))
        val out = mergeRecent(held, listOf(row("c", 40, title = "c2")))
        assertEquals(listOf("c", "a", "b"), out.map { it.id })
        assertEquals("c2", out.first().title)
    }

    @Test
    fun `a new session joins and nothing held is lost`() {
        val held = listOf(row("a", 30), row("b", 20))
        assertEquals(listOf("n", "a", "b"), mergeRecent(held, listOf(row("n", 50))).map { it.id })
    }

    @Test
    fun `an archived row leaves and an empty page changes nothing`() {
        val held = listOf(row("a", 30), row("b", 20))
        assertEquals(listOf("b"), mergeRecent(held, listOf(row("a", 31, archived = true))).map { it.id })
        assertEquals(held, mergeRecent(held, emptyList()))
    }

    @Test
    fun `a compacted chat replaces the row it continues instead of doubling`() {
        val held = listOf(row("a", 30), row("root", 20))
        val out = mergeRecent(held, listOf(row("tip", 40, lineage = listOf("root", "tip"))))
        assertEquals(listOf("tip", "a"), out.map { it.id })
    }
}
