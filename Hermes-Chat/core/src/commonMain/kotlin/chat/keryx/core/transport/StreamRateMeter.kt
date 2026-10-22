package chat.keryx.core.transport

import chat.keryx.core.model.LiveRate
import chat.keryx.core.model.TokenRate

// Lives here, not in core.model: the app's Compose stability config marks every core.model class
// stable (3.0), and this is a mutable accumulator. What the UI sees is its LiveRate snapshot.

/**
 * Streamed characters, timed. Fed every delta (answer and reasoning alike, since the gateway's
 * output count includes the reasoning tokens); reports the recent character rate and the time
 * the characters were actually flowing.
 *
 * The rate ([cps]) is total characters over elapsed time across the last [WINDOW_MS] of
 * streaming: the characters that arrived after the window's first frame, divided by the time
 * since that frame. The first frame's own characters are left out because the time that
 * produced them is before the window. Until 3.0 this was an EMA of each frame's own rate
 * (characters ÷ gap), a mean of ratios: frames arrive in bursts over the relay and the network,
 * and a burst after a short gap counted as a fast moment, so the live figure read about three
 * times the brain's real speed (~140 against a measured ~48 tok/s). Bursts no longer matter:
 * the same characters over the same time read the same rate however they were grouped.
 *
 * A gap longer than [TokenRate.ACTIVE_GAP_MS] (a tool ran, the model stalled) starts a new
 * window: the pause is not generation time, so it neither dilutes the rate nor counts towards
 * [activeMs].
 */
class StreamRateMeter {
    var cps: Float = 0f; private set
    var firstAtMs: Long = 0L; private set
    var lastAtMs: Long = 0L; private set
    var chars: Long = 0L; private set
    var activeMs: Long = 0L; private set

    // The window: arrival time and characters of each recent frame, oldest first.
    private val frameAt = ArrayDeque<Long>()
    private val frameChars = ArrayDeque<Int>()
    private var windowChars = 0L // characters in the window, the oldest frame's included

    fun reset() {
        cps = 0f; firstAtMs = 0L; lastAtMs = 0L; chars = 0L; activeMs = 0L
        frameAt.clear(); frameChars.clear(); windowChars = 0L
    }

    fun onChars(n: Int, nowMs: Long) {
        if (n <= 0) return
        chars += n
        if (firstAtMs == 0L) firstAtMs = nowMs
        val dt = if (lastAtMs == 0L) -1L else nowMs - lastAtMs
        if (dt in 0..TokenRate.ACTIVE_GAP_MS) {
            activeMs += dt
        } else if (dt > TokenRate.ACTIVE_GAP_MS) {
            // A pause: what came before it says nothing about the speed after it.
            frameAt.clear(); frameChars.clear(); windowChars = 0L; cps = 0f
        }
        lastAtMs = nowMs
        frameAt.addLast(nowMs); frameChars.addLast(n); windowChars += n
        // Keep one frame at or before the window's start as its origin.
        while (frameAt.size > 1 && nowMs - frameAt[1] >= WINDOW_MS) {
            frameAt.removeFirst(); windowChars -= frameChars.removeFirst()
        }
        val span = nowMs - frameAt.first()
        if (span >= MIN_SPAN_MS) cps = (windowChars - frameChars.first()) * 1000f / span
    }

    fun sample(charsPerToken: Float): LiveRate = LiveRate(cps, lastAtMs, charsPerToken)

    companion object {
        /** How much recent streaming the live rate looks at. */
        const val WINDOW_MS = 3_000L

        /** Less time than this between the window's first and last frame and the division is
         *  noise; the previous reading stands (0 at the start). */
        const val MIN_SPAN_MS = 250L
    }
}
