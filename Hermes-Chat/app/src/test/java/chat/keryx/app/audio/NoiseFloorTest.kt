package chat.keryx.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFloorTest {

    private fun NoiseFloor.feed(rms: Double, frames: Int) = repeat(frames) { update(rms) }

    @Test
    fun `quiet room settles to a low start gate`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)
        assertEquals(350.0, floor.startGate, 1.0)
        assertEquals(180.0, floor.endGate, 1.0)
    }

    @Test
    fun `absolute minimum gates hold in a silent room`() {
        val floor = NoiseFloor()
        floor.feed(10.0, 50)
        assertEquals(250.0, floor.startGate, 0.0)
        assertEquals(140.0, floor.endGate, 0.0)
    }

    /** The v1.22.0 deaf-high bug: the mic reopens the moment the agent stops talking, while the
     *  user is already mid-reply. Their speech must not become the "noise floor" — the quiet
     *  frames still in the window keep the gate where the room put it. */
    @Test
    fun `speech at capture start never raises its own gate`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)          // room learned before the agent's turn
        val gateBefore = floor.startGate
        floor.feed(3000.0, 40)         // capture reopens onto 1.2 s of ongoing reply
        assertEquals(gateBefore, floor.startGate, 1.0)
        assertTrue("reply must clear the gate", 3000.0 > floor.startGate)
    }

    /** The v1.22.1/2 deaf-low bug: near-zero DSP ramp-in frames must not pin the floor under
     *  the room's real ambience forever — they age out of the window and the gates recover,
     *  so an utterance that opened on ambience can still reach its end-silence. */
    @Test
    fun `ramp-in zeros age out and the end gate rises above real ambience`() {
        val floor = NoiseFloor()
        floor.feed(0.0, 3)             // hardware ramp-in
        floor.feed(400.0, 100)         // fan-loud room for one window length
        assertEquals(400.0 * 1.8, floor.endGate, 1.0)
        assertTrue("ambience must read as trailing silence", 400.0 < floor.endGate)
    }

    @Test
    fun `floor seeded entirely on speech recovers as soon as quiet returns`() {
        val floor = NoiseFloor()
        floor.feed(3000.0, 50)         // call opened mid-sentence
        floor.update(100.0)            // first quiet frame
        assertEquals(350.0, floor.startGate, 1.0)
    }

    /** A long reply only lifts the floor as high as its own inter-word dips, so the gate stays
     *  under speech peaks even after the pre-speech quiet has left the window. */
    @Test
    fun `long speech with word gaps keeps the gate below speech peaks`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 100)
        repeat(40) {                   // ~6 s of words with 3×-room inter-word dips
            floor.feed(3000.0, 4)
            floor.update(300.0)
        }
        assertTrue("gate ${floor.startGate} must stay below speech", floor.startGate < 3000.0)
        assertEquals(300.0 * 3.5, floor.startGate, 1.0)
    }
}
