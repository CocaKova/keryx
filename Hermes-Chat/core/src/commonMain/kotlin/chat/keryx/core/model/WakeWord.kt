package chat.keryx.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * "Hey Hermes" wake word — the pure half (protocol §5.4, desktop `store/wake-word.ts` +
 * `lib/wake-client-capture.ts`).
 *
 * Detection is SERVER-side (openWakeWord in the gateway); the phone only ships mic PCM via
 * `wake.feed` and reacts to `wake.detected`. Everything here is platform-free so the frame
 * pipeline, the wire shapes and the re-arm decision are unit-testable; AudioRecord, the
 * foreground service and the RPC socket live in the app module.
 */

/** `wake.start` / `wake.status` say why the ear is (not) armed. Hint text wins; codes are
 *  desktop's REASON_TEXT wording so both surfaces explain a refusal the same way. Unknown
 *  codes fall through raw — a new server-side reason must stay visible, not vanish. */
object WakeReason {
    private val TEXT = mapOf(
        "disabled" to "off in the gateway config — flip the switch off and on to re-enable",
        "disabled_for_surface" to "scoped to another surface (config wake_word.surface)",
        "not_owner" to "another surface owns the listener",
        "owned" to "another surface owns the listener",
        "unavailable" to "unavailable on this gateway",
        "empty" to "",
    )

    fun text(reason: String?, hint: String?): String {
        val h = hint?.trim().orEmpty()
        if (h.isNotEmpty()) return h
        val r = reason?.trim().orEmpty()
        return if (r.isEmpty()) "" else TEXT[r] ?: r
    }
}

/** `wake.start` response. */
data class WakeStartResult(
    val started: Boolean,
    val reason: String? = null,
    val hint: String? = null,
    val phrase: String? = null,
    val capture: String? = null,
    val sampleRate: Int = 16_000,
    val frameLength: Int = 1280,
    val enabledPersisted: Boolean = false,
    val ownerSurface: String? = null,
) {
    /** The gateway wants OUR mic: `capture` is client/remote/external (desktop's exact test). */
    val clientCapture: Boolean get() = WakeProtocol.isClientCapture(capture)

    companion object {
        fun from(o: JsonObject?): WakeStartResult = WakeStartResult(
            started = o.bool("started"),
            reason = o.str("reason"),
            hint = o.str("hint"),
            phrase = o.str("phrase"),
            capture = o.str("capture"),
            sampleRate = o.int("sample_rate") ?: 16_000,
            frameLength = o.int("frame_length") ?: 1280,
            enabledPersisted = o.bool("enabled_persisted"),
            ownerSurface = o.str("owner_surface"),
        )
    }
}

/** `wake.status` response — the gateway's truth about the shared listener. */
data class WakeStatus(
    val listening: Boolean,
    val ownedByCaller: Boolean,
    val available: Boolean,
    /** Config truth (`wake_word.enabled`); drives post-voice re-arm. */
    val enabled: Boolean,
    val phrase: String? = null,
    val capture: String? = null,
    val hint: String? = null,
    val audioSilent: Boolean = false,
    val frameLength: Int = 1280,
    val ownerSurface: String? = null,
) {
    val clientCapture: Boolean get() = WakeProtocol.isClientCapture(capture)

    companion object {
        fun from(o: JsonObject?): WakeStatus = WakeStatus(
            listening = o.bool("listening"),
            ownedByCaller = o.bool("owned_by_caller"),
            available = o.bool("available"),
            enabled = o.bool("enabled"),
            phrase = o.str("phrase"),
            capture = o.str("capture"),
            hint = o.str("hint"),
            audioSilent = o.bool("audio_silent"),
            frameLength = o.int("frame_length") ?: 1280,
            ownerSurface = o.str("owner_surface"),
        )
    }
}

/** `wake.stop` response. `{stopped:false, reason:"not_owner"}` still means WE aren't listening. */
data class WakeStopResult(val stopped: Boolean, val reason: String? = null, val disabledPersisted: Boolean = false) {
    companion object {
        fun from(o: JsonObject?) = WakeStopResult(
            stopped = o.bool("stopped"), reason = o.str("reason"), disabledPersisted = o.bool("disabled_persisted"),
        )
    }
}

