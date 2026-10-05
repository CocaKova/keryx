package chat.keryx.core.model

/**
 * Intentional silence (Bot Mode, 2.17.3): an agent with nothing to add may end a turn with one
 * of these markers. The turn stays in the transcript; a Bot Chat renders nothing for it and
 * nobody is notified. Ported from the gateway's `gateway/response_filters.py` so both sides
 * agree on exactly which replies are silence: the WHOLE reply must be the marker (case and
 * spacing normalized, stray edge punctuation like `*NO_REPLY*` forgiven); prose that merely
 * mentions one, a blank reply, or a failed turn is never silence.
 */
object SilenceTokens {
    val MARKERS: Set<String> = setOf(
        "[SILENT]", "SILENT", "NO_REPLY", "NO REPLY",
        "[静默]", "静默", "[沉默]", "沉默",
    )

    /** Longer than any marker could plausibly be, even with stray punctuation. */
    private const val LENGTH_CAP = 64

    fun isSilent(text: String?): Boolean {
        val stripped = text?.trim().orEmpty()
        if (stripped.isEmpty() || stripped.length > LENGTH_CAP) return false
        val bare = stripEdgePunctuation(stripped)
        return canonical(stripped) in MARKERS || canonical(bare) in MARKERS
    }

    /** A finished agent reply that is pure silence — the transcript and the notifier drop it. */
    fun isSilentReply(m: Message): Boolean =
        m.sender == SenderType.HERMES && m.mediaKind == null && m.failure == null &&
            !m.isStreaming && isSilent(m.content)

    private fun canonical(s: String): String = s.trim().uppercase().split(Regex("\\s+")).joinToString(" ")

    // Square brackets stay structural so a malformed `[SILENT` cannot become `SILENT`.
    private fun isEdgePunctuation(c: Char): Boolean = c != '[' && c != ']' && c.category in PUNCTUATION

    private fun stripEdgePunctuation(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isEdgePunctuation(s[start])) start++
        while (end > start && isEdgePunctuation(s[end - 1])) end--
        return s.substring(start, end).trim()
    }

    private val PUNCTUATION = setOf(
        CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION, CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
    )
}
