package chat.keryx.core.transport

import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomInvite
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.TypingState
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the one UI tree and however messages actually travel.
 *
 * This is what every transport genuinely satisfies: a room list, a message flow, send/reply,
 * attachments, media bytes, typing, read markers, history-around-an-event. It is deliberately
 * NOT a lowest common denominator of everything the Matrix path can do — the Matrix-only verbs
 * (invites, reactions, avatars, room creation, membership, redaction) live behind [matrix], so
 * the UI asks "does this transport have reactions?" instead of pretending every transport has
 * everything and failing quietly on the one that doesn't.
 *
 * "Room" here is the transport's unit of conversation: a Matrix room on the Matrix path, a
 * gateway session on the direct path. The word "session" is not used — it was freed in 2.x
 * (the vestigial Keryx Session died) and now means only a real gateway session.
 */
interface ChatTransport {
    /** Emits true once the transport is connected and authenticated (logged in or restored). */
    fun isLoggedIn(): Flow<Boolean>

    /** The authenticated identity (Matrix MXID / gateway user), or null when signed out. */
    fun currentUserId(): Flow<String?>

    fun getRooms(): Flow<List<RoomProfile>>

    /** Materialize up to [limit] recent timeline events. Increasing [limit] backfills older history. */
    fun getMessages(roomId: String, limit: Int): Flow<List<Message>>

    /**
     * A one-shot window of history around [eventId] (the Archive's context view): up to [before]
     * events older and [after] newer, anchor included, oldest-first. Gaps are fetched from the
     * server as needed; the call is time-bounded and returns what resolved.
     */
    suspend fun messagesAround(roomId: String, eventId: String, before: Int, after: Int): List<Message>

    suspend fun sendMessage(roomId: String, content: String)

    /** Send a text message that quote-replies to [replyToEventId]. */
    suspend fun sendReply(roomId: String, content: String, replyToEventId: String)

    /** Download bytes for a media message (plaintext or E2EE-encrypted on Matrix; a file URL on direct). */
    suspend fun mediaBytes(roomId: String, eventId: String): ByteArray?

    /** Upload and send a media attachment (image -> image, otherwise file). A non-null [caption]
     *  rides with the media so text + media land as one turn. */
    suspend fun sendAttachment(roomId: String, bytes: ByteArray, fileName: String, contentType: String, caption: String? = null)

    /** Mark the room read up to [eventId]. */
    suspend fun markRead(roomId: String, eventId: String)

    /** Who's typing in [roomId], split agent vs humans. The agent's typing signal is a reliable
     *  "agent is busy" indicator even through long single tool calls. */
    fun typing(roomId: String): Flow<TypingState>

    /** Broadcast our own typing state into [roomId] so other clients see us composing. */
    suspend fun setTyping(roomId: String, typing: Boolean)

    /** End the current session and clear local transport state. */
    suspend fun logout()

    /** The Matrix-only surface, or null on a transport that doesn't speak Matrix. */
    val matrix: MatrixCapabilities?
}

/**
 * What only a Matrix transport can do. Non-null from [ChatTransport.matrix] on Matrix, null on
 * the direct path — the UI gates each affordance on its presence instead of catching failures.
 */
interface MatrixCapabilities {
    /** Password login against the configured homeserver. Url + insecure flag come from settings. */
    suspend fun login(username: String, password: String): Result<Unit>

    /** Toggle a reaction [emoji] on [eventId]. */
    suspend fun react(roomId: String, eventId: String, emoji: String)

    /**
     * Live aggregated reactions on [eventId] (emoji -> count + whether the current user reacted).
     * Updates the moment a reaction is added or redacted by anyone.
     */
    fun reactionsFlow(roomId: String, eventId: String): Flow<List<MessageReaction>>

    /** Redact (delete) a message. Own messages always work; others need room power. */
    suspend fun redactMessage(roomId: String, eventId: String): Result<Unit>

    /** Pull the full member list for [roomId] (lazy-loaded members otherwise stay raw MXIDs). */
    suspend fun ensureMembersLoaded(roomId: String)

    /** Download bytes for an mxc:// content URI (e.g. a room avatar), or null on failure. */
    suspend fun avatarBytes(mxc: String): ByteArray?

    /** Upload [bytes] and set it as the room's avatar (server-side m.room.avatar state event). */
    suspend fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String): Result<Unit>

    /** Rooms this account is invited to but hasn't joined (live — updates on sync). */
    fun getInvites(): Flow<List<RoomInvite>>

    /** Accept an invite: join the room. It enters the room list on the next sync. */
    suspend fun acceptInvite(roomId: String): Result<Unit>

    /** Open (or create) a direct-message room with [userId]. Returns the room id. */
    suspend fun startDirectMessage(userId: String): Result<String>

    /** Create a private room named [name], optionally inviting [inviteUserIds]. Returns room id. */
    suspend fun createRoom(name: String, inviteUserIds: List<String>): Result<String>

    /** Join a room by `#alias:server` or `!roomid:server`. Returns the joined room id. */
    suspend fun joinRoomByAddress(address: String): Result<String>

    /** Invite [userId] into [roomId] (needs invite power in the room). */
    suspend fun inviteUser(roomId: String, userId: String): Result<Unit>

    /** Leave a room — also how an invite is declined (same Matrix call). */
    suspend fun leaveRoom(roomId: String): Result<Unit>
}