/** The `wake.detected` event payload. `sessionId` on the frame is "" — it is a global event. */
data class WakeDetection(val phrase: String, val profile: String?, val startNewSession: Boolean) {
    companion object {
        fun from(payload: JsonObject?): WakeDetection = WakeDetection(
            phrase = payload.str("phrase") ?: "hey hermes",
            profile = payload.str("profile")?.takeIf { it.isNotBlank() },
            // Absent = true (desktop: `start_new_session !== false`).
            startNewSession = payload?.get("start_new_session")?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }
}

object WakeProtocol {
    const val SAMPLE_RATE = 16_000
    const val DEFAULT_FRAME = 1280           // 80 ms — tools/wake_word.py engine frame
    /** Coalesce N frames per `wake.feed`. Desktop sends 4 (≈3 RPC/s); a phone radio prefers
     *  fewer, fatter sends — 8 × 80 ms = 20480 B PCM (27 KB base64), well under the 64000 B
     *  cap, ≈1.5 RPC/s while speech is present, +320 ms worst-case detection latency. */
    const val FRAMES_PER_FEED = 8
    /** Bounded backlog; oldest dropped under remote latency so the detector sees RECENT audio. */
    const val MAX_QUEUED_FRAMES = 24         // ~1.9 s
    /** Server rejects > 64000 bytes per feed (2 s of 16 kHz int16). */
    const val MAX_FEED_BYTES = 64_000
    /** First `wake.start` lazy-installs the engine (onnxruntime) — desktop uses 180 s. */
    const val START_TIMEOUT_MS = 180_000L

    fun isClientCapture(capture: String?): Boolean =
        when (capture?.trim()?.lowercase()) { "client", "remote", "external" -> true; else -> false }
}

/**
 * Post-voice / reconnect reconcile (desktop `resumeWakeAfterVoice`): what to do given the
 * gateway's status. Config `enabled` is the authority; a user who turned the ear off during
 * the call stays respected. Never flips config itself (that is `persist:true`, a gesture).
 */
enum class WakeReconcileAction { REST_OFF, REATTACH_FEED, ARM }

object WakeReconcile {
    fun decide(status: WakeStatus): WakeReconcileAction = when {
        !status.enabled || !status.available -> WakeReconcileAction.REST_OFF
        status.listening && status.ownedByCaller -> WakeReconcileAction.REATTACH_FEED
        // Listening but owned by someone else: try to arm — the server answers `owned`
        // and the caller then rests. (Owner may be a dead transport it hasn't reaped yet.)
        else -> WakeReconcileAction.ARM
    }
}

/**
 * Turns an arbitrary stream of 16 kHz samples into engine-sized frames, keeps a bounded
 * backlog (drop-OLDEST — a wake detector needs contiguous recent audio, not old audio), and
 * hands out coalesced batches for `wake.feed`. Not thread-safe by itself: the caller
 * serialises [push] and [nextBatch] (the app wraps it in a lock).
 */
class WakeFrameQueue(
    val frameLength: Int = WakeProtocol.DEFAULT_FRAME,
    private val maxQueued: Int = WakeProtocol.MAX_QUEUED_FRAMES,
    private val framesPerBatch: Int = WakeProtocol.FRAMES_PER_FEED,
) {
    private var pending = ShortArray(0)
    private val frames = ArrayDeque<ShortArray>()
    var dropped: Long = 0
        private set

    val queued: Int get() = frames.size

    /** Append [n] samples of [samples]; emits complete frames into the queue. */
    fun push(samples: ShortArray, n: Int = samples.size) {
        if (n <= 0) return
        val merged = ShortArray(pending.size + n)
        pending.copyInto(merged, 0, 0, pending.size)
        samples.copyInto(merged, pending.size, 0, n)
        var offset = 0
        while (offset + frameLength <= merged.size) {
            frames.addLast(merged.copyOfRange(offset, offset + frameLength))
            offset += frameLength
            while (frames.size > maxQueued) { frames.removeFirst(); dropped++ }
        }
        pending = merged.copyOfRange(offset, merged.size)
    }

    /** Up to [framesPerBatch] frames merged into one array, or null when nothing is queued. */
    fun nextBatch(): ShortArray? {
        if (frames.isEmpty()) return null
        val take = minOf(framesPerBatch, frames.size)
        val out = ShortArray(take * frameLength)
        repeat(take) { i -> frames.removeFirst().copyInto(out, i * frameLength) }
        return out
    }

    fun clear() { frames.clear(); pending = ShortArray(0) }
}

/** PCM helpers shared by the feeder and its tests. */
object WakePcm {
    /** int16 samples → little-endian bytes (the `wake.feed` wire format). */
    fun toLittleEndian(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = s.toInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Box-average downsample to 16 kHz (desktop `downsampleTo16k`, sample-for-sample) for
     * devices whose AudioRecord won't open at 16 kHz natively (44.1/48 kHz are the only
     * rates Android guarantees). Identity at 16 kHz; empty for a nonsense rate.
     */
    fun downsampleTo16k(input: ShortArray, n: Int, inputRate: Int): ShortArray {
        if (inputRate == WakeProtocol.SAMPLE_RATE) return if (n == input.size) input else input.copyOf(n)
        if (inputRate <= 0 || n <= 0) return ShortArray(0)
        val ratio = inputRate.toDouble() / WakeProtocol.SAMPLE_RATE
        val outLen = maxOf(1, (n / ratio).toInt())
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val start = (i * ratio).toInt()
            val end = minOf(n, ((i + 1) * ratio).toInt())
            var sum = 0L
            var count = 0
            for (j in start until end) { sum += input[j]; count++ }
            out[i] = if (count > 0) (sum / count).toInt().coerceIn(-32768, 32767).toShort() else 0
        }
        return out
    }
}

