package chat.keryx.core.model

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Honest tokens per second (2.16).
 *
 * The stream carries characters, not tokens, and until 2.16 every readout divided the character
 * rate by four and called the result tokens. Jonny's rule: a token figure is a token figure only
 * when the gateway counted the tokens. So there are three readings, and each says what it is:
 *
 *  - **live** (the working cloud, Tap-In): the measured character rate, converted with a
 *    chars-per-token ratio that was itself measured against the gateway's real output count on an
 *    earlier turn, and marked "≈". Before any turn has calibrated it, the live reading stays in
 *    characters ("chars/s"), because that is all it knows. It falls toward zero while the model
 *    stalls instead of freezing on its last good number.
 *  - **settled** (the finished turn's meta): the turn's real output tokens (the gateway's
 *    cumulative `usage.output`, before and after) over the time the tokens were flowing. No "≈".
 *  - **average** (the context sheet): the gateway's own `avg_tps`, untouched ([SessionUsage]).
 */
object TokenRate {
    /** The live figure holds this long after the last characters before it starts to fall. */
    const val HOLD_MS = 1_500L

    /** Then halves every this many ms, so a stall reads as a stall within a few seconds. */
    const val HALF_LIFE_MS = 900.0

    /** A gap between two frames longer than this is not generation time (a tool ran, the model
     *  stalled); [StreamRateMeter.activeMs] leaves it out of the settled figure. */
    const val ACTIVE_GAP_MS = 2_500L

    /** A ratio outside this band is a measurement accident (a turn that was mostly tool-call
     *  arguments, a provider that counts strangely), not a property of the model. */
    const val MIN_CHARS_PER_TOKEN = 1.5f
    const val MAX_CHARS_PER_TOKEN = 8f

    /** Too few tokens or too little time and the division is noise. */
    const val MIN_TOKENS = 24L
    const val MIN_CHARS = 64L
    const val MIN_ACTIVE_MS = 400L

    /** [cps] measured at [lastAtMs], as it stands at [nowMs]: held briefly, then decaying. */
    fun decayed(cps: Float, lastAtMs: Long, nowMs: Long): Float {
        if (cps <= 0f || lastAtMs <= 0L) return 0f
        val quiet = nowMs - lastAtMs - HOLD_MS
        if (quiet <= 0L) return cps
        val v = cps * exp(-quiet * kotlin.math.ln(2.0) / HALF_LIFE_MS).toFloat()
        return if (v < 0.5f) 0f else v
    }

    /**
     * A new chars-per-token ratio from one finished turn, blended into [previous] (0 = none yet).
     * Returns [previous] unchanged when the turn is no evidence: too short, or a ratio outside
     * the plausible band.
     */
    fun calibrate(previous: Float, chars: Long, tokens: Long): Float {
        if (tokens < MIN_TOKENS || chars < MIN_CHARS) return previous
        val ratio = chars.toFloat() / tokens.toFloat()
        if (ratio !in MIN_CHARS_PER_TOKEN..MAX_CHARS_PER_TOKEN) return previous
        return if (previous <= 0f) ratio else previous * 0.5f + ratio * 0.5f
    }

    /**
     * The live readout: "≈41 tok/s" with a calibrated [charsPerToken], "164 chars/s" without one,
     * null when there is nothing worth saying (stalled, or under one token a second).
     */
    fun liveLabel(cps: Float, charsPerToken: Float): String? {
        if (cps <= 0f) return null
        return if (charsPerToken > 0f) {
            val tps = cps / charsPerToken
            if (tps < 1f) null else "≈${tps.roundToInt()} tok/s"
        } else {
            if (cps < 4f) null else "${cps.roundToInt()} chars/s"
        }
    }

    /** Real tokens per second for a finished turn, or null when the numbers can't support one. */
    fun settled(tokens: Long?, activeMs: Long): Double? {
        if (tokens == null || tokens < MIN_TOKENS || activeMs < MIN_ACTIVE_MS) return null
        val tps = tokens * 1000.0 / activeMs
        return tps.takeIf { it.isFinite() && it > 0.0 && it < 100_000.0 }
    }

    /** A finished answer's second key: its text, which survives a transcript re-read that
     *  renames the live rows. */
    fun answerKey(roomId: String, text: String): String = "$roomId#${text.trim().hashCode()}"

    /** The settled figure for [messageId] / its [text] in [rates], if one was measured. */
    fun lookup(rates: Map<String, Double>, messageId: String, roomId: String, text: String): Double? =
        if (rates.isEmpty()) null else rates[messageId] ?: text.takeIf { it.isNotBlank() }?.let { rates[answerKey(roomId, it)] }

    /** "41 tok/s", "7.5 tok/s": a counted figure, so no "≈". */
    fun settledLabel(tps: Double): String = "${SessionUsage.rate(tps)} tok/s"
}

/** A live rate as sampled: the character EMA at [lastAtMs], and the ratio to read it with. */
data class LiveRate(
    val cps: Float,
    val lastAtMs: Long,
    /** Chars per token measured against real counts; 0 = never calibrated. */
    val charsPerToken: Float = 0f,
) {
    fun label(nowMs: Long): String? =
        TokenRate.liveLabel(TokenRate.decayed(cps, lastAtMs, nowMs), charsPerToken)

    /** Tokens (or, uncalibrated, token-equivalents at 4 chars) per second right now, for the
     *  things that only need a magnitude — the sand's pour rate, never a printed number. */
    fun intensity(nowMs: Long): Float {
        val c = TokenRate.decayed(cps, lastAtMs, nowMs)
        return c / (if (charsPerToken > 0f) charsPerToken else 4f)
    }
}

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
