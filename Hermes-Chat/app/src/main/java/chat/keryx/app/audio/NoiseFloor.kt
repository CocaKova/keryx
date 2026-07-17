package chat.keryx.app.audio

import kotlin.math.max

/**
 * The call's adaptive noise floor — one instance per call, fed every captured frame.
 *
 * The floor must live across captures, not per capture: in a half-duplex call the next listen
 * opens the instant the agent stops speaking, which in a natural conversation is exactly when
 * the user is already mid-reply. A per-capture calibration window read at that moment measures
 * the user's own voice as "room noise" and pushes the start gate 3.5× above it — the whole
 * capture goes deaf (the v1.22.0 silent-second-turn bug). So the floor is seeded once, at call
 * start, and afterwards learns from every frame that isn't speech:
 *
 *  - below the current floor → fall fast (a quieter room should be trusted immediately);
 *  - below the start gate and not inside a confirmed utterance → rise slowly (ambience);
 *  - at/above the start gate, or mid-utterance → ignored (speech never raises its own gate).
 *
 * The rise ceiling must be the START GATE ITSELF, not a multiple of the floor: a floor-relative
 * ceiling has a dead zone at the bottom (the v1.22.1 no-turns-at-all bug — AudioRecord's first
 * frame is often near-zero DSP ramp-in, and a floor seeded below half the room's ambience could
 * then never climb, pinning the end gate under the room noise so no utterance ever ended).
 * Anything the gate itself won't treat as speech is, by definition, safe to learn from.
 */
internal class NoiseFloor {

    private var floor = -1.0

    /** RMS a frame must exceed to open the speech gate. */
    val startGate: Double get() = max(floor * START_OVER_FLOOR, MIN_START_RMS)

    /** RMS below which a frame counts as trailing silence once speech has started. */
    val endGate: Double get() = max(floor * END_OVER_FLOOR, MIN_END_RMS)

    /** Feed every frame; [inSpeech] = the caller's gate has confirmed an utterance is running. */
    fun update(rms: Double, inSpeech: Boolean = false) {
        if (floor < 0) {
            floor = rms
            return
        }
        if (rms < floor) {
            floor = floor * FALL_KEEP + rms * (1 - FALL_KEEP)
        } else if (!inSpeech && rms < startGate) {
            floor = floor * RISE_KEEP + rms * (1 - RISE_KEEP)
        }
    }

    private companion object {
        const val START_OVER_FLOOR = 3.5
        const val END_OVER_FLOOR = 1.8
        const val MIN_START_RMS = 250.0
        const val MIN_END_RMS = 140.0
        /** ~30 ms frames: fall reaches a quieter room in ~10 frames, rise takes ~1.5 s of
         *  sustained louder ambience. */
        const val FALL_KEEP = 0.7
        const val RISE_KEEP = 0.98
    }
}
