package chat.keryx.core

import chat.keryx.core.model.SpokenStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The streaming reply under TalkBack (2.16): a sentence at a time, never a token at a time. */
class SpokenStreamTest {

    private val first = "The build finished in four minutes and every test passed. "
    private val second = "Two warnings remain in the parser, both about unused imports. "

    @Test
    fun `nothing is said before a sentence is finished`() {
        assertNull(SpokenStream.next("The build finished in four min", anchor = "", final = false))
    }

    @Test
    fun `a short first sentence waits for company`() {
        // "Sure." alone is a stutter; it goes out with the next sentence.
        assertNull(SpokenStream.next("Sure. Let me", anchor = "", final = false))
    }

    @Test
    fun `the finished sentences go out, the half sentence waits`() {
        val step = SpokenStream.next(first + "Two warn", anchor = "", final = false)!!
        assertEquals(first.trim(), step.say)
        // The next look resumes after what was said, and the half sentence is still not ready.
        assertNull(SpokenStream.next(first + "Two warn", step.anchor, final = false))
        val again = SpokenStream.next(first + second + "And", step.anchor, final = false)!!
        assertEquals(second.trim(), again.say)
    }

    @Test
    fun `the end of the turn flushes whatever is left`() {
        val step = SpokenStream.next(first + "Done", anchor = "", final = false)!!
        val last = SpokenStream.next(first + "Done", step.anchor, final = true)!!
        assertEquals("Done", last.say)
        assertNull(SpokenStream.next(first + "Done", last.anchor, final = true))
    }

    @Test
    fun `a short whole reply is still said when the turn ends`() {
        assertEquals("Yes.", SpokenStream.next("Yes.", anchor = "", final = true)?.say)
    }

    @Test
    fun `the anchor survives the tail window sliding`() {
        val step = SpokenStream.next(first + second, anchor = "", final = false)!!
        // The overlay dropped the first sentence off the front and prefixed its ellipsis.
        val slid = "… " + second + "Third sentence here, long enough to be worth saying aloud. "
        val next = SpokenStream.next(slid, step.anchor, final = false)!!
        assertEquals("Third sentence here, long enough to be worth saying aloud.", next.say)
    }

    @Test
    fun `an anchor the window slid past resumes at what is there`() {
        assertEquals(0, SpokenStream.resumeAt("entirely new text", "something said long ago"))
    }

    @Test
    fun `the gap follows the length of what was said`() {
        assertEquals(SpokenStream.MIN_GAP_MS, SpokenStream.gapMs("Short."))
        assertTrue(SpokenStream.gapMs("x".repeat(300)) >= 20_000L)
    }
}
