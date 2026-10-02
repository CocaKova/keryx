package chat.keryx.core.model

/**
 * A streaming reply read aloud the way a person would read it: a sentence at a time, at the pace
 * a screen reader speaks (2.16).
 *
 * The live bubble changes ~10 times a second. A live region on it would have TalkBack restart
 * the whole reply on every token — or, as it was, say nothing at all, so a TalkBack user heard
 * the working cloud and then silence until they went looking. This picks the next stretch to
 * announce: everything after what was already said, up to the last finished sentence, once there
 * is enough of it to be worth a breath. When the turn ends, whatever is left goes out whole.
 *
 * Position is held as an ANCHOR (the tail of what was said), not an index: the overlay renders a
 * tail window of long turns and re-parses as it goes, so an index into one tick's text means
 * nothing in the next tick's.
 *
 * Pure Kotlin (KMP rule): no android.* here.
 */
object SpokenStream {

    /** Don't announce a stretch shorter than this (unless the turn is over): one "Sure." per
     *  breath is a stutter, not reading. */
    const val MIN_CHARS = 40

    /** Roughly what TalkBack speaks per second at its default rate — paces the next stretch so
     *  a new announcement does not cut the last one off mid-sentence. */
    const val CHARS_PER_SECOND = 15

    /** The floor between two looks at the stream. */
    const val MIN_GAP_MS = 1_500L

    /** How much of the said text the anchor keeps. */
    const val ANCHOR_CHARS = 24

    data class Step(
        /** What to announce now. */
        val say: String,
        /** Where the next stretch starts: the tail of what has now been said. */
        val anchor: String,
    )

    private val SENTENCE_END = Regex("""[.!?…:;](?=\s)""")

    /**
     * The next stretch of [speakable] to announce after [anchor] (empty = nothing said yet), or
     * null when there is nothing ready. On [final] everything left is returned, sentence or not.
     */
    fun next(speakable: String, anchor: String, final: Boolean): Step? {
        val from = resumeAt(speakable, anchor)
        if (from >= speakable.length) return null
        val rest = speakable.substring(from)
        val end = if (final) rest.length else {
            val last = SENTENCE_END.findAll(rest).lastOrNull() ?: return null
            last.range.last + 1
        }
        if (!final && end < MIN_CHARS) return null
        val say = rest.substring(0, end).trim()
        if (say.isEmpty()) return null
        val upTo = from + end
        return Step(say, speakable.substring(maxOf(0, upTo - ANCHOR_CHARS), upTo))
    }

    /**
     * Where to resume in [speakable]: just after [anchor]. An anchor the text no longer holds
     * means the window slid past it while the reader was still speaking — resume at the start of
     * what is there rather than skip it.
     */
    fun resumeAt(speakable: String, anchor: String): Int {
        if (anchor.isEmpty()) return 0
        val at = speakable.lastIndexOf(anchor)
        return if (at >= 0) at + anchor.length else 0
    }

    /** How long to let [said] be spoken before looking again. */
    fun gapMs(said: String): Long = maxOf(MIN_GAP_MS, said.length * 1_000L / CHARS_PER_SECOND)
}
