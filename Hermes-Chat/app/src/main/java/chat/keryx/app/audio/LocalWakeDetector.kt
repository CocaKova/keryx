package chat.keryx.app.audio

import android.content.Context
import chat.keryx.core.model.WakeEnergyGate
import chat.keryx.core.model.WakeFrameQueue
import chat.keryx.core.model.WakePipeline
import chat.keryx.core.model.WakeScoreGate
import chat.keryx.app.util.KLog
import java.util.concurrent.Executors

/**
 * The zero-network ear: [WakeMic] → exact 1280-sample frames → [LocalWakeEngine] on a
 * dedicated worker → [WakeScoreGate] (gateway's threshold / confirmation / cooldown) → [onWake].
 * Nothing touches the radio; no `wake.*` lease exists in this mode.
 *
 * Battery inside battery: the same energy gate the streaming feeder uses skips inference in a
 * silent room (the models still cost a few ms of CPU per frame; silence needs none). The
 * pipeline is fed the pre-roll frames the gate held back, so its mel history is continuous
 * across the gate opening — but NOT the silence before that; a long-closed gate is a reset,
 * which openWakeWord tolerates (its history seeds with ones anyway).
 */
class LocalWakeDetector(
    context: Context,
    private val onWake: () -> Unit,
    private val onFatal: (String) -> Unit,
) {
    private val engine: LocalWakeEngine? = LocalWakeEngine.load(context)
    val available: Boolean get() = engine != null

    private val frames = WakeFrameQueue(frameLength = WakePipeline.FRAME, maxQueued = 24, framesPerBatch = 1)
    private val lock = Any()
    private val energy = WakeEnergyGate()
    private val noiseFloor = NoiseFloor()
    private val decide = WakeScoreGate()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keryx-wake-infer").apply { isDaemon = true } }
    @Volatile private var stopped = true
    private var gateWasClosed = true
    @Volatile var maxScore = 0f
        private set

    private val mic = WakeMic(frameLength = WakePipeline.FRAME, onChunk = ::onChunk, onFatal = onFatal)

    fun start(): Boolean {
        val eng = engine ?: run { onFatal("on-device detector unavailable"); return false }
        if (!stopped) return true
        stopped = false
        eng.reset(); decide.reset(); energy.reset()
        return mic.start()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        mic.stop()
        synchronized(lock) { frames.clear() }
        KLog.i(TAG) { "local detector stopped (gated=${energy.skipped} maxScore=$maxScore)" }
    }

    fun close() { stop(); engine?.close(); worker.shutdownNow() }

    /** Mic thread: energy-gate, frame, hand to the inference worker. */
    private fun onChunk(at16k: ShortArray, n: Int) {
        if (stopped) return
        val rms = WakeEnergyGate.rms(at16k, n)
        noiseFloor.update(rms)
        val send = energy.offer(if (n == at16k.size) at16k else at16k.copyOf(n), rms, noiseFloor.startGate, noiseFloor.endGate)
        if (send.isEmpty()) { gateWasClosed = true; return }
        synchronized(lock) { send.forEach { frames.push(it, it.size) } }
        worker.execute(::drain)
    }

    private fun drain() {
        val eng = engine ?: return
        while (!stopped) {
            val frame = synchronized(lock) { frames.nextBatch() } ?: return
            if (gateWasClosed) {
                // Fresh utterance after silence: don't let a stale confirmation streak or
                // features from minutes ago vote on this one.
                decide.reset(); gateWasClosed = false
            }
            val score = runCatching { eng.process(frame) }.getOrNull() ?: continue
            if (score > maxScore) maxScore = score
            if (decide.offer(score, System.nanoTime() / 1_000_000)) {
                KLog.i(TAG) { "local wake fired score=$score" }
                onWake()
            }
        }
    }

    private companion object { const val TAG = "KeryxWake" }
}
