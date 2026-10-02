package chat.keryx.core.model

/**
 * Find in chat (2.16): which loaded messages say [query], newest first — the order the
 * transcript's reversed list holds them, so "next" walks back in time. Every whitespace-separated
 * term must appear, case-insensitively; markdown punctuation in the message does not hide a word
 * (`**bold**` still matches "bold"). Blank finds nothing.
 */
object FindInChat {
    fun hits(messagesNewestFirst: List<Pair<String, String>>, query: String): List<String> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return messagesNewestFirst.filter { (_, text) ->
            val hay = text.lowercase()
            terms.all { it in hay }
        }.map { it.first }
    }

    /** "3 of 12", or "no match". */
    fun label(index: Int, total: Int): String = if (total == 0) "no match" else "${index + 1} of $total"
}
