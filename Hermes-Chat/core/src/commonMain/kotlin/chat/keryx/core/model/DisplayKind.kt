package chat.keryx.core.model

/**
 * `display_kind` — the gateway telling us, in a column, what a regex has been guessing.
 *
 * Three of Keryx's transcript surfaces exist because the gateway files machinery under
 * `role:"user"`: compaction carry-overs (0.6.2), delegation reports (0.6.9), inter-agent
 * deliveries (0.6.11), todo re-injections. Each one is a text sniff, and a text sniff is a
 * guess that a reworded upstream string silently breaks. `display_kind` is the same claim
 * made by the side that KNOWS, so where it is present it wins.
 *
 * ⚠️ It does not cover everything, and the gap is the point of keeping both: verified live
 * against a real 0.20.2 gateway on 2026-08-17, an inter-agent delivery lands with
 * `display_kind` NULL. The regexes stay load-bearing — this is a second, better gate for the
 * rows it does classify, not a replacement for the first.
 *
 * Pure Kotlin (KMP rule): no android.* here.
 */
object DisplayKind {

    /** Model-facing scaffolding — an empty placeholder the user was never meant to read. */
    const val HIDDEN = "hidden"

    /** `/model` took effect mid-conversation. */
    const val MODEL_SWITCH = "model_switch"

    /** The personality changed mid-conversation. */
    const val PERSONALITY_SWITCH = "personality_switch"

    /** An interrupted turn was picked back up on its own. */
    const val AUTO_CONTINUE = "auto_continue"

    /** A background fan-out reported its batch complete. */
    const val ASYNC_DELEGATION_COMPLETE = "async_delegation_complete"

    /**
     * A synthetic self-injected turn (background watch notice, resume wake-up) — gateway
     * `#82888`. ⚠️ Desktop does NOT render this one specially yet (zero matches in
     * `apps/desktop/src` on 0.20.2), so it still shows there as a user bubble. We class it
     * as machinery anyway: the commit that introduced it says outright that it exists "so
     * transcripts/UIs can render them as timeline notices instead of user bubbles", and a
     * machine turn wearing the user's face is the exact bug this whole family of rows is
     * about. Divergence is deliberate.
     */
    const val INTERNAL_NOTIFICATION = "internal_notification"

    /**
     * Kinds that mean "this row is not the user speaking". [HIDDEN] is absent on purpose —
     * it is not a quiet row, it is a row with nothing in it (see [hidesText]).
     */
    private val MACHINERY = setOf(
        MODEL_SWITCH,
        PERSONALITY_SWITCH,
        AUTO_CONTINUE,
        ASYNC_DELEGATION_COMPLETE,
        INTERNAL_NOTIFICATION,
    )

    /** Does the gateway class this row as machinery rather than speech? */
    fun isMachinery(kind: String?): Boolean = kind != null && kind in MACHINERY

    /** [HIDDEN] rows carry no readable text. Tool calls on the same row still stand — the
     *  work happened, only the placeholder prose is scaffolding. */
    fun hidesText(kind: String?): Boolean = kind == HIDDEN

    /**
     * Desktop's `timelineDisplayContent` phrasing, ported verbatim so the two clients narrate
     * the same event with the same words. Null means "no canned phrase — show the row's own
     * text", which is what every unrecognised kind gets: a machine row we cannot name is
     * still better shown quietly than dressed up as the user.
     */
    fun timelineLabel(kind: String?): String? = when (kind) {
        MODEL_SWITCH -> "model changed"
        AUTO_CONTINUE -> "resumed interrupted turn"
        PERSONALITY_SWITCH -> "personality changed"
        ASYNC_DELEGATION_COMPLETE -> "background agent work finished"
        else -> null
    }
}
