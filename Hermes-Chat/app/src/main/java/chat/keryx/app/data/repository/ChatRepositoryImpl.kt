package chat.keryx.app.data.repository

import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.domain.model.MediaKind
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.MessageReaction
import chat.keryx.app.domain.model.RoomInvite
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.domain.model.RoomType
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.domain.model.Session
import chat.keryx.app.domain.model.TypingState
import chat.keryx.app.domain.repository.ChatRepository
import chat.keryx.app.domain.repository.SettingsRepository
import io.ktor.http.ContentType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import net.folivo.trixnity.utils.toByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import net.folivo.trixnity.client.media
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.room.message.file
import net.folivo.trixnity.client.room.message.image
import net.folivo.trixnity.client.room.message.video
import net.folivo.trixnity.client.room.message.react
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.Room
import net.folivo.trixnity.client.user
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.clientserverapi.model.rooms.CreateRoom
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.DirectEventContent
import net.folivo.trixnity.core.model.events.m.ReactionEventContent
import net.folivo.trixnity.core.model.events.m.RelationType
import net.folivo.trixnity.core.model.events.m.room.AvatarEventContent
import net.folivo.trixnity.core.model.events.m.room.ImageInfo
import net.folivo.trixnity.core.model.events.m.room.Membership
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent

