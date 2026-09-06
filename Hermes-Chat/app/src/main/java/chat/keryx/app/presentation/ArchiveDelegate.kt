package chat.keryx.app.presentation

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Archive (1.26 "Mnemosyne"): local index, search, saved messages, context views. */
class ArchiveDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val archiveStore: chat.keryx.app.data.archive.ArchiveStore?,
    private val archiveIndexer: chat.keryx.app.data.archive.ArchiveSweeper?,
    /** The open room, or null — every surface here is scoped to it. */
    private val currentRoomId: () -> String?,
    /** The roster's session ids, newest first — the cross-session sweep's list (direct door). */
    private val rosterIds: () -> List<String> = { emptyList() },
) {
    private val scope = deps.scope
    private val toast = deps.toast

    // --- The Archive (1.26 "Mnemosyne") -----------------------------------------------------

    val available: Boolean get() = archiveStore != null

    /** Live progress of the current index sweep (null before the first sweep of this process). */
    val progress: StateFlow<chat.keryx.app.data.archive.ArchiveIndexer.Progress?> =
        archiveIndexer?.progress ?: MutableStateFlow(null)

    // Kept (saved) event ids for the open room — drives the bookmark state in the bubble menu.
    private val _savedIds = MutableStateFlow<Set<String>>(emptySet())
    val savedIds: StateFlow<Set<String>> = _savedIds.asStateFlow()

    fun refreshSavedIds() {
        val roomId = currentRoomId() ?: return
        val store = archiveStore ?: return
        scope.launch(Dispatchers.IO) { _savedIds.value = store.savedIds(roomId) }
    }

    /** Kick an index sweep of the open room. First ever run is the big backfill; later runs catch
     *  up on what's new and stop. Safe to call every time the Archive opens. */
    /**
     * Cross-session on the direct door (2.10): a gateway session is one conversation, and an
     * Archive that could only see the open one answered "nothing" to almost every question —
     * the door opened onto blank tabs. Now the direct door reads the whole index and the sweep
     * walks the roster, the Desktop's Artifacts rule ("one place, everything the sessions
     * made"). Matrix keeps the room: its rooms are E2EE and a room is a world.
     */
    val crossSession: Boolean get() = transport.gateway != null

    /** The room a read is scoped to — none at all when the Archive spans sessions. */
    private fun scopeRoom(): String? = if (crossSession) null else currentRoomId()

    /** Kick an index sweep. Direct door: the open session first, then the roster's newest;
     *  Matrix: the open room. First ever run is the big backfill; later runs catch up. */
    fun startSweep() {
        if (crossSession) {
            val ids = (listOfNotNull(currentRoomId()) + rosterIds().take(SWEEP_ROSTER)).distinct()
            if (ids.isNotEmpty()) archiveIndexer?.sweepMany(scope, ids)
            return
        }
        val roomId = currentRoomId() ?: return
        archiveIndexer?.sweep(scope, roomId)
    }

    suspend fun search(query: String): List<chat.keryx.app.data.archive.ArchiveStore.Hit> {
        val store = archiveStore ?: return emptyList()
        val roomId = scopeRoom()
        if (roomId == null && !crossSession) return emptyList()
        return withContext(Dispatchers.IO) { store.search(roomId, query) }
    }

    /** What the Search tab shows before a query: the newest things remembered. */
    suspend fun recent(): List<chat.keryx.app.data.archive.ArchiveStore.Entry> {
        val store = archiveStore ?: return emptyList()
        val roomId = scopeRoom()
        if (roomId == null && !crossSession) return emptyList()
        return withContext(Dispatchers.IO) { store.recent(roomId) }
    }

    suspend fun media(): List<chat.keryx.app.data.archive.ArchiveStore.Entry> {
        val store = archiveStore ?: return emptyList()
        val roomId = scopeRoom()
        if (roomId == null && !crossSession) return emptyList()
        return withContext(Dispatchers.IO) { store.media(roomId) }
    }

    suspend fun saved(): List<chat.keryx.app.data.archive.ArchiveStore.Entry> {
        val store = archiveStore ?: return emptyList()
        val roomId = scopeRoom()
        if (roomId == null && !crossSession) return emptyList()
        return withContext(Dispatchers.IO) { store.saved(roomId) }
    }

    /** Oldest→newest indexed timestamps for the open room (bounds the date picker). */
    suspend fun timeSpan(): Pair<Long, Long>? {
        val roomId = currentRoomId() ?: return null
        val store = archiveStore ?: return null
        return withContext(Dispatchers.IO) { store.timeSpan(roomId) }
    }

    suspend fun eventForDate(dayStartMillis: Long): String? {
        val roomId = currentRoomId() ?: return null
        val store = archiveStore ?: return null
        return withContext(Dispatchers.IO) { store.eventForDate(roomId, dayStartMillis) }
    }

    /** History around an event for the Archive's context view (server-fetches gaps, bounded). */
    suspend fun context(eventId: String, roomId: String? = null, before: Int = 25, after: Int = 25): List<Message> {
        val roomId = roomId ?: currentRoomId() ?: return emptyList()
        return runCatching { transport.messagesAround(roomId, eventId, before, after) }
            .onFailure { android.util.Log.w("KeryxArchive", "context load failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Toggle "Keep" on a message: saved messages survive in the archive DB and list in the
     *  Archive's Saved tab. */
    fun toggleSaved(message: Message) {
        val store = archiveStore ?: return
        scope.launch(Dispatchers.IO) {
            val kept = message.id in _savedIds.value
            if (kept) {
                store.removeSaved(message.id)
            } else {
                store.addSaved(
                    chat.keryx.app.data.archive.ArchiveStore.Entry(
                        eventId = message.id,
                        roomId = message.roomId,
                        sender = message.senderId.ifBlank { message.senderName },
                        timestamp = message.timestamp,
                        mediaKind = message.mediaKind?.name,
                        fileName = message.fileName,
                        // Agent messages keep their prose, not their tool-call machinery — the
                        // same rule the index applies.
                        body = chat.keryx.app.data.archive.ArchiveIndexer.searchableText(
                            message.content,
                            fromMe = message.sender == SenderType.ME,
                        ),
                    )
                )
            }
            _savedIds.value = store.savedIds(message.roomId)
            toast(if (kept) "Removed from Saved" else "Kept — find it in the Archive")
        }
    }

    /** Room switch: the bookmark state in the bubble menu belongs to the room being opened. */
    fun onRoomOpened() {
        _savedIds.value = emptySet()
        refreshSavedIds()
    }
}

/** How many roster sessions the cross-session sweep walks on an Archive open. */
private const val SWEEP_ROSTER = 30
