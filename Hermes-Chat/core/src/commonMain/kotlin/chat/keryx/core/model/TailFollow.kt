package chat.keryx.core.model

/**
 * When a scroll region that is being written into should move itself.
 *
 * The transcript learned this law the hard way and its rules are spelled out in ChatScreen:
 * follow the newest only while the reader is actually at the newest, and never while a gesture
 * is in flight. The panes that stream *inside* a turn — the reasoning disclosure's preview, the
 * live run console's transcript — never learned it. They pinned themselves to the tail on every
 * token, so reading back through a long think was impossible: each new word dragged you to the
 * bottom mid-sentence, and the only way to read a thought was to wait for the model to stop
 * having it.
 *
 * The rule, whole: you are following while you are at the tail. Scrolling away detaches; coming
 * back to the tail re-attaches; nothing moves under a finger.
 *
 * The subtlety that makes this a latch and not a live read: arriving content extends the tail
 * *before* anything gets to ask "am I at the bottom", so a reader who never moved would answer
 * "no" the instant a token landed and detach itself. [atTail] is therefore consulted when a
 * scroll SETTLES — the last moment the reader's position and the content's end were the same
 * question — and the answer is held until the next one.
 */
object TailFollow {

    /** How close to the end still counts as the end. A hair, not a screenful. */
    const val SLOP_DP: Int = 24

    /**
     * Is [offset] at the end of a region whose furthest scroll is [extent], give or take
     * [slopPx]? A region with nothing to scroll ([extent] <= 0, content shorter than its own
     * viewport) is always at its tail — there is nowhere else to be.
     */
    fun atTail(offset: Int, extent: Int, slopPx: Int = 0): Boolean {
        if (extent <= 0) return true
        return offset >= extent - slopPx.coerceAtLeast(0)
    }

    /**
     * Should new content move the region now? Only when the reader was left at the tail and is
     * not touching it. The second clause is not politeness: a scroll issued under an active
     * drag or fling fights the finger, which is precisely how scrolling up mid-stream became
     * impossible in the transcript.
     */
    fun shouldFollow(following: Boolean, gestureInFlight: Boolean): Boolean =
        following && !gestureInFlight
}
