package chat.keryx.app

import chat.keryx.app.presentation.CallSentenceChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Call's pacing heart: streamed deltas must come back out as speakable sentences —
 * early enough to feel live, never split mid-decimal or mid-code-fence.
 */
class CallSentenceChunkerTest {

    @Test
    fun sentencesEmergeAsDeltasArrive() {
        val c = CallSentenceChunker()
        val out = mutableListOf<String>()
        listOf("The gateway is heal", "thy and running. Every", " platform is connected. Sho", "uld I check the jobs too?")
            .forEach { out += c.feed(it) }
        out += listOfNotNull(c.flush())
        assertEquals(
            listOf(
                "The gateway is healthy and running.",
                "Every platform is connected.",
                "Should I check the jobs too?",
            ),
            out,
        )
    }

    @Test
    fun decimalsAndVersionsNeverSplit() {
        val c = CallSentenceChunker()
        val out = c.feed("Keryx v1.19 shipped with R8 enabled today. Memory sits at 242.5 megabytes now. ").toMutableList()
        out += listOfNotNull(c.flush())
        assertEquals(
            listOf(
                "Keryx v1.19 shipped with R8 enabled today.",
                "Memory sits at 242.5 megabytes now.",
            ),
            out,
        )
    }

    @Test
    fun shortFragmentsWaitForMoreText() {
        val c = CallSentenceChunker(minChars = 24)
        // "Yes." alone is under the floor — it must ride with what follows, not staccato out.
        assertTrue(c.feed("Yes. ").isEmpty())
        // A buffer-final "." also waits: the next delta could reveal it was "1." mid-decimal.
        assertTrue(c.feed("The tests are green and the build is on your phone already.").isEmpty())
        val rest = c.feed(" Enjoy.")
        assertTrue(rest.isNotEmpty())
        assertTrue(rest.first().startsWith("Yes. The tests"))
        assertEquals("Enjoy.", c.flush())
    }

    @Test
    fun codeFencesAreOneUnit() {
        val c = CallSentenceChunker()
        val out = mutableListOf<String>()
        out += c.feed("Here is the fix. It goes like this:\n```\nval x = 1. Also a fake? Boundary!\n")
        out += c.feed("```\nDone. Try running it now, then tell me what broke.")
        out += listOfNotNull(c.flush())
        // Nothing inside the fence split, and prose before/after still did.
        assertTrue(out.first().startsWith("Here is the fix."))
        val fenced = out.first { it.contains("```") }
        assertTrue(fenced.contains("Also a fake? Boundary!"))
        assertTrue(out.last().contains("tell me what broke"))
    }

    @Test
    fun newlinesEndSentences() {
        val c = CallSentenceChunker()
        val out = c.feed("First point about the deploy pipeline\nSecond point that is still coming")
        assertEquals(listOf("First point about the deploy pipeline"), out)
        assertEquals("Second point that is still coming", c.flush())
    }

    @Test
    fun flushOnEmptyIsNull() {
        val c = CallSentenceChunker()
        c.feed("A complete sentence that certainly ends here. ").let { assertEquals(1, it.size) }
        assertNull(c.flush())
    }
}
