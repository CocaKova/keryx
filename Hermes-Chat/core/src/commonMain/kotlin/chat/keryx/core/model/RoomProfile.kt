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
)

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
