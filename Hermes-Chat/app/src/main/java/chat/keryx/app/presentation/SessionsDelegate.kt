package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The session list's verbs (split out of [ChatViewModel] in 3.0): pin, mark unread, create
 * (temporary, sticky model), rename, archive and restore, delete, older pages, search, and
 * the drawer's Needs-you / Running sets. Mostly the direct door's; pins work on both.
 */
class SessionsDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val rooms: StateFlow<List<RoomProfile>>,
    private val models: ModelDelegate,
    private val openRoomById: (String) -> Unit,
    /** Leave [roomId] if it is the open one: the next room, or none. */
    private val moveOffRoom: (roomId: String) -> Unit,
) {
    private val scope = deps.scope
    private val settings: SettingsRepository = deps.settings
    private val toast = deps.toast
    private val notice = deps.notice
    private val gateway get() = transport.gateway
    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport

    // Two pin ledgers, one per door, and the UI reads one set. Matrix rooms pin into the
    // phone's own ledger (a homeserver has no such flag). Gateway sessions pin ON THE GATEWAY —
    // the Desktop sidebar's durable "keep" flag, which also exempts the session from the
    // auto-archive sweep — so on the direct door the set is read straight off the roster
    // rows, and the phone's ledger is not consulted at all (it would drift from the truth the
    // moment Desktop pinned something).
    private val _localPinnedRoomIds = MutableStateFlow(settings.pinnedRoomIds)
    val pinnedRoomIds: StateFlow<Set<String>> =
        combine(_localPinnedRoomIds, rooms) { local, rooms ->
            if (transport.matrix == null) rooms.filter { it.pinned }.mapTo(LinkedHashSet()) { it.id }
            else local
        }.stateIn(scope, SharingStarted.Eagerly, if (transport.matrix == null) emptySet() else settings.pinnedRoomIds)

    /** [undoable]: say what happened with an Undo (2.16). The Undo itself flips back quietly —
     *  an undo that offers to undo itself is a loop, not a safety net. */
    fun togglePin(roomId: String, undoable: Boolean = true) {
        val gw = gateway
        val name = rooms.value.firstOrNull { it.id == roomId }?.name?.takeIf { it.isNotBlank() }
        fun undo(pinnedNow: Boolean) {
            if (!undoable) return
            val what = (if (pinnedNow) "Pinned" else "Unpinned") + (name?.let { " “${it.take(40)}”" } ?: "")
            notice(KeryxNotice.undo(what) { togglePin(roomId, undoable = false) })
        }
        if (gw != null) {
            val nowPinned = roomId in pinnedRoomIds.value
            scope.launch {
                gw.pinSession(roomId, pinned = !nowPinned)
                    .onSuccess { undo(pinnedNow = !nowPinned) }
                    .onFailure { toast("${if (nowPinned) "Unpin" else "Pin"} failed: ${it.message?.take(80)}") }
            }
            return
        }
        val updated = _localPinnedRoomIds.value.toMutableSet()
        val pinnedNow = updated.add(roomId)
        if (!pinnedNow) updated.remove(roomId)
        _localPinnedRoomIds.value = updated
        settings.pinnedRoomIds = updated
        undo(pinnedNow)
    }

    /** Direct door: flip the gateway's read watermark to "explicitly unread" — the Desktop
     *  sidebar's Mark as unread. The row reads unread until it is opened again. */
    fun markSessionUnread(sessionId: String) {
        val gw = gateway ?: return
        scope.launch {
            gw.markSessionRead(sessionId, read = false)
                .onSuccess {
                    notice(KeryxNotice.undo("Marked unread") {
                        scope.launch {
                            gw.markSessionRead(sessionId, read = true)
                                .onFailure { toast("Couldn't mark read: ${it.message?.take(80)}") }
                        }
                    })
                }
                .onFailure { toast("Couldn't mark unread: ${it.message?.take(80)}") }
        }
    }

    // --- Gateway sessions (direct door): the lifecycle the homeserver would otherwise own -------
    // The mirror of the ViewModel's Matrix membership block — same shapes, the other capability.

    /** Sessions the user asked to be TEMPORARY — normal rooms until the next cold start,
     *  which deletes them from the gateway (see [sweepTemporarySessions]). */
    private val _temporarySessionIds = MutableStateFlow(settings.temporarySessionIds)
    val temporarySessionIds: StateFlow<Set<String>> = _temporarySessionIds.asStateFlow()

    private fun markTemporary(sessionId: String) {
        settings.temporarySessionIds = settings.temporarySessionIds + sessionId
        _temporarySessionIds.value = settings.temporarySessionIds
    }

    private fun unmarkTemporary(sessionId: String) {
        settings.temporarySessionIds = settings.temporarySessionIds - sessionId
        _temporarySessionIds.value = settings.temporarySessionIds
    }

    /**
     * The temporary contract, kept at launch: every ledgered session is deleted from the
     * gateway. Cold-start-only on purpose — deleting the moment you switch rooms would eat a
     * conversation you meant to hop back to, and hooking app-background would kill the chat
     * you are IN the moment you check a text. An id whose delete fails (offline, gateway
     * down) stays ledgered and dies at the next launch instead; one already gone elsewhere
     * comes off the ledger regardless, or it would be retried forever.
     */
    fun sweepTemporarySessions() {
        val doomed = settings.temporarySessionIds
        if (doomed.isEmpty()) return
        // Before the room restore runs: last night's temp must not greet you as today's room.
        if (settings.lastRoomId in doomed) settings.lastRoomId = null
        scope.launch {
            val gw = gateway ?: return@launch
            val remaining = doomed.filterNotTo(mutableSetOf()) { id ->
                gw.deleteSession(id).isSuccess
            }
            // Self-heal: whatever the roster no longer carries is gone however it went.
            val known = runCatching { rooms.value.mapTo(HashSet()) { it.id } }.getOrNull()
            settings.temporarySessionIds =
                if (known.isNullOrEmpty()) remaining else remaining.intersect(known)
            _temporarySessionIds.value = settings.temporarySessionIds
        }
    }

    private val _stickyModel = MutableStateFlow(settings.stickyModel)
    val stickyModel: StateFlow<Boolean> = _stickyModel.asStateFlow()
    fun setStickyModel(on: Boolean) {
        _stickyModel.value = on
        settings.stickyModel = on
    }

    /**
     * A new session starts on the last model you picked (2.10) — the Desktop's rule ("pick a
     * model once and the next new chat opens on it"), and the missing "default model for
     * Keryx". Applies straight to the session, never to the profile default; the pick that
     * needs confirming (an expensive model) is left to the picker, with a word.
     */
    fun applyStickyModel(sessionId: String) {
        if (!settings.stickyModel) return
        val recents = settings.recentModels
        if (recents.isEmpty()) return
        val gw = gateway ?: return
        scope.launch {
            // Resolve the ledger against what the gateway serves NOW. A remembered route the
            // brain no longer carries (the local model was swapped under the same endpoint)
            // is skipped, not pinned: the gateway accepts any name for a custom endpoint and the
            // first turn dies on it later — and every new chat would reopen on the ghost.
            // Every way out below says so: a silent skip read as "sticky model doesn't work".
            val catalog = gw.modelOptions(sessionId).getOrElse {
                android.util.Log.w("KeryxModel", "sticky model: catalog unavailable: ${it.message}")
                toast("Couldn't read the models — opened on the gateway's default")
                return@launch
            }
            val lastName = recents.first().substringAfter('|')
            val choice = chat.keryx.core.model.ModelPicker.stickyChoice(recents, catalog) ?: run {
                toast("$lastName isn't served now — opened on the gateway's default")
                return@launch
            }
            // The ledger fell through to an older pick: say the newest one is gone, not "your last pick".
            val fellBack = chat.keryx.core.model.ModelPicker.recentKey(choice) != recents.first()
            // Already this session's route: nothing to switch, and no "model changed" notice
            // to inject into a chat that has not started.
            if (catalog.isCurrent(choice)) {
                if (fellBack) toast("$lastName isn't served now — opened on the gateway's default")
                return@launch
            }
            val model = choice.name
            gw.selectModel(sessionId, model, choice.provider)
                .onSuccess { out ->
                    if (out.confirmRequired) toast("$model needs confirming — pick it in the model picker")
                    else {
                        // The pill and the reasoning dial read the route it switched to now,
                        // not the profile default a chat without a first message reports.
                        models.adopt(sessionId, catalog, out.model.ifBlank { model }, choice.provider)
                        // Never silent: a chat that opens on a model other than the gateway's
                        // says so, and says why, so a surprise on the first turn is impossible.
                        toast(
                            if (fellBack) "Opened on $model — $lastName isn't served now"
                            else "Opened on $model — your last pick (sticky model, in Settings)",
                        )
                    }
                }
                .onFailure {
                    android.util.Log.w("KeryxModel", "sticky model refused: ${it.message}")
                    toast("$model refused — opened on the gateway's default")
                }
        }
    }

    /**
     * A fresh gateway session, opened. [profile] picks the agent it runs as (2.17.3); null or
     * the launch profile is the gateway's own. Another agent's session runs on that agent's
     * own model, so the "last model picked" pin is for the launch profile's sessions only.
     */
    fun createSession(
        title: String,
        temporary: Boolean = false,
        profile: chat.keryx.core.model.BotProfile? = null,
        onDone: (String?) -> Unit,
    ) {
        scope.launch {
            gateway?.createSession(title.trim().ifBlank { null }, profile)
                ?.onSuccess { sessionId ->
                    if (temporary) markTemporary(sessionId)
                    if (profile == null || profile.isDefault) applyStickyModel(sessionId)
                    openRoomById(sessionId); onDone(null)
                }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't create the session") }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        scope.launch {
            gateway?.renameSession(sessionId, title)
                ?.onFailure { toast("Rename failed: ${it.message?.take(80)}") }
        }
    }

    /**
     * Archive (2.10): the row leaves the list and stays resumable — the gateway's soft-hide,
     * the Desktop's "Archive". The open session stays open; you are in it, and the next
     * message would put it straight back anyway. Never a move-off, never a toast of success:
     * the row leaving is the feedback.
     *
     * Archive and its reverse each say so with an Undo that is the other (2.16); the Undo
     * runs quietly ([undoable] false) so the two never volley.
     */
    fun archiveSession(sessionId: String, undoable: Boolean = true) {
        val name = rooms.value.firstOrNull { it.id == sessionId }?.name?.takeIf { it.isNotBlank() }
        scope.launch {
            gateway?.archiveSession(sessionId)
                ?.onFailure { toast("Couldn't archive: ${it.message?.take(80)}") }
                ?.onSuccess {
                    _archivedRooms.value = _archivedRooms.value.filterNot { it.id == sessionId }
                    if (undoable) notice(
                        KeryxNotice.undo("Archived" + (name?.let { " “${it.take(40)}”" } ?: "")) {
                            unarchiveSession(sessionId, undoable = false)
                        }
                    )
                }
        }
    }

    fun unarchiveSession(sessionId: String, undoable: Boolean = true) {
        val name = _archivedRooms.value.firstOrNull { it.id == sessionId }?.name?.takeIf { it.isNotBlank() }
        scope.launch {
            gateway?.unarchiveSession(sessionId)
                ?.onFailure { toast("Couldn't restore: ${it.message?.take(80)}") }
                ?.onSuccess {
                    _archivedRooms.value = _archivedRooms.value.filterNot { it.id == sessionId }
                    if (undoable) notice(
                        KeryxNotice.undo("Restored" + (name?.let { " “${it.take(40)}”" } ?: "")) {
                            archiveSession(sessionId, undoable = false)
                        }
                    )
                }
        }
    }

    /** The Archived shelf's rows — fetched when the shelf is opened, never part of the roster. */
    private val _archivedRooms = MutableStateFlow<List<RoomProfile>>(emptyList())
    val archivedRooms: StateFlow<List<RoomProfile>> = _archivedRooms.asStateFlow()

    fun loadArchivedSessions() {
        scope.launch {
            gateway?.archivedSessions()
                ?.onSuccess { _archivedRooms.value = it }
                ?.onFailure { toast("Couldn't read the archive: ${it.message?.take(80)}") }
        }
    }

    /** The roster's next page (2.10) — the drawer's "show older" row. */
    val hasMoreSessions: StateFlow<Boolean> =
        (direct?.hasMoreSessions() ?: flowOf(false)).stateIn(scope, SharingStarted.Eagerly, false)
    val loadingMoreSessions: StateFlow<Boolean> =
        (direct?.loadingMoreSessions() ?: flowOf(false)).stateIn(scope, SharingStarted.Eagerly, false)
    fun loadMoreSessions() {
        val d = direct ?: return
        scope.launch {
            d.loadMoreSessions().onFailure { toast("Couldn't load older sessions: ${it.message?.take(80)}") }
        }
    }

    /** Stored ids with a decision waiting on you — an approval, a clarify, a secret, a sudo.
     *  The drawer's "Needs you" filter; the shade already keeps this map for notifications. */
    val needsYouIds: StateFlow<Set<String>> =
        (direct?.shadePending() ?: flowOf(emptyMap()))
            .map { it.keys }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Stored ids with a turn in flight — the drawer's "Running" filter. */
    val busySessionIds: StateFlow<Set<String>> =
        (gateway?.busySessionIds() ?: flowOf(emptySet()))
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun deleteSession(sessionId: String) {
        scope.launch {
            gateway?.deleteSession(sessionId)
                ?.onSuccess {
                    toast("Session deleted")
                    unmarkTemporary(sessionId) // a hand-deleted temp owes the sweep nothing
                    if (settings.lastRoomId == sessionId) settings.lastRoomId = null
                    // Move off the dead session so the chat pane never points at a transcript
                    // that's gone (same shape as leaveRoom's move-off).
                    moveOffRoom(sessionId)
                }
                ?.onFailure { toast("Delete failed: ${it.message?.take(80)}") }
        }
    }

    /** Server-side transcript search (drawer deep search). Empty off the direct door. */
    suspend fun searchGatewaySessions(query: String): List<chat.keryx.core.transport.SessionSearchHit> =
        gateway?.searchSessions(query)?.getOrNull() ?: emptyList()
}
