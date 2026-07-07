package chat.keryx.app.domain.repository

import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.MessageReaction
import chat.keryx.app.domain.model.RoomInvite
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /** Emits true once a Matrix session is active (logged in or restored). */
    fun isLoggedIn(): Flow<Boolean>

    /** The logged-in user's Matrix ID, or null when signed out. */
    fun currentUserId(): Flow<String?>

    fun getRooms(): Flow<List<RoomProfile>>
    fun getSessions(roomId: String): Flow<List<Session>>

    /** Materialize up to [limit] recent timeline events. Increasing [limit] backfills older history. */
    fun getMessages(sessionId: String, limit: Int): Flow<List<Message>>
    suspend fun sendMessage(sessionId: String, content: String)

    /** Send a text message that quote-replies to [replyToEventId]. */
    suspend fun sendReply(sessionId: String, content: String, replyToEventId: String)

    /** Toggle a reaction [emoji] on [eventId]. */
    suspend fun react(sessionId: String, eventId: String, emoji: String)

    /**
     * Live aggregated reactions on [eventId] (emoji -> count + whether the current user reacted).
     * Unlike a one-shot fetch this updates the moment a reaction is added or redacted by anyone, so
     * incoming reactions no longer appear "a chat late".
     */
    fun reactionsFlow(sessionId: String, eventId: String): Flow<List<MessageReaction>>

    /** Download bytes for a media message (handles both plaintext mxc and E2EE-encrypted files). */
    suspend fun mediaBytes(sessionId: String, eventId: String): ByteArray?

    /** Upload and send a media attachment (image -> m.image, otherwise m.file). */
    suspend fun sendAttachment(sessionId: String, bytes: ByteArray, fileName: String, contentType: String)

    /** Mark the room read up to [eventId] (sends read receipt + fully-read marker). */
    suspend fun markRead(roomId: String, eventId: String)

    /** Emits true while someone OTHER than me is typing in [sessionId]. Hermes sends typing while it
     *  works, so this is a reliable "agent is busy" signal even through long single tool calls. */
    fun othersTyping(sessionId: String): Flow<Boolean>

    /** Download bytes for a Matrix mxc:// content URI (e.g. a room avatar), or null on failure. */
    suspend fun avatarBytes(mxc: String): ByteArray?

    /** Upload [bytes] and set it as the room's avatar (server-side m.room.avatar state event). */
    suspend fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String): Result<Unit>

    /** Rooms this account is invited to but hasn't joined (live — updates on sync). */
    fun getInvites(): Flow<List<RoomInvite>>

    /** Accept an invite: join the room. It enters [getRooms] on the next sync. */
    suspend fun acceptInvite(roomId: String): Result<Unit>

    /** Leave a room — also how an invite is declined (same Matrix call). */
    suspend fun leaveRoom(roomId: String): Result<Unit>

    /** Broadcast our own m.typing state into [roomId] so other clients see us composing. */
    suspend fun setTyping(roomId: String, typing: Boolean)

    /** Redact (delete) a message. Own messages always work; others need room power. */
    suspend fun redactMessage(sessionId: String, eventId: String): Result<Unit>

    /** Password login against the configured homeserver. Url + insecure flag come from settings. */
    suspend fun login(username: String, password: String): Result<Unit>

    /** End the current Matrix session and clear the local client. */
    suspend fun logout()
}
