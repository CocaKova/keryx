package chat.keryx.app.audio

import kotlin.math.max
import kotlin.math.min

/**
 * [PcmPlayer] rule 4's arithmetic — how much lead a sentence needs before its held bytes are
 * released — kept free of AudioTrack so a phone's call log can be replayed as a test.
 *
 * Lead = audio still queued in the track + audio held back. It has to cover the sentence's
 * projected shortfall: at arrival [rate] r < 1 (audio ms per wall ms), every second of audio
 * still to come lands (1 − r) seconds late, so a sentence with `remaining` ms to come needs
 * `remaining × (1 − r)` ms of lead on top of the base breath.
 *
 * Two things the first cut got wrong, both read off the 22:38 call of 2026-09-05:
 *  - it trusted the length estimate. When the estimate ran out but the audio did not (a
 *    145-char sentence rendered 11.9 s against a 7.5 s estimate), every re-hold sized itself
 *    for "nothing left" — 265 ms — and the track ran dry again 400 ms later, eleven times
 *    inside one sentence. A sentence that ran dry has disproved its estimate, so each dry
 *    assumes at least [ASSUMED_REMAINING_MS] more audio, doubling per dry, and the base
 *    breath doubles with it: a few longer pauses instead of a storm of chops.
 *  - the rate was measured over the hold alone (a 250 ms window). The server delivers in
 *    bursts, so a sentence arriving at 0.98× real time read as 1.2× and was given no lead at
 *    all — and ran dry every five seconds. The player now measures across the whole sentence.
 */
object SentenceLead {
    /** Audio a dry sentence is assumed to still have coming, when its estimate says none. */
    const val ASSUMED_REMAINING_MS = 1_000L

    /**
     * @param baseMs the breath a sentence always gets ([PcmPlayer.LEAD_MS] mid-track, the
     *   opening cushion before the first word)
     * @param rate audio ms per wall ms since this sentence's first byte
     * @param remainingMs audio still projected to come (estimate − released − held), ≥ 0;
     *   0 when the estimate is unknown or exhausted
     * @param dryCount how many times this sentence has run the track dry so far
     * @param maxMs the longest hold allowed — a pause past this reads as a hang, not a breath
     */
    fun neededMs(baseMs: Long, rate: Double, remainingMs: Long, dryCount: Int, maxMs: Long): Long {
        val dries = max(0, dryCount)
        val assumed = if (dries == 0) 0L else ASSUMED_REMAINING_MS shl min(dries - 1, 4)
        val remaining = max(remainingMs, assumed)
        val shortfall = 1.0 - min(rate, 1.0)
        // A delivery gap — not a rate deficit — is what drains a sentence arriving at ~1×,
        // and only time bridges a gap; so after a dry the breath itself grows too.
        val base = baseMs shl min(dries, 3)
        return min(maxMs, Math.round(remaining * shortfall) + base)
    }
}
