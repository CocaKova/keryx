package chat.keryx.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Push-to-talk microphone capture for composer dictation: one AAC/m4a file per
 * take in the app cache, 16 kHz mono — small on the wire and exactly what ASR
 * models want. The caller owns the returned file (and deletes it after upload).
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Begins capture; throws if the mic is unavailable (caller shows the error). */
    fun start() {
        stopQuietly()
        val out = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        recorder = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
        ).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(out.absolutePath)
            prepare()
            start()
        }
        file = out
    }

    /** Ends capture and hands over the take, or null when nothing usable was recorded. */
    fun stop(): File? {
        val out = file
        stopQuietly()
        // MediaRecorder writes a valid header even for near-instant taps; drop empty takes.
        return out?.takeIf { it.exists() && it.length() > 0 }
    }

    /** Aborts and discards the current take. */
    fun cancel() {
        val out = file
        stopQuietly()
        out?.delete()
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file = null
    }
}
