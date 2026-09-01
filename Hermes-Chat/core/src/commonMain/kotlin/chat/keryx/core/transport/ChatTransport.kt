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
 * attachments, media bytes, typing, read markers, reactions, history-around-an-event. It is
 * deliberately NOT a lowest common denominator of everything one side can do — the Matrix-only
 * verbs (invites, avatars, room creation, membership, redaction) live behind [matrix], and the
 * gateway-only verbs (session lifecycle, server-side search) behind [gateway], so the UI asks
 * "does this transport have invites?" instead of pretending every transport has everything and
 * failing quietly on the one that doesn't.
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

    /**
     * Toggle a reaction [emoji] on [eventId]. Tapback semantics on both transports: re-sending
     * the emoji you already left retracts it. (Matrix redacts the annotation event; the gateway
     * enforces one reaction per author per message in its own store.)
     */
    suspend fun react(roomId: String, eventId: String, emoji: String)

    /**
     * Live aggregated reactions on [eventId] (emoji -> count + whether the current user reacted).
     * On Matrix this updates the moment anyone reacts; on direct it reflects hydration plus this
     * client's own reacts (the gateway has no reaction push yet).
     */
    fun reactionsFlow(roomId: String, eventId: String): Flow<List<MessageReaction>>

    /** Who's typing in [roomId], split agent vs humans. The agent's typing signal is a reliable
     *  "agent is busy" indicator even through long single tool calls. */
    fun typing(roomId: String): Flow<TypingState>

    /** Broadcast our own typing state into [roomId] so other clients see us composing. */
    suspend fun setTyping(roomId: String, typing: Boolean)

    /** End the current session and clear local transport state. */
    suspend fun logout()

    /** The Matrix-only surface, or null on a transport that doesn't speak Matrix. */
    val matrix: MatrixCapabilities?

    /** The gateway-only surface, or null on a transport whose rooms aren't gateway sessions. */
    val gateway: GatewayCapabilities?
}

/**
 * What only the direct (gateway) transport can do. Its rooms are real gateway sessions, so the
 * lifecycle verbs a homeserver would own — create, rename, delete, archive — go straight to the
 * gateway, and search runs server-side over full message content, which a client can never do
 * for itself (local filtering only sees the titles it already downloaded). Non-null from
 * [ChatTransport.gateway] on the direct path, null on Matrix — the exact mirror of
 * [MatrixCapabilities], so the UI asks the same kind of question in both directions instead of
 * branching on a transport flag.
 */
interface GatewayCapabilities {
    /** Create a fresh gateway session, optionally titled, and return its stored id. */
    suspend fun createSession(title: String?): Result<String>

    /** Retitle a session (the drawer name; the gateway's auto-title stops competing). */
    suspend fun renameSession(sessionId: String, title: String): Result<Unit>

    /** Permanently delete a session and its transcript from the gateway. */
    suspend fun deleteSession(sessionId: String): Result<Unit>

    /** Soft-hide: the session leaves the list but survives on the gateway. */
    suspend fun archiveSession(sessionId: String): Result<Unit>

    /**
     * Server-side session search: FTS over message content plus session-id prefix matching,
     * deduped by compression lineage (one logical chat split across stored ids answers once).
     */
    suspend fun searchSessions(query: String, limit: Int = 20): Result<List<SessionSearchHit>>

    /**
     * The gateway's model catalog, with [sessionId]'s live model overlaid on the config
     * default. A slow call on the gateway side (live pricing + endpoint probes) — fetch when
     * the picker opens, never per keystroke.
     */
    suspend fun modelOptions(sessionId: String): Result<chat.keryx.core.model.ModelCatalog>

    /**
     * Route [sessionId] to [model] on [provider] for the rest of the session (`/model … --session`
     * grammar). [confirm] answers a prior [chat.keryx.core.model.ModelSwitchOutcome.confirmRequired].
     */
    suspend fun selectModel(
        sessionId: String,
        model: String,
        provider: String?,
        confirm: Boolean = false,
    ): Result<chat.keryx.core.model.ModelSwitchOutcome>

    // ---- Projects: the gateway's native workspace grouping (`projects.*`). Membership is
    // cwd-derived — a session belongs to whichever project claims its working directory, so
    // "moving into a project" re-homes the workspace and "new chat here" is a session born
    // with that cwd. The whole surface is direct-door: Matrix rooms are profiles, not folders.

    suspend fun projectsTree(): Result<chat.keryx.core.model.ProjectsTree>
    suspend fun projectSessions(projectId: String): Result<chat.keryx.core.model.ProjectTreeNode?>
    suspend fun projectsCatalog(): Result<chat.keryx.core.model.ProjectsCatalog>
    suspend fun createProject(name: String, folderPath: String?): Result<chat.keryx.core.model.ProjectInfo>
    suspend fun deleteProject(projectId: String): Result<Unit>
    suspend fun archiveProject(projectId: String, restore: Boolean = false): Result<Unit>
    /** Child folders of [query] on the gateway (`complete.path`, `@folder:` mode). */
    suspend fun listFolders(query: String): Result<chat.keryx.core.model.FolderPage>
    suspend fun folderExists(path: String): Result<Boolean>
    suspend fun moveSessionToProject(sessionId: String, cwd: String): Result<Unit>
    /** A session born inside [cwd]; returns its stored id. */
    suspend fun createSessionIn(title: String?, cwd: String): Result<String>
    /** Make a session the list does not carry (another machine's, a project's) openable by
     *  id — it joins the local roster under [title] until the server lists it. */
    fun adoptSession(sessionId: String, title: String)

    // The Shipyard is NOT on this seam: its git routes live on the keryx payload surface
    // (Hermes Link base), which both doors reach the same way — see ShipyardRest. Routing
    // it through the transport left Matrix silently empty and aimed direct at a server
    // that never mounts the routes (the 2.6.0 device walk, 08-31).
}

/** One hit from the gateway's session search: the session, plus the line that matched. */
data class SessionSearchHit(
    val sessionId: String,
    val title: String,
    /** The matching message text, with the query wrapped in `>>>…<<<` by the server. */
    val snippet: String,
    val role: String,
    val lastActive: Long,
)

/**
 * What only a Matrix transport can do. Non-null from [ChatTransport.matrix] on Matrix, null on
 * the direct path — the UI gates each affordance on its presence instead of catching failures.
 */
interface MatrixCapabilities {
    /** Password login against the configured homeserver. Url + insecure flag come from settings. */
    suspend fun login(username: String, password: String): Result<Unit>

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
