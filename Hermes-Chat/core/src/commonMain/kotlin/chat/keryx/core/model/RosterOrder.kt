package chat.keryx.core.model

/**
 * The drawer's order is the app's to keep, not the wire's to promise.
 *
 * The gateway lists sessions newest-activity-first, and that is the order the roster shows —
 * but the roster is not only the gateway's page. A chat minted on the phone that the server
 * has not listed yet, a row the phone just spoke into or heard the agent answer in (stamped
 * locally, ahead of the next list pull), and the page itself all meet in one list. Sorting
 * that list here, by the newest activity the app knows of, is what makes "the one I am
 * talking to is at the top" hold no matter which side reported the activity or when.
 */
object RosterOrder {
    /**
     * Newest activity first. The sort is stable: rows carrying the same stamp keep their
     * incoming order (the gateway's own tie-break), and unstamped rows (0) sink to the end in
     * the order they arrived.
     */
    fun byActivity(rows: List<RoomProfile>): List<RoomProfile> =
        rows.sortedByDescending { it.timestamp }

    /**
     * Rows with the phone's own activity stamps laid over them. A local stamp wins only when
     * it is newer than what the row carries — the server's answer is never moved backwards,
     * and a row the server already lists later than the phone's clock is left alone.
     */
    fun withLocalStamps(rows: List<RoomProfile>, stamps: Map<String, Long>): List<RoomProfile> {
        if (stamps.isEmpty()) return rows
        return rows.map { r ->
            val local = stamps[r.id] ?: return@map r
            if (local > r.timestamp) r.copy(timestamp = local) else r
        }
    }

    /**
     * [stamps] with [id] stamped [now] — unless it was stamped within [slackMs] already, in
     * which case the same map comes back (a turn's per-token stream must not rebuild the
     * roster on every delta; callers skip the emit on identity).
     */
    fun stamp(stamps: Map<String, Long>, id: String, now: Long, slackMs: Long = TOUCH_SLACK_MS): Map<String, Long> {
        val prev = stamps[id] ?: 0L
        if (now - prev < slackMs) return stamps
        return stamps + (id to now)
    }

    /** A row re-stamped inside this window is "already now" — the roster is not rebuilt for it. */
    const val TOUCH_SLACK_MS = 5_000L
}
