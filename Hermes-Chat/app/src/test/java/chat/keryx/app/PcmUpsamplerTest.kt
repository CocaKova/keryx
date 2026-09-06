package chat.keryx.app

import chat.keryx.app.audio.PcmUpsampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmUpsamplerTest {
    private fun pcm(vararg s: Int): ByteArray {
        val b = ByteArray(s.size * 2)
        s.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
        return b
    }
    private fun samples(b: ByteArray): IntArray =
        IntArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)) }

    @Test
    fun `doubles the rate, duplicates to stereo, interpolates between samples`() {
        val up = PcmUpsampler(24_000, 48_000)
        val out = samples(up.convert(pcm(0, 100, -100), 0, 6))
        // per input sample: 2 output frames × 2 channels
        assertEquals(12, out.size)
        // first sample has no predecessor: both frames land on 0
        assertArrayEquals(intArrayOf(0, 0, 0, 0), out.copyOfRange(0, 4))
        // 0 → 100: midpoint 50 then 100, left == right
        assertArrayEquals(intArrayOf(50, 50, 100, 100), out.copyOfRange(4, 8))
        // 100 → -100: midpoint 0 then -100
        assertArrayEquals(intArrayOf(0, 0, -100, -100), out.copyOfRange(8, 12))
    }

    @Test
    fun `a sample split across two chunks is reassembled`() {
        val up = PcmUpsampler(24_000, 48_000)
        val whole = pcm(1000, 2000)
        val a = up.convert(whole, 0, 3) // one sample + a dangling low byte
        val b = up.convert(whole, 3, 1) // the high byte completes the second sample
        val out = samples(a + b)
        assertArrayEquals(intArrayOf(1000, 1000, 1000, 1000, 1500, 1500, 2000, 2000), out)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, PcmUpsampler(24_000, 48_000).convert(ByteArray(0), 0, 0).size)
    }
}
