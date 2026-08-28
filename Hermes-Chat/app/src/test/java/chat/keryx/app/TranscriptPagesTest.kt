package chat.keryx.app

import chat.keryx.app.transport.direct.TranscriptPages
import chat.keryx.core.protocol.MessageRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPagesTest {

    private fun row(id: Long) = MessageRow(id, "assistant", "m$id", null, id * 1000, null)

    /** A fake gateway: [total] rows, ids 1..total, paged newest-first by offset, each page
     *  chronological — the endpoint's real shape. */
    private fun gateway(total: Int, pageSize: Int): suspend (Int) -> List<MessageRow> = { offset ->
        val newest = total - offset
        val oldest = (newest - pageSize + 1).coerceAtLeast(1)
        if (newest < 1) emptyList() else (oldest..newest).map { row(it.toLong()) }
    }

    @Test
    fun `stitches pages oldest first and reports exhaustion`() = runTest {
        val walk = TranscriptPages.pageUntil(pageSize = 4, fetch = gateway(10, 4), enough = { false })
        assertEquals((1L..10L).toList(), walk.rows.map { it.id })
        assertTrue(walk.exhausted)
    }

    @Test
    fun `stops at the page boundary once enough is in hand`() = runTest {
        var calls = 0
        val fetch = gateway(100, 10)
        val walk = TranscriptPages.pageUntil(
            pageSize = 10,
            fetch = { calls++; fetch(it) },
            enough = { rows -> rows.first().id <= 85 },
        )
        assertEquals(2, calls)
        assertEquals((81L..100L).toList(), walk.rows.map { it.id })
        assertFalse(walk.exhausted)
    }

    @Test
    fun `an exact page count still ends on the empty page`() = runTest {
        val walk = TranscriptPages.pageUntil(pageSize = 5, fetch = gateway(10, 5), enough = { false })
        assertEquals(10, walk.rows.size)
        assertTrue(walk.exhausted)
    }

    @Test
    fun `a row seen twice across a moving seam is filed once`() = runTest {
        // A new row lands between two fetches: the second page overlaps the first by one.
        val pages = listOf(listOf(row(8), row(9), row(10)), listOf(row(6), row(7), row(8)), listOf(row(5)))
        var i = 0
        val walk = TranscriptPages.pageUntil(pageSize = 3, fetch = { pages[i++] }, enough = { false })
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L), walk.rows.map { it.id })
    }

    @Test
    fun `row id is read off every client id shape`() {
        assertEquals(42L, TranscriptPages.rowIdOf("42"))
        assertEquals(42L, TranscriptPages.rowIdOf("tools-42"))
        assertEquals(42L, TranscriptPages.rowIdOf("think-42"))
        assertNull(TranscriptPages.rowIdOf("live-abc"))
        assertNull(TranscriptPages.rowIdOf("\$matrixEvent"))
    }

    @Test
    fun `window clamps at both ends and is empty off the list`() {
        val items = (0..9).toList()
        assertEquals(listOf(0, 1, 2), TranscriptPages.window(items, 0, 5, 2))
        assertEquals(listOf(7, 8, 9), TranscriptPages.window(items, 9, 2, 5))
        assertEquals(listOf(3, 4, 5, 6, 7), TranscriptPages.window(items, 5, 2, 2))
        assertTrue(TranscriptPages.window(items, -1, 2, 2).isEmpty())
        assertTrue(TranscriptPages.window(items, 10, 2, 2).isEmpty())
    }
}
