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
    private val archiveIndexer: chat.keryx.app.data.archive.ArchiveIndexer? = null,
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

    private val _pinnedRoomIds = MutableStateFlow(settingsRepository.pinnedRoomIds)
    val pinnedRoomIds: StateFlow<Set<String>> = _pinnedRoomIds.asStateFlow()

    fun togglePin(roomId: String) {
        val updated = _pinnedRoomIds.value.toMutableSet()
        if (!updated.add(roomId)) updated.remove(roomId)
        _pinnedRoomIds.value = updated
        settingsRepository.pinnedRoomIds = updated
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

    /** The last turn's context occupancy, from the side-channel's finish-line usage frame. */
    data class ContextUsage(val roomId: String, val used: Long, val max: Long, val model: String)
    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)
    val contextUsage: StateFlow<ContextUsage?> = _contextUsage.asStateFlow()

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
    val pet = PetDelegate(deps)
    val missions = MissionsDelegate(deps) { _rooms.value }
    val console = ConsoleDelegate(deps)
    val voice = VoiceDelegate(deps)
    val archive = ArchiveDelegate(deps, transport, archiveStore, archiveIndexer) { _currentRoom.value?.id }

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
                    if (room != null) _currentRoom.value = room
                }
            }
        }
        viewModelScope.launch {
            matrix?.getInvites()?.collectLatest { _invites.value = it }
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
        hub.refreshReasoningCaps()
    }

    private fun workStateMessage(messages: List<Message>, latest: Message): Message {
        // Judge the work state from the real answer, not from a runtime footer or a blank
        // placeholder that landed after it. HERMES-only: the blank-agent-id blindness this filter
        // once hedged against is now solved structurally in senderTypeOf (legacy agent-room
        // fallback), and "not mine" would let a HUMAN's group-room message relabel the banner.
        if (!MessageParser.isRuntimeFooterMessage(latest.content) && latest.content.isNotBlank()) return latest
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
    var callTurnTap: CallTurnTap? = null


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
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Stop -> {
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
                        } else {
                            // Mid-stream drop with visible partial text: keep it, show the alert,
                            // and recover the moment the final event syncs via Matrix.
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
    fun openRoomById(roomId: String) {
        val room = _rooms.value.firstOrNull { it.id == roomId }
        if (room != null) selectRoom(room)
        else pendingOpenRoomId = roomId
    }

    fun selectRoom(room: RoomProfile) {
        _currentRoom.value = room
        limitDecayJob?.cancel()
        limitDecayJob = null
        _timelineLimit.value = INITIAL_LIMIT
        _hasMoreHistory.value = true
        _isLoadingMore.value = false
        _replyTarget.value = null
        quietJob?.cancel()
        _awaitingReply.value = false
        _workStartedAt.value = null
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

    fun sendMessage(content: String) {
        val session = _currentRoom.value ?: return
        val replyTo = _replyTarget.value
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
                        else _currentRoom.value = null
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

    fun createSession(title: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            gateway?.createSession(title.trim().ifBlank { null })
                ?.onSuccess { sessionId -> openRoomById(sessionId); onDone(null) }
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
                    if (settingsRepository.lastRoomId == sessionId) settingsRepository.lastRoomId = null
                    // Move off the dead session so the chat pane never points at a transcript
                    // that's gone (same shape as leaveRoom's move-off).
                    if (_currentRoom.value?.id == sessionId) {
                        val next = _rooms.value.firstOrNull { it.id != sessionId }
                        if (next != null) selectRoom(next)
                        else _currentRoom.value = null
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
    fun loginToGateway(url: String, apiKey: String, onResult: (ok: Boolean, needsRestart: Boolean, message: String?) -> Unit) {
        viewModelScope.launch {
            val probe = chat.keryx.app.transport.direct.GatewayRest(
                url, apiKey, settingsRepository.allowInsecure,
            )
            val status = probe.status()
            val validated = status.mapCatching { st ->
                if (st.authRequired || apiKey.isNotBlank()) probe.validateToken().getOrThrow()
            }
            validated.fold(
                onSuccess = {
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
                },
                onFailure = { onResult(false, false, it.message?.take(160) ?: "Gateway unreachable") },
            )
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
            _currentRoom.value = null
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
    fun sendReasoningCommand(arg: String) {
        recordCommandUse("/reasoning")
        sendMessage("/reasoning $arg".trim())
    }

}