/**
 * Bridges the app's domain layer onto the Trixnity Matrix SDK (via [MatrixService]).
 * In Matrix a room *is* the conversation, so each room maps to one [Session].
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class ChatRepositoryImpl(
    private val matrix: MatrixService,
    private val settingsRepository: SettingsRepository,
) : ChatRepository {

    override fun isLoggedIn(): Flow<Boolean> = matrix.client.map { it != null }

    override fun currentUserId(): Flow<String?> = matrix.client.map { it?.userId?.full }

    override fun getRooms(): Flow<List<RoomProfile>> =
        matrix.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList())
            else client.room.getAll().flatMapLatest { roomMap ->
                val roomFlows = roomMap.values.toList()
                if (roomFlows.isEmpty()) flowOf(emptyList())
                else combine(roomFlows) { rooms ->
                    rooms.filterNotNull()
                        .filter { it.membership == Membership.JOIN }
                        .sortedByDescending { it.lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L }
                        .map { it.toProfile() }
                }
            }
        }

    override fun getSessions(roomId: String): Flow<List<Session>> =
        getRooms().map { rooms ->
            rooms.filter { it.id == roomId }.map { Session(it.id, it.id, it.name, 0L) }
        }

    override fun getMessages(sessionId: String, limit: Int): Flow<List<Message>> {
        val roomId = RoomId(sessionId)
        val agentId = settingsRepository.agentMatrixId
        return matrix.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList())
            else {
                val myId = client.userId.full
                // Room shape decides the blank-agent-id fallback (see senderTypeOf).
                // distinctUntilChanged: the Room object churns on every message, but the derived
                // boolean only flips when membership crosses the DM threshold — never rebuild the
                // timeline pipeline for ordinary traffic.
                client.room.getById(roomId)
                    .map { legacyAgentRoomOf(it) }
                    .distinctUntilChanged()
                    .flatMapLatest { legacyAgentRoom ->
                client.room.getLastTimelineEvents(roomId).flatMapLatest { pagingFlow ->
                    // pagingFlow emits one Flow<TimelineEvent> per event, newest first (null when empty).
                    // Taking more from it backfills older history from the server.
                    val eventFlows = pagingFlow?.take(limit)?.toList().orEmpty()
                    if (eventFlows.isEmpty()) flowOf(emptyList())
                    // Give every inner event-flow an immediate initial `null` (onStart) so combine()
                    // emits right away instead of blocking until ALL events have resolved. A single
                    // slow / undecryptable / gap event used to stall combine forever and freeze the
                    // whole room to empty — the likely cause of "messages missing" and the empty room
                    // after resync. Unresolved events simply stay null and are filtered out.
                    else combine(
                        eventFlows.map { ev -> ev.map { it as TimelineEvent? }.onStart { emit(null) } }
                    ) { events ->
                        events.filterNotNull()
                            .reversed() // newest-first -> oldest-first for display
                            .mapNotNull { it.toMessage(myId, agentId, legacyAgentRoom) }
                            // The gateway occasionally emits empty placeholder messages (an edit
                            // target that never got its edit while protocol streaming is
                            // suppressed). They render as stray timestamp rows and poison the
                            // "is it still working?" classifier — drop them.
                            .filter { it.content.isNotBlank() || it.mediaKind != null }
                            .distinctBy { it.id } // guard against any double-emission of the same event
                    }
                }
                    }
            }
        }
            // Trixnity can throw "Detected a loop in timeline generation" on a corrupted timeline
            // (e.g. after heavy m.replace edit-streaming). That used to crash the whole app on launch
            // when restoring the offending room. Swallow it so the room degrades to empty instead.
            .catch { e ->
                android.util.Log.e("KeryxTimeline", "timeline flow failed for $sessionId: ${e.message}", e)
                emit(emptyList())
            }
            // Every NEW timeline event re-emits the paging flow, so flatMapLatest tears the inner
            // combine down and rebuilds it: one all-null (empty) emission, then a staircase of
            // partial lists as each event re-resolves from the store. Downstream that read as the
            // room flashing empty and messages popping back one by one (replayed item animations,
            // scroll jumps, the working banner re-lighting) — worst on a cold reopen. Two guards:
            //  * never emit a transient empty list once this flow instance has shown content;
            //  * debounce the staircase so only the settled list reaches the UI.
            .let { upstream ->
                var hadContent = false
                upstream.filter { list ->
                    if (list.isNotEmpty()) hadContent = true
                    list.isNotEmpty() || !hadContent
                }
            }
            .debounce(80)
            // Resolve sender display names from the room's member store, AFTER the debounce so a
            // name lookup never adds churn to the settled list. Names change rarely; unresolved
            // members keep the raw MXID (the UI's localpart fallback still applies).
            .flatMapLatest { msgs -> withSenderNames(roomId, msgs) }
    }

    private fun withSenderNames(roomId: RoomId, msgs: List<Message>): Flow<List<Message>> {
        val client = matrix.client.value ?: return flowOf(msgs)
        val senderIds = msgs.asSequence().map { it.senderId }.filter { it.startsWith("@") }.distinct().toList()
        if (senderIds.isEmpty()) return flowOf(msgs)
        return combine(
            senderIds.map { sid ->
                client.user.getById(roomId, UserId(sid))
                    .map { it?.name }
                    .onStart { emit(null) } // emit immediately; the raw list must never stall on a lookup
            }
        ) { names ->
            val nameById = senderIds.indices.associate { senderIds[it] to names[it] }
            msgs.map { m ->
                val resolved = nameById[m.senderId]
                if (resolved.isNullOrBlank() || resolved == m.senderName) m else m.copy(senderName = resolved)
            }
        }
    }

    override suspend fun sendMessage(sessionId: String, content: String) {
        val client = matrix.client.value ?: return
        client.room.sendMessage(RoomId(sessionId)) { text(content) }
    }

    override suspend fun sendReply(sessionId: String, content: String, replyToEventId: String) {
        val client = matrix.client.value ?: return
        val roomId = RoomId(sessionId)
        val target = runCatching {
            client.room.getTimelineEvent(roomId, EventId(replyToEventId)).first()
        }.getOrNull()
        client.room.sendMessage(roomId) {
            if (target != null) reply(target)
            text(content)
        }
    }

    override suspend fun react(sessionId: String, eventId: String, emoji: String) {
        val client = matrix.client.value ?: return
        val roomId = RoomId(sessionId)
        val myId = client.userId.full
        // If we already reacted with this emoji, redact that reaction (toggle off); otherwise add it.
        val mine = runCatching {
            client.room.getTimelineEventRelations(roomId, EventId(eventId), RelationType.Annotation).first()
                ?.keys?.firstNotNullOfOrNull { reactionId ->
                    val ev = runCatching { client.room.getTimelineEvent(roomId, reactionId).first() }.getOrNull()
                    val rc = ev?.content?.getOrNull() as? ReactionEventContent
                    if (rc?.relatesTo?.key == emoji && ev?.event?.sender?.full == myId) reactionId else null
                }
        }.getOrNull()
        if (mine != null) {
            runCatching { client.api.room.redactEvent(roomId, mine) }
        } else {
            runCatching { client.room.sendMessage(roomId) { react(EventId(eventId), emoji) } }
        }
    }

    override fun reactionsFlow(sessionId: String, eventId: String): Flow<List<MessageReaction>> =
        matrix.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList())
            else {
                val roomId = RoomId(sessionId)
                val myId = client.userId.full
                // The set of annotation (reaction) relations updates live as reactions are added or
                // redacted by anyone — that's what makes incoming reactions appear immediately.
                client.room.getTimelineEventRelations(roomId, EventId(eventId), RelationType.Annotation)
                    .flatMapLatest { relations ->
                        val reactionIds = relations?.keys?.toList().orEmpty()
                        if (reactionIds.isEmpty()) flowOf(emptyList())
                        else combine(
                            // onStart(null) so combine emits as soon as any reaction resolves rather
                            // than blocking until every one has (mirrors the timeline fix).
                            reactionIds.map { rid ->
                                client.room.getTimelineEvent(roomId, rid)
                                    .map { it as TimelineEvent? }
                                    .onStart { emit(null) }
                            }
                        ) { events ->
                            // emoji -> (count, anyMine)
                            val agg = LinkedHashMap<String, Pair<Int, Boolean>>()
                            events.filterNotNull().forEach { ev ->
                                val rc = ev.content?.getOrNull() as? ReactionEventContent ?: return@forEach
                                val key = rc.relatesTo?.key ?: return@forEach
                                val mine = ev.event.sender.full == myId
                                val (count, wasMine) = agg[key] ?: (0 to false)
                                agg[key] = (count + 1) to (wasMine || mine)
                            }
                            agg.map { (emoji, v) -> MessageReaction(emoji, v.first, v.second) }
                        }
                    }
            }
        }
            .catch { e ->
                android.util.Log.w("KeryxReactions", "reactions flow failed for $eventId: ${e.message}")
                emit(emptyList())
            }

    override suspend fun mediaBytes(sessionId: String, eventId: String): ByteArray? {
        val client = matrix.client.value ?: return null
        val roomId = RoomId(sessionId)
        return runCatching {
            // Wait (briefly) for the event to resolve to decrypted FileBased content — for E2EE
            // rooms the first emission can arrive before decryption finishes.
            val c = withTimeoutOrNull(20_000) {
                client.room.getTimelineEvent(roomId, EventId(eventId))
                    .map { it?.content?.getOrNull() }
                    .first { it is RoomMessageEventContent.FileBased }
            } as? RoomMessageEventContent.FileBased
            if (c == null) {
                android.util.Log.w("KeryxMedia", "no FileBased content for $eventId")
                return null
            }
            val encrypted = c.file
            val url = c.url
            val info = c.info as? ImageInfo
            android.util.Log.i(
                "KeryxMedia",
                "loading $eventId class=${c::class.simpleName} encrypted=${encrypted != null} url=$url " +
                    "thumbUrl=${info?.thumbnailUrl} thumbFile=${info?.thumbnailFile != null}",
            )
            // Try the full media first; if that fails (e.g. authenticated-media quirks or a slow
            // upload), fall back to the thumbnail so the user at least sees the image.
            suspend fun fetch(enc: net.folivo.trixnity.core.model.events.m.room.EncryptedFile?, u: String?): ByteArray? =
                runCatching {
                    val media = when {
                        enc != null -> client.media.getEncryptedMedia(enc).getOrThrow()
                        u != null -> client.media.getMedia(u).getOrThrow()
                        else -> return null
                    }
                    android.util.Log.i("KeryxMedia", "got PlatformMedia for $eventId, collecting bytes…")
                    // PlatformMedia is a Flow<ByteArray>; collect it directly. (The member
                    // toByteArray(scope,…) deadlocks when wrapped in coroutineScope{}.)
                    val flow: kotlinx.coroutines.flow.Flow<ByteArray> = media
                    val bytes = flow.toByteArray()
                    android.util.Log.i("KeryxMedia", "collected ${bytes?.size ?: -1} bytes for $eventId")
                    bytes
                }.onFailure { android.util.Log.w("KeryxMedia", "fetch failed for $eventId: ${it.message}") }.getOrNull()

            fetch(encrypted, url)
                ?: fetch(info?.thumbnailFile, info?.thumbnailUrl)
                ?: run { android.util.Log.w("KeryxMedia", "all media fetches returned null for $eventId"); null }
        }.onFailure { android.util.Log.w("KeryxMedia", "load failed for $eventId: ${it.message}") }.getOrNull()
    }

    override suspend fun sendAttachment(sessionId: String, bytes: ByteArray, fileName: String, contentType: String, caption: String?) {
        val client = matrix.client.value ?: return
        val ct = ContentType.parse(contentType)
        // MSC2530: with a caption, body carries the user's words and fileName keeps the real name —
        // one event, so Hermes sees the question and the image as a single turn instead of
        // describing the image first and answering from memory after.
        val body = caption?.takeIf { it.isNotBlank() } ?: fileName
        client.room.sendMessage(RoomId(sessionId)) {
            when (ct.contentType) {
                "image" -> image(body = body, image = flowOf(bytes), fileName = fileName, type = ct, size = bytes.size.toLong())
                "video" -> video(body = body, video = flowOf(bytes), fileName = fileName, type = ct, size = bytes.size.toLong())
                else -> file(body = body, file = flowOf(bytes), fileName = fileName, type = ct, size = bytes.size.toLong())
            }
        }
    }

    override fun typing(sessionId: String): Flow<TypingState> =
        matrix.client.flatMapLatest { client ->
            if (client == null) flowOf(TypingState())
            else {
                val roomId = RoomId(sessionId)
                val myId = client.userId
                val agentId = settingsRepository.agentMatrixId
                client.room.getById(roomId)
                    .map { legacyAgentRoomOf(it) }
                    .distinctUntilChanged()
                    .flatMapLatest { legacyAgentRoom ->
                        // RoomService.usersTyping is a live map of roomId -> who's typing (m.typing EDU).
                        client.room.usersTyping.flatMapLatest { byRoom ->
                            val typers = byRoom[roomId]?.users.orEmpty().filter { it != myId }
                            val (agents, humans) = typers.partition {
                                senderTypeOf(it.full, myId.full, agentId, legacyAgentRoom) == SenderType.HERMES
                            }
                            if (humans.isEmpty()) flowOf(TypingState(agentTyping = agents.isNotEmpty()))
                            else combine(
                                humans.map { uid ->
                                    client.user.getById(roomId, uid)
                                        .map { it?.name?.takeIf { n -> n.isNotBlank() } }
                                        .onStart { emit(null) }
                                        .map { it ?: uid.full.removePrefix("@").substringBefore(':') }
                                }
                            ) { names ->
                                TypingState(agentTyping = agents.isNotEmpty(), humanNames = names.toList())
                            }
                        }
                    }
            }
        }
            .distinctUntilChanged()
            .catch { emit(TypingState()) }

    override suspend fun ensureMembersLoaded(roomId: String) {
        val client = matrix.client.value ?: return
        runCatching { client.user.loadMembers(RoomId(roomId), wait = false) }
    }

    override suspend fun markRead(roomId: String, eventId: String) {
        val client = matrix.client.value ?: return
        runCatching {
            client.api.room.setReadMarkers(
                roomId = RoomId(roomId),
                read = EventId(eventId),
                fullyRead = EventId(eventId),
            )
        }
    }

    override suspend fun avatarBytes(mxc: String): ByteArray? {
        val client = matrix.client.value ?: return null
        return runCatching {
            val media = client.media.getMedia(mxc).getOrThrow()
            val flow: kotlinx.coroutines.flow.Flow<ByteArray> = media
            val bytes = flow.toByteArray()
            android.util.Log.i("KeryxMedia", "avatar $mxc -> ${bytes?.size ?: -1} bytes")
            bytes
        }.onFailure { android.util.Log.w("KeryxMedia", "avatar $mxc failed: ${it.message}") }.getOrNull()
    }

    override suspend fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String): Result<Unit> {
        val client = matrix.client.value ?: return Result.failure(IllegalStateException("Not logged in"))
        return runCatching {
            val cacheUri = client.media.prepareUploadMedia(flowOf(bytes), ContentType.parse(contentType))
            val mxc = client.media.uploadMedia(cacheUri).getOrThrow()
            client.api.room.sendStateEvent(RoomId(roomId), AvatarEventContent(url = mxc), "").getOrThrow()
            Unit
        }
    }

    override suspend fun login(username: String, password: String): Result<Unit> =
        matrix.login(
            baseUrl = settingsRepository.homeserverUrl,
            username = username,
            password = password,
            allowInsecure = settingsRepository.allowInsecure,
        ).map { }

    override suspend fun logout() {
        runCatching { matrix.logout() }
    }

    override fun getInvites(): Flow<List<RoomInvite>> =
        matrix.client.flatMapLatest { client ->
            if (client == null) flowOf(emptyList())
            else client.room.getAll().flatMapLatest { roomMap ->
                val roomFlows = roomMap.values.toList()
                if (roomFlows.isEmpty()) flowOf(emptyList())
                else combine(roomFlows) { rooms ->
                    rooms.filterNotNull()
                        .filter { it.membership == Membership.INVITE }
                        .map { room ->
                            RoomInvite(
                                id = room.roomId.full,
                                name = room.name?.explicitName
                                    ?: room.name?.heroes?.firstOrNull()?.full
                                    ?: room.roomId.full,
                            )
                        }
                }
            }
        }

    override suspend fun acceptInvite(roomId: String): Result<Unit> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        client.api.room.joinRoom(RoomId(roomId)).getOrThrow()
        Unit
    }

    override suspend fun leaveRoom(roomId: String): Result<Unit> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        client.api.room.leaveRoom(RoomId(roomId)).getOrThrow()
    }

    /** Normalize a typed user id: "anna:server" and "anna" (using [myServer]) both become MXIDs. */
    private fun normalizeUserId(input: String, myServer: String): String {
        val t = input.trim()
        val withSigil = if (t.startsWith("@")) t else "@$t"
        return if (':' in withSigil) withSigil else "$withSigil:$myServer"
    }

    override suspend fun startDirectMessage(userId: String): Result<String> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        val target = UserId(normalizeUserId(userId, client.userId.domain))
        require(target != client.userId) { "that's you" }
        // Reuse an existing DM (m.direct) when we're still joined to it — no duplicate rooms.
        val direct = runCatching {
            client.user.getAccountData(DirectEventContent::class, "").first()
        }.getOrNull()
        val existing = direct?.mappings?.get(target)?.firstOrNull { candidate ->
            runCatching {
                client.room.getById(candidate).first()?.membership == Membership.JOIN
            }.getOrDefault(false)
        }
        if (existing != null) return@runCatching existing.full
        client.api.room.createRoom(
            invite = setOf(target),
            preset = CreateRoom.Request.Preset.TRUSTED_PRIVATE,
            // Trixnity's DirectRoomEventHandler sees the is_direct member event and writes the
            // m.direct account data for us — which then drives Room.isDirect / the DM room type.
            isDirect = true,
        ).getOrThrow().full
    }

    override suspend fun createRoom(name: String, inviteUserIds: List<String>): Result<String> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        val invitees = inviteUserIds
            .filter { it.isNotBlank() }
            .map { UserId(normalizeUserId(it, client.userId.domain)) }
            .toSet()
        client.api.room.createRoom(
            name = name.trim().ifBlank { null },
            invite = invitees,
            preset = CreateRoom.Request.Preset.PRIVATE,
        ).getOrThrow().full
    }

    override suspend fun joinRoomByAddress(address: String): Result<String> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        val t = address.trim()
        when {
            t.startsWith("#") -> client.api.room.joinRoom(RoomAliasId(t)).getOrThrow().full
            t.startsWith("!") -> client.api.room.joinRoom(RoomId(t)).getOrThrow().full
            else -> error("enter a #alias:server or !roomid:server address")
        }
    }

    override suspend fun inviteUser(roomId: String, userId: String): Result<Unit> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        val target = UserId(normalizeUserId(userId, client.userId.domain))
        client.api.room.inviteUser(RoomId(roomId), target).getOrThrow()
    }

    override suspend fun setTyping(roomId: String, typing: Boolean) {
        val client = matrix.client.value ?: return
        // 30s server-side timeout: refreshed by the caller's throttle while composing continues,
        // expires on its own if the app dies mid-thought. Best-effort — never raises.
        runCatching {
            client.api.room.setTyping(
                roomId = RoomId(roomId),
                userId = client.userId,
                typing = typing,
                timeout = if (typing) 30_000 else null,
            ).getOrThrow()
        }
    }

    override suspend fun redactMessage(sessionId: String, eventId: String): Result<Unit> = runCatching {
        val client = matrix.client.value ?: error("not logged in")
        client.api.room.redactEvent(RoomId(sessionId), EventId(eventId)).getOrThrow()
        Unit
    }

    // --- mapping helpers ---

    private fun Room.toProfile(): RoomProfile {
        val displayName = name?.explicitName
            ?: name?.heroes?.firstOrNull()?.full
            ?: roomId.full
        // m.direct account data is authoritative (Trixnity folds it into Room.isDirect); the
        // member-count heuristic stays as fallback for legacy rooms created without the flag.
        val type = if (isDirect || (name?.otherUsersCount ?: 0) <= 1) RoomType.DIRECT_MESSAGE else RoomType.SHARED_GROUP
        return RoomProfile(
            id = roomId.full,
            name = displayName,
            type = type,
            timestamp = lastRelevantEventTimestamp?.toEpochMilliseconds() ?: 0L,
            unreadCount = unreadMessageCount,
            avatarUrl = avatarUrl,
        )
    }

    private fun TimelineEvent.toMessage(myId: String, agentId: String, legacyAgentRoom: Boolean): Message? {
        val messageContent = content?.getOrNull() as? RoomMessageEventContent ?: return null
        // Hide m.replace edit events: Trixnity already applies the replacement to the ORIGINAL
        // event's content (that bubble just grows), but the replace events themselves also appear
        // in the timeline with their "* edited text" fallback bodies — which is what rendered a
        // streamed/edited reply as three or four duplicate bubbles.
        if (messageContent.relatesTo is net.folivo.trixnity.core.model.events.m.RelatesTo.Replace) return null
        val senderId = event.sender.full
        val sender = senderTypeOf(senderId, myId, agentId, legacyAgentRoom)
        var mediaUrl: String? = null
        var mediaKind: MediaKind? = null
        var fileName = ""
        when (messageContent) {
            is RoomMessageEventContent.FileBased.Image -> {
                mediaUrl = messageContent.url; mediaKind = MediaKind.IMAGE; fileName = messageContent.fileName ?: messageContent.body
            }
            is RoomMessageEventContent.FileBased.Video -> {
                mediaUrl = messageContent.url; mediaKind = MediaKind.VIDEO; fileName = messageContent.fileName ?: messageContent.body
            }
            is RoomMessageEventContent.FileBased.Audio -> {
                mediaUrl = messageContent.url; mediaKind = MediaKind.AUDIO; fileName = messageContent.fileName ?: messageContent.body
            }
            is RoomMessageEventContent.FileBased.File -> {
                mediaUrl = messageContent.url; mediaKind = MediaKind.FILE; fileName = messageContent.fileName ?: messageContent.body
            }
            else -> Unit
        }
        val replyToId = messageContent.relatesTo?.replyTo?.eventId?.full
        // Matrix puts a "> quoted…" fallback in the body for replies; strip it (we render our own quote).
        val body = if (replyToId != null) stripReplyFallback(messageContent.body) else messageContent.body
        return Message(
            id = event.id.full,
            sessionId = event.roomId.full,
            sender = sender,
            content = body,
            timestamp = event.originTimestamp,
            senderId = senderId,
            senderName = senderId,
            mediaUrl = mediaUrl,
            mediaKind = mediaKind,
            fileName = fileName,
            replyToId = replyToId,
        )
    }

    /**
     * Classify a sender. A strict full-id equality here is what broke streaming handoff, telemetry
     * rendering, and the working-banner lifecycle in one stroke (live-debugged 2026-07-02): with
     * the de-personalized default (`agentMatrixId = ""`), every agent message fell to OTHER and
     * everything downstream that filtered on HERMES went blind. But the 07-02 fix overcorrected:
     * "blank agent id → any non-me sender is the agent" restyled real humans as agent output
     * (tool cards, telemetry demotion, the working banner on their typing). Now:
     *  - a configured id matches case-insensitively, and by bare localpart too, so "silas",
     *    "@silas" and "@SILAS:silas.local" all hit "@silas:silas.local";
     *  - blank agent id falls back to HERMES only in [legacyAgentRoom]s — a Hermes gateway is
     *    wired up AND the room has at most one other member (the existing single-agent-DM
     *    install base keeps agent rendering with zero config);
     *  - everyone else is OTHER: a pure-Matrix install renders humans as humans everywhere.
     */
    private fun senderTypeOf(
        senderId: String,
        myId: String,
        agentId: String,
        legacyAgentRoom: Boolean,
    ): SenderType {
        if (senderId == myId) return SenderType.ME
        val cfg = agentId.trim()
        if (cfg.isEmpty()) return if (legacyAgentRoom) SenderType.HERMES else SenderType.OTHER
        if (senderId.equals(cfg, ignoreCase = true)) return SenderType.HERMES
        val cfgLocal = cfg.removePrefix("@").substringBefore(':').lowercase()
        val senderLocal = senderId.removePrefix("@").substringBefore(':').lowercase()
        return if (cfgLocal.isNotEmpty() && senderLocal == cfgLocal) SenderType.HERMES else SenderType.OTHER
    }

    /** The blank-agent-id fallback only applies on installs that actually talk to a Hermes
     *  gateway, and only in rooms shaped like an agent DM (≤1 other member). */
    private fun legacyAgentRoomOf(room: Room?): Boolean =
        settingsRepository.gatewayConfigured && (room?.name?.otherUsersCount ?: 0) <= 1

    /** Drop the leading "> …" mx-reply fallback block (and the blank line after it) from a reply body. */
    private fun stripReplyFallback(body: String): String {
        val lines = body.lines()
        var i = 0
        while (i < lines.size && lines[i].startsWith(">")) i++
        if (i < lines.size && lines[i].isBlank()) i++
        val rest = lines.drop(i).joinToString("\n").trim()
        return rest.ifBlank { body.trim() }
    }
}
