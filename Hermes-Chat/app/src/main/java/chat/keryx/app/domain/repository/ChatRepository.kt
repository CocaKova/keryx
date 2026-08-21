package chat.keryx.app.domain.repository

import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomInvite
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.TypingState
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /** Emits true once a Matrix session is active (logged in or restored). */
    fun isLoggedIn(): Flow<Boolean>

    /** The logged-in user's Matrix ID, or null when signed out. */
    fun currentUserId(): Flow<String?>

    fun getRooms(): Flow<List<RoomProfile>>
    /** Materialize up to [limit] recent timeline events. Increasing [limit] backfills older history. */
    fun getMessages(roomId: String, limit: Int): Flow<List<Message>>

    /**
     * A one-shot window of history around [eventId] (the Archive's context view): up to [before]
     * events older and [after] newer, anchor included, oldest-first. Timeline gaps are fetched
     * from the server as needed; the call is time-bounded and returns what resolved.
     */
    suspend fun messagesAround(roomId: String, eventId: String, before: Int, after: Int): List<Message>
    suspend fun sendMessage(roomId: String, content: String)

    /** Send a text message that quote-replies to [replyToEventId]. */
    suspend fun sendReply(roomId: String, content: String, replyToEventId: String)

    /** Toggle a reaction [emoji] on [eventId]. */
    suspend fun react(roomId: String, eventId: String, emoji: String)

    /**
     * Live aggregated reactions on [eventId] (emoji -> count + whether the current user reacted).
     * Unlike a one-shot fetch this updates the moment a reaction is added or redacted by anyone, so
     * incoming reactions no longer appear "a chat late".
     */
    fun reactionsFlow(roomId: String, eventId: String): Flow<List<MessageReaction>>

    /** Download bytes for a media message (handles both plaintext mxc and E2EE-encrypted files). */
    suspend fun mediaBytes(roomId: String, eventId: String): ByteArray?

    /** Upload and send a media attachment (image -> m.image, otherwise m.file). A non-null
     *  [caption] rides in the event body (MSC2530) so text + media land as one turn. */
    suspend fun sendAttachment(roomId: String, bytes: ByteArray, fileName: String, contentType: String, caption: String? = null)

    /** Mark the room read up to [eventId] (sends read receipt + fully-read marker). */
    suspend fun markRead(roomId: String, eventId: String)

    /** Who's typing in [roomId], split agent vs humans. Hermes sends typing while it works, so
     *  [TypingState.agentTyping] is a reliable "agent is busy" signal even through long single tool
     *  calls; human typers surface separately (display names) for a plain typing indicator. */
    fun typing(roomId: String): Flow<TypingState>

    /** Pull the full member list for [roomId] (Trixnity lazy-loads members; without this, display
     *  names in cold group rooms stay raw MXIDs until each member happens to send something). */
    suspend fun ensureMembersLoaded(roomId: String)

    /** Download bytes for a Matrix mxc:// content URI (e.g. a room avatar), or null on failure. */
    suspend fun avatarBytes(mxc: String): ByteArray?

    /** Upload [bytes] and set it as the room's avatar (server-side m.room.avatar state event). */
    suspend fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String): Result<Unit>

    /** Rooms this account is invited to but hasn't joined (live — updates on sync). */
    fun getInvites(): Flow<List<RoomInvite>>

    /** Accept an invite: join the room. It enters [getRooms] on the next sync. */
    suspend fun acceptInvite(roomId: String): Result<Unit>

    /** Open (or create) a direct-message room with [userId]. Reuses an existing joined DM from
     *  m.direct account data; otherwise creates a private room flagged is_direct. Returns the
     *  room id — it appears in [getRooms] on the next sync. */
    suspend fun startDirectMessage(userId: String): Result<String>

    /** Create a private room named [name], optionally inviting [inviteUserIds]. Returns room id. */
    suspend fun createRoom(name: String, inviteUserIds: List<String>): Result<String>

    /** Join a room by `#alias:server` or `!roomid:server`. Returns the joined room id. */
    suspend fun joinRoomByAddress(address: String): Result<String>

    /** Invite [userId] into [roomId] (needs invite power in the room). */
    suspend fun inviteUser(roomId: String, userId: String): Result<Unit>

    /** Leave a room — also how an invite is declined (same Matrix call). */
    suspend fun leaveRoom(roomId: String): Result<Unit>

    /** Broadcast our own m.typing state into [roomId] so other clients see us composing. */
    suspend fun setTyping(roomId: String, typing: Boolean)

    /** Redact (delete) a message. Own messages always work; others need room power. */
    suspend fun redactMessage(roomId: String, eventId: String): Result<Unit>

    /** Password login against the configured homeserver. Url + insecure flag come from settings. */
    suspend fun login(username: String, password: String): Result<Unit>

    /** End the current Matrix session and clear the local client. */
    suspend fun logout()
}
