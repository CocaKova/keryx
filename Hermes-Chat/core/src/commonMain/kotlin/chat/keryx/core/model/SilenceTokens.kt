package chat.keryx.core.model

/**
 * Intentional silence (Bot Mode, 2.17.3): an agent with nothing to add may end a turn with one
 * of these markers. The turn stays in the transcript; Keryx renders nothing for it and nobody
 * is notified, but only when a machine started the turn (another bot, a background process,
 * a scheduled run). A turn you started always shows its answer, even a bare "No reply.": the
 * gateway's `silence_allowed` gives a human turn a visible fallback, and the direct door has no
 * such fallback, so Keryx keeps the words. Ported from `gateway/response_filters.py` so both sides
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

    /** A finished agent reply that is only a marker. Whether it may vanish is [mayVanish]'s call. */
    fun isSilentReply(m: Message): Boolean =
        m.sender == SenderType.HERMES && m.mediaKind == null && m.failure == null &&
            !m.isStreaming && isSilent(m.content)

    /**
     * May [reply] render as nothing? [trigger] is the nearest earlier message not from the agent
     * (the row that started its turn). Unknown (null) or yours: no, the words stay.
     */
    fun mayVanish(reply: Message, trigger: Message?): Boolean =
        isSilentReply(reply) && trigger != null && trigger.sender != SenderType.ME

    /** Ids in [chrono] (oldest first) that [mayVanish]; [before] is the message just before it. */
    fun vanishingIds(chrono: List<Message>, before: Message? = null): Set<String> {
        var trigger = before?.takeIf { it.sender != SenderType.HERMES }
        val out = HashSet<String>()
        for (m in chrono) {
            if (m.sender != SenderType.HERMES) trigger = m
            else if (mayVanish(m, trigger)) out += m.id
        }
        return out
    }

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
