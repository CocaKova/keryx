package chat.keryx.app.audio

import kotlin.math.max

/**
 * The call's noise floor: the minimum frame RMS over a sliding ~3 s window (minimum-statistics
 * noise estimation). One instance per call, fed every captured frame; the VAD gates ride above it.
 *
 * The window — not a learned/blended estimate — is the load-bearing choice. Two shipped bugs
 * proved that any one-shot or rate-limited floor has stuck states with opposite polarity:
 *
 *  - v1.22.0 calibrated per capture: captures after the first open the instant the agent stops
 *    speaking, i.e. while the user is already mid-reply, so their voice became the "floor" and
 *    the start gate rose 3.5× above their own speech — deaf-high.
 *  - v1.22.1/2 learned call-long with bounded rise: AudioRecord's first frames are near-silent
 *    DSP ramp-in, and in a room whose ambience clears the minimum start gate the gate opened on
 *    ambience and locked the floor low, so the end gate never saw "silence" — deaf-low, capture
 *    never ended.
 *
 * A sliding minimum has neither: the room is as loud as its quietest recent moment. Speech can
 * only lift the floor as high as its own inter-word dips (≈ room level), ramp-in zeros age out
 * of the window in seconds, and because callers re-read the gates every frame, even a capture
 * that opened under a bad floor recovers while it is still running.
 */
internal class NoiseFloor {

    private val window = ArrayDeque<Double>()

    /** RMS a frame must exceed to open the speech gate. */
    val startGate: Double get() = max(floor * START_OVER_FLOOR, MIN_START_RMS)

    /** RMS below which a frame counts as trailing silence once speech has started. */
    val endGate: Double get() = max(floor * END_OVER_FLOOR, MIN_END_RMS)

    private val floor: Double get() = window.minOrNull() ?: 0.0

    fun update(rms: Double) {
        window.addLast(rms)
        if (window.size > WINDOW_FRAMES) window.removeFirst()
    }

    private companion object {
        const val START_OVER_FLOOR = 3.5
        const val END_OVER_FLOOR = 1.8
        const val MIN_START_RMS = 250.0
        const val MIN_END_RMS = 140.0
        /** ~3 s of 30 ms frames: long enough that normal speech always contains a dip near room
         *  level, short enough that any bad state ages out mid-capture. */
        const val WINDOW_FRAMES = 100
    }
}
