package chat.keryx.app.presentation

import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.protocol.MessageParser
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bytes and small derived things the transcript and drawer ask for, cached for the session
 * (split out of [ChatViewModel] in 3.0): message media, avatars, drawer previews and live
 * reaction flows, plus the reaction send. Memory pressure trims the byte caches.
 */
class MediaDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val currentRoom: StateFlow<RoomProfile?>,
    private val rooms: StateFlow<List<RoomProfile>>,
    /** Sheds the pet picker's decoded thumbnails alongside these caches. */
    private val trimThumbs: () -> Unit,
) {
    private val scope = deps.scope
    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport
    private val matrix get() = transport.matrix

    // Media/avatar downloads are owned by the ViewModel's scope (NOT the Compose produceState coroutine),
    // so a recomposition or a brief scroll-off no longer cancels an in-flight fetch ("The coroutine
    // scope left the composition"). In-flight maps exist only to dedup concurrent requests — an
    // entry is removed the moment its download completes; completed bytes live in BYTE-BUDGETED
    // LRUs. The old unbounded Deferred maps kept every image/avatar ever viewed for the whole
    // session — a direct driver of the 514 MB marathon RSS.
    private val mediaInFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val avatarInFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val mediaBytesCache =
        chat.keryx.app.util.BoundedByteCache((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L shl 20))
    private val avatarBytesCache = chat.keryx.app.util.BoundedByteCache(2L shl 20)

    // Registered with CacheRegistry (driven by KeryxApp.onTrimMemory): under memory pressure the
    // session caches shed weight — everything here re-fetches/re-decodes on demand. Unregistered
    // in dispose() so a dead ViewModel isn't pinned by the process-wide registry.
    private val cacheTrimmer: (Boolean) -> Unit = { aggressive ->
        if (aggressive) {
            mediaBytesCache.clear()
            avatarBytesCache.clear()
            trimThumbs()
        } else {
            mediaBytesCache.trimToBytes(mediaBytesCache.sizeBytes / 2)
            avatarBytesCache.trimToBytes(avatarBytesCache.sizeBytes / 2)
        }
    }

    init {
        chat.keryx.app.util.CacheRegistry.register(cacheTrimmer)
    }

    /** A room switch: the old room's reaction flows are all unsubscribed now — drop them rather
     *  than letting them age out of the LRU while the new room fills it. */
    fun onRoomSwitched() = synchronized(reactionFlows) { reactionFlows.clear() }

    /** The ViewModel is going: a dead one must not stay pinned by the process-wide registry. */
    fun dispose() = chat.keryx.app.util.CacheRegistry.unregister(cacheTrimmer)

    suspend fun loadMessageMedia(sessionId: String, eventId: String): ByteArray? {
        mediaBytesCache.get(eventId)?.let { return it }
        val deferred = mediaInFlight.getOrPut(eventId) {
            scope.async(Dispatchers.IO) {
                transport.mediaBytes(sessionId, eventId)?.also { mediaBytesCache.put(eventId, it) }
            }.also { d -> d.invokeOnCompletion { mediaInFlight.remove(eventId, d) } }
        }
        return deferred.await() // cancellation propagates to the caller, not the download
    }

    // Drawer previews: the latest meaningful message per room, fetched lazily (only when a drawer
    // row actually composes) and cached against the room's last-event timestamp so re-opening the
    // drawer is free until new activity lands. Deliberately NOT folded into the rooms flow — that
    // would keep a timeline flow alive per room for the whole session just to serve a snippet.
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    suspend fun roomPreview(roomId: String, stamp: Long): String? {
        previewCache[roomId]?.let { (cachedStamp, preview) -> if (cachedStamp == stamp) return preview }
        // Direct door: never fetch for a preview. Subscribing to a session's messages here
        // hydrated its transcript AND resumed it on the gateway — one live agent per drawer
        // row. The opened rows already hold their newest line; the rest carry the gateway's
        // own recognition preview from the list call. (Not cached by stamp: both sources are
        // already in memory, and the store's tail moves without the row's timestamp.)
        direct?.let { d ->
            d.peekPreview(roomId)?.let { return ChatViewModel.previewOf(it) }
            return rooms.value.firstOrNull { it.id == roomId }?.preview?.takeIf { it.isNotBlank() }
        }
        val msgs = withTimeoutOrNull(5_000L) {
            transport.getMessages(roomId, 8).first { it.isNotEmpty() }
        } ?: return previewCache[roomId]?.second
        // Skip runtime footers so the preview reads as the conversation, not plumbing.
        val last = msgs.lastOrNull { !MessageParser.isRuntimeFooterMessage(it.content) } ?: msgs.lastOrNull() ?: return null
        val preview = ChatViewModel.previewOf(last)
        previewCache[roomId] = stamp to preview
        return preview
    }

    // Live reactions: a cold flow per event, from whichever transport is underneath (Matrix
    // updates the instant anyone reacts; direct reflects hydration + our own reacts). Cached by
    // event id so re-subscribing the same bubble during scroll reuses the running flow (shareIn
    // keeps it hot briefly) instead of spinning up a fresh transport subscription.
    // LRU-bounded: scrolling a marathon room used to leave one retained StateFlow per event id
    // for the ViewModel's whole life. Eviction is safe — WhileSubscribed(5s) has already stopped
    // an off-screen flow's upstream, and a re-scrolled bubble simply recreates it.
    private val reactionFlows = object : LinkedHashMap<String, kotlinx.coroutines.flow.Flow<List<MessageReaction>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, kotlinx.coroutines.flow.Flow<List<MessageReaction>>>) =
            size > REACTION_FLOW_MAX
    }

    fun reactionsFlow(sessionId: String, eventId: String): kotlinx.coroutines.flow.Flow<List<MessageReaction>> =
        synchronized(reactionFlows) {
            reactionFlows.getOrPut(eventId) {
                transport.reactionsFlow(sessionId, eventId)
                    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
            }
        }

    fun sendReaction(eventId: String, emoji: String) {
        val session = currentRoom.value ?: return
        scope.launch { transport.react(session.id, eventId, emoji) }
    }

    suspend fun loadAvatar(mxc: String): ByteArray? {
        avatarBytesCache.get(mxc)?.let { return it }
        val deferred = avatarInFlight.getOrPut(mxc) {
            scope.async(Dispatchers.IO) {
                matrix?.avatarBytes(mxc)?.also { avatarBytesCache.put(mxc, it) }
            }.also { d -> d.invokeOnCompletion { avatarInFlight.remove(mxc, d) } }
        }
        return deferred.await()
    }

    private companion object {
        // Session-cache bound: retained reaction StateFlows were unbounded before 1.19.0.
        const val REACTION_FLOW_MAX = 200
    }
}
