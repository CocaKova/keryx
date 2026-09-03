package chat.keryx.core.model

/**
 * What a notification says when an agent speaks (2.8). The old banner was room-shaped — the
 * session's name up top, a line under it — which reads fine for a human chat and wrong for an
 * agent: the thing that happened is *who* said something, and when one bot messages another the
 * sender is a third party the room name never mentions. This is the pure decision of title,
 * speaker and line; the platform side turns it into a conversation-style notification with the
 * speaker as the person.
 */
data class AgentNotice(
    /** The conversation's name — the bot's label for a Bot Chat, else the session title. */
    val conversation: String,
    /** Who is speaking, as the notification's person. */
    val speaker: String,
    /** A stable key for the speaker (bot handle or sender id) so the same voice groups. */
    val speakerKey: String,
    val line: String,
    /** True when [speaker] is another agent relayed INTO this conversation, not its owner. */
    val relayed: Boolean,
) {
    /** The single-line title a compact banner shows. */
    val title: String get() = when {
        relayed -> "$speaker → $conversation"
        speaker == conversation -> "${Heralds.SIGIL} $speaker"
        else -> "${Heralds.SIGIL} $speaker · $conversation"
    }
}

object AgentNotices {
    private const val LINE_MAX = 160

    /** The first readable line of a message body, marker-free, bounded for a banner. */
    fun line(text: String, mediaKind: MediaKind?, fileName: String): String = when {
        mediaKind == MediaKind.IMAGE -> "🖼 Photo"
        mediaKind != null -> "📎 " + fileName.ifBlank { "Attachment" }
        text.isNotBlank() -> chat.keryx.core.protocol.MessageParser.extractKeryx(text).text
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(LINE_MAX) ?: "New message"
        else -> "New message"
    }

    /**
     * Compose the notice for [message] landing in a conversation named [conversation].
     * [botLabel] / [botHandle] name the conversation's own agent when it is a Bot Chat; a
     * plain session passes null and the speaker is the session's agent under the session's
     * name. A delivery (`Message from 🤖 X`) makes X the speaker, relayed.
     */
    fun compose(
        message: Message,
        conversation: String,
        botLabel: String? = null,
        botHandle: String? = null,
    ): AgentNotice {
        val delivery = message.agentDelivery
        if (delivery != null) {
            return AgentNotice(
                conversation = botLabel ?: conversation,
                speaker = delivery.sender,
                speakerKey = "agent:" + delivery.handle.lowercase(),
                line = line(delivery.body, null, "").ifBlank { "New message" },
                relayed = true,
            )
        }
        val speaker = botLabel
            ?: message.senderName.takeIf { it.isNotBlank() && !it.startsWith("@") }
            ?: conversation
        val key = botHandle?.let { "bot:$it" }
            ?: message.senderId.takeIf { it.isNotBlank() }?.let { "sender:$it" }
            ?: "conversation:$conversation"
        return AgentNotice(
            conversation = botLabel ?: conversation,
            speaker = speaker,
            speakerKey = key,
            line = line(message.content, message.mediaKind, message.fileName),
            relayed = false,
        )
    }
}
