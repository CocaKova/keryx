package chat.keryx.core.transport

import chat.keryx.core.model.LiveRate
import chat.keryx.core.model.TokenRate

// Lives here, not in core.model: the app's Compose stability config marks every core.model class
// stable (3.0), and this is a mutable accumulator. What the UI sees is its LiveRate snapshot.

/**
 * Streamed characters, timed. Fed every delta (answer and reasoning alike, since the gateway's
 * output count includes the reasoning tokens); reports an EMA of the instantaneous rate and the
 * time the characters were actually flowing.
 *
 * The EMA is of the per-frame rate, not a cumulative average: the cumulative form was diluted
 * by think latency and tool gaps, so a 150 tok/s brain read ~32. Frames closer than
 * [MIN_FRAME_MS] (coalesced bursts spike) or farther than [MAX_FRAME_MS] (stalls tank) are not
 * sampled; their characters still count.
 */
class StreamRateMeter {
    var cps: Float = 0f; private set
    var firstAtMs: Long = 0L; private set
    var lastAtMs: Long = 0L; private set
    var chars: Long = 0L; private set
    var activeMs: Long = 0L; private set

    fun reset() { cps = 0f; firstAtMs = 0L; lastAtMs = 0L; chars = 0L; activeMs = 0L }

    fun onChars(n: Int, nowMs: Long) {
        if (n <= 0) return
        chars += n
        if (firstAtMs == 0L) { firstAtMs = nowMs; lastAtMs = nowMs; return }
        val dt = nowMs - lastAtMs
        if (dt in 0..TokenRate.ACTIVE_GAP_MS) activeMs += dt
        if (dt in MIN_FRAME_MS..MAX_FRAME_MS) {
            val instant = n * 1000f / dt
            cps = if (cps <= 0f) instant else EMA_WEIGHT * cps + (1f - EMA_WEIGHT) * instant
        }
        lastAtMs = nowMs
    }

    fun sample(charsPerToken: Float): LiveRate = LiveRate(cps, lastAtMs, charsPerToken)

    companion object {
        const val EMA_WEIGHT = 0.6f
        const val MIN_FRAME_MS = 15L
        const val MAX_FRAME_MS = 4_000L
    }
}
