package chat.keryx.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The Call's ears: open-mic utterance capture with energy-based voice activity detection.
 *
 * [captureUtterance] listens until the speaker starts talking, keeps recording until ~a second
 * of trailing silence, and returns the take as a 16 kHz mono WAV ready for the STT endpoint —
 * or null for a non-take (too short to be speech, or the call was cancelled/muted mid-listen).
 * A small pre-roll ring keeps the first syllable: the gate opens *because* of it, so without
 * the ring every utterance would start clipped.
 *
 * The gate is adaptive: a call-long [NoiseFloor] tracks the room and the thresholds ride above
 * it, so a quiet bedroom and a running dishwasher both work without a sensitivity setting.
 * [level] publishes a smoothed 0..1 mic level for the UI orb.
 */
class CallAudio {

    /** Smoothed mic level (0..1, log-scaled) — drives the listening orb's pulse. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Call-long room-noise estimate; see [NoiseFloor] for why it must outlive one capture. */
    private val noiseFloor = NoiseFloor()

    @SuppressLint("MissingPermission") // callers gate on RECORD_AUDIO before starting the call
    suspend fun captureUtterance(context: Context): File? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING,
            max(minBuf, FRAME_SAMPLES * 8),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext null
        }
        val frame = ShortArray(FRAME_SAMPLES)
        val preroll = ArrayDeque<ShortArray>()
        val voiced = ArrayList<ShortArray>()
        var speechStarted = false
        var speechFrames = 0
        var trailingSilence = 0
        var totalFrames = 0
        try {
            recorder.startRecording()
            while (coroutineContext.isActive) {
                val n = recorder.read(frame, 0, FRAME_SAMPLES)
                if (n <= 0) break
                totalFrames++
                if (totalFrames > MAX_FRAMES) break
                val rms = rms(frame, n)
                _level.value = smoothLevel(_level.value, rms)

                noiseFloor.update(rms, inSpeech = speechStarted)
                val startGate = noiseFloor.startGate
                val endGate = noiseFloor.endGate

                if (!speechStarted) {
                    preroll.addLast(frame.copyOf(n))
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    if (rms > startGate) {
                        speechFrames++
                        if (speechFrames >= START_CONFIRM_FRAMES) {
                            speechStarted = true
                            voiced += preroll
                            preroll.clear()
                            chat.keryx.app.util.KLog.i("KeryxCallVad") {
                                "speech open: rms=${rms.toInt()} startGate=${startGate.toInt()} endGate=${endGate.toInt()} at=${totalFrames * 30}ms"
                            }
                        }
                    } else {
                        speechFrames = 0
                    }
                } else {
                    voiced += frame.copyOf(n)
                    if (rms < endGate) {
                        trailingSilence++
                        if (trailingSilence >= END_SILENCE_FRAMES) break
                    } else {
                        trailingSilence = 0
                        speechFrames++
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            _level.value = 0f
        }
        // A take is real speech only if the voiced stretch outlasts a door slam.
        chat.keryx.app.util.KLog.i("KeryxCallVad") {
            "capture end: started=$speechStarted speechFrames=$speechFrames trailingSilence=$trailingSilence " +
                "frames=$totalFrames startGate=${noiseFloor.startGate.toInt()} endGate=${noiseFloor.endGate.toInt()}"
        }
        if (!speechStarted || speechFrames < MIN_SPEECH_FRAMES) return@withContext null
        val wav = File(context.cacheDir, "call_${System.currentTimeMillis()}.wav")
        writeWav(wav, voiced)
        wav
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            val s = frame[i].toDouble()
            acc += s * s
        }
        return sqrt(acc / n)
    }

    private fun smoothLevel(prev: Float, rms: Double): Float {
        // Perceptual-ish: ~200 RMS is a quiet room, ~8000 is close speech.
        val db = 20 * log10(max(1.0, rms))
        val norm = ((db - 40) / 40).coerceIn(0.0, 1.0).toFloat()
        return prev * 0.6f + norm * 0.4f
    }

    private fun writeWav(out: File, frames: List<ShortArray>) {
        val totalSamples = frames.sumOf { it.size }
        val dataBytes = totalSamples * 2
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            f.write("RIFF".toByteArray())
            f.writeIntLe(36 + dataBytes)
            f.write("WAVEfmt ".toByteArray())
            f.writeIntLe(16)            // PCM chunk size
            f.writeShortLe(1)           // PCM
            f.writeShortLe(1)           // mono
            f.writeIntLe(SAMPLE_RATE)
            f.writeIntLe(SAMPLE_RATE * 2) // byte rate
            f.writeShortLe(2)           // block align
            f.writeShortLe(16)          // bits per sample
            f.write("data".toByteArray())
            f.writeIntLe(dataBytes)
            val bytes = ByteArray(FRAME_SAMPLES * 2)
            frames.forEach { fr ->
                var bi = 0
                fr.forEach { s ->
                    bytes[bi++] = (s.toInt() and 0xFF).toByte()
                    bytes[bi++] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                f.write(bytes, 0, bi)
            }
        }
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
    }

    private fun RandomAccessFile.writeShortLe(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SAMPLES = 480               // 30 ms
        const val PREROLL_FRAMES = 10               // 300 ms kept from before the gate opened
        const val START_CONFIRM_FRAMES = 3          // 90 ms above gate = speech, not a click
        const val END_SILENCE_FRAMES = 30           // 900 ms of quiet ends the utterance
        const val MIN_SPEECH_FRAMES = 12            // < 360 ms voiced = noise, discard
        const val MAX_FRAMES = 45 * 1000 / 30       // hard cap: 45 s per utterance
    }
}
