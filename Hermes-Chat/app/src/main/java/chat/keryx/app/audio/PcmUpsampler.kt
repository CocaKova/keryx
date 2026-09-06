package chat.keryx.app.audio

/**
 * 16-bit mono PCM at [inRate] → 16-bit stereo PCM at [outRate] (an integer multiple), linear
 * interpolation between input samples, left = right. Pure Kotlin so it is unit-tested.
 *
 * Why this exists: the phone's audio server misbehaved on a 24 kHz mono track (it stopped taking
 * data after the first underrun), while every music app feeds it 48 kHz stereo and is fine. So
 * the voice is converted to that ordinary shape before it reaches the track.
 */
class PcmUpsampler(private val inRate: Int, private val outRate: Int) {
    init {
        require(outRate % inRate == 0 && outRate >= inRate) { "outRate must be a multiple of inRate" }
    }
    private val factor = outRate / inRate
    private var carry: Int? = null // last input sample, to interpolate across chunk boundaries
    private var odd: Byte? = null // a dangling low byte when a chunk splits a sample

    /** Convert one chunk of little-endian mono 16-bit PCM. Output is little-endian stereo. */
    fun convert(input: ByteArray, off: Int, len: Int): ByteArray {
        var i = off
        val end = off + len
        // samples available this call
        val pending = odd
        var count = 0
        val samples = IntArray((len + 1) / 2 + 1)
        if (pending != null && i < end) {
            samples[count++] = (pending.toInt() and 0xFF) or (input[i].toInt() shl 8)
            i++
            odd = null
        }
        while (i + 1 < end) {
            samples[count++] = (input[i].toInt() and 0xFF) or (input[i + 1].toInt() shl 8)
            i += 2
        }
        if (i < end) odd = input[i]
        if (count == 0) return ByteArray(0)

        val out = ByteArray(count * factor * 4)
        var o = 0
        var prev = carry ?: samples[0]
        for (k in 0 until count) {
            val cur = samples[k]
            for (f in 0 until factor) {
                // interpolate from prev toward cur; f == factor-1 lands on cur exactly
                val v = prev + (cur - prev) * (f + 1) / factor
                val lo = (v and 0xFF).toByte(); val hi = ((v shr 8) and 0xFF).toByte()
                out[o++] = lo; out[o++] = hi; out[o++] = lo; out[o++] = hi
            }
            prev = cur
        }
        carry = prev
        return out
    }
}
