package chat.keryx.app.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val sender: SenderType,
    val content: String,
    val timestamp: Long,
    val senderId: String = "",
    val senderName: String = "",
    val isStreaming: Boolean = false,
    val toolActivity: ToolActivity? = null,
    val mediaUrl: String? = null,             // mxc:// for media messages (null when E2EE-encrypted)
    val mediaKind: MediaKind? = null,
    val fileName: String = "",
    val replyToId: String? = null,            // event id this message is a reply to, if any
)

/** A single aggregated reaction on a message. */
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

enum class MediaKind { IMAGE, AUDIO, VIDEO, FILE }

enum class SenderType {
    ME,      // the logged-in user (right-aligned)
    HERMES,  // the configured Hermes agent
    OTHER,   // any other participant
    SYSTEM
}

data class ToolActivity(
    val name: String,
    val status: ToolStatus
)

enum class ToolStatus {
    EXECUTING,
    COMPLETED,
    FAILED
}
