package chat.keryx.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPaceTest {
    @Test
    fun `starts from the measured middle with headroom`() {
        val pace = SpeechPace()
        assertEquals((100 * 65.0 * SpeechPace.HEADROOM).toLong(), pace.expectedMs(100))
        assertEquals(0L, pace.expectedMs(0))
    }

    @Test
    fun `learns toward the voice actually heard`() {
        val pace = SpeechPace()
        // The 09-05 call: 145 chars → 11 920 ms (82 ms/char).
        pace.learn(145, 11_920)
        assertTrue(pace.msPerChar > 65.0)
        assertTrue(pace.msPerChar < 82.0)
        pace.learn(145, 11_920)
        pace.learn(145, 11_920)
        assertTrue("converges: ${pace.msPerChar}", pace.msPerChar > 76.0)
    }

    @Test
    fun `a runaway rendering and a one-word onset are not pace`() {
        val pace = SpeechPace()
        pace.learn(156, 32_200) // the model failed to stop
        assertEquals(65.0, pace.msPerChar, 0.0)
        pace.learn(4, 900)      // "Yes." — onset, not pace
        assertEquals(65.0, pace.msPerChar, 0.0)
        pace.learn(50, 0)
        assertEquals(65.0, pace.msPerChar, 0.0)
    }
}
