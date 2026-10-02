package chat.keryx.app

import chat.keryx.app.presentation.KeryxNotice
import chat.keryx.app.presentation.KeryxNoticeQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notice line (2.16): which notices are shown, in what order, and that the app no longer
 * reaches past it for a system Toast.
 */
class KeryxNoticeTest {

    private val noop: () -> Unit = {}
    private fun plain(t: String) = KeryxNotice(t)
    private fun undo(t: String) = KeryxNotice.undo(t, noop)

    @Test
    fun `plain notices queue in order behind the one on screen`() {
        val d = KeryxNoticeQueue.admit(plain("a"), listOf(plain("b")), plain("c"))
        assertFalse(d.supersede)
        assertEquals(listOf("b", "c"), d.pending.map { it.text })
    }

    @Test
    fun `an echo of the last notice is dropped`() {
        // A retry loop that fails the same way says so once, not ten times.
        val onScreen = KeryxNoticeQueue.admit(plain("Link failed"), emptyList(), plain("Link failed"))
        assertTrue(onScreen.dropped)
        val waiting = KeryxNoticeQueue.admit(plain("x"), listOf(plain("Link failed")), plain("Link failed"))
        assertTrue(waiting.dropped)
        // The same words after something else are news again.
        val later = KeryxNoticeQueue.admit(plain("Link failed"), listOf(plain("other")), plain("Link failed"))
        assertFalse(later.dropped)
    }

    @Test
    fun `an Undo is never kept waiting`() {
        // Archive two sessions in a row: the second Undo is on screen now, not after eight seconds.
        val d = KeryxNoticeQueue.admit(undo("Archived A"), listOf(plain("later")), undo("Archived B"))
        assertTrue(d.supersede)
        assertEquals(listOf("Archived B", "later"), d.pending.map { it.text })
    }

    @Test
    fun `an Undo with nothing on screen goes to the front without cutting anything`() {
        val d = KeryxNoticeQueue.admit(null, listOf(plain("waiting")), undo("Pinned"))
        assertFalse(d.supersede)
        assertEquals("Pinned", d.pending.first().text)
    }

    @Test
    fun `a repeated Undo replaces the older offer instead of echoing it`() {
        val d = KeryxNoticeQueue.admit(plain("x"), listOf(undo("Unpinned")), undo("Unpinned"))
        assertEquals(1, d.pending.count { it.text == "Unpinned" })
    }

    @Test
    fun `the queue stays short and drops plain notices before actions`() {
        val full = listOf(undo("u1"), plain("p1"), plain("p2"))
        val d = KeryxNoticeQueue.admit(plain("now"), full, plain("p3"))
        assertEquals(KeryxNoticeQueue.MAX_PENDING, d.pending.size)
        assertEquals(listOf("u1", "p2", "p3"), d.pending.map { it.text })
        // An incoming Undo that jumps the queue is never the one trimmed away.
        val allActions = listOf(undo("a1"), undo("a2"), undo("a3"))
        val j = KeryxNoticeQueue.admit(plain("now"), allActions, undo("new"))
        assertEquals("new", j.pending.first().text)
        assertEquals(KeryxNoticeQueue.MAX_PENDING, j.pending.size)
    }

    @Test
    fun `blank notices say nothing`() {
        assertTrue(KeryxNoticeQueue.admit(null, emptyList(), plain("  ")).dropped)
    }

    @Test
    fun `an action needs both a label and something to do`() {
        assertTrue(undo("x").hasAction)
        assertEquals(KeryxNotice.UNDO, undo("x").actionLabel)
        assertFalse(KeryxNotice("x", actionLabel = "Undo").hasAction)
        assertFalse(KeryxNotice("x", actionLabel = " ", onAction = noop).hasAction)
        assertTrue(KeryxNotice.durationMillis(undo("x")) > KeryxNotice.durationMillis(plain("x")))
    }

    @Test
    fun `no screen reaches for a system Toast`() {
        // 2.16 moved every in-app message onto the themed notice line. Two activities keep the
        // Toast on purpose: they finish() right after the message, and only a Toast outlives
        // the window that raised it. KeryxSnack's own last-resort fallback is the third.
        val here = File(System.getProperty("user.dir") ?: ".")
        val root = listOf(File(here, "src/main/java"), File(here, "app/src/main/java"))
            .first { it.isDirectory }
        val allowed = setOf("ShareActivity.kt", "NoteActivity.kt", "KeryxSnack.kt")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if ("Toast.makeText" in line) "${f.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue(
            "Use viewModel.toast(…) or LocalKeryxSnack.current.show(…):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
