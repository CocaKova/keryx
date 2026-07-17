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
 * start (a user-initiated quiet moment), and afterwards only ever learns from frames that
 * don't look like speech:
 *
 *  - below the current floor → fall fast (a quieter room should be trusted immediately);
 *  - under [RISE_CEIL_OVER_FLOOR]× the floor → rise slowly (ambience genuinely got louder);
 *  - anything louder → ignored (speech or transient; it must never raise its own gate).
 */
internal class NoiseFloor {

    private var floor = -1.0

    /** RMS a frame must exceed to open the speech gate. */
    val startGate: Double get() = max(floor * START_OVER_FLOOR, MIN_START_RMS)

    /** RMS below which a frame counts as trailing silence once speech has started. */
    val endGate: Double get() = max(floor * END_OVER_FLOOR, MIN_END_RMS)

    fun update(rms: Double) {
        if (floor < 0) {
            floor = rms
            return
        }
        if (rms < floor) {
            floor = floor * FALL_KEEP + rms * (1 - FALL_KEEP)
        } else if (rms < floor * RISE_CEIL_OVER_FLOOR) {
            floor = floor * RISE_KEEP + rms * (1 - RISE_KEEP)
        }
    }

    private companion object {
        const val START_OVER_FLOOR = 3.5
        const val END_OVER_FLOOR = 1.8
        const val MIN_START_RMS = 250.0
        const val MIN_END_RMS = 140.0
        /** Frames between this×floor and the start gate are ambiguous (soft speech onset,
         *  breath, keyboard) — they teach the floor nothing. */
        const val RISE_CEIL_OVER_FLOOR = 2.0
        /** ~30 ms frames: fall reaches a quieter room in ~10 frames, rise takes ~1.5 s of
         *  sustained louder ambience. */
        const val FALL_KEEP = 0.7
        const val RISE_KEEP = 0.98
    }
}
