package chat.keryx.app.transport.direct

/**
 * The names a live turn's rows go by on the direct door.
 *
 * A turn is drawn twice: as an overlay while it streams, and folded into the transcript the
 * moment `message.complete` lands. The list is keyed by message id, so if the fold renames a row
 * the list sees one row leave and a stranger arrive, and `animateItem` fades the old one out
 * beside its own copy — a ghost of the reply under the reply (device-caught 2026-09-21). So both
 * draws ask here, and a row keeps its name from its first token to the re-read that replaces it
 * with the gateway's own row.
 *
 * [turn] is the turn's start stamp: ids must not collide with an earlier turn's folded rows,
 * which stay in the list until that re-read.
 */
internal object LiveTurnIds {
    /** A sealed step of the turn (a text segment or a tool call), by its append-stable [seq]. */
    fun item(turn: Long, seq: Int): String = "live-$turn-step$seq"

    /** The answer: the streaming bubble, and the final message it settles into. */
    fun answer(turn: Long): String = "live-$turn-final"

    /** The thought, once it folds out of the streaming bubble into its own row above. */
    fun thought(turn: Long): String = "live-$turn-think"
}
