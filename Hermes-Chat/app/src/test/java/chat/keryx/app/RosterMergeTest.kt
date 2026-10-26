package chat.keryx.app

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.mergeRecent
import chat.keryx.app.transport.direct.unlistedPending
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
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

    private fun placeholder(id: String) = RoomProfile(id = id, name = "New session", type = RoomType.DIRECT_MESSAGE)

    @Test
    fun `a placeholder stays until its chat is listed`() {
        val pending = listOf(placeholder("p"))
        assertEquals(listOf("p"), unlistedPending(pending, setOf("a"), { it }).map { it.id })
        assertEquals(emptyList<String>(), unlistedPending(pending, setOf("p"), { it }).map { it.id })
    }

    @Test
    fun `a placeholder whose chat compacted to a new id does not come back as a second row`() {
        // The phone minted "p"; compaction moved it to "tip", and the list now names only the tip.
        val forward = mapOf("p" to "tip")
        val out = unlistedPending(listOf(placeholder("p"), placeholder("q")), setOf("tip")) { forward[it] ?: it }
        assertEquals(listOf("q"), out.map { it.id })
    }
}
