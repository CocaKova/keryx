package chat.keryx.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.SenderType
import chat.keryx.core.transport.ChatTransport
import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.protocol.MessageParser
import chat.keryx.core.protocol.StreamTailTracker
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class ChatViewModel(
    private val transport: ChatTransport,
    private val settingsRepository: SettingsRepository,
    // The Archive (1.26): nullable so the ViewModel stays constructible in plain-JVM tests.
    private val archiveStore: chat.keryx.app.data.archive.ArchiveStore? = null,
    private val archiveIndexer: chat.keryx.app.data.archive.ArchiveSweeper? = null,
    // Whether the app is on screen right now (wired to KeryxApp.isForeground). Null — the
    // plain-JVM test default — reads as foregrounded, so behavior is unchanged where unwired.
    private val isAppForeground: (() -> Boolean)? = null,
    // The room on screen, told SYNCHRONOUSLY to whoever suppresses notifications for it
    // (KeryxApp.openRoomId). A flow collector learns of a room switch a dispatch later —
    // long enough for the roster to emit the freshly adopted session and the watcher to
    // read it as news the user "isn't looking at" (the cron-run trap, 2.8.1).
    private val onOpenRoomChanged: ((String?) -> Unit)? = null,
    // The ear (2.7): process-scoped, direct door only; null on Matrix and in plain-JVM tests.
    private val wake: chat.keryx.app.audio.WakeWordController? = null,
) : ViewModel() {

    // The two capability surfaces, if this transport has them. Affordances only one side offers
    // (Matrix: invites, avatars, membership, redaction; gateway: session lifecycle, deep search)
    // no-op quietly on a transport without them; the UI gates their chrome on the same
    // nullability.
    private val matrix get() = transport.matrix
    private val gateway get() = transport.gateway

    companion object {
        private const val INITIAL_LIMIT = 50
        private const val PAGE = 50
        // Soft ceiling + decay for the loaded window (see loadOlderMessages/onViewportAtBottom):
        // the limit used to grow +50 per scroll-back forever and never shrink until room switch.
        private const val MAX_LIMIT = 1_000
        private const val LIMIT_DECAY_DWELL_MS = 10_000L
        // How long after the agent's last activity to keep the "working" animation up. Adaptive:
        // while the agent is mid-run (last thing we saw was a tool call or pure reasoning, so more is
        // coming) we wait LONG — deliberately generous, because a local brain can go quiet for
        // minutes between steps (a slow build/terminal command, a long think) without emitting a
        // single Matrix event to reset the timer. We'd rather over-stay slightly than have the banner
        // vanish while it's genuinely working. The SHORT window kicks in the moment a real prose
        // answer lands, so it still settles promptly when the turn is actually done.
        private const val QUIET_LONG_MS = 240_000L
        private const val QUIET_SHORT_MS = 1_200L
        // Absolute cap covering time-to-first-response if the agent never says anything at all.
        private const val NO_REPLY_MS = 240_000L
        // On opening a room, treat the agent as still working if its last (mid-run) message landed
        // within this window — so the cloud/quips appear when you open the app mid-run, not only when
        // you were the one who sent the message.
        private const val WORKING_RECENT_MS = 150_000L
        // Bridge after Hermes stops typing — long enough to hand off to the final message's settle,
        // short enough that the banner doesn't loiter once it's genuinely done.
        private const val TYPING_STOP_GRACE_MS = 5_000L
        // Typing stopped and the answer is already rendered: the turn is over — settle fast.
        private const val ANSWER_SETTLED_MS = 350L
        // How long a typing=true flag keeps its veto with NO other sign of life. Matrix typing is
        // ephemeral state: a stop EDU that lands in a sync gap (app backgrounded mid-turn) is
        // simply never re-delivered, and the flag then reads true forever — the cloud and the
        // newest card's working shimmer never die. Fresh evidence (a typing emission, a new or
        // growing agent message) renews the trust; when it runs out, the banner settles even if
        // the flag still says typing.
        private const val TYPING_TRUST_MS = QUIET_LONG_MS
        // How often a quiet timer parked behind a compaction re-checks whether it is over.
        private const val COMPACTING_POLL_MS = 1_000L

        // --- Side-channel stream tuning ---
        // UI dispatch throttle for the live token buffer: flush to state when either trips.
        // ~10 dispatches/s keeps recomposition (and the markdown re-parse) far off the frame budget
        // even at high token rates, while still reading as a live stream.
        private const val STREAM_DISPATCH_MS = 100L
        private const val STREAM_DISPATCH_CHARS = 240
        // The overlay renders (and markdown-parses) on every dispatch, so what it shows must stay
        // bounded no matter how long a marathon turn grows — only the tail is live anyway; the
        // committed Matrix message renders the whole thing. The answer window is shared with the
        // tier-2 streaming render (MessageParser.STREAM_RENDER_WINDOW): one invariant, one bound.
        private const val STREAM_WINDOW_CHARS = MessageParser.STREAM_RENDER_WINDOW
        private const val STREAM_REASONING_WINDOW_CHARS = 6_000

        // tok/s readout smoothing: an EMA of the *instantaneous* per-frame rate, not a cumulative
        // average — the cumulative form was diluted by think-latency and tool-call gaps, so a
        // 150 tok/s brain read ~32. Frames closer than MIN or farther than MAX apart are skipped
        // (coalesced sub-frame bursts spike; tool/think stalls tank) so the number tracks live
        // decode speed. Weight favors history for a steady readout.
        private const val TPS_EMA_WEIGHT = 0.6f
        private const val TPS_MIN_FRAME_MS = 15L
        private const val TPS_MAX_FRAME_MS = 4_000L
        // How long to hold the overlay waiting for the final Matrix event after `stop` — sync is
        // normally sub-second; past this the commit clearly isn't coming as-streamed.
        private const val STREAM_SYNC_GRACE_MS = 20_000L
        // Optimistic send bubble safety timeout: if the echo never matches (edited en route,
        // network hiccup), stop double-rendering after this long — the real event wins.
        private const val PENDING_SEND_TIMEOUT_MS = 12_000L
        // Session-cache bounds (see the cache fields): retained reaction StateFlows and decoded
        // pet-picker thumbnails were unbounded before 1.19.0.
        private const val REACTION_FLOW_MAX = 200
        // How long to keep partial text + the alert after a mid-stream drop before giving the
        // timeline back to plain Matrix rendering.
        private const val STREAM_INTERRUPT_HOLD_MS = 60_000L

        /** True when a timeline event [echo] is the homeserver copy of the optimistic [sent] text.
         *  Reply sends come back wrapped (quote-fallback prefix), so an exact match OR the echo
         *  ending with the sent text both count. */
        fun pendingEchoMatches(echo: String, sent: String): Boolean {
            val e = echo.trim()
            val s = sent.trim()
            return s.isNotEmpty() && (e == s || e.endsWith(s))
        }

        /** One-line drawer preview of a message: dialogue prose only (reasoning/tool chrome and
         *  markers stripped), "You:" prefix for own sends, sensible stand-ins for media and for
         *  agent messages that have no prose at all (pure tool runs, telemetry heartbeats). */
        fun previewOf(m: Message): String {
            val who = if (m.sender == SenderType.ME) "You: " else ""
            val body = when {
                m.mediaKind == chat.keryx.core.model.MediaKind.IMAGE ->
                    "🖼 " + (m.content.takeIf { it.isNotBlank() && it != m.fileName } ?: "Photo")
                m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "Attachment" }}"
                MessageParser.isSelfImprovementReview(m.content) -> "💾 self-improvement review"
                m.content.trimStart().startsWith("🗜") -> "🗜 compacting context"
                MessageParser.isTelemetryMessage(m.content) -> "⏳ status check-in"
                else -> {
                    val prose = StreamHandoff.normalize(m.content)
                    if (prose.isNotBlank()) prose
                    else {
                        // Structure first (3.1 §C2): direct-door tool messages carry their work
                        // in Message.toolCalls with no content — the parse-only read rendered
                        // "💭 thinking…" for every tool message where Matrix named the tool.
                        val tools = m.toolCalls.ifEmpty {
                            MessageParser.parse(m.content)
                                .filterIsInstance<MessageParser.Segment.Tools>()
                                .flatMap { it.calls }
                        }
                        if (tools.isNotEmpty()) "🛠 ${tools.last().name}" else "💭 thinking…"
                    }
                }
            }
            return (who + body).take(140)
        }
    }

    val isLoggedIn: StateFlow<Boolean> = transport.isLoggedIn()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentUserId: StateFlow<String?> = transport.currentUserId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _rooms = MutableStateFlow<List<RoomProfile>>(emptyList())
    val rooms: StateFlow<List<RoomProfile>> = _rooms.asStateFlow()

    // MUST be declared above the init block that collects into it: viewModelScope is
    // Main.immediate and matrix.client is a StateFlow, so the first emission lands
    // SYNCHRONOUSLY inside init — a later declaration is still null at that moment
    // (the v1.9.0 crash-on-open).
    private val _invites = MutableStateFlow<List<chat.keryx.core.model.RoomInvite>>(emptyList())
    val invites: StateFlow<List<chat.keryx.core.model.RoomInvite>> = _invites.asStateFlow()

    private val _currentRoom = MutableStateFlow<RoomProfile?>(null)
    val currentRoom: StateFlow<RoomProfile?> = _currentRoom.asStateFlow()

    /** The ONE writer of the open room: the hook hears it in the same frame the flow does. */
    private fun setCurrentRoom(room: RoomProfile?) {
        _currentRoom.value = room
        onOpenRoomChanged?.invoke(room?.id)
    }

    // How many timeline events to load for the open room; grows as the user scrolls into history.
    private val _timelineLimit = MutableStateFlow(INITIAL_LIMIT)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> =
        combine(_currentRoom.filterNotNull(), _timelineLimit) { room, limit -> room.id to limit }
            .flatMapLatest { (sessionId, limit) -> transport.getMessages(sessionId, limit) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Latest runtime-footer telemetry in the open room (`model · 42% · 22s · ~/dir`) — the data
     *  behind the Hub's context meter. Newest-first scan, bounded so a footer-less room stays cheap;
     *  the footer rides either the tail of an agent reply or its own trailing event (streaming). */
    val runtimeFooter: StateFlow<MessageParser.RuntimeFooter?> = messages
        .map { list ->
            list.asSequence()
                .filter { it.sender == SenderType.HERMES }
                .take(60)
                .mapNotNull { MessageParser.parseRuntimeFooter(it.content) }
                .firstOrNull()
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Whether more history may exist (we keep paging until the loaded count stops growing). */
    private val _hasMoreHistory = MutableStateFlow(true)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    // Guards against the scroll listener firing loadOlderMessages repeatedly while a page is still
    // resolving — each rapid increment used to cancel the in-flight backfill (flatMapLatest), which
    // is why "scroll up does nothing until you leave and come back".
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // The message currently being replied to (null = composing a normal message).
    private val _replyTarget = MutableStateFlow<Message?>(null)
    val replyTarget: StateFlow<Message?> = _replyTarget.asStateFlow()

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null) // null = system default
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    private val _accentColor = MutableStateFlow<Color>(
        try {
            Color(android.graphics.Color.parseColor(settingsRepository.accentColorHex))
        } catch (e: Exception) {
            Color(0xFFE55A00)
        }
    )
    val accentColor: StateFlow<Color> = _accentColor.asStateFlow()

    private val _accentColor2 = MutableStateFlow<Color>(
        try {
            Color(android.graphics.Color.parseColor(settingsRepository.accentColor2Hex))
        } catch (e: Exception) {
            Color(0xFF8B5CF6)
        }
    )
    val accentColor2: StateFlow<Color> = _accentColor2.asStateFlow()

    // Command menu state
    private val _commandMenuVisible = MutableStateFlow(false)
    val commandMenuVisible: StateFlow<Boolean> = _commandMenuVisible.asStateFlow()

    private val _commandFilter = MutableStateFlow("")
    val commandFilter: StateFlow<String> = _commandFilter.asStateFlow()

    private val _recentCommands = MutableStateFlow(settingsRepository.recentCommands)
    val recentCommands: StateFlow<List<String>> = _recentCommands.asStateFlow()

    private val _matrixUrl = MutableStateFlow(settingsRepository.homeserverUrl)
    val matrixUrl: StateFlow<String> = _matrixUrl.asStateFlow()

    private val _agentMatrixId = MutableStateFlow(settingsRepository.agentMatrixId)
    val agentMatrixId: StateFlow<String> = _agentMatrixId.asStateFlow()

    /** Per-herald accent overrides (localpart -> "#RRGGBB"). Empty entries fall back to the
     *  derived council palette (2.3 §1). */
    private val _heraldAccents = MutableStateFlow(settingsRepository.heraldAccents)
    val heraldAccents: StateFlow<Map<String, String>> = _heraldAccents.asStateFlow()

    private val _matrixToken = MutableStateFlow(settingsRepository.matrixToken)
    val matrixToken: StateFlow<String> = _matrixToken.asStateFlow()

    private val _allowInsecure = MutableStateFlow(settingsRepository.allowInsecure)
    val allowInsecure: StateFlow<Boolean> = _allowInsecure.asStateFlow()

    /** The words this door uses for its rows ("room" / "session") — chosen once, from the
     *  transport, and threaded to every surface that says the noun. */
    val lexicon: chat.keryx.core.model.DoorLexicon =
        chat.keryx.core.model.DoorLexicon.forDoor(direct = transport.matrix == null)

    // Two pin ledgers, one per door, and the UI reads one set. Matrix rooms pin into the
    // phone's own ledger (a homeserver has no such flag). Gateway sessions pin ON THE GATEWAY —
    // the Desktop sidebar's durable "keep" flag, which also exempts the session from the
    // auto-archive sweep — so on the direct door the set is read straight off the roster
    // rows, and the phone's ledger is not consulted at all (it would drift from the truth the
    // moment Desktop pinned something).
    private val _localPinnedRoomIds = MutableStateFlow(settingsRepository.pinnedRoomIds)
    val pinnedRoomIds: StateFlow<Set<String>> =
        combine(_localPinnedRoomIds, _rooms) { local, rooms ->
            if (transport.matrix == null) rooms.filter { it.pinned }.mapTo(LinkedHashSet()) { it.id }
            else local
        }.stateIn(viewModelScope, SharingStarted.Eagerly, if (transport.matrix == null) emptySet() else settingsRepository.pinnedRoomIds)

    fun togglePin(roomId: String) {
        val gw = gateway
        if (gw != null) {
            val nowPinned = roomId in pinnedRoomIds.value
            viewModelScope.launch {
                gw.pinSession(roomId, pinned = !nowPinned)
                    .onFailure { _toasts.tryEmit("${if (nowPinned) "Unpin" else "Pin"} failed: ${it.message?.take(80)}") }
            }
            return
        }
        val updated = _localPinnedRoomIds.value.toMutableSet()
        if (!updated.add(roomId)) updated.remove(roomId)
        _localPinnedRoomIds.value = updated
        settingsRepository.pinnedRoomIds = updated
    }

    /** Direct door: flip the gateway's read watermark to "explicitly unread" — the Desktop
     *  sidebar's Mark as unread. The row reads unread until it is opened again. */
    fun markSessionUnread(sessionId: String) {
        val gw = gateway ?: return
        viewModelScope.launch {
            gw.markSessionRead(sessionId, read = false)
                .onFailure { _toasts.tryEmit("Couldn't mark unread: ${it.message?.take(80)}") }
        }
    }

    /** Direct door: re-pull the session roster (the drawer asks on open — the list is the one
     *  thing another client, a cron run or a compaction can change behind the phone's back). */
    fun refreshRoster() {
        if (transportIsDirect) bots.refresh()
        val d = direct ?: return
        // Asking for the list is also the moment to stop waiting out a backoff entered while
        // the phone slept: the route may be back (tailnet up, wifi joined, plane mode off) and
        // nothing else tells the socket so — which reads as "the app is broken, reopen it",
        // and reopening only helps because a fresh process starts its backoff at one second.
        // Free when the socket is already up; at worst one early connection attempt.
        d.networkMayHaveChanged()
        viewModelScope.launch { runCatching { d.refreshSessionList() } }
    }

    private val _biometricLock = MutableStateFlow(settingsRepository.biometricLockEnabled)
    val biometricLock: StateFlow<Boolean> = _biometricLock.asStateFlow()

    private val _e2eeEnabled = MutableStateFlow(settingsRepository.e2eeEnabled)
    val e2eeEnabled: StateFlow<Boolean> = _e2eeEnabled.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(settingsRepository.hapticsEnabled)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _animationStyle = MutableStateFlow(settingsRepository.animationStyle)
    val animationStyle: StateFlow<String> = _animationStyle.asStateFlow()

    private val _bubbleStyle = MutableStateFlow(settingsRepository.bubbleStyle)
    val bubbleStyle: StateFlow<String> = _bubbleStyle.asStateFlow()

    private val _messageTextScale = MutableStateFlow(settingsRepository.messageTextScale)
    val messageTextScale: StateFlow<Float> = _messageTextScale.asStateFlow()

    // --- Hermes side-channel (tier-1 streaming) ---
    private val _gatewayUrl = MutableStateFlow(settingsRepository.gatewayUrl)
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    private val _gatewayApiKey = MutableStateFlow(settingsRepository.gatewayApiKey)
    val gatewayApiKey: StateFlow<String> = _gatewayApiKey.asStateFlow()

    private val _sideChannelEnabled = MutableStateFlow(settingsRepository.sideChannelEnabled)
    val sideChannelEnabled: StateFlow<Boolean> = _sideChannelEnabled.asStateFlow()

    /** Auto-speak: completed agent replies in the open room, emitted once each for the UI to
     *  voice. One-shot like [toasts]; nothing replays on resubscribe. */
    private val _speakRequests = kotlinx.coroutines.flow.MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val speakRequests: kotlinx.coroutines.flow.SharedFlow<Message> = _speakRequests
    private var lastAutoSpokenId: String? = null

    /** An optimistic own-message bubble shown the instant Send is tapped, retired when the
     *  homeserver echo appears in the timeline (or after a safety timeout). */
    data class PendingSend(val roomId: String, val text: String, val sentAt: Long)

    private val _pendingSend = MutableStateFlow<PendingSend?>(null)
    val pendingSend: StateFlow<PendingSend?> = _pendingSend.asStateFlow()
    private var pendingSendClearJob: kotlinx.coroutines.Job? = null

    private fun clearPendingSend() {
        pendingSendClearJob?.cancel()
        _pendingSend.value = null
    }

    // Last known Hermes Link health, surfaced as the quiet top-bar dot. Session-scoped: it starts
    // UNKNOWN and is updated by every side-channel attempt and by the Settings "Test link" probe.
    private val _linkHealth = MutableStateFlow(
        if (settingsRepository.sideChannelEnabled && settingsRepository.gatewayUrl.isNotBlank())
            LinkHealth.UNKNOWN else LinkHealth.OFF
    )
    val linkHealth: StateFlow<LinkHealth> = _linkHealth.asStateFlow()

    /**
     * The last turn's context occupancy — the composer's ring. Two feeds, one gauge: the Matrix
     * side-channel's finish-line `usage` frame, and on the direct door the gateway's own
     * `usage` that rides `session.info` and `message.complete` (folded into the transport's
     * [chat.keryx.core.model.SessionMeta], which until 2.8.2 nothing read — the ring simply
     * never lit on a gateway session). The open room's direct reading wins when there is one.
     */
    data class ContextUsage(val roomId: String, val used: Long, val max: Long, val model: String)
    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)
    val contextUsage: StateFlow<ContextUsage?> =
        combine(
            _contextUsage,
            _currentRoom.flatMapLatest { r ->
                val d = transport as? chat.keryx.app.transport.direct.DirectTransport
                if (r == null || d == null) flowOf(null)
                else d.sessionMeta(r.id).map { m ->
                    m.contextGauge?.let { (used, max) -> ContextUsage(r.id, used, max, m.model) }
                }
            },
        ) { sideChannel, direct -> direct ?: sideChannel }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _showTelemetry = MutableStateFlow(settingsRepository.showTelemetry)
    val showTelemetry: StateFlow<Boolean> = _showTelemetry.asStateFlow()

    // --- Gateway plumbing shared with the delegates ---

    /** A configured gateway client, or null when Hermes Link is off/unconfigured. */
    private fun gatewayClient(): chat.keryx.app.data.remote.HermesStreamClient? {
        val url = _gatewayUrl.value.trim()
        if (!_sideChannelEnabled.value || url.isBlank()) return null
        return chat.keryx.app.data.remote.HermesStreamClient(
            url, _gatewayApiKey.value, settingsRepository.allowInsecure,
            snapshotStore = settingsRepository::putHubSnapshot,
        )
    }

    // Feature-scoped delegates (Phase 2 of the absorption): each gateway feature owns its
    // state and its verbs, over one shared GatewayDeps. What stays behind in this class is
    // what is genuinely the chat turn's: the timeline, the work-state machine, and the
    // tier-1 stream orchestration.
    private val deps = GatewayDeps(
        scope = viewModelScope,
        settings = settingsRepository,
        client = ::gatewayClient,
        bareClient = {
            val url = _gatewayUrl.value.trim()
            if (!_sideChannelEnabled.value || url.isBlank()) null
            else chat.keryx.app.data.remote.HermesStreamClient(url, _gatewayApiKey.value, settingsRepository.allowInsecure)
        },
        toast = { _toasts.tryEmit(it) },
    )
    // --- Direct-transport instruments (the harvest, plan §5). Null/quiet on Matrix. ---
    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport

    /** The agent is stopped on a tool approval in the open room (direct path only). */
    val pendingApproval: StateFlow<chat.keryx.core.model.ApprovalRequest?> =
        _currentRoom.flatMapLatest { r ->
            val d = direct
            if (r == null || d == null) flowOf(null) else d.pendingApproval(r.id)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * The gateway's lifecycle status for the open room — context compaction above all (2.5.7).
     *
     * Before this, compaction was a silence with a clock on it: the agent core announces it,
     * the chat gateway swallows the announcement by design, and the direct door stored its copy
     * in a flow nothing read. Both doors now land here: the Matrix side-channel's `event: status`
     * (a `ready` frame clears it, so does any sign of the turn moving on), and the direct
     * transport's `status.update` flow, keyed to the open session.
     */
    private val _matrixStatus = MutableStateFlow<chat.keryx.core.model.SessionStatus?>(null)
    val sessionStatus: StateFlow<chat.keryx.core.model.SessionStatus?> =
        combine(
            _matrixStatus,
            _currentRoom.flatMapLatest { r ->
                val d = direct
                if (r == null || d == null) flowOf(null) else d.sessionStatus(r.id)
            },
        ) { matrix, direct -> matrix ?: direct }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * A compaction — announced by WHICHEVER door carries it — parks the quiet timer instead of
     * racing it. The announcement comes once and the work it names has no upper bound, so the
     * hold is opened here and re-armed on the way out, where quiet counts from the moment the
     * work actually stopped. The two callers are the two doors and only one exists per process.
     */
    private fun noteCompacting(status: chat.keryx.core.model.SessionStatus?) {
        val nowCompacting = status?.isCompacting == true
        val wasCompacting = compactingSince != null
        compactingSince = if (nowCompacting) System.currentTimeMillis() else null
        // Only while a turn is actually being waited on: scheduleClearAwaiting can never
        // raise the banner, only settle it, so arming it with nothing awaiting would park a
        // polling job for the length of the hold to do nothing at the end of it.
        if ((nowCompacting || wasCompacting) && _awaitingReply.value) scheduleClearAwaiting(NO_REPLY_MS)
    }

    /** The agent is stopped on a question / sudo / secret in the open room (direct path only). */
    val pendingBlocking: StateFlow<chat.keryx.core.model.BlockingRequest?> =
        _currentRoom.flatMapLatest { r ->
            val d = direct
            if (r == null || d == null) flowOf(null) else d.pendingBlocking(r.id)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** The agent's own todo plan for the open room — the Flight Plan strip's fuel. */
    val flightPlan: StateFlow<chat.keryx.core.model.TodoPlan?> =
        _currentRoom.flatMapLatest { r ->
            val d = direct
            if (r == null || d == null) flowOf(null) else d.todoPlan(r.id)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun respondApproval(choice: String) {
        val room = _currentRoom.value ?: return
        viewModelScope.launch {
            direct?.respondApproval(room.id, choice)
                ?.onSuccess { resolved -> if (!resolved) _toasts.tryEmit("Approval had already expired") }
                ?.onFailure { _toasts.tryEmit("Approval failed: ${it.message?.take(80)}") }
        }
    }

    fun respondBlocking(request: chat.keryx.core.model.BlockingRequest, answer: String) {
        val room = _currentRoom.value ?: return
        viewModelScope.launch {
            direct?.respondBlocking(room.id, request.requestId, request.kind, answer)
                ?.onFailure { _toasts.tryEmit("Reply failed: ${it.message?.take(80)}") }
        }
    }

    val hub = HubDelegate(deps)
    val models = ModelDelegate(
        deps, transport, { _currentRoom.value?.id },
        sendRoomCommand = { sendMessage(it) },
        // A switched brain owns a different ladder: re-probe the dial for this session.
        onSwitched = { refreshReasoningCaps() },
        readRecents = { settingsRepository.recentModels },
        writeRecents = { settingsRepository.recentModels = it },
    )
    val projects = ProjectsDelegate(deps, transport) { id, title -> openSessionById(id, title) }
    val shipyard = ShipyardDelegate(deps)
    val pet = PetDelegate(deps)
    val missions = MissionsDelegate(deps) { _rooms.value }
    val console = ConsoleDelegate(deps)
    val voice = VoiceDelegate(deps)
    val archive = ArchiveDelegate(deps, transport, archiveStore, archiveIndexer) { _currentRoom.value?.id }
    /** Bot Mode (2.8): the roster of profiles and the door into each one's forever-chat. */
    val bots = BotsDelegate(deps, transport, hub) { id, title -> openSessionById(id, title) }

    // --- Real push (UnifiedPush) — the caller drives PushManager (it needs a Context). ---
    private val _pushEnabled = MutableStateFlow(settingsRepository.pushEnabled)
    val pushEnabled: StateFlow<Boolean> = _pushEnabled.asStateFlow()
    private val _pushGatewayUrl = MutableStateFlow(settingsRepository.pushGatewayUrl)
    val pushGatewayUrl: StateFlow<String> = _pushGatewayUrl.asStateFlow()

    fun setPushEnabled(enabled: Boolean) {
        settingsRepository.pushEnabled = enabled
        _pushEnabled.value = enabled
    }

    fun setPushGatewayUrl(url: String) {
        settingsRepository.pushGatewayUrl = url
        _pushGatewayUrl.value = url
    }

    fun toast(message: String) { _toasts.tryEmit(message) }

    /** The transient live response overlay (null = nothing streaming over the side-channel). */
    private val _liveStream = MutableStateFlow<LiveStream?>(null)
    val liveStream: StateFlow<LiveStream?> = _liveStream.asStateFlow()
    private var streamJob: Job? = null
    private var streamClearJob: Job? = null
    private var limitDecayJob: Job? = null

    // Set by openSideChannel for the lifetime of one SSE turn. Hermes commits each text segment
    // of a multi-segment turn (text → tool → text) as its OWN Matrix message mid-run; when one of
    // those lands, the overlay must shed the already-committed part and KEEP STREAMING — killing
    // the whole SSE job there is what made every post-tool reasoning phase invisible until commit.
    private var consumeStreamedSegment: (() -> Unit)? = null

    // Materializes the full sanitized stream text ON DEMAND for handoff matching while the turn
    // is still STREAMING. LiveStream.matchText used to carry a fresh full-buffer copy on every
    // ~100 ms dispatch tick; now it's only set once at `stop`, and mid-turn segment matching pulls
    // the full text here — i.e. per messages emission, not per token dispatch.
    private var currentStreamFullText: (() -> String)? = null

    // Fingerprint of the last handoff evaluation (stream status + the recent candidate window).
    // maybeHandOffStream runs on EVERY messages emission during a turn and normalizing the
    // streamed target is a full uncached parse — skip when nothing it looks at has changed.
    private var lastHandoffFingerprint: Int = 0

    // True while the agent is working (drives the waiting animation). Stays up through the agent's
    // thinking / tool-call / streaming phases and only clears after activity goes quiet.
    private val _awaitingReply = MutableStateFlow(false)
    val awaitingReply: StateFlow<Boolean> = _awaitingReply.asStateFlow()

    /** The current turn has already shown SOMETHING — a live overlay, a tool row, streamed
     *  reasoning. The quips indicator gates on this being false (3.1 §C3): `liveTheater`
     *  only covers the Matrix side-channel, and on the direct door it is null for the whole
     *  turn, which kept the quips chattering under a live run row and streaming thought for
     *  minutes (device-caught on the 08-24 fluency walk). midRun is exactly "the turn has
     *  signs but no settled answer", and isStreaming covers the first token onward. */
    private val _liveTurnSigns = MutableStateFlow(false)
    val liveTurnSigns: StateFlow<Boolean> = _liveTurnSigns.asStateFlow()

    /** Display names of HUMAN typers in the open room — the plain "X is typing…" line.
     *  (The agent's typing drives [awaitingReply]/the working banner instead.) */
    private val _typingHumans = MutableStateFlow<List<String>>(emptyList())
    val typingHumans: StateFlow<List<String>> = _typingHumans.asStateFlow()

    /** MXIDs of the heralds typing right now — the working bar wears one sigil each (2.3 §1). */
    private val _typingAgentIds = MutableStateFlow<List<String>>(emptyList())
    val typingAgentIds: StateFlow<List<String>> = _typingAgentIds.asStateFlow()
    private var quietJob: Job? = null

    // Tracks the last message we evaluated, so we can tell genuine live activity (a new message, or a
    // streamed m.replace edit growing the current one) from merely re-observing the same timeline.
    private var lastSeenId: String? = null
    private var lastSeenLen: Int = -1
    private var lastSeenRoomId: String? = null

    // True while Hermes' typing indicator is up. Authoritative: a pending quiet-timeout will NOT
    // clear the banner while this is set, so a long silent tool call (curl) can't make it vanish.
    @Volatile private var agentTyping = false
    // Last wall-clock moment the agent showed a verifiable sign of life (a typing emission, a new
    // or growing message). The typing veto in scheduleClearAwaiting trusts the flag only within
    // TYPING_TRUST_MS of this — the bound that keeps a stale EDU from holding the cloud forever.
    @Volatile private var agentEvidenceAt = 0L
    // When the gateway last told us it was compacting, or null when it isn't. Unlike typing there
    // is no repeating beat to renew — the announcement comes once, at the start — so this is a
    // start time the quiet timer waits out (CompactionHold), not evidence that decays.
    @Volatile private var compactingSince: Long? = null
    // True once the latest agent message reads as a real answer (not mid-run); used to shorten the
    // typing-stop grace so the working animation dies WITH the delivery, not seconds after it.
    @Volatile private var answerLanded = false

    // When the current "working" stretch began (elapsed clock for the top status counter); null when
    // idle. Set when we start awaiting and cleared when the animation goes quiet.
    private val _workStartedAt = MutableStateFlow<Long?>(null)
    val workStartedAt: StateFlow<Long?> = _workStartedAt.asStateFlow()

    // Message ids whose mid-run banner already ran its course (lit, then settled on the quiet
    // timeout with no new activity). Re-entering the room re-baselines lastSeenId, which makes the
    // opened-mid-run recency check fire AGAIN for the same stale message — the banner kept
    // resurrecting with an ever-growing clock (workStartedAt = the old message's timestamp). A
    // settled id never relights the banner; genuinely new activity has a new id, and the typing
    // indicator can always light it regardless.
    private val settledWorkIds = HashSet<String>()
    private var workStateId: String? = null

    // A short label of what the agent is currently doing, for the top counter ("Reasoning",
    // "Running terminal", …). Derived from the latest agent message.
    private val _workLabel = MutableStateFlow("Working")
    val workLabel: StateFlow<String> = _workLabel.asStateFlow()

    // One-shot text to drop into the composer (e.g. from the Steer quick-action).
    private val _composerPrefill = MutableStateFlow<String?>(null)
    val composerPrefill: StateFlow<String?> = _composerPrefill.asStateFlow()

    // The assistant doorway (2.0 Phase 5): each ACTION_ASSIST bumps the counter; the UI walks
    // home and focuses the composer. [assistConsumed] keeps a config-change replay from
    // re-summoning the keyboard.
    private val _assistSummon = MutableStateFlow(0)
    val assistSummon: StateFlow<Int> = _assistSummon.asStateFlow()
    var assistConsumed = 0
    fun summonAssist() { _assistSummon.value += 1 }

    // The wake word's doorway (2.7): `wake.detected` → the ear parks a Summon → this bumps
    // the counter; HermesApp walks home and opens the Call (in a fresh session when the
    // gateway asked, `start_new_session`). Same consumed-guard shape as the assist.
    private val _callSummon = MutableStateFlow(0)
    val callSummon: StateFlow<Int> = _callSummon.asStateFlow()
    var callSummonConsumed = 0
    @Volatile var callSummonNewSession: Boolean = false
        private set
    /** The ear's live state for Settings (null = no ear in this process: Matrix door or tests). */
    val wakeUi: StateFlow<chat.keryx.app.audio.WakeWordController.Ui>? = wake?.ui

    init {
        val w = wake
        if (w != null) viewModelScope.launch {
            w.summon.collect { s ->
                if (s == null) return@collect
                callSummonNewSession = s.detection.startNewSession
                _callSummon.value += 1
                w.consumeSummon(s.nonce)
            }
        }
    }

    fun setWakeWordEnabled(on: Boolean) { wake?.setEnabled(on) }
    fun setWakePolicy(policy: chat.keryx.core.model.WakePolicy) { wake?.setPolicy(policy) }
    fun setWakeOnDevice(on: Boolean) { wake?.setOnDevice(on) }

    /** CallScreen lifecycle → the ear yields the mic before CallAudio opens it, and reclaims
     *  the lease once the call is over. */
    fun onCallStarted() { wake?.pauseForVoice() }
    fun onCallEnded() { wake?.resumeAfterVoice() }

    private fun scheduleClearAwaiting(delayMs: Long, force: Boolean = false) {
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            delay(delayMs)
            // Typing owns the lifecycle — except when the caller KNOWS the turn ended (the
            // streamed answer's committed copy just handed off), where lingering typing EDUs
            // must not keep the cloud up. The veto is not a permanent excusal, though: it
            // only holds while the trust window since the last sign of life is open. Wait it
            // out — a genuine typing-stop or fresh activity reschedules (cancelling this job),
            // and a flag nothing ever renews stops being believed.
            if (!force) {
                // A compaction outranks the window this job was armed with: it is announced once
                // and then runs unannounced for as long as it takes, so counting quiet against it
                // settles the banner in the middle of live work. Wait it out — the status that
                // ends the compaction re-arms this job with a window that counts from then.
                while (true) {
                    val left = chat.keryx.core.model.CompactionHold
                        .remaining(compactingSince, System.currentTimeMillis())
                    if (left <= 0) break
                    // Sliced, not slept whole: the hold usually ends because the compaction
                    // ended, and a job parked on a 30-minute delay would settle the banner
                    // half an hour after the turn it was watching. The poll only ticks while
                    // a compaction is actually in flight.
                    delay(minOf(left, COMPACTING_POLL_MS))
                }
                while (agentTyping) {
                    val remaining =
                        TYPING_TRUST_MS - (System.currentTimeMillis() - agentEvidenceAt)
                    if (remaining <= 0) break
                    delay(remaining)
                }
            }
            _awaitingReply.value = false
            _workStartedAt.value = null
            // This message's working stretch is over; don't let a room re-entry resurrect it.
            workStateId?.let { settledWorkIds.add(it) }
        }
    }

    /** Pick the quiet window from the agent's latest message, and update the "what it's doing" label.
     *  Mid-run (a tool call, pure reasoning, or automated telemetry → more is coming) waits long;
     *  a real answer settles fast. Telemetry counting as an "answer" was why the working banner and
     *  quips vanished after one automated check-in even though the agent was still mid-run. */
    private fun updateWorkStateFrom(last: Message): Long {
        val segs = MessageParser.parse(last.content)
        // Structure first (3.1 §C1): a direct-door tool message carries its work in
        // Message.toolCalls with content "" — the parse found nothing and the banner said
        // "Working" forever where Matrix names the tool. The parse stays the fallback for
        // text-borne facts.
        val tools = last.toolCalls.ifEmpty {
            segs.filterIsInstance<MessageParser.Segment.Tools>().flatMap { it.calls }
        }
        val hasReasoning = !last.reasoning.isNullOrBlank() ||
            segs.any { it is MessageParser.Segment.Thinking }
        val isTelemetry = MessageParser.isTelemetryMessage(last.content)
        // Judge the turn by how the message ENDS, not by what it contains: a message that runs
        // tools and then delivers the answer is a finished turn, but "any tool call anywhere"
        // used to force the long mid-run window — the cloud (and the newest card's working
        // shimmer) outstayed an answer already on screen by minutes, and re-opening the room
        // re-lit it via the openedMidRun recency check. Mid-run is a trailing tool call, bare
        // reasoning, or a structured tool payload; any visible content after those (prose, a
        // table, a diagram, ask-chips) is the answer having landed.
        val tail = segs.lastOrNull {
            !(it is MessageParser.Segment.Telemetry) &&
                !(it is MessageParser.Segment.Text && it.text.isBlank())
        }
        val hasAnswer = !isTelemetry && tail != null &&
            tail !is MessageParser.Segment.Tools &&
            tail !is MessageParser.Segment.Thinking &&
            tail !is MessageParser.Segment.ActionOutput
        _workLabel.value = when {
            // Telemetry first: a "⏳ Working…" heartbeat parses as a tool-shaped line too, and
            // "Running Working" is not a label.
            isTelemetry -> "Working"
            // The shared grammar names it, so the banner says what the theater says: "Reading
            // a.txt", not "Running read_file" (and never the "Running Reading" a friendly
            // progress line used to produce).
            !hasAnswer && tools.isNotEmpty() -> tools.last().let {
                ToolGrammar.title(it.name, ToolGrammar.targetOf(it.name, it.context), running = true)
            }
            hasReasoning && !hasAnswer -> "Reasoning"
            else -> "Working"
        }
        // The background review's post-turn "💾 Self-improvement review" is telemetry that
        // arrives AFTER the answer — it must settle immediately, not hold the working banner
        // through the full mid-run quiet window.
        if (isTelemetry && MessageParser.isSelfImprovementReview(last.content)) return QUIET_SHORT_MS
        return if (hasAnswer) QUIET_SHORT_MS else QUIET_LONG_MS
    }

    // A room the user asked to open (e.g. by tapping a notification) before the room list loaded.
    private var pendingOpenRoomId: String? = null

    init {
        // Before anything restores a room: last launch's temporary sessions die first, and the
        // synchronous lastRoomId clear inside keeps a dead temp from being this launch's room.
        sweepTemporarySessions()
        viewModelScope.launch {
            transport.getRooms().collectLatest { roomList ->
                _rooms.value = roomList
                // A pending notification-tap target takes priority once its room is available.
                val pending = pendingOpenRoomId
                if (pending != null) {
                    val room = roomList.firstOrNull { it.id == pending }
                    if (room != null) {
                        pendingOpenRoomId = null
                        selectRoom(room)
                        return@collectLatest
                    }
                }
                // Restore the last open conversation once rooms are available.
                if (_currentRoom.value == null) {
                    val lastId = settingsRepository.lastRoomId
                    val room = roomList.firstOrNull { it.id == lastId }
                    if (room != null) setCurrentRoom(room)
                }
            }
        }
        // Each room is its own session with its own brain: the model catalog and the
        // reasoning dial both belong to the room you left, so drop one and re-probe the other.
        viewModelScope.launch {
            _currentRoom.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                models.clear()
                refreshReasoningCaps()
            }
        }
        viewModelScope.launch {
            matrix?.getInvites()?.collectLatest { _invites.value = it }
        }
        // The compaction hold, on the door this app actually runs on. The gateway narrates a
        // compaction over `status.update`, which the direct transport already keys per session
        // — but only the Matrix side-channel's `status` frame armed the hold, so a fifteen-
        // minute compaction still settled the banner at the four-minute mark here. Its own
        // StateFlow (not `sessionStatus`, which is Lazily-shared and therefore only alive while
        // some screen collects it): the hold must be armed whether or not anything is drawing.
        direct?.let { d ->
            viewModelScope.launch {
                _currentRoom
                    .flatMapLatest { r -> if (r == null) flowOf(null) else d.sessionStatus(r.id) }
                    .collect { noteCompacting(it) }
            }
        }
        // A thinking level the MODEL won't render, walked back instead of died over. The
        // transport has republished these refusals from three places since the day the channel
        // was written — "so the level that killed the turn can be walked back instead of killing
        // the next one too" — and nothing collected them, so the turn just quietly failed, and
        // the next one, and the next.
        direct?.let { d ->
            viewModelScope.launch {
                d.reasoningRejections().collect { walkBackReasoning() }
            }
        }
        viewModelScope.launch {
            messages.collect { msgs ->
                val last = msgs.lastOrNull()
                chat.keryx.app.util.KLog.i("KeryxFlow") {
                    "emission n=${msgs.size} last=${last?.id?.take(12)} sender=${last?.sender} len=${last?.content?.length} " +
                        "stream=${_liveStream.value?.status} awaiting=${_awaitingReply.value}"
                }
                // An empty emission (room switch reset, transient flow restart) must NOT touch the
                // classification state: nulling lastSeenId made the NEXT emission look like a first
                // open, and the openedMidRun recency check re-lit the working banner for a message
                // that had already settled.
                if (last == null) return@collect
                // Classify this emission relative to the last one we saw. Crossing into another
                // room gets first-open semantics (recency-guarded openedMidRun), NOT "new message"
                // — an old conversation that merely ended on a tool call must not light the banner.
                val roomChanged = lastSeenRoomId != last.roomId
                val firstEval = lastSeenId == null || roomChanged
                val isNewMsg = !firstEval && last.id != lastSeenId
                val grew = !firstEval && last.id == lastSeenId && last.content.length > lastSeenLen
                lastSeenRoomId = last.roomId
                lastSeenId = last.id
                lastSeenLen = last.content.length

                // Side-channel handoff: the moment the committed Matrix event for the streamed
                // response is present in the timeline, drop the overlay — same frame, no pop. Look
                // beyond just the last event because Hermes may emit a separate runtime footer right
                // after the answer; that footer must not keep the stream bubble pinned until timeout.
                // Runs BEFORE the own-echo early-return: an emission whose last event is my echo can
                // still be the one that carried the committed answer into the list.
                maybeHandOffStream(msgs, last, isNewMsg)

                if (last.sender == SenderType.ME) {
                    // A fresh prompt of ours starts a turn with no signs yet — quips may speak
                    // until the agent shows something (3.1 §C3).
                    _liveTurnSigns.value = false
                    // Our echo is back from the homeserver: retire the optimistic send bubble the
                    // same frame its real timeline event renders — that's the seamless swap. Reply
                    // echoes may carry a quote-fallback prefix, so suffix match is accepted too.
                    val pending = _pendingSend.value
                    if (pending != null && last.roomId == pending.roomId &&
                        pendingEchoMatches(last.content, pending.text)
                    ) clearPendingSend()
                    return@collect
                }
                if (last.sender == SenderType.OTHER) {
                    // A human's message is dialogue, not agent work — it must never light (or
                    // relabel) the working banner. If the agent is still busy through a human
                    // interjection, its typing signal keeps the banner alive on its own.
                    return@collect
                }

                // updateWorkStateFrom both sets the "what it's doing" label and returns the adaptive
                // quiet window; QUIET_LONG_MS means the agent is mid-run (a tool call, or reasoning
                // with no answer yet — more is coming).
                val stateMessage = workStateMessage(msgs, last)
                workStateId = stateMessage.id
                val window = updateWorkStateFrom(stateMessage)
                val midRun = window == QUIET_LONG_MS
                answerLanded = !midRun
                _liveTurnSigns.value = midRun || last.isStreaming

                // Auto-speak: exactly the settled-answer moment, for both delivery tiers (the
                // committed Matrix event always lands here). ME/OTHER senders returned above, so
                // this is agent output; !midRun reuses updateWorkStateFrom's classification —
                // mid-turn tool commits and reasoning-only chunks never speak, telemetry is
                // filtered explicitly, and background rooms stay silent.
                if (voice.ttsAutoSpeak.value && isNewMsg && !midRun && !last.isStreaming &&
                    last.sender == SenderType.HERMES &&
                    last.roomId == _currentRoom.value?.id &&
                    last.id != lastAutoSpokenId &&
                    !chat.keryx.core.protocol.MessageParser.isTelemetryMessage(last.content)
                ) {
                    lastAutoSpokenId = last.id
                    _speakRequests.tryEmit(last)
                }
                // Live activity = a brand-new message or a streamed edit growing the current one.
                val liveActivity = isNewMsg || grew
                if (liveActivity) agentEvidenceAt = System.currentTimeMillis()
                // On first open, fall back to recency so opening the app mid-run still lights up,
                // without falsely firing for an old conversation that merely ended on a tool call —
                // and never for a message whose banner already lit and settled once.
                val openedMidRun = firstEval && stateMessage.id !in settledWorkIds &&
                    (System.currentTimeMillis() - stateMessage.timestamp) < WORKING_RECENT_MS

                when {
                    _awaitingReply.value -> scheduleClearAwaiting(window)
                    midRun && (liveActivity || openedMidRun) -> {
                        // The agent is working but we didn't initiate it (app opened / room switched
                        // mid-run, or a run started elsewhere). Light up the cloud + quips.
                        _awaitingReply.value = true
                        if (_workStartedAt.value == null) _workStartedAt.value = last.timestamp
                        scheduleClearAwaiting(window)
                    }
                }
            }
        }
        // The typing indicator is the authoritative "busy" signal; this collector was defined but
        // never started, which is why the banner still died during long silent tool calls.
        observeTyping()
        refreshReasoningCaps()
    }

    private fun workStateMessage(messages: List<Message>, latest: Message): Message {
        // Judge the work state from the real answer, not from a runtime footer or a blank
        // placeholder that landed after it. HERMES-only: the blank-agent-id blindness this filter
        // once hedged against is now solved structurally in senderTypeOf (legacy agent-room
        // fallback), and "not mine" would let a HUMAN's group-room message relabel the banner.
        // Structure counts as content (3.1 §C1, both doors symmetric): the direct door's live
        // overlay carries its thought in Message.reasoning with content "" — skipping it made
        // the cloud say "Working" during pure thinking where Matrix says "Reasoning"
        // (device-caught on the 08-24 fluency walk).
        val hasStructure = !latest.reasoning.isNullOrBlank() || latest.toolCalls.isNotEmpty()
        if (!MessageParser.isRuntimeFooterMessage(latest.content) &&
            (latest.content.isNotBlank() || hasStructure)
        ) return latest
        return messages.asReversed()
            .asSequence()
            .dropWhile { it.id == latest.id }
            .firstOrNull {
                it.roomId == latest.roomId &&
                    it.sender == SenderType.HERMES &&
                    it.content.isNotBlank() &&
                    !MessageParser.isTelemetryMessage(it.content)
            }
            ?: latest
    }

    /** Drive the working banner from Hermes' Matrix typing indicator — the authoritative "busy"
     *  signal. It stays true (Hermes refreshes it) through long single tool calls that emit nothing,
     *  fixing the "banner vanished while it was still working on a curl" case. Human typers are a
     *  separate lane: they surface as a plain "X is typing…" line, never as the working banner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTyping() {
        viewModelScope.launch {
            _currentRoom
                .flatMapLatest { s ->
                    if (s == null) flowOf(chat.keryx.core.model.TypingState())
                    else transport.typing(s.id)
                }
                .collect { state ->
                    _typingHumans.value = state.humanNames
                    _typingAgentIds.value = state.agentIds
                    val typing = state.agentTyping
                    chat.keryx.app.util.KLog.i("KeryxTyping") { "typing=$typing humans=${state.humanNames.size} awaiting=${_awaitingReply.value} answerLanded=$answerLanded" }
                    agentTyping = typing
                    if (typing) {
                        agentEvidenceAt = System.currentTimeMillis()
                        // Not a bare cancel: typing holds the banner, but only on a leash.
                        // This reschedule is the watchdog that fires if nothing — no stop
                        // EDU, no message — ever renews the evidence again.
                        scheduleClearAwaiting(TYPING_TRUST_MS)
                        if (!_awaitingReply.value) {
                            _awaitingReply.value = true
                            if (_workStartedAt.value == null) _workStartedAt.value = System.currentTimeMillis()
                        }
                    } else if (_awaitingReply.value) {
                        // Stopped typing → the final message is landing. If the answer is ALREADY
                        // on screen (it arrived while typing was still flagged), don't make the
                        // quips linger through the full grace — settle almost immediately.
                        scheduleClearAwaiting(if (answerLanded) ANSWER_SETTLED_MS else TYPING_STOP_GRACE_MS)
                    }
                }
        }
    }

    // --- The Call (1.22) ------------------------------------------------------------------------

    /** Live-call tap: while non-null, the tier-1 stream mirrors this turn's assistant text here —
     *  raw deltas while streaming, then the terminal signal. The Call layers sentence-chunking
     *  and TTS on top; a null tap costs the stream path nothing. Reasoning is deliberately NOT
     *  mirrored: the agent thinks silently, like anyone worth talking to. */
    interface CallTurnTap {
        fun onDelta(text: String)
        fun onTurnEnd(finalText: String?)
        fun onTurnFailed()
    }

    @Volatile
    private var _callTurnTap: CallTurnTap? = null
    private var callBridgeJob: Job? = null

    /**
     * The Call's tap, on either door. The Matrix door feeds it from the tier-1 SSE below; the
     * direct door has no side channel at all — the turn IS the WS — so setting a tap opens a
     * bridge off the transport's own [chat.keryx.core.model.TurnEvent] stream, which the
     * transport has always emitted and nothing has ever read. Without it the Call transcribed,
     * sent, and then sat in silence until its watchdog gave up, on the one door the app ships on.
     *
     * The bridge is scoped to the session the tap was set for: `turnEvents` carries every live
     * session on this gateway (a cron run, another bot answering), and the Call must voice the
     * conversation it is in and nothing else.
     */
    var callTurnTap: CallTurnTap?
        get() = _callTurnTap
        set(value) {
            _callTurnTap = value
            callBridgeJob?.cancel()
            callBridgeJob = null
            val d = direct ?: return
            val sessionId = _currentRoom.value?.id ?: return
            if (value == null) return
            callBridgeJob = viewModelScope.launch {
                d.turnEvents().collect { ev ->
                    if (ev.sessionId != sessionId) return@collect
                    val tap = _callTurnTap ?: return@collect
                    when (ev) {
                        is chat.keryx.core.model.TurnEvent.Delta -> tap.onDelta(ev.text)
                        // A segment boundary is a pause in speech, exactly as SegmentBreak is.
                        is chat.keryx.core.model.TurnEvent.Break -> tap.onDelta("\n")
                        is chat.keryx.core.model.TurnEvent.End ->
                            if (ev.error) tap.onTurnFailed() else tap.onTurnEnd(ev.finalText)
                    }
                }
            }
        }


    // --- Side-channel stream orchestration (tier-1) -------------------------------------------

    /**
     * Open the transient SSE subscription for this turn. Called right before the command is sent
     * so the gateway sees the subscriber and diverts tokens here instead of protocol edits.
     * Unreachable channel → the overlay silently vanishes and tier-2 (Matrix sync rendering,
     * including any throttled m.replace edits) is simply what the user sees.
     */
    private fun openSideChannel(roomId: String) {
        streamJob?.cancel()
        streamClearJob?.cancel()
        _liveStream.value = null
        val url = _gatewayUrl.value.trim()
        if (!_sideChannelEnabled.value || url.isBlank()) {
            _linkHealth.value = LinkHealth.OFF
            return
        }
        val client = chat.keryx.app.data.remote.HermesStreamClient(
            baseUrl = url,
            apiKey = _gatewayApiKey.value,
            allowInsecure = settingsRepository.allowInsecure,
        )
        streamJob = viewModelScope.launch {
            // Incremental trackers: every dispatch tick reads O(window) off these no matter how
            // long the turn grows — the old StringBuilder + full sanitize/window pass copied and
            // re-scanned the whole buffer 10×/s (the marathon-freeze class v1.18.3 chased).
            val buf = StreamTailTracker(STREAM_WINDOW_CHARS, sanitize = true)
            val reasoningBuf = StreamTailTracker(STREAM_REASONING_WINDOW_CHARS, sanitize = false)
            var lastDispatch = 0L
            var charsSinceDispatch = 0
            var firstDeltaAt = 0L
            var lastDeltaAt = 0L
            // EMA of the instantaneous delta rate (chars/s); see TPS_* constants.
            var emaCps = 0f
            var theater = chat.keryx.core.model.TheaterState()
            fun dispatch(status: LiveStreamStatus, finalText: String? = null) {
                val cur = _liveStream.value ?: LiveStream(roomId, "", status, System.currentTimeMillis())
                _liveStream.value = cur.copy(
                    text = buf.windowText(),
                    // Full-text copy only when the turn ends; STREAMING-phase matching pulls it
                    // on demand through currentStreamFullText instead of paying O(n) per tick.
                    matchText = if (status == LiveStreamStatus.AWAITING_SYNC) buf.sanitizedFullText()
                                else cur.matchText,
                    status = status,
                    finalText = finalText ?: cur.finalText,
                    charsPerSec = emaCps,
                    reasoning = reasoningBuf.windowText(),
                    theater = theater,
                )
                lastDispatch = System.currentTimeMillis()
                charsSinceDispatch = 0
            }
            currentStreamFullText = { buf.sanitizedFullText() }
            lastHandoffFingerprint = 0
            _liveStream.value = LiveStream(roomId, "", LiveStreamStatus.CONNECTING, System.currentTimeMillis())
            // Mid-turn segment commit: everything streamed so far is now a committed Matrix
            // message. Shed it from the overlay (text AND its reasoning — the commit carries its
            // own folded 💭 block) but leave the SSE subscription running for the next phase.
            consumeStreamedSegment = {
                chat.keryx.app.util.KLog.i("KeryxHandoff") { "consume segment (${buf.length}ch text, ${reasoningBuf.length}ch reasoning) — stream stays live" }
                buf.clear()
                reasoningBuf.clear()
                // The beats are NOT cleared here (3.1 §A1, reversing 2.4's workaround).
                //
                // They used to be, because the committed segment carries its own parsed tool rows
                // and a second renderer inside the bubble then showed every call twice. There is
                // no second renderer now: the beats reach the transcript through `withLiveTheater`,
                // which drops exactly as many as the committed run already carries and enriches
                // those with what the text could never say. Clearing them here would have thrown
                // away the first segment's durations, verdicts and diffs on every steered turn.
                if (_liveStream.value != null) dispatch(LiveStreamStatus.STREAMING)
            }
            client.stream(roomId).collect { ev ->
                if (ev !is chat.keryx.app.data.remote.HermesStreamClient.Event.Delta)
                    chat.keryx.app.util.KLog.i("KeryxSSE") { "event=$ev bufLen=${buf.length}" }
                when (ev) {
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Opened -> {
                        _linkHealth.value = LinkHealth.LIVE
                        dispatch(LiveStreamStatus.STREAMING)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Delta -> {
                        // Tokens flowing means whatever the status announced is over, whether or
                        // not its `ready` arrived.
                        if (_matrixStatus.value != null) _matrixStatus.value = null
                        val now = System.currentTimeMillis()
                        if (firstDeltaAt == 0L) {
                            firstDeltaAt = now
                        } else {
                            val dt = now - lastDeltaAt
                            if (dt in TPS_MIN_FRAME_MS..TPS_MAX_FRAME_MS && ev.text.isNotEmpty()) {
                                val instant = ev.text.length * 1000f / dt
                                emaCps = if (emaCps <= 0f) instant
                                         else TPS_EMA_WEIGHT * emaCps + (1f - TPS_EMA_WEIGHT) * instant
                            }
                        }
                        lastDeltaAt = now
                        buf.append(ev.text)
                        callTurnTap?.onDelta(ev.text)
                        charsSinceDispatch += ev.text.length
                        if (now - lastDispatch >= STREAM_DISPATCH_MS || charsSinceDispatch >= STREAM_DISPATCH_CHARS) {
                            dispatch(LiveStreamStatus.STREAMING)
                        }
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Reasoning -> {
                        // Live reasoning: shares the delta throttle so a fast thinker can't outpace
                        // recomposition. While only reasoning has arrived, the top banner says so.
                        reasoningBuf.append(ev.text)
                        charsSinceDispatch += ev.text.length
                        if (buf.isEmpty() && _workLabel.value == "Working") _workLabel.value = "Reasoning"
                        val now = System.currentTimeMillis()
                        if (now - lastDispatch >= STREAM_DISPATCH_MS || charsSinceDispatch >= STREAM_DISPATCH_CHARS) {
                            dispatch(LiveStreamStatus.STREAMING)
                        }
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Tool -> {
                        // Rare next to token deltas (a handful per turn), so it never waits for
                        // the throttle — a tool starting is exactly the beat you want on screen.
                        theater = chat.keryx.core.model.Theater.reduce(theater, ev.event)
                        dispatch(_liveStream.value?.status ?: LiveStreamStatus.STREAMING)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.SegmentBreak -> {
                        if (buf.isNotEmpty() && !buf.endsWith("\n\n")) buf.append("\n\n")
                        callTurnTap?.onDelta("\n")
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Usage -> {
                        _contextUsage.value = ContextUsage(roomId, ev.used, ev.max, ev.model)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Status -> {
                        _matrixStatus.value = ev.status
                        // Compaction is the turn WORKING, for as long as the summary model
                        // takes; the no-reply timer must not read it as the agent gone quiet.
                        // Arming a fixed window here was the bug: the gateway says "compacting"
                        // exactly once, so the four minutes ran out while the compaction didn't,
                        // and the room went quiet mid-turn. Hold open instead, and re-arm on the
                        // way out so quiet is counted from when the work actually stopped.
                        noteCompacting(ev.status)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Stop -> {
                        _matrixStatus.value = null
                        // The turn is over, so whatever it was compacting is over with it. A drop
                        // (Failed) deliberately does NOT clear this: the gateway is very likely
                        // still compacting on the other side of a dead socket, and the hold's own
                        // ceiling is what bounds that case.
                        compactingSince = null
                        _linkHealth.value = LinkHealth.OK
                        callTurnTap?.onTurnEnd(ev.finalText)
                        if (ev.finalText.isNullOrBlank() && buf.isBlank() && reasoningBuf.isBlank()) {
                            // Everything this turn produced was already consumed by mid-turn
                            // segment commits — nothing left to hand off; don't hold an invisible
                            // overlay through the 20 s sync grace.
                            clearStream(); settleTurn()
                            return@collect
                        }
                        dispatch(LiveStreamStatus.AWAITING_SYNC, finalText = ev.finalText ?: buf.rawText())
                        // The committed event may have synced BEFORE stop arrived; nothing else
                        // re-triggers the handoff check (the messages flow won't emit again), so
                        // evaluate against the current timeline right now — otherwise the overlay
                        // sits beside its own committed copy until the sync-grace timeout.
                        messages.value.lastOrNull()?.let { maybeHandOffStream(messages.value, it, isNewMsg = false) }
                        if (_liveStream.value != null) scheduleStreamClear(STREAM_SYNC_GRACE_MS)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Failed -> {
                        callTurnTap?.onTurnFailed()
                        if (!ev.connected) _linkHealth.value = LinkHealth.UNREACHABLE
                        else if (_linkHealth.value == LinkHealth.LIVE) _linkHealth.value = LinkHealth.OK
                        if (!ev.connected || buf.isBlank()) {
                            // Never connected / nothing shown yet: fall back to tier-2, but SAY so —
                            // a silently dead side-channel just looks like "streaming doesn't work".
                            _liveStream.value = null
                            if (!ev.connected) {
                                _toasts.tryEmit("Hermes Link unreachable (${ev.reason.take(80)}) — using Matrix sync")
                            }
                        } else if (isAppForeground?.invoke() == false) {
                            // Mid-stream drop while the app is OFF SCREEN — Doze cutting the
                            // socket is the normal fate of an unattended phone, and the run is
                            // almost certainly still alive on the gateway, so painting
                            // INTERRUPTED here reads as a false "the run died" (08-25
                            // diagnosis). Park the partial as awaiting-sync — the same
                            // machinery the stop path uses — so the committed event swaps in
                            // seamlessly when sync catches up on reopen, and the overlay
                            // retires quietly otherwise.
                            dispatch(LiveStreamStatus.AWAITING_SYNC, finalText = buf.rawText())
                            scheduleStreamClear(STREAM_SYNC_GRACE_MS)
                        } else {
                            // Mid-stream drop with visible partial text while the user is
                            // watching: keep it, show the alert, and recover the moment the
                            // final event syncs via Matrix.
                            dispatch(LiveStreamStatus.INTERRUPTED)
                            scheduleStreamClear(STREAM_INTERRUPT_HOLD_MS)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleStreamClear(delayMs: Long) {
        streamClearJob?.cancel()
        streamClearJob = viewModelScope.launch {
            delay(delayMs)
            clearStream()
        }
    }

    /** The streamed answer's committed copy is on screen — the turn is over. Retire the
     *  working banner almost immediately instead of waiting out typing-stop grace windows. */
    private fun settleTurn() {
        answerLanded = true
        scheduleClearAwaiting(ANSWER_SETTLED_MS, force = true)
    }

    /**
     * The structured record of the turn that just finished, so the committed transcript can show
     * what the message text never carried — durations, real verdicts, real diffs (2.4).
     *
     * Room-keyed and one deep, on purpose: this is "the run you just watched", not a cache. It
     * dies with the process, and history then renders exactly as it did before — same grammar,
     * fewer facts. Persisting it would mean a second store of tool results, and the answer to
     * "what did that edit change" a week later is the file, not a phone's memory of it.
     */
    private val _lastTurnTheater =
        MutableStateFlow<Pair<String, chat.keryx.core.model.TheaterState>?>(null)
    val lastTurnTheater: StateFlow<Pair<String, chat.keryx.core.model.TheaterState>?> =
        _lastTurnTheater.asStateFlow()

    private fun clearStream() {
        chat.keryx.app.util.KLog.i("KeryxHandoff") { "clearStream (was ${_liveStream.value?.status})" }
        _matrixStatus.value = null
        _liveStream.value?.let { s ->
            if (s.theater.beats.isNotEmpty()) _lastTurnTheater.value = s.roomId to s.theater
        }
        streamClearJob?.cancel()
        streamJob?.cancel()
        consumeStreamedSegment = null
        currentStreamFullText = null
        _liveStream.value = null
    }

    /** Drop the overlay when its committed Matrix counterpart is in the timeline. */
    private fun maybeHandOffStream(messages: List<Message>, last: Message, isNewMsg: Boolean) {
        val s = _liveStream.value ?: return
        if (last.roomId != s.roomId) return
        // Runs on every messages emission during a turn, and normalizing the streamed target is a
        // full uncached parse — skip when neither the stream phase nor the candidate window (last
        // 8 non-ME messages in this room, by id + length) has changed since the last evaluation.
        var fingerprint = s.status.ordinal * 31 + if (isNewMsg) 1 else 0
        var seen = 0
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.roomId != s.roomId || m.sender == SenderType.ME) continue
            fingerprint = fingerprint * 31 + m.id.hashCode()
            fingerprint = fingerprint * 31 + m.content.length
            if (++seen == 8) break
        }
        if (fingerprint == lastHandoffFingerprint) return
        lastHandoffFingerprint = fingerprint
        chat.keryx.app.util.KLog.i("KeryxHandoff") {
            "check status=${s.status} new=$isNewMsg last=${last.sender}/${last.content.length}ch"
        }
        when (s.status) {
            LiveStreamStatus.STREAMING -> {
                // matchText is only materialized at `stop`; while streaming, pull the full
                // sanitized text on demand (once per evaluation, not per dispatch tick).
                val target = s.finalText ?: currentStreamFullText?.invoke() ?: s.matchText
                if (target.isNotBlank()) {
                    val normalizedTarget = StreamHandoff.normalize(target, cacheable = false)
                    val matched = normalizedTarget.isNotEmpty() && messages.asReversed()
                        .asSequence()
                        .filter { it.roomId == s.roomId && it.sender != SenderType.ME }
                        .filterNot { MessageParser.isTelemetryMessage(it.content) }
                        .take(8)
                        .any { StreamHandoff.matchesNormalized(it.content, normalizedTarget) }
                    // A match while still STREAMING is a MID-TURN segment commit (tool call coming
                    // up — Hermes committed the text so far as its own message). Shed the committed
                    // part but keep the SSE channel: the post-tool reasoning + answer are still on
                    // their way down this same subscription. `stop` / AWAITING_SYNC ends the turn.
                    if (matched) consumeStreamedSegment?.invoke() ?: run { clearStream(); settleTurn() }
                }
            }
            LiveStreamStatus.AWAITING_SYNC -> {
                val recentHermes = messages.asReversed()
                    .asSequence()
                    .filter { it.roomId == s.roomId && it.sender != SenderType.ME }
                    .take(8)
                    .toList()
                val target = s.finalText ?: s.matchText
                val normalizedTarget =
                    if (target.isBlank()) "" else StreamHandoff.normalize(target, cacheable = false)
                val matched = normalizedTarget.isNotEmpty() && recentHermes
                    .asSequence()
                    .filterNot { MessageParser.isTelemetryMessage(it.content) }
                    .any { StreamHandoff.matchesNormalized(it.content, normalizedTarget) }
                val hasCommittedAnswer = recentHermes.any {
                    !MessageParser.isTelemetryMessage(it.content) &&
                        StreamHandoff.normalize(it.content).isNotBlank()
                }
                chat.keryx.app.util.KLog.i("KeryxHandoff") { "awaitSync matched=$matched newMsg=$isNewMsg committed=$hasCommittedAnswer recent=${recentHermes.size}" }
                if (matched || (isNewMsg && hasCommittedAnswer)) { clearStream(); settleTurn() }
            }
            LiveStreamStatus.INTERRUPTED -> {
                // Any fresh substantive answer ends the recovery hold — the sync loop has caught up.
                if (isNewMsg && last.sender != SenderType.ME &&
                    !MessageParser.isTelemetryMessage(last.content) &&
                    StreamHandoff.normalize(last.content).isNotBlank()
                ) clearStream()
            }
            else -> Unit
        }
    }

    fun prefillComposer(text: String) { _composerPrefill.value = text }
    fun consumeComposerPrefill() { _composerPrefill.value = null }

    /** Open a room by id (from a notification tap). Defers until the room list is loaded if needed. */
    /** Open a session by id even when the roster does not carry it (a project's session, a
     *  run from another machine): it is adopted locally under [title] first. */
    fun openSessionById(sessionId: String, title: String) {
        if (_rooms.value.none { it.id == sessionId }) {
            // Claim the session as "on screen" BEFORE the roster learns of it: adopting
            // publishes a row stamped now, and the notification watcher would otherwise
            // read that row as new activity in a session nobody is looking at — and
            // notify you about the very run you just tapped.
            onOpenRoomChanged?.invoke(sessionId)
            transport.gateway?.adoptSession(sessionId, title)
        }
        openRoomById(sessionId)
    }

    fun openRoomById(roomId: String) {
        val room = _rooms.value.firstOrNull { it.id == roomId }
        if (room != null) selectRoom(room)
        else pendingOpenRoomId = roomId
    }

    fun selectRoom(room: RoomProfile) {
        setCurrentRoom(room)
        limitDecayJob?.cancel()
        limitDecayJob = null
        _timelineLimit.value = INITIAL_LIMIT
        _hasMoreHistory.value = true
        _isLoadingMore.value = false
        _replyTarget.value = null
        quietJob?.cancel()
        _awaitingReply.value = false
        _workStartedAt.value = null
        compactingSince = null
        // The stream is NOT cancelled on a room switch: the overlay is already room-filtered in
        // the UI, so hopping to another room and back mid-turn resumes the live view instead of
        // silently degrading the whole turn to Matrix sync (mobile users switch rooms constantly).
        // The SSE job keeps collecting in viewModelScope; handoff re-evaluates the moment the
        // origin room's timeline is observed again, and the post-`stop` sync-grace timer still
        // bounds its lifetime if the user never returns.
        clearPendingSend()
        // Re-baseline activity tracking so the newly-opened room is judged on its own recency, not
        // treated as "new activity" just because its last message differs from the previous room's.
        lastSeenId = null
        lastSeenLen = -1
        // The old room's reaction flows are all unsubscribed now — drop them rather than letting
        // them age out of the LRU while the new room fills it.
        synchronized(reactionFlows) { reactionFlows.clear() }
        settingsRepository.lastRoomId = room.id
        _typingHumans.value = emptyList()
        // Warm the member store so sender display names resolve in cold group rooms.
        viewModelScope.launch { matrix?.ensureMembersLoaded(room.id) }
        archive.onRoomOpened()
        // Opening a bot's forever-chat is looking at it: the roster's news dot clears.
        bots.touchOpenSession(room.id)
    }

    /** Load an older page of history (called when the user scrolls to the top of the timeline). */
    fun loadOlderMessages() {
        if (_isLoadingMore.value || !_hasMoreHistory.value) return
        if (_timelineLimit.value >= MAX_LIMIT) {
            // Memory backstop: past this the loaded window stops growing (every loaded event is a
            // resolved Message + a live store flow in the repository combine). Deeper archaeology
            // is what session search / the desktop is for.
            _hasMoreHistory.value = false
            return
        }
        _isLoadingMore.value = true
        val before = messages.value.size
        _timelineLimit.value += PAGE
        viewModelScope.launch {
            // Wait for the bigger page to actually resolve (the count grows). If it doesn't within a
            // few seconds, we've hit the start of the room's history.
            val grew = withTimeoutOrNull(8_000L) {
                messages.first { it.size > before }
                true
            } ?: false
            if (!grew) _hasMoreHistory.value = false
            _isLoadingMore.value = false
        }
    }

    /** Timeline-window decay: after the user has sat at the BOTTOM of the list for a dwell, a
     *  deep-scrolled window shrinks back to [INITIAL_LIMIT] — a long back-read must not pin
     *  hundreds of resolved events (and their per-event store flows) for the rest of the session.
     *  Never fires while the user is actually reading history (not at bottom cancels the timer),
     *  and scrolling up again just refetches from the local store. */
    fun onViewportAtBottom(atBottom: Boolean) {
        if (!atBottom) {
            limitDecayJob?.cancel()
            limitDecayJob = null
            return
        }
        // Only worth decaying when the window actually holds more than the initial page — a tiny
        // room whose auto-pagination bumped the limit past its total must not enter a
        // decay→re-paginate cycle (nothing would be freed anyway).
        if (_timelineLimit.value <= INITIAL_LIMIT || messages.value.size <= INITIAL_LIMIT ||
            limitDecayJob?.isActive == true
        ) return
        limitDecayJob = viewModelScope.launch {
            delay(LIMIT_DECAY_DWELL_MS)
            _timelineLimit.value = INITIAL_LIMIT
            _hasMoreHistory.value = true
        }
    }

    fun setReplyTarget(message: Message?) { _replyTarget.value = message }
    fun clearReplyTarget() { _replyTarget.value = null }

    // Media/avatar downloads are owned by viewModelScope (NOT the Compose produceState coroutine),
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
    // in onCleared so a dead ViewModel isn't pinned by the process-wide registry.
    private val cacheTrimmer: (Boolean) -> Unit = { aggressive ->
        if (aggressive) {
            mediaBytesCache.clear()
            avatarBytesCache.clear()
            pet.trimThumbs()
        } else {
            mediaBytesCache.trimToBytes(mediaBytesCache.sizeBytes / 2)
            avatarBytesCache.trimToBytes(avatarBytesCache.sizeBytes / 2)
        }
    }

    init {
        chat.keryx.app.util.CacheRegistry.register(cacheTrimmer)
    }

    override fun onCleared() {
        chat.keryx.app.util.CacheRegistry.unregister(cacheTrimmer)
        super.onCleared()
    }

    suspend fun loadMessageMedia(sessionId: String, eventId: String): ByteArray? {
        mediaBytesCache.get(eventId)?.let { return it }
        val deferred = mediaInFlight.getOrPut(eventId) {
            viewModelScope.async(Dispatchers.IO) {
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
            d.peekPreview(roomId)?.let { return previewOf(it) }
            return _rooms.value.firstOrNull { it.id == roomId }?.preview?.takeIf { it.isNotBlank() }
        }
        val msgs = withTimeoutOrNull(5_000L) {
            transport.getMessages(roomId, 8).first { it.isNotEmpty() }
        } ?: return previewCache[roomId]?.second
        // Skip runtime footers so the preview reads as the conversation, not plumbing.
        val last = msgs.lastOrNull { !MessageParser.isRuntimeFooterMessage(it.content) } ?: msgs.lastOrNull() ?: return null
        val preview = previewOf(last)
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
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            }
        }

    fun sendReaction(eventId: String, emoji: String) {
        val session = _currentRoom.value ?: return
        viewModelScope.launch { transport.react(session.id, eventId, emoji) }
    }

    fun toggleTheme(isDark: Boolean?) {
        _isDarkTheme.value = isDark
    }

    fun setAccentColor(color: Color) {
        _accentColor.value = color
        settingsRepository.accentColorHex = String.format("#%06X", (0xFFFFFF and color.toArgb()))
    }

    fun setAccentColor2(color: Color) {
        _accentColor2.value = color
        settingsRepository.accentColor2Hex = String.format("#%06X", (0xFFFFFF and color.toArgb()))
    }

    fun setMatrixUrl(url: String) {
        _matrixUrl.value = url
        settingsRepository.homeserverUrl = url
    }

    fun setAgentMatrixId(id: String) {
        _agentMatrixId.value = id
        settingsRepository.agentMatrixId = id
    }

    /** Set (or, with a null [hex], clear) one herald's accent override. */
    fun setHeraldAccent(localpart: String, hex: String?) {
        val key = localpart.trim().lowercase()
        if (key.isEmpty()) return
        val next = _heraldAccents.value.toMutableMap()
        if (hex.isNullOrBlank()) next.remove(key) else next[key] = hex
        _heraldAccents.value = next
        settingsRepository.heraldAccents = next
    }

    fun setMatrixToken(token: String) {
        _matrixToken.value = token
        settingsRepository.matrixToken = token
    }

    fun setBiometricLock(enabled: Boolean) {
        _biometricLock.value = enabled
        settingsRepository.biometricLockEnabled = enabled
    }

    fun setE2eeEnabled(enabled: Boolean) {
        _e2eeEnabled.value = enabled
        settingsRepository.e2eeEnabled = enabled
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _hapticsEnabled.value = enabled
        settingsRepository.hapticsEnabled = enabled
    }

    fun setAnimationStyle(style: String) {
        _animationStyle.value = style
        settingsRepository.animationStyle = style
    }

    fun setBubbleStyle(style: String) {
        _bubbleStyle.value = style
        settingsRepository.bubbleStyle = style
    }

    fun setMessageTextScale(scale: Float) {
        _messageTextScale.value = scale
        settingsRepository.messageTextScale = scale
    }

    /** Restore default message appearance: gradient bubbles, default accent, default text size. */
    fun resetMessageAppearance() {
        setBubbleStyle("Gradient")
        setMessageTextScale(1.0f)
        setAccentColor(Color(0xFFE55A00))
        setAccentColor2(Color(0xFF8B5CF6))
    }

    fun onComposerTextChanged(text: String) {
        // Show the palette only while typing the command token itself (before the first space),
        // so it auto-dismisses once a command is chosen and the user moves on to its arguments.
        _commandMenuVisible.value = text.startsWith("/") && !text.contains(' ')
        if (text.startsWith("/")) _commandFilter.value = text.removePrefix("/").substringBefore(' ')
        // Keep the per-room draft current so an app kill / room switch never loses typed text.
        _currentRoom.value?.id?.let { settingsRepository.setDraft(it, text) }
        broadcastTyping(text)
    }

    /** The saved (unsent) composer text for [roomId] — restored when the room opens. */
    fun draftFor(roomId: String): String = settingsRepository.getDraft(roomId)

    /** Remember a slash command the user picked, so the palette can surface recents first. */
    fun recordCommandUse(command: String) {
        val cmd = command.trim().substringBefore(' ')
        if (cmd.isBlank()) return
        val updated = (listOf(cmd) + _recentCommands.value.filter { it != cmd }).take(8)
        _recentCommands.value = updated
        settingsRepository.recentCommands = updated
    }

    fun sendAttachment(bytes: ByteArray, fileName: String, contentType: String, caption: String? = null) {
        val session = _currentRoom.value ?: return
        viewModelScope.launch { transport.sendAttachment(session.id, bytes, fileName, contentType, caption) }
    }

    fun markRoomRead(roomId: String, eventId: String) {
        viewModelScope.launch { transport.markRead(roomId, eventId) }
    }

    suspend fun loadAvatar(mxc: String): ByteArray? {
        avatarBytesCache.get(mxc)?.let { return it }
        val deferred = avatarInFlight.getOrPut(mxc) {
            viewModelScope.async(Dispatchers.IO) {
                matrix?.avatarBytes(mxc)?.also { avatarBytesCache.put(mxc, it) }
            }.also { d -> d.invokeOnCompletion { avatarInFlight.remove(mxc, d) } }
        }
        return deferred.await()
    }

    // One-shot user-facing messages (e.g. avatar set result) — collected once at the app root.
    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts

    fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String) {
        viewModelScope.launch {
            matrix?.setRoomAvatar(roomId, bytes, contentType)
                ?.onSuccess { _toasts.tryEmit("Room photo updated") }
                ?.onFailure {
                    android.util.Log.w("KeryxAvatar", "set avatar failed for $roomId: ${it.message}", it)
                    val raw = it.message.orEmpty()
                    val msg = if ("403" in raw || raw.contains("forbidden", true) || raw.contains("M_FORBIDDEN")) {
                        "No permission to set this room's photo — you need moderator rights in it."
                    } else {
                        "Couldn't set photo: ${raw.take(140).ifBlank { "unknown error" }}"
                    }
                    _toasts.tryEmit(msg)
                }
        }
    }

    fun sendMessage(rawContent: String) {
        val session = _currentRoom.value ?: return
        val replyTo = _replyTarget.value
        // Bot Mode (2.8), two rules of the forever-chat. `/new` inside a bot's canonical
        // chat would fork the relationship into a scratch session — rerouted to /compact
        // (fresh working context, SAME conversation) and said so. And an @mention of
        // another bot rides with a note naming exactly who the tag means, so the agent can
        // hand off with its message_agent tool instead of guessing (desktop parity: the
        // user's words are never forwarded verbatim; the bot composes its own message).
        val inBotChat = bots.isCanonicalChat(session.id)
        val content = run {
            val rerouted = chat.keryx.core.model.BotRoster.reroute(rawContent, inBotChat)
            if (rerouted != null) {
                _toasts.tryEmit("This chat never resets — compacting instead. For a throwaway session, start a new one.")
                return@run rerouted
            }
            val roster = bots.roster.value.data
            if (inBotChat && roster != null && roster.messagingArmed && rawContent.contains('@')) {
                val mentioned = chat.keryx.core.model.BotRoster.mentions(rawContent, roster.bots)
                if (mentioned.isNotEmpty()) return@run rawContent + chat.keryx.core.model.BotRoster.mentionNote(mentioned)
            }
            rawContent
        }
        if (inBotChat) bots.touchOpenSession(session.id)
        _awaitingReply.value = true
        _workStartedAt.value = System.currentTimeMillis()
        _workLabel.value = "Working"
        answerLanded = false
        // Clear if the agent never responds at all; agent activity resets this to a shorter quiet timeout.
        scheduleClearAwaiting(NO_REPLY_MS)
        // Subscribe the side-channel BEFORE the command lands, so the gateway already sees a
        // live subscriber when it decides how to deliver this turn's tokens. Matrix-path only:
        // the direct transport IS its own stream — the WS delivers the turn into the timeline.
        if (transport.matrix != null) openSideChannel(session.id)
        // Optimistic echo: the bubble appears the moment Send is tapped instead of waiting for the
        // homeserver round-trip. Retired by the echo match in the messages collector; the timeout
        // is only a safety net (the real event still renders normally if matching ever misses).
        pendingSendClearJob?.cancel()
        // The optimistic echo shows what I typed, not the sense marker riding along with it (2.3 §4);
        // the committed event is stripped the same way, so the echo still matches it.
        _pendingSend.value = PendingSend(
            session.id,
            chat.keryx.app.senses.KeryxSenses.stripMarker(content),
            System.currentTimeMillis(),
        )
        pendingSendClearJob = viewModelScope.launch {
            delay(PENDING_SEND_TIMEOUT_MS)
            _pendingSend.value = null
        }
        viewModelScope.launch {
            if (replyTo != null) transport.sendReply(session.id, content, replyTo.id)
            else transport.sendMessage(session.id, content)
        }
        _replyTarget.value = null
        settingsRepository.setDraft(session.id, "")
        stopTypingBroadcast(session.id)
    }

    // --- Outgoing typing (m.typing) — so other clients see us composing ------------------------

    private var typingSentAt = 0L
    private var typingRoomId: String? = null

    /** Throttled m.typing broadcast driven by composer text changes. A refresh every ~4s keeps the
     *  30s server-side timeout alive while composing; clearing the composer stops it eagerly. */
    private fun broadcastTyping(text: String) {
        val room = _currentRoom.value?.id ?: return
        val now = System.currentTimeMillis()
        if (text.isBlank()) {
            stopTypingBroadcast(room)
        } else if (now - typingSentAt > 4_000L || typingRoomId != room) {
            typingSentAt = now
            typingRoomId = room
            viewModelScope.launch { transport.setTyping(room, true) }
        }
    }

    private fun stopTypingBroadcast(roomId: String) {
        if (typingRoomId == null) return
        typingSentAt = 0L
        typingRoomId = null
        viewModelScope.launch { transport.setTyping(roomId, false) }
    }

    // --- Room membership: invites, leaving ------------------------------------------------------
    // (_invites/invites live up top with _rooms — see the init-order note there.)

    fun acceptInvite(roomId: String) {
        viewModelScope.launch {
            matrix?.acceptInvite(roomId)
                ?.onSuccess { _toasts.tryEmit("Joined — the room appears on the next sync") }
                ?.onFailure { _toasts.tryEmit("Join failed: ${it.message?.take(80)}") }
        }
    }

    fun declineInvite(roomId: String) {
        viewModelScope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess { _toasts.tryEmit("Invite declined") }
                ?.onFailure { _toasts.tryEmit("Decline failed: ${it.message?.take(80)}") }
        }
    }

    // --- Starting conversations (NewChatSheet) --------------------------------------------------
    // Each [onDone] gets null on success (the sheet closes; the room opens via openRoomById as
    // soon as sync surfaces it) or the gateway/homeserver's own error text to show inline.

    fun startDirectMessage(userId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            matrix?.startDirectMessage(userId)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't start the chat") }
        }
    }

    fun createRoom(name: String, invitee: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            matrix?.createRoom(name, listOf(invitee))
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't create the room") }
        }
    }

    fun joinRoomByAddress(address: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            matrix?.joinRoomByAddress(address)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't join") }
        }
    }

    fun inviteUser(roomId: String, userId: String) {
        viewModelScope.launch {
            matrix?.inviteUser(roomId, userId)
                ?.onSuccess { _toasts.tryEmit("Invite sent") }
                ?.onFailure { _toasts.tryEmit("Invite failed: ${it.message?.take(80)}") }
        }
    }

    fun leaveRoom(roomId: String) {
        viewModelScope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess {
                    _toasts.tryEmit("Left room")
                    if (settingsRepository.lastRoomId == roomId) settingsRepository.lastRoomId = null
                    // Move off the dead room so the chat pane never points at a membership we lost.
                    if (_currentRoom.value?.id == roomId) {
                        val next = _rooms.value.firstOrNull { it.id != roomId }
                        if (next != null) selectRoom(next)
                        else setCurrentRoom(null)
                    }
                }
                ?.onFailure { _toasts.tryEmit("Leave failed: ${it.message?.take(80)}") }
        }
    }

    /** Delete (redact) a message. The event body vanishes for everyone on sync. */
    fun deleteMessage(sessionId: String, eventId: String) {
        viewModelScope.launch {
            matrix?.redactMessage(sessionId, eventId)
                ?.onFailure { _toasts.tryEmit("Delete failed: ${it.message?.take(80)}") }
        }
    }

    /** Message redaction is a Matrix power; the gateway keeps its transcript. */
    val canDeleteMessages: Boolean get() = matrix != null

    // --- Gateway sessions (direct door): the lifecycle the homeserver would otherwise own -------
    // The mirror of the membership block above — same shapes, gated on the other capability.

    /** Sessions the user asked to be TEMPORARY — normal rooms until the next cold start,
     *  which deletes them from the gateway (see [sweepTemporarySessions]). */
    private val _temporarySessionIds = MutableStateFlow(settingsRepository.temporarySessionIds)
    val temporarySessionIds: StateFlow<Set<String>> = _temporarySessionIds.asStateFlow()

    private fun markTemporary(sessionId: String) {
        settingsRepository.temporarySessionIds = settingsRepository.temporarySessionIds + sessionId
        _temporarySessionIds.value = settingsRepository.temporarySessionIds
    }

    private fun unmarkTemporary(sessionId: String) {
        settingsRepository.temporarySessionIds = settingsRepository.temporarySessionIds - sessionId
        _temporarySessionIds.value = settingsRepository.temporarySessionIds
    }

    /**
     * The temporary contract, kept at launch: every ledgered session is deleted from the
     * gateway. Cold-start-only on purpose — deleting the moment you switch rooms would eat a
     * conversation you meant to hop back to, and hooking app-background would kill the chat
     * you are IN the moment you check a text. An id whose delete fails (offline, gateway
     * down) stays ledgered and dies at the next launch instead; one already gone elsewhere
     * comes off the ledger regardless, or it would be retried forever.
     */
    private fun sweepTemporarySessions() {
        val doomed = settingsRepository.temporarySessionIds
        if (doomed.isEmpty()) return
        // Before the room restore runs: last night's temp must not greet you as today's room.
        if (settingsRepository.lastRoomId in doomed) settingsRepository.lastRoomId = null
        viewModelScope.launch {
            val gw = gateway ?: return@launch
            val remaining = doomed.filterNotTo(mutableSetOf()) { id ->
                gw.deleteSession(id).isSuccess
            }
            // Self-heal: whatever the roster no longer carries is gone however it went.
            val known = runCatching { _rooms.value.mapTo(HashSet()) { it.id } }.getOrNull()
            settingsRepository.temporarySessionIds =
                if (known.isNullOrEmpty()) remaining else remaining.intersect(known)
            _temporarySessionIds.value = settingsRepository.temporarySessionIds
        }
    }

    fun createSession(title: String, temporary: Boolean = false, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            gateway?.createSession(title.trim().ifBlank { null })
                ?.onSuccess { sessionId ->
                    if (temporary) markTemporary(sessionId)
                    openRoomById(sessionId); onDone(null)
                }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't create the session") }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            gateway?.renameSession(sessionId, title)
                ?.onFailure { _toasts.tryEmit("Rename failed: ${it.message?.take(80)}") }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            gateway?.deleteSession(sessionId)
                ?.onSuccess {
                    _toasts.tryEmit("Session deleted")
                    unmarkTemporary(sessionId) // a hand-deleted temp owes the sweep nothing
                    if (settingsRepository.lastRoomId == sessionId) settingsRepository.lastRoomId = null
                    // Move off the dead session so the chat pane never points at a transcript
                    // that's gone (same shape as leaveRoom's move-off).
                    if (_currentRoom.value?.id == sessionId) {
                        val next = _rooms.value.firstOrNull { it.id != sessionId }
                        if (next != null) selectRoom(next)
                        else setCurrentRoom(null)
                    }
                }
                ?.onFailure { _toasts.tryEmit("Delete failed: ${it.message?.take(80)}") }
        }
    }

    /** Server-side transcript search (drawer deep search). Empty off the direct door. */
    suspend fun searchGatewaySessions(query: String): List<chat.keryx.core.transport.SessionSearchHit> =
        gateway?.searchSessions(query)?.getOrNull() ?: emptyList()

    /** This process rides the direct gateway door (login screen adapts; Matrix chrome hides). */
    val transportIsDirect: Boolean get() = transport.matrix == null

    /** Login-screen prefill for the direct door (its own keys — never Hermes Link's). */
    val directGatewayUrl: String get() = settingsRepository.directGatewayUrl
    val directApiKey: String get() = settingsRepository.directApiKey

    /**
     * The login screen's second door (plan §5 Phase 4): a gateway URL and an API key instead
     * of a homeserver. Validates against the gateway, persists the choice, and reports
     * [needsRestart] when the process was booted on the other spine — the transport is not
     * hot-swappable under this ViewModel, so the door change takes effect on relaunch.
     */
    fun loginToGateway(
        url: String,
        apiKey: String,
        /** Fires the system browser at the gateway's sign-in page (gated gateways only). */
        launchBrowser: (String) -> Unit,
        onResult: (ok: Boolean, needsRestart: Boolean, message: String?) -> Unit,
    ) {
        viewModelScope.launch {
            val insecure = settingsRepository.allowInsecure
            val probe = chat.keryx.app.transport.direct.GatewayRest(url, apiKey, insecure)
            val st = probe.status().getOrElse {
                onResult(false, false, it.message?.take(160) ?: "Gateway unreachable")
                return@launch
            }
            if (!st.authRequired) {
                // Ungated gateway: the legacy token dialect, exactly as before.
                runCatching { if (apiKey.isNotBlank()) probe.validateToken().getOrThrow() }.fold(
                    onSuccess = {
                        settingsRepository.directAuthMode =
                            chat.keryx.app.transport.direct.DirectAuth.MODE_TOKEN
                        commitDirectDoor(url, apiKey, onResult)
                    },
                    onFailure = { onResult(false, false, it.message?.take(160) ?: "Gateway unreachable") },
                )
                return@launch
            }
            // GATED gateway (hermes ≥0.20.5, auth_required): the legacy token is rejected there
            // by upstream design — sign in the way Hermes Desktop does. RFC 8252: our PKCE pair,
            // the SYSTEM browser on the gateway's /login, a loopback listener catching ?code=,
            // then the code+verifier→token exchange. DirectAuth owns the whole dance.
            val auth = chat.keryx.app.transport.direct.DirectAuth(settingsRepository, insecure)
            val pending = auth.beginLogin(url)
            launchBrowser(pending.authorizeUrl)
            runCatching {
                auth.awaitLogin(url, pending)
                settingsRepository.directAuthMode =
                    chat.keryx.app.transport.direct.DirectAuth.MODE_NATIVE
                // Prove the minted pair actually authenticates before committing the door
                // (mirrors the token path's validateToken gate).
                chat.keryx.app.transport.direct.GatewayRest(url, "", insecure, auth)
                    .validateToken().getOrThrow()
            }.fold(
                onSuccess = { commitDirectDoor(url, null, onResult) },
                onFailure = { onResult(false, false, it.message?.take(160) ?: "Browser sign-in failed") },
            )
        }
    }

    private suspend fun commitDirectDoor(
        url: String,
        apiKey: String?,
        onResult: (Boolean, Boolean, String?) -> Unit,
    ) {
        // One synchronous commit: the caller relaunches the process on our answer,
        // and apply()'s async disk write loses that race (device-caught).
        settingsRepository.commitTransportDoor(url, apiKey, "direct", true)
        val direct = transport as? chat.keryx.app.transport.direct.DirectTransport
        if (direct != null) {
            direct.login()
            onResult(true, false, null)
        } else {
            onResult(true, true, null)
        }
    }

    /**
     * The transport TOGGLE: flip the spine for the next process life, keeping BOTH credential
     * sets — the Matrix session stays in Trixnity's store, the sealed direct token stays in
     * prefs. Not a logout; whichever door comes up resumes what it holds (or lands on its own
     * login pane if it holds nothing). The caller relaunches.
     */
    fun switchTransport(mode: String) {
        settingsRepository.commitTransportMode(mode)
    }

    /** The other door's stored state — the toggle's "resumes signed in" hints. */
    val matrixSessionOnFile: Boolean get() = settingsRepository.matrixSessionOnFile
    val directCredentialsOnFile: Boolean
        get() = settingsRepository.directLoggedIn && settingsRepository.directGatewayUrl.isNotBlank()

    /** Login-form prefill — the durable Matrix credential is the session, never the password. */
    val lastMatrixUsername: String get() = settingsRepository.lastMatrixUsername

    fun loginToMatrix(username: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = matrix?.login(username, password)
                ?: Result.failure(IllegalStateException("This transport has no Matrix login"))
            result.fold(
                onSuccess = {
                    settingsRepository.lastMatrixUsername = username
                    onResult(true, "Logged in")
                },
                onFailure = { onResult(false, it.message ?: "Login failed. Check URL or credentials.") },
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            settingsRepository.lastRoomId = null
            setCurrentRoom(null)
            transport.logout()
        }
    }

    fun setAllowInsecure(enabled: Boolean) {
        _allowInsecure.value = enabled
        settingsRepository.allowInsecure = enabled
    }

    fun setGatewayUrl(url: String) {
        _gatewayUrl.value = url
        settingsRepository.gatewayUrl = url
        _linkHealth.value = if (url.isBlank()) LinkHealth.OFF else LinkHealth.UNKNOWN
    }

    fun setGatewayApiKey(key: String) {
        _gatewayApiKey.value = key
        settingsRepository.gatewayApiKey = key
        if (_linkHealth.value != LinkHealth.OFF) _linkHealth.value = LinkHealth.UNKNOWN
    }

    fun setSideChannelEnabled(enabled: Boolean) {
        _sideChannelEnabled.value = enabled
        settingsRepository.sideChannelEnabled = enabled
        if (!enabled) {
            clearStream()
            _linkHealth.value = LinkHealth.OFF
        } else {
            _linkHealth.value = if (_gatewayUrl.value.isBlank()) LinkHealth.OFF else LinkHealth.UNKNOWN
        }
    }

    /** Settings "Test link": one-shot /health probe against the configured gateway, result toasted. */
    fun testGatewayLink() {
        val url = _gatewayUrl.value.trim()
        if (url.isBlank()) {
            _toasts.tryEmit("Set a Gateway URL first")
            return
        }
        viewModelScope.launch {
            chat.keryx.app.data.remote.HermesStreamClient(url, _gatewayApiKey.value, settingsRepository.allowInsecure)
                .health()
                .onSuccess {
                    _linkHealth.value = LinkHealth.OK
                    _toasts.tryEmit("Hermes Link OK — $it")
                }
                .onFailure {
                    _linkHealth.value = LinkHealth.UNREACHABLE
                    _toasts.tryEmit("Hermes Link failed: ${(it.message ?: "connection error").take(80)}")
                }
        }
    }

    fun setShowTelemetry(enabled: Boolean) {
        _showTelemetry.value = enabled
        settingsRepository.showTelemetry = enabled
    }

    /** Dynamic reasoning control: rides Hermes' native `/reasoning` command (per-session scope). */
    /** Re-probe the reasoning dial for the CURRENT room's brain, not the profile default.
     *  Direct door: the session's live route (the catalog's model + provider, once fetched)
     *  and its stored id, with the effective level read straight off the live agent. Matrix:
     *  the global default, as before — a room has no session id the gateway can look up. */
    /**
     * Levels this process has watched a model refuse. In memory on purpose: the walk-back itself
     * lands on the gateway, which remembers, so this only has to stop the SAME dead rung being
     * tried twice inside one run.
     */
    private val refusedLevels: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * A turn died because the brain refused the thinking level: step down one rung and say so.
     *
     * The scale is Hermes'; which rungs a brain can actually render is decided by its chat
     * template, and nothing knows that list until a real turn dies on it. So the level that
     * killed this turn is recorded and the next one down is set for this session — never
     * globally, and never all the way to off (see [ReasoningEffort.fallbackBelow]).
     *
     * One dead turn can announce itself on three channels (`message.complete` with an error, the
     * buffered lifecycle line, the `error` event). `refusedLevels.add` is the gate: the first
     * announcement to name a rung owns the walk-back, and the other two return — otherwise one
     * refusal would fall three rungs.
     */
    private suspend fun walkBackReasoning() {
        val d = direct ?: return
        val roomId = _currentRoom.value?.id ?: return
        val caps = hub.reasoningCaps.value
        val model = caps?.model.orEmpty().trim()
        // Authoritative: what the gateway says is in force right now, not what the pill last drew.
        val current = d.reasoningEffort(roomId).getOrNull()?.takeIf { it.isNotBlank() }
            ?: caps?.current.orEmpty()
        if (current.isBlank()) return
        if (model.isBlank()) {
            // Nothing to key the memory on — say the true thing rather than guess a rung.
            _toasts.tryEmit("This brain refused that thinking level — pick a lower one.")
            return
        }
        if (!refusedLevels.add(chat.keryx.core.model.ReasoningEffort.rejectionKey(model, current))) return

        val label = { level: String ->
            chat.keryx.core.model.ReasoningEffort.longLabel(level).ifBlank { level }
        }
        val next = chat.keryx.core.model.ReasoningEffort.fallbackBelow(model, current, refusedLevels)
        if (next == null) {
            _toasts.tryEmit("This brain refused ${label(current)} thinking, and there's nothing lower to try.")
            return
        }
        d.setReasoningEffort(roomId, next, global = false)
            .onSuccess {
                _toasts.tryEmit("This brain refused ${label(current)} thinking — dropped to ${label(next)}.")
                refreshReasoningCaps()
            }
            .onFailure {
                _toasts.tryEmit("This brain refused ${label(current)} thinking; couldn't drop to ${label(next)}.")
            }
    }

    fun refreshReasoningCaps() {
        val roomId = _currentRoom.value?.id
        val d = direct
        val catalog = if (d != null) models.catalog.value else null
        hub.refreshReasoningCaps(
            model = catalog?.model,
            provider = catalog?.provider,
            sessionId = if (d != null) roomId else null,
            liveCurrent = if (d != null && roomId != null) ({ d.reasoningEffort(roomId).getOrNull() }) else null,
        )
    }

    /** A pick from the reasoning menu: `<level>` (this session), `<level> --global` (every
     *  session), or `show` / `hide` / `reset`. The direct door sets a level through the
     *  gateway's own `config.set` — it lands on the live agent at once and echoes the
     *  authoritative value — where the slash text only reaches a worker copy. Either way the
     *  pill re-probes afterwards instead of waiting for the next tap. */
    fun sendReasoningCommand(arg: String) {
        recordCommandUse("/reasoning")
        val parts = arg.trim().split(Regex("\\s+"))
        val level = parts.firstOrNull().orEmpty()
        val global = parts.drop(1).any { it == "--global" }
        val d = direct
        val roomId = _currentRoom.value?.id
        val isLevel = level.isNotBlank() && level !in setOf("show", "hide", "reset", "status")
        if (d != null && roomId != null && isLevel) {
            viewModelScope.launch {
                d.setReasoningEffort(roomId, level, global)
                    .onSuccess { v ->
                        toast(if (global) "Reasoning → ${v.ifBlank { level }} for every session" else "Reasoning → ${v.ifBlank { level }} — this session")
                        refreshReasoningCaps()
                    }
                    .onFailure { toast("Reasoning refused: ${it.message?.take(80)}") }
            }
            return
        }
        sendMessage("/reasoning $arg".trim())
        viewModelScope.launch { delay(1200); refreshReasoningCaps() }
    }

    // --- Busy-turn inputs (the Talaria way: the send button IS the submit tree) ----------------
    // Text typed while a turn runs steers it; a long-press queues it for the next turn; an empty
    // composer stops it. The direct door speaks the gateway's RPC verbs. The Matrix door speaks
    // the gateway's own slash verbs, which every platform dispatches while busy (`/steer` and
    // `/queue` carry busy_policy=dispatch) — a plain message there would follow the operator's
    // busy_input_mode instead, which may be "interrupt".

    /** Whether this door can stop a running turn: `session.interrupt` exists on the direct
     *  door only — the Matrix gateway has no interrupt verb a room can send. */
    val canInterruptTurn: Boolean get() = direct != null

    /** Steer the running turn: the text reaches the model on its next step, no interrupt.
     *  Falls back to a queue when the agent declines (turn past its last tool batch, or a
     *  model that can't be steered). */
    fun steerTurn(text: String) {
        val session = _currentRoom.value ?: return
        val d = direct
        if (d == null) {
            recordCommandUse("/steer")
            sendMessage("/steer $text")
            return
        }
        viewModelScope.launch {
            d.steerTurn(session.id, text)
                .onSuccess { accepted ->
                    if (accepted) {
                        toast("Steered — the agent sees it on its next step")
                    } else {
                        d.queuePrompt(session.id, text)
                            .onSuccess { toast("Agent declined the steer — queued for the next turn") }
                            .onFailure { toast("Queue failed: ${it.message?.take(80)}") }
                    }
                }
                .onFailure { toast("Steer failed: ${it.message?.take(80)}") }
        }
    }

    /** Queue a message to run after the current turn finishes. */
    fun queueMessage(text: String) {
        val session = _currentRoom.value ?: return
        val d = direct
        if (d == null) {
            recordCommandUse("/queue")
            sendMessage("/queue $text")
            return
        }
        viewModelScope.launch {
            d.queuePrompt(session.id, text)
                .onSuccess { toast("Queued — sends when this turn finishes") }
                .onFailure { toast("Queue failed: ${it.message?.take(80)}") }
        }
    }

    /** Stop the running turn (direct door). */
    fun interruptTurn() {
        val session = _currentRoom.value ?: return
        val d = direct ?: return
        viewModelScope.launch {
            d.interruptTurn(session.id)
                .onSuccess { toast("Stopped") }
                .onFailure { toast("Stop failed: ${it.message?.take(80)}") }
        }
    }

}
