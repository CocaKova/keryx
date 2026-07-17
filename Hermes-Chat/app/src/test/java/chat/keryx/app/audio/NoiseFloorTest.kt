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
        // floor ≈ 100 → gate = max(350, 250)
        assertEquals(350.0, floor.startGate, 10.0)
    }

    @Test
    fun `absolute minimum gate holds in a silent room`() {
        val floor = NoiseFloor()
        floor.feed(10.0, 50)
        assertEquals(250.0, floor.startGate, 0.0)
        assertEquals(140.0, floor.endGate, 0.0)
    }

    /** The v1.22.0 silent-second-turn bug: the mic reopens the moment the agent stops talking,
     *  while the user is already mid-reply. Their speech must not become the "noise floor" —
     *  the gate has to stay where the quiet room put it, so the reply itself opens it. */
    @Test
    fun `speech at capture start never raises its own gate`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)          // capture 1: quiet room learned
        val gateBefore = floor.startGate
        floor.feed(3000.0, 40)         // capture 2 opens onto 1.2 s of ongoing reply
        assertEquals(gateBefore, floor.startGate, 1.0)
        assertTrue("reply must clear the gate", 3000.0 > floor.startGate)
    }

    @Test
    fun `loud seed frame recovers fast once the room goes quiet`() {
        val floor = NoiseFloor()
        floor.update(3000.0)           // call opened on a tap thud / first syllable
        floor.feed(100.0, 12)
        assertTrue("gate should be usable again, was ${floor.startGate}", floor.startGate < 500.0)
    }

    @Test
    fun `sustained louder ambience raises the floor slowly`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)
        val gateBefore = floor.startGate
        floor.feed(180.0, 300)         // dishwasher starts: ~9 s below the rise ceiling
        assertTrue(floor.startGate > gateBefore)
        assertEquals(180.0 * 3.5, floor.startGate, 40.0)
    }

    @Test
    fun `ambiguous mid-band frames teach the floor nothing`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)
        val gateBefore = floor.startGate
        floor.feed(250.0, 300)         // 2.5× floor: soft speech / breath territory
        assertEquals(gateBefore, floor.startGate, 1.0)
    }
}
