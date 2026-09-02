package chat.keryx.app.audio

import android.content.Context
import chat.keryx.core.model.WakePipeline
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * openWakeWord on the phone: LiteRT interpreters for the melspectrogram, Google's
 * speech-embedding, and hermes' `hey_hermes` classifier — the exact model files the gateway
 * runs (openwakeword `resources/models/` mel + embedding, hermes `tools/wakewords/hey_hermes.tflite`),
 * driven by the shared [WakePipeline] so the streaming math is identical to the reference.
 *
 * ~2.6 MB of models + a 4.3 MB runtime; one 80 ms frame costs a few ms of one core, so the
 * ear's price becomes "an open mic", not "an open radio". Not thread-safe: one caller
 * (the mic thread) at a time.
 */
class LocalWakeEngine private constructor(
    private val mel: Interpreter,
    private val embed: Interpreter,
    private val classifier: Interpreter,
) {
    private val melIn = direct(WakePipeline.MEL_INPUT * 4)
    private val melOut = direct(WakePipeline.MEL_NEW_ROWS * WakePipeline.MEL_BINS * 4)
    private val embIn = direct(WakePipeline.MEL_WINDOW * WakePipeline.MEL_BINS * 4)
    private val embOut = direct(WakePipeline.EMBED_DIM * 4)
    private val clsIn = direct(WakePipeline.FEATURE_WINDOW * WakePipeline.EMBED_DIM * 4)
    private val clsOut = direct(4)

    private val melScratch = FloatArray(WakePipeline.MEL_NEW_ROWS * WakePipeline.MEL_BINS)
    private val embScratch = FloatArray(WakePipeline.EMBED_DIM)

    val pipeline = WakePipeline(
        melspectrogram = { raw ->
            melIn.rewind(); melIn.asFloatBuffer().put(raw); melIn.rewind()
            melOut.rewind()
            mel.run(melIn, melOut)
            melOut.rewind(); melOut.asFloatBuffer().get(melScratch)
            melScratch
        },
        embed = { window ->
            embIn.rewind(); embIn.asFloatBuffer().put(window); embIn.rewind()
            embOut.rewind()
            embed.run(embIn, embOut)
            embOut.rewind(); embOut.asFloatBuffer().get(embScratch)
            embScratch
        },
        score = { feats ->
            clsIn.rewind(); clsIn.asFloatBuffer().put(feats); clsIn.rewind()
            clsOut.rewind()
            classifier.run(clsIn, clsOut)
            clsOut.rewind(); clsOut.asFloatBuffer().get(0)
        },
    )

    /** One 1280-sample frame → score, or null while warming up. */
    fun process(frame: ShortArray): Float? = pipeline.process(frame)

    fun reset() = pipeline.reset()

    fun close() {
        runCatching { mel.close() }; runCatching { embed.close() }; runCatching { classifier.close() }
    }

    companion object {
        private const val TAG = "KeryxWake"

        /** Load the three models from assets. Null when the runtime or a model is unusable —
         *  the controller then falls back to the gateway detector (desktop's way). */
        fun load(context: Context): LocalWakeEngine? = runCatching {
            val opts = Interpreter.Options().setNumThreads(1)
            val mel = Interpreter(asset(context, "wake/melspectrogram.tflite"), opts).apply {
                resizeInput(0, intArrayOf(1, WakePipeline.MEL_INPUT))
                allocateTensors()
            }
            val embed = Interpreter(asset(context, "wake/embedding_model.tflite"), opts).apply {
                resizeInput(0, intArrayOf(1, WakePipeline.MEL_WINDOW, WakePipeline.MEL_BINS, 1))
                allocateTensors()
            }
            val cls = Interpreter(asset(context, "wake/hey_hermes.tflite"), opts).apply { allocateTensors() }
            LocalWakeEngine(mel, embed, cls)
        }.onFailure { android.util.Log.w(TAG, "local wake engine unavailable: ${it.message}") }.getOrNull()

        private fun asset(context: Context, path: String): ByteBuffer {
            val bytes = context.assets.open(path).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
