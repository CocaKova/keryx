package chat.keryx.core.model

data class RoomProfile(
    val id: String,
    val name: String,
    val type: RoomType,
    val timestamp: Long = 0L,
    val unreadCount: Long = 0L,
    val avatarUrl: String? = null,
    /**
     * The configured heralds (agent MXIDs) known to be in this room, in member order.
     *
     * Read from Trixnity's member STORE only — never a forced sync. Matrix lazy-loads members,
     * so a room you have never opened contributes nothing here and simply keeps its monogram;
     * opening it once calls `ensureMembersLoaded` and the sigil is there from then on, because
     * members persist. The alternative — syncing every drawer room's full member list just to
     * decorate a 34dp circle — costs a request per room to learn something the timeline
     * already carries.
     */
    val heraldIds: List<String> = emptyList(),
    // --- gateway-session facts (direct transport) — the drawer's meta line + live shimmer.
    // Matrix rooms leave these at rest; a "room" on the direct path IS a gateway session.
    val messageCount: Long = 0L,
    val source: String = "",
    val isActive: Boolean = false,
    /** First line of the session's own content — the only readable thing about a scheduled
     *  run you have never opened. */
    val preview: String = "",
    /**
     * The gateway's own pin (the Desktop sidebar's durable "keep" flag: pinned sessions are
     * exempt from the auto-archive sweep). Server truth on the direct door; Matrix rooms have no
     * server pin and leave this false — their pins live in the phone's ledger.
     */
    val pinned: Boolean = false,
    /**
     * The gateway's read watermark, derived server-side: activity postdates the last time this
     * session was marked read. Matrix rooms count unread events instead ([unreadCount]); this
     * flag is the direct door's one-bit equivalent, so a row is "unread" when either says so.
     */
    val unread: Boolean = false,
) {
    /** Either signal: a Matrix count, or the gateway's watermark. */
    val hasUnread: Boolean get() = unreadCount > 0L || unread
}

enum class RoomType {
    DIRECT_MESSAGE,
    SHARED_GROUP,
    THREAD
}

/** A room this account is invited to but hasn't joined — surfaced for accept/decline. */
data class RoomInvite(
    val id: String,
    val name: String,
)
