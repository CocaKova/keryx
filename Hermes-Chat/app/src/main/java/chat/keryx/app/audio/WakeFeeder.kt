package chat.keryx.app.audio

import android.util.Base64
import chat.keryx.core.model.WakeFrameQueue
import chat.keryx.core.model.WakePcm
import chat.keryx.core.model.WakeProtocol
import chat.keryx.app.util.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The ear's wire: open mic → 16 kHz int16 frames → coalesced `wake.feed` RPCs, for as long as
 * the gateway-side detector is armed in client-capture mode (protocol §5.4; desktop's
 * `lib/wake-client-capture.ts`, frame for frame).
 *
 * Two threads, one bounded queue: the [WakeMic] reader pushes into a [WakeFrameQueue]
 * (drop-oldest under latency, so the detector always sees RECENT contiguous audio), and one
 * drain coroutine sends batches sequentially — never more than one `wake.feed` in flight, so
 * frames arrive in order and a slow link degrades to dropped-oldest rather than a pile-up.
 * A failed RPC is logged and the drain carries on: one lost feed must not freeze the ear.
 *
 * This is the GATEWAY-detector mode (desktop's way). The zero-network mode is [LocalWakeDetector].
 */
class WakeFeeder(
    private val frameLength: Int,
    private val request: suspend (method: String, params: JsonObject) -> JsonObject,
    private val onFatal: (String) -> Unit,
) {
    private val queue = WakeFrameQueue(frameLength = maxOf(160, frameLength))
    /** Battery: stream only while the room has energy (see [WakeEnergyGate]); the noise floor
     *  is the Call's — minimum-statistics, so a dishwasher and a bedroom both work. */
    private val gate = chat.keryx.core.model.WakeEnergyGate()
    private val noiseFloor = NoiseFloor()
    @Volatile private var fedFrames = 0L
    private val lock = Any()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var drain: Job? = null
    @Volatile private var stopped = false
    /** Consecutive feed failures — a dead socket shouldn't spin the base64 mill at full tilt. */
    private var failures = 0
    private val mic = WakeMic(frameLength = frameLength, onChunk = ::onChunk, onFatal = onFatal)

    val active: Boolean get() = !stopped && mic.active

    fun start(): Boolean {
        if (mic.active) return true
        stopped = false
        drain = scope.launch { drainLoop() }
        if (!mic.start()) { drain?.cancel(); drain = null; return false }
        KLog.i(TAG) { "feeder started frame=$frameLength" }
        return true
    }

    /** Reader-thread hot path: gate on energy, frame, wake the drain. */
    private fun onChunk(at16k: ShortArray, n: Int) {
        if (stopped) return
        val rms = chat.keryx.core.model.WakeEnergyGate.rms(at16k, n)
        noiseFloor.update(rms)
        val send = gate.offer(if (n == at16k.size) at16k else at16k.copyOf(n), rms, noiseFloor.startGate, noiseFloor.endGate)
        if (send.isEmpty()) return
        synchronized(lock) { send.forEach { queue.push(it, it.size) } }
        fedFrames += send.size
        kick.trySend(Unit)
    }

    fun stop() {
        if (stopped && !mic.active) return
        stopped = true
        drain?.cancel(); drain = null
        mic.stop()
        synchronized(lock) { queue.clear() }
        KLog.i(TAG) { "feeder stopped (fed=$fedFrames gated=${gate.skipped} dropped=${queue.dropped})" }
        gate.reset()
    }

    private suspend fun drainLoop() {
        while (scope.isActive && !stopped) {
            val batch = synchronized(lock) { queue.nextBatch() }
            if (batch == null) { kick.receive(); continue }
            val bytes = WakePcm.toLittleEndian(batch)
            if (bytes.size > WakeProtocol.MAX_FEED_BYTES) continue // cannot happen at 4×1280, guard anyway
            val ok = runCatching {
                request(
                    "wake.feed",
                    buildJsonObject {
                        put("pcm", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                        put("sample_rate", JsonPrimitive(WakeProtocol.SAMPLE_RATE))
                    },
                )
            }.onFailure { e -> KLog.d(TAG) { "wake.feed failed: ${e.message}" } }.isSuccess
            if (ok) failures = 0 else {
                failures++
                // Socket down: back off (the controller re-arms on the next gateway.ready and
                // restarts us; until then don't hammer). Bounded so a blip recovers fast.
                kotlinx.coroutines.delay((250L * failures).coerceAtMost(3_000L))
            }
        }
    }

    private companion object { const val TAG = "KeryxWake" }
}
