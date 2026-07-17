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
        floor.feed(300.0, 300)         // dishwasher starts: ~9 s of 3× ambience, below the gate
        assertTrue(floor.startGate > gateBefore)
        assertEquals(300.0 * 3.5, floor.startGate, 80.0)
    }

    /** The v1.22.1 no-turns-at-all bug: AudioRecord's first frame is often near-zero DSP
     *  ramp-in. A floor-relative rise ceiling could never climb out of that seed, pinning the
     *  end gate below the room's ambience — no utterance ever ended. Sub-gate ambience must
     *  always be able to lift the floor, from any seed. */
    @Test
    fun `near-zero seed climbs back to the room's real ambience`() {
        val floor = NoiseFloor()
        floor.update(0.0)              // DSP ramp-in frame seeds the floor
        floor.feed(120.0, 200)         // 6 s of ordinary room noise
        assertEquals(120.0 * 1.8, floor.endGate, 15.0)
        assertEquals(120.0 * 3.5, floor.startGate, 30.0)
    }

    @Test
    fun `confirmed utterance frames teach the floor nothing`() {
        val floor = NoiseFloor()
        floor.feed(100.0, 50)
        val gateBefore = floor.startGate
        repeat(300) { floor.update(300.0, inSpeech = true) }   // 9 s of mid-level speech body
        assertEquals(gateBefore, floor.startGate, 1.0)
    }
}
