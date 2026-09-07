package chat.keryx.app.audio

/**
 * How long the voice takes per character, learned from the sentences it has already spoken.
 *
 * The lead a sentence is held for ([SentenceLead]) is sized from its projected length, and a
 * fixed 52 ms/char undershot Sy by a third on 2026-09-05 (measured 50–82 ms/char across one
 * call, the long tail being pauses and vocal events) — every hold was too short and every
 * long sentence stuttered. So the pace is measured: each finished sentence's audio ÷ chars
 * feeds an average, and projections carry [HEADROOM] on top, because holding a little too
 * long costs one longer breath while holding too short costs a dozen chops.
 *
 * Pure and single-threaded by contract (the call's playback thread owns it).
 */
class SpeechPace(initialMsPerChar: Double = DEFAULT_MS_PER_CHAR) {
    var msPerChar: Double = initialMsPerChar
        private set

    /** Projected length of [chars] characters of speech, with headroom. 0 for nothing. */
    fun expectedMs(chars: Int): Long =
        if (chars <= 0) 0L else (chars * msPerChar * HEADROOM).toLong()

    /**
     * A sentence finished: [chars] of text became [audioMs] of sound. Short sentences are
     * skipped (a one-word "Yes." carries a fixed onset that isn't pace), and a runaway
     * rendering (the model failing to stop — 32 s for 156 chars was seen live) is not pace
     * either and must not poison the average.
     */
    fun learn(chars: Int, audioMs: Long) {
        if (chars < MIN_CHARS || audioMs <= 0) return
        val sample = audioMs.toDouble() / chars
        if (sample > RUNAWAY_MS_PER_CHAR) return
        msPerChar = msPerChar * (1 - WEIGHT) + sample * WEIGHT
    }

    companion object {
        /** Middle of Sy's measured range on 09-05 — the starting guess before any sentence lands. */
        const val DEFAULT_MS_PER_CHAR = 65.0
        const val HEADROOM = 1.15
        const val WEIGHT = 0.4
        const val MIN_CHARS = 12
        /** Past this the model is babbling, not speaking (normal tail ≈ 80 ms/char). */
        const val RUNAWAY_MS_PER_CHAR = 150.0
    }
}
