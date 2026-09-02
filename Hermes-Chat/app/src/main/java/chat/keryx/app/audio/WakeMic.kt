package chat.keryx.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import chat.keryx.core.model.WakePcm
import chat.keryx.core.model.WakeProtocol
import chat.keryx.app.util.KLog
import kotlin.math.max

/**
 * The ear's microphone: one AudioRecord reader thread delivering ~80 ms chunks of 16 kHz
 * int16 mono to [onChunk] (on the reader thread — keep it cheap or hand off).
 *
 * Opens at 16 kHz natively when the device allows (nearly all do) and falls back to 48/44.1 kHz
 * + box-average downsample otherwise — the only rates Android guarantees. Shared by both ear
 * modes: the gateway feeder (stream PCM) and the local detector (score PCM on the phone).
 */
class WakeMic(
    private val frameLength: Int = WakeProtocol.DEFAULT_FRAME,
    private val onChunk: (samples: ShortArray, n: Int) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    @Volatile private var reader: Thread? = null
    @Volatile private var stopped = false

    val active: Boolean get() = !stopped && reader?.isAlive == true

    @SuppressLint("MissingPermission") // the controller gates on RECORD_AUDIO before arming
    fun start(): Boolean {
        if (reader != null) return true
        val opened = openRecorder() ?: run { onFatal("microphone unavailable"); return false }
        val (recorder, rate) = opened
        stopped = false
        reader = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // Native-rate chunk that maps to exactly one 16 kHz frame.
            val chunk = ShortArray(max(frameLength, frameLength * rate / WakeProtocol.SAMPLE_RATE))
            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onFatal("microphone busy")
                    return@Thread
                }
                while (!stopped) {
                    val n = recorder.read(chunk, 0, chunk.size)
                    if (n <= 0) {
                        if (n < 0) { onFatal("microphone read error $n"); break }
                        continue
                    }
                    val at16k = WakePcm.downsampleTo16k(chunk, n, rate)
                    onChunk(at16k, at16k.size)
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }, "keryx-wake-mic").also { it.isDaemon = true; it.start() }
        KLog.i(TAG) { "mic open rate=$rate frame=$frameLength" }
        return true
    }

    fun stop() {
        if (stopped && reader == null) return
        stopped = true
        reader?.let { t -> runCatching { t.join(300) } } // ≤ one blocking read; may run on Main
        reader = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(WakeProtocol.SAMPLE_RATE, 48_000, 44_100)) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val rec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, rate * 2 /* one second */),
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            runCatching { rec.release() }
        }
        return null
    }

    private companion object { const val TAG = "KeryxWake" }
}
