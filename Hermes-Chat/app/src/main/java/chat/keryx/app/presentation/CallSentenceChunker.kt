package chat.keryx.app.presentation

/**
 * Splits a streaming assistant answer into speakable sentences as deltas arrive — the Call's
 * text→voice pacing. Pure and incremental: [feed] returns whatever sentences that delta
 * completed, [flush] returns the tail when the turn ends.
 *
 * Structural rules, not incident lists:
 *  - a sentence ends at `.` `!` `?` followed by whitespace, or at a newline — once at least
 *    [minChars] have accumulated (kills staccato fragments AND decimal splits like "v1.19",
 *    whose dot is never followed by whitespace);
 *  - nothing splits inside a ``` fence: code is one unit, and the downstream
 *    [TtsText.speakable] pass decides how much of it deserves breath.
 */
class CallSentenceChunker(private val minChars: Int = 24) {

    private val buf = StringBuilder()

    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val sentence = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (sentence.isNotBlank()) out += sentence
        }
        return out
    }

    /** The unterminated tail (turn ended mid-sentence), or null when nothing is pending. */
    fun flush(): String? {
        val tail = buf.toString().trim()
        buf.clear()
        return tail.ifBlank { null }
    }

    private fun nextCut(): Int? {
        var inFence = false
        var i = 0
        while (i < buf.length) {
            // Fence delimiters toggle; everything inside is one un-splittable block.
            if (buf.startsWith("```", i)) {
                inFence = !inFence
                i += 3
                continue
            }
            if (!inFence && i + 1 >= minChars) {
                val c = buf[i]
                if (c == '\n') return i + 1
                if ((c == '.' || c == '!' || c == '?') && i + 1 < buf.length && buf[i + 1].isWhitespace()) {
                    return i + 1
                }
            }
            i++
        }
        return null
    }
}
