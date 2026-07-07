package chat.keryx.app.domain.model

data class RoomProfile(
    val id: String,
    val name: String,
    val type: RoomType,
    val timestamp: Long = 0L,
    val unreadCount: Long = 0L,
    val avatarUrl: String? = null,
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
