package chat.keryx.app.presentation.ui.components

/**
 * A live thought, cut into pieces that never change once cut (3.0 perf).
 *
 * The streaming reasoning pane handed the WHOLE thought to one Text on every publish (up to ten
 * a second). Inside its scroll Compose measured, laid out and drew every line of it to show the
 * last eight. A turn's thought keeps growing across its model calls, and a long DeepSeek run
 * (h2h, 10-18: ~1 MB of reasoning over 286 calls, 210 KB of it on the tail between resets)
 * re-laid out hundreds of kilobytes per tick until Keryx fell behind the stream (Jonny, 10-18:
 * "with the super long reasoning for this task Keryx is lagging").
 *
 * Cut at line ends, the pieces stack into the same lines the one Text drew, and only the last
 * piece is new on a tick. The pane lays out that piece, not the thought.
 *
 * Prefix-stable: where a cut falls depends only on the text before it, so a thought that grows
 * keeps every earlier piece (the same String, so its Text skips). A thought that changes behind
 * a cut (the gateway replacing it, a new turn) starts over.
 */
internal class ThoughtPieces {
    private val cut = ArrayList<String>()
    private var source = ""
    /** Where the shown text starts in [source]: its leading whitespace is trimmed, as before. */
    private var begin = 0
    /** Characters of [source] the cut pieces cover, with the line end each one dropped. */
    private var covered = 0
    private var shown: List<String> = emptyList()

    /**
     * [text] as pieces. Stacked, they read as `text.trim()`: each cut drops the line end it fell
     * on, so the next piece starts on the next line. A line too long to wait for its end is cut
     * at a space, which the break replaces, as a wrap there would.
     */
    fun of(text: String): List<String> {
        if (text === source) return shown
        val start = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
        val keeps = start == begin && text.length >= covered && text.regionMatches(0, source, 0, covered)
        if (!keeps) { cut.clear(); covered = start }
        source = text
        begin = start
        while (true) {
            val (end, next) = nextCut(text, covered) ?: break
            cut += text.substring(covered, end)
            covered = next
        }
        val open = text.substring(covered).trimEnd()
        shown = if (open.isEmpty()) cut.toList() else cut + open
        return shown
    }

    /** The cut for the piece starting at [from], as (its end, where the next piece starts), or
     *  null while the text can't say yet. */
    private fun nextCut(text: String, from: Int): Pair<Int, Int>? {
        if (text.length - from <= PIECE) return null
        // Bounded: a thought with no line ends must not send every cut to the end of it.
        for (i in from + PIECE until minOf(text.length, from + MAX_PIECE)) {
            if (text[i] == '\n') return i to i + 1
        }
        // No line end in reach. Wait until the whole window is here, so the cut never moves.
        if (text.length - from < MAX_PIECE) return null
        val space = text.lastIndexOf(' ', from + MAX_PIECE - 1)
        return if (space > from + PIECE) space to space + 1 else (from + MAX_PIECE) to (from + MAX_PIECE)
    }

    companion object {
        /** A piece closes at the first line end past this many characters: a few panes of the
         *  live preview, a couple of milliseconds to lay out. */
        const val PIECE = 1_200
        /** A line with no end by here is cut at its last space instead. */
        const val MAX_PIECE = 2_400
    }
}
