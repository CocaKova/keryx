package chat.keryx.app

import chat.keryx.app.data.archive.ArchiveStore
import chat.keryx.app.presentation.ui.components.snippetRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveQueryTest {

    @Test
    fun `blank input builds no query`() {
        assertNull(ArchiveStore.buildMatchQuery(""))
        assertNull(ArchiveStore.buildMatchQuery("   "))
    }

    @Test
    fun `single word becomes a prefix query`() {
        assertEquals("\"palworld*\"", ArchiveStore.buildMatchQuery("palworld"))
    }

    @Test
    fun `only the last word gets the prefix star`() {
        assertEquals("\"server\" \"boot*\"", ArchiveStore.buildMatchQuery("server boot"))
    }

    @Test
    fun `quotes in input cannot break the match expression`() {
        assertEquals("\"say*\"", ArchiveStore.buildMatchQuery("\"say\""))
    }

    @Test
    fun `extra whitespace is collapsed`() {
        assertEquals("\"a\" \"b*\"", ArchiveStore.buildMatchQuery("  a   b  "))
    }

    @Test
    fun `snippet markers become highlight ranges`() {
        val (plain, ranges) = snippetRanges("…the ⟪palworld⟫ server is ⟪up⟫…")
        assertEquals("…the palworld server is up…", plain)
        assertEquals(2, ranges.size)
        assertEquals("palworld", plain.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("up", plain.substring(ranges[1].first, ranges[1].last + 1))
    }

    @Test
    fun `snippet without markers passes through`() {
        val (plain, ranges) = snippetRanges("no markers here")
        assertEquals("no markers here", plain)
        assertEquals(0, ranges.size)
    }

    @Test
    fun `unbalanced end marker is ignored`() {
        val (plain, ranges) = snippetRanges("odd⟫ text")
        assertEquals("odd text", plain)
        assertEquals(0, ranges.size)
    }
}