private fun JsonObject?.str(k: String): String? = this?.get(k)?.jsonPrimitive?.contentOrNull
private fun JsonObject?.bool(k: String): Boolean = this?.get(k)?.jsonPrimitive?.booleanOrNull ?: false
private fun JsonObject?.int(k: String): Int? = this?.get(k)?.jsonPrimitive?.intOrNull


/**
 * The battery gate: stream to the gateway only while the room has energy.
 *
 * Client capture as desktop does it — every frame, forever — is a laptop-on-mains design;
 * on a phone the radio never sleeps and the ear costs more than the screen. Nearly all of a
 * nightstand's hours are silence, and silence carries no wake phrase, so it need not be sent.
 * The gate rides the same adaptive noise floor the Call's VAD uses (thresholds are passed in);
 * while closed it keeps a short PRE-ROLL ring so the phrase's first syllable is not the frame
 * that opened the gate and got dropped ("hey" is soft), and once open it HANGS for a while
 * past the last loud frame so inter-word dips don't chop the phrase. Server side tolerates
 * gaps: `wake.feed` pads/splits per call and the detector merely counts quiet as silence.
 */
class WakeEnergyGate(
    private val prerollFrames: Int = DEFAULT_PREROLL_FRAMES,
    private val hangFrames: Int = DEFAULT_HANG_FRAMES,
) {
    private val ring = ArrayDeque<ShortArray>()
    private var open = false
    private var hang = 0
    /** Frames NOT sent (for the diagnostics line). */
    var skipped: Long = 0
        private set
    val isOpen: Boolean get() = open

    /** Offer one frame with its RMS; returns the frames to send now (possibly the pre-roll). */
    fun offer(frame: ShortArray, rms: Double, startGate: Double, endGate: Double): List<ShortArray> {
        if (!open) {
            // Ring = the last [prerollFrames] frames BEFORE this one, plus this one.
            while (ring.size > prerollFrames) { ring.removeFirst(); skipped++ }
            ring.addLast(frame)
            if (rms <= startGate) return emptyList()
            open = true
            hang = hangFrames
            val out = ring.toList()
            ring.clear()
            return out
        }
        if (rms < endGate) {
            hang--
            if (hang <= 0) { open = false; return listOf(frame) }
        } else {
            hang = hangFrames
        }
        return listOf(frame)
    }

    fun reset() { ring.clear(); open = false; hang = 0 }

    companion object {
        /** 8 × 80 ms = 640 ms kept from before the gate opened. */
        const val DEFAULT_PREROLL_FRAMES = 8
        /** 15 × 80 ms = 1.2 s of quiet before the gate closes again. */
        const val DEFAULT_HANG_FRAMES = 15

        fun rms(frame: ShortArray, n: Int = frame.size): Double {
            if (n <= 0) return 0.0
            var acc = 0.0
            for (i in 0 until n) { val v = frame[i].toDouble(); acc += v * v }
            return kotlin.math.sqrt(acc / n)
        }
    }
}

/**
 * When the ear may run at all on THIS device — the policy half of battery management. Pure
 * so the table is testable; the app supplies the facts (charging, metered/cellular, idle).
 */
data class WakePolicy(
    val onlyWhileCharging: Boolean = true,
    val notOnCellular: Boolean = true,
    /** 0 = never auto-off. */
    val idleHours: Int = 4,
) {
    data class Facts(val charging: Boolean, val cellular: Boolean, val idleMs: Long)

    /** null = allowed; else the human reason the ear is resting. */
    fun restReason(f: Facts): String? = when {
        onlyWhileCharging && !f.charging -> "resting — plug in to listen"
        notOnCellular && f.cellular -> "resting — not on mobile data"
        idleHours > 0 && f.idleMs >= idleHours * 3_600_000L -> "auto-off after ${idleHours}h idle — open Keryx to re-arm"
        else -> null
    }
}

/**
 * openWakeWord's streaming pipeline, model calls injected — the phone runs it locally so no
 * audio ever leaves the device until the phrase is heard (the zero-network ear).
 *
 * Verified 08-18 against `openwakeword.utils.AudioFeatures` + `Model.predict` (ONNX and tflite
 * both): per 80 ms chunk of 1280 samples → melspectrogram of the last 1760 samples (chunk +
 * 3 hops of 160 for window continuity) → 8 mel frames × 32 bins, transformed `x/10 + 2` →
 * appended to a mel history seeded with ONES(76×32) → embedding of the last 76 rows → one
 * 96-float feature → last 16 features → the classifier's 0..1 score. Same clip, same numbers
 * (0.93 / 0.96 / 0.96 / 0.94) as the reference.
 */
