package chat.keryx.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * The "I heard you" chime played the instant `wake.detected` lands, BEFORE the voice loop
 * opens the mic (desktop `playWakeSound`). Synthesized, not an asset: two short rising
 * sines with a soft envelope — audible confirmation with the screen off, and no file to
 * ship. Fire-and-forget on a daemon thread; a device that refuses the track just stays quiet.
 */
object WakeChime {
    private const val RATE = 24_000

    fun play() {
        Thread({
            runCatching {
                val pcm = render()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep((pcm.size * 1000L / RATE) + 60)
                } finally {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                }
            }
        }, "keryx-wake-chime").apply { isDaemon = true }.start()
    }

    /** Two notes (E5 → B5), 110 ms each with a 20 ms crossfade, ~10 ms attack / 40 ms release. */
    internal fun render(): ShortArray {
        val note = (RATE * 0.11).toInt()
        val total = note * 2
        val out = ShortArray(total)
        fun tone(freq: Double, start: Int) {
            for (i in 0 until note) {
                val t = i.toDouble() / RATE
                val attack = (i / (RATE * 0.010)).coerceAtMost(1.0)
                val release = ((note - i) / (RATE * 0.040)).coerceAtMost(1.0)
                val env = attack * release * 0.35
                val s = sin(2 * PI * freq * t) * env
                val idx = start + i
                out[idx] = (out[idx] + (s * 32767).toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        tone(659.25, 0)
        tone(987.77, note - (RATE * 0.020).toInt())
        return out
    }
}
