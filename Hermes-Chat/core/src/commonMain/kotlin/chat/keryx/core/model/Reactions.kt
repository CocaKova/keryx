package chat.keryx.core.model

/**
 * One author's reaction on a message, as the gateway stores it (`display_metadata.reactions`).
 * Tapback semantics: at most one per author per message, so `author` is a natural key. The
 * author is a role name ("user", "agent"), not an identity — the gateway session has exactly
 * one of each.
 */
data class RawReaction(
    val emoji: String,
    val author: String,
)

object MessageReactions {
    /** The author name the gateway files this client's own reactions under. */
    const val SELF_AUTHOR = "user"

    /**
     * Fold per-author raw reactions into the aggregated per-emoji shape the bubble renders —
     * the same [MessageReaction] list the Matrix side computes from annotation events, so the
     * chip row is transport-blind. First-seen emoji order is kept (stable across a toggle).
     */
    fun aggregate(raw: List<RawReaction>, self: String = SELF_AUTHOR): List<MessageReaction> {
        if (raw.isEmpty()) return emptyList()
        val agg = LinkedHashMap<String, Pair<Int, Boolean>>()
        for (r in raw) {
            if (r.emoji.isEmpty()) continue
            val (count, wasMine) = agg[r.emoji] ?: (0 to false)
            agg[r.emoji] = (count + 1) to (wasMine || r.author == self)
        }
        return agg.map { (emoji, v) -> MessageReaction(emoji, v.first, v.second) }
    }
}