class WakePipeline(
    /** 1760 float samples (raw int16 values, NOT normalized) → 8×32 mel, row-major. */
    private val melspectrogram: (FloatArray) -> FloatArray,
    /** 76×32 mel window, row-major → 96 floats. */
    private val embed: (FloatArray) -> FloatArray,
    /** 16×96 features, row-major → score 0..1. */
    private val score: (FloatArray) -> Float,
) {
    private val raw = FloatArray(MEL_INPUT)           // last 1760 samples, oldest first
    private var rawFilled = 0
    private val mel = FloatArray(MEL_WINDOW * MEL_BINS)  // last 76 mel rows
    private val feats = FloatArray(FEATURE_WINDOW * EMBED_DIM)
    private var featCount = 0

    init { reset() }

    fun reset() {
        raw.fill(0f); rawFilled = 0
        mel.fill(1f)   // openWakeWord seeds the mel history with ones
        feats.fill(0f); featCount = 0
    }

    /** Ready when 16 real embeddings exist (~1.3 s of audio); scores before that are noise. */
    val warm: Boolean get() = featCount >= FEATURE_WINDOW

    /** Feed exactly one 80 ms frame (1280 samples); returns the classifier score, or null while
     *  the feature window is still filling. */
    fun process(frame: ShortArray): Float? {
        require(frame.size == FRAME) { "frame must be $FRAME samples, got ${frame.size}" }
        // slide raw left by FRAME, append
        System_arraycopy(raw, FRAME, raw, 0, MEL_INPUT - FRAME)
        for (i in 0 until FRAME) raw[MEL_INPUT - FRAME + i] = frame[i].toFloat()
        rawFilled = minOf(MEL_INPUT, rawFilled + FRAME)
        val m = melspectrogram(raw)
        require(m.size == MEL_NEW_ROWS * MEL_BINS) { "mel must be ${MEL_NEW_ROWS}×$MEL_BINS, got ${m.size}" }
        // append 8 rows to the 76-row window (drop oldest 8)
        System_arraycopy(mel, MEL_NEW_ROWS * MEL_BINS, mel, 0, (MEL_WINDOW - MEL_NEW_ROWS) * MEL_BINS)
        for (i in m.indices) mel[(MEL_WINDOW - MEL_NEW_ROWS) * MEL_BINS + i] = m[i] / 10f + 2f
        val e = embed(mel)
        require(e.size == EMBED_DIM) { "embedding must be $EMBED_DIM, got ${e.size}" }
        System_arraycopy(feats, EMBED_DIM, feats, 0, (FEATURE_WINDOW - 1) * EMBED_DIM)
        for (i in 0 until EMBED_DIM) feats[(FEATURE_WINDOW - 1) * EMBED_DIM + i] = e[i]
        featCount++
        return if (warm) score(feats) else null
    }

    companion object {
        const val FRAME = 1280
        const val MEL_INPUT = 1760          // 1280 + 3 × 160-sample hops
        const val MEL_NEW_ROWS = 8
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val EMBED_DIM = 96
        const val FEATURE_WINDOW = 16
    }
}

// KMP-safe array shift (System.arraycopy is JVM-only).
private fun System_arraycopy(src: FloatArray, srcPos: Int, dst: FloatArray, dstPos: Int, len: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + len)
}

/**
 * Score → fire decision, mirroring `tools/wake_word.py` `_OpenWakeWordEngine.process` +
 * `WakeWordDetector` cooldown: `confirmationFrames` CONSECUTIVE frames ≥ `threshold`, then a
 * cooldown before it may fire again. Same defaults as the gateway (0.6 / 3 / 2 s), so a phrase
 * that wakes the Spark wakes the phone.
 */
class WakeScoreGate(
    val threshold: Float = 0.6f,
    val confirmationFrames: Int = 3,
    private val cooldownMs: Long = 2_000L,
) {
    private var streak = 0
    private var lastFireAt = Long.MIN_VALUE / 2

    /** True exactly when the wake fires. [nowMs] is any monotonic clock. */
    fun offer(score: Float, nowMs: Long): Boolean {
        if (score >= threshold) {
            streak++
            if (streak >= confirmationFrames) {
                streak = 0
                if (nowMs - lastFireAt >= cooldownMs) { lastFireAt = nowMs; return true }
            }
            return false
        }
        streak = 0
        return false
    }

    fun reset() { streak = 0 }
}
