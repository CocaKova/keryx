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
import kotlinx.coroutines.flow.flowOn
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
    // The fleet (2.11): what to do with a removed gateway's files on disk (KeryxApp owns the
    // paths). No-op in plain-JVM tests.
    private val purgeGatewayFiles: (String) -> Unit = {},
) : ViewModel() {

    // The two capability surfaces, if this transport has them. Affordances only one side offers
    // (Matrix: invites, avatars, membership, redaction; gateway: session lifecycle, deep search)
    // no-op quietly on a transport without them; the UI gates their chrome on the same
    // nullability.
    private val matrix get() = transport.matrix
    private val gateway get() = transport.gateway

    companion object {
        /** Test link, when the Link answers but the API server behind it doesn't. */
        const val LINK_UP_API_DOWN =
            "Hermes Link is up, but Hermes's API server behind it isn't running, so Missions and Runs " +
                "won't load. Set API_SERVER_ENABLED=true and an API_SERVER_KEY in ~/.hermes/.env, " +
                "then restart the gateway."

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
        // After the gateway's own end-of-turn, how long the message walk refuses to relight the
        // banner off the fold that ends the same turn.
        private const val TURN_END_GRACE_MS = 2_000L
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

        // How long to hold the overlay waiting for the final Matrix event after `stop` — sync is
        // normally sub-second; past this the commit clearly isn't coming as-streamed.
        private const val STREAM_SYNC_GRACE_MS = 20_000L
        // Optimistic send bubble safety timeout: if the echo never matches (edited en route,
        // network hiccup), stop double-rendering after this long — the real event wins.
        private const val PENDING_SEND_TIMEOUT_MS = 12_000L
        // How long to keep partial text + the alert after a mid-stream drop before giving the
        // timeline back to plain Matrix rendering.
        private const val STREAM_INTERRUPT_HOLD_MS = 60_000L

        /** True when a timeline event [echo] is the homeserver copy of the optimistic [sent] text.
         *  Reply sends come back wrapped (quote-fallback prefix), so an exact match OR the echo
         *  ending with the sent text both count. */
        /**
         * What Retry re-sends: the newest prompt, and only when it is plain text. An image or a
         * file cannot be re-sent from its transcript row, and skipping past it to an older
         * text prompt re-asked a question the undone exchange never contained.
         */
        fun retryPromptOf(msgs: List<Message>): String? =
            msgs.lastOrNull { it.sender == SenderType.ME }
                ?.takeIf { it.mediaKind == null }
                ?.content?.takeIf { it.isNotBlank() }

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
                    // Flattened as a voice reads it (3.0): a preview line is no place for the
                    // `**` and `- ` the bubble renders away ("**Good morning.** - **Weath…").
                    val prose = TtsText.speakable(m.content)
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

    /** A room's transcript, named by the room it was read for. */
    data class RoomTimeline(val roomId: String?, val messages: List<Message>) {
        /** The list, if it is [room]'s; null while [room]'s first page is still on its way. */
        fun of(room: String?): List<Message>? = messages.takeIf { roomId == room }
    }

    /**
     * The open room's transcript, with the room it belongs to (3.0). The list follows the room
     * a beat late (a dispatch, or a first page still on the wire), and in that beat it is the
     * room being left: the chat draws a list only under its own room's title. Read bare, it
     * mixed two chats on every switch over a slow link (10-16, "ghost chats").
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val timeline: StateFlow<RoomTimeline> =
        combine(_currentRoom.filterNotNull(), _timelineLimit) { room, limit -> room.id to limit }
            .flatMapLatest { (sessionId, limit) ->
                transport.getMessages(sessionId, limit).map { RoomTimeline(sessionId, it) }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, RoomTimeline(null, emptyList()))

    /** The transcript alone, as it stands — a beat behind a room switch (see [timeline]). */
    val messages: StateFlow<List<Message>> =
        timeline.map { it.messages }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
        // Off the main thread, and only while the Hub shows it: Lazily kept this scan running
        // on every streaming tick for the rest of the session once the Hub had been opened.
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    private val _allowInsecure = MutableStateFlow(settingsRepository.allowInsecure)
    val allowInsecure: StateFlow<Boolean> = _allowInsecure.asStateFlow()

    /** The words this door uses for its rows ("room" / "session") — chosen once, from the
     *  transport, and threaded to every surface that says the noun. */
    val lexicon: chat.keryx.core.model.DoorLexicon =
        chat.keryx.core.model.DoorLexicon.forDoor(direct = transport.matrix == null)

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
        when {
            // The demo's gateway is on the phone: always there.
            transport is chat.keryx.app.transport.demo.DemoTransport -> LinkHealth.OK
            settingsRepository.sideChannelEnabled && settingsRepository.gatewayUrl.isNotBlank() -> LinkHealth.UNKNOWN
            else -> LinkHealth.OFF
        }
    )
    val linkHealth: StateFlow<LinkHealth> = _linkHealth.asStateFlow()

    /**
     * The last turn's context occupancy — the composer's ring. Two feeds, one gauge: the Matrix
     * side-channel's finish-line `usage` frame, and on the direct door the gateway's own
     * `usage` that rides `session.info` and `message.complete` (folded into the transport's
     * [chat.keryx.core.model.SessionMeta], which until 2.8.2 nothing read — the ring simply
     * never lit on a gateway session). The open room's direct reading wins when there is one.
     */
    data class ContextUsage(
        val roomId: String, val used: Long, val max: Long, val model: String,
        /** The auto-compaction trigger when the gateway names it (2.13.11), else 0. */
        val compactAt: Long = 0L,
    )
    private val _contextUsage = MutableStateFlow<ContextUsage?>(null)
    val contextUsage: StateFlow<ContextUsage?> =
        combine(
            _contextUsage,
            _currentRoom.flatMapLatest { r ->
                val d = transport as? chat.keryx.core.transport.SessionMetaSource
                if (r == null || d == null) flowOf(null)
                else d.sessionMeta(r.id).map { m ->
                    m.contextGauge?.let { (used, max) ->
                        ContextUsage(r.id, used, max, m.model, m.compactionTrigger ?: 0L)
                    }
                }
            },
        ) { sideChannel, direct -> direct ?: sideChannel }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** The open session's prefix-cache hit rate (2.16, `usage.cache_hit_pct`); null = unknown. */
    val cacheHitPct: StateFlow<Int?> =
        _currentRoom.flatMapLatest { r ->
            val d = transport as? chat.keryx.core.transport.SessionMetaSource
            if (r == null || d == null) flowOf(null) else d.sessionMeta(r.id).map { it.cacheHitPct }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Bumped when a compaction's length is recorded, so [compactionTypicalSeconds] re-reads. */
    private val _compactionSamples = MutableStateFlow(0)

    /** How long the open room's brain has usually taken to compact, on this phone (2.13.11). */
    val compactionTypicalSeconds: StateFlow<Int?> =
        combine(contextUsage.map { it?.model.orEmpty() }.distinctUntilChanged(), _compactionSamples) { model, _ ->
            model.takeIf { it.isNotBlank() }
                ?.let { chat.keryx.core.model.CompactionTimings.typical(settingsRepository.compactionSeconds(it)) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** The drawer's folded shelves (RosterGroup names) — see [chat.keryx.core.model.RosterGroups]. */
    private val _collapsedRosterGroups = MutableStateFlow(settingsRepository.collapsedRosterGroups)
    val collapsedRosterGroups: StateFlow<Set<String>> = _collapsedRosterGroups.asStateFlow()

    private val _resumeLastRoom = MutableStateFlow(settingsRepository.resumeLastRoom)
    val resumeLastRoom: StateFlow<Boolean> = _resumeLastRoom.asStateFlow()
    fun setResumeLastRoom(on: Boolean) {
        _resumeLastRoom.value = on
        settingsRepository.resumeLastRoom = on
    }

    /** A settings row to land on the next time Settings opens (the drawer palette's doing).
     *  Consumed on arrival, so a later plain open lands on the hub as ever. */
    private val _settingsJump = MutableStateFlow<String?>(null)
    val settingsJump: StateFlow<String?> = _settingsJump.asStateFlow()
    fun jumpToSetting(id: String) { _settingsJump.value = id }
    fun consumeSettingsJump() { _settingsJump.value = null }

    fun toggleRosterGroup(name: String) {
        val next = if (name in _collapsedRosterGroups.value) _collapsedRosterGroups.value - name
        else _collapsedRosterGroups.value + name
        _collapsedRosterGroups.value = next
        settingsRepository.collapsedRosterGroups = next
    }

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
        notice = { _notices.tryEmit(it) },
    )
    // --- Direct-transport instruments (the harvest, plan §5). Null/quiet on Matrix. ---
    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport

    /** The agent's own instruments: the gateway door's events, or the demo's script (3.0). */
    private val instruments get() = transport as? chat.keryx.core.transport.AgentInstruments

    /** The agent is stopped on a tool approval in the open room (direct path and the demo). */
    val pendingApproval: StateFlow<chat.keryx.core.model.ApprovalRequest?> =
        _currentRoom.flatMapLatest { r ->
            val d = instruments
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
        timeCompaction(nowCompacting)
        // Only while a turn is actually being waited on: scheduleClearAwaiting can never
        // raise the banner, only settle it, so arming it with nothing awaiting would park a
        // polling job for the length of the hold to do nothing at the end of it.
        if ((nowCompacting || wasCompacting) && _awaitingReply.value) scheduleClearAwaiting(NO_REPLY_MS)
    }

    // The banner's first frame for the compaction in progress, and the room it was in. Not
    // [compactingSince]: every heartbeat line re-arms that, and a sample measured from the last
    // heartbeat would say a forty-second compaction took five.
    private var compactionStartedAt: Long? = null
    private var compactionRoomId: String? = null

    /** Records how long the banner was up, per brain, when a compaction ends in the room it began in. */
    private fun timeCompaction(nowCompacting: Boolean) {
        val room = _currentRoom.value?.id
        if (nowCompacting) {
            if (compactionStartedAt == null || compactionRoomId != room) {
                compactionStartedAt = System.currentTimeMillis()
                compactionRoomId = room
            }
            return
        }
        val started = compactionStartedAt ?: return
        compactionStartedAt = null
        // A room switch mid-compaction is not the compaction ending; nor is a rotation, which
        // is the compaction's own handoff and keeps the room — [followRotation] carries it.
        if (compactionRoomId != room) return
        val model = contextUsage.value?.model.orEmpty()
        val seconds = ((System.currentTimeMillis() - started) / 1000L).toInt()
        settingsRepository.recordCompactionSeconds(model, seconds)
        _compactionSamples.value++
    }

    /**
     * The open room's session was compacted into a continuation (2.13.11). The transport has
     * already moved the live runtime, the busy mark and the stream over; the room follows, or it
     * keeps reading the parent — a transcript that stopped at the compaction — while the turn
     * streams into the tip. Keeps the open room's wait as it is: this is the same conversation.
     */
    private fun followRotation(old: String, new: String) {
        val cur = _currentRoom.value ?: return
        if (cur.id != old) return
        val next = _rooms.value.firstOrNull { it.id == new } ?: cur.copy(id = new)
        if (compactionRoomId == old) compactionRoomId = new
        setCurrentRoom(next)
        settingsRepository.lastRoomId = new
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
            val d = instruments
            if (r == null || d == null) flowOf(null) else d.todoPlan(r.id)
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun respondApproval(choice: String) {
        val room = _currentRoom.value ?: return
        viewModelScope.launch {
            instruments?.respondApproval(room.id, choice)
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

    /** Theme, accents, bubbles, motion, lock, E2EE, telemetry, Matrix fields (3.0 split). */
    val prefs = PreferencesDelegate(settingsRepository)
    val hub = HubDelegate(deps)
    /** Create and edit scheduled jobs (2.16) — the Runs page and the Hub's Jobs spoke share it. */
    val runsEditor = RunsEditorDelegate(deps, hub) { direct?.restClient }
    /** The "/" palette, recent commands, drafts, the typing notice (3.0 split). */
    val composer = ComposerDelegate(deps, transport, currentRoom)
    /** Steer, queue, stop, redirect, branch, asides, standing orders, the crew (3.0 split). */
    val turns = TurnControlDelegate(
        deps, transport, currentRoom,
        sendMessage = { sendMessage(it) },
        recordCommandUse = { composer.recordCommandUse(it) },
        openSessionById = { id, title -> openSessionById(id, title) },
    )
    /** The fleet and both sign-in doors (3.0 split). */
    val doors = FleetDelegate(deps, transport, purgeGatewayFiles, onLinkCommitted = { reloadLink() })
    val models = ModelDelegate(
        deps, transport, { _currentRoom.value?.id },
        sendRoomCommand = { sendMessage(it) },
        hub = hub,
        recordCommandUse = { composer.recordCommandUse(it) },
        readRecents = { settingsRepository.recentModels },
        writeRecents = { settingsRepository.recentModels = it },
    )
    /** The session list's verbs: pins, temporary, sticky model, archive, delete, paging (3.0 split). */
    val sessions = SessionsDelegate(
        deps, transport, rooms, models,
        openRoomById = { openRoomById(it) },
        moveOffRoom = { moveOffRoom(it) },
    )
    /** Matrix invites answered, rooms started/joined/left, redaction (3.0 split). */
    val matrixRooms = MatrixRoomsDelegate(
        deps, transport,
        openRoomById = { openRoomById(it) },
        moveOffRoom = { moveOffRoom(it) },
    )
    val projects = ProjectsDelegate(
        deps, transport,
        openSession = { id, title -> openSessionById(id, title) },
        onSessionCreated = { sessions.applyStickyModel(it) },
    )
    val shipyard = ShipyardDelegate(deps)
    val pet = PetDelegate(deps)
    /** Message media, avatars, drawer previews, reaction flows (3.0 split). */
    val media = MediaDelegate(deps, transport, currentRoom, rooms) { pet.trimThumbs() }
    val missions = MissionsDelegate(deps) { _rooms.value }

    /** Edit a mission after it was made (2.16): words, priority, owner. */
    val missionEdit = MissionEditDelegate(deps, missions) { direct?.restClient }
    val console = ConsoleDelegate(deps)
    val voice = VoiceDelegate(deps)
    val archive = ArchiveDelegate(
        deps, transport, archiveStore, archiveIndexer,
        currentRoomId = { _currentRoom.value?.id },
        rosterIds = { _rooms.value.map { it.id } },
    )
    /** Bot Mode (2.8): the roster of profiles and the door into each one's forever-chat. */
    val bots = BotsDelegate(deps, transport, hub) { id, title -> openSessionById(id, title) }
    /** Group chats (2.18): the gateway's hosted rooms of 2–6 bots. Direct door only. */
    /** Empty-chat starters: the switches and the AI-written prompts by agent (3.0 split). */
    val starters = StartersDelegate(deps, bots)
    val groups = GroupsDelegate(deps, transport, isForeground = { isAppForeground?.invoke() != false })
    /** The Hub's Memory spoke (2.16) — dashboard routes, so the direct door's REST client. */
    val memory = MemoryDelegate(deps) { direct?.restClient }
    /** Export and share out (2.16): one session as a file, from the drawer's long-press menu. */
    val exports = SessionExportDelegate({ direct?.restClient }, { direct?.profileForSession(it) })
    /** Hermes update from the phone (2.16): the plugin's /keryx/update and the dashboard's routes. */
    val hermesUpdate = HermesUpdateDelegate(deps) { direct?.restClient }

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

    /** A notice with one action — "Archived · Undo". [onAction] runs only if it is tapped. */
    fun toast(message: String, actionLabel: String, onAction: () -> Unit) {
        _notices.tryEmit(KeryxNotice(message, actionLabel, onAction))
    }

    /** For the Call's log line: the direct door's link state, or "matrix". */
    fun linkState(): String = direct?.connectionState?.value?.let { it::class.simpleName ?: "?" } ?: "matrix"
    fun currentRoomId(): String? = _currentRoom.value?.id

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
    /** Whether this turn's side-channel has delivered anything beyond its own open/keepalive —
     *  see [StreamHandoff.answeredWithoutStreaming]. */
    private var streamCarriedTurn = false

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
    /** Work rows of turns the gateway itself ended (`message.complete`): a stopped turn's fold
     *  reads mid-run forever, and every later walk that lands on it must still say "over". */
    private val endedTurnWorkIds = HashSet<String>()
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

    /** Find in chat is open (2.16): the top bar's search glyph opens it, Done closes it. */
    private val _findOpen = MutableStateFlow(false)
    val findOpen: StateFlow<Boolean> = _findOpen.asStateFlow()
    fun setFindOpen(open: Boolean) { _findOpen.value = open }

    /** A place asked for from outside (2.16 `keryx://missions` etc.): a route the host walks to
     *  and then clears. A flag, not an event, so it waits for the host to compose. */
    private val _spaceRequest = MutableStateFlow<String?>(null)
    val spaceRequest: StateFlow<String?> = _spaceRequest.asStateFlow()
    fun requestSpace(route: String) { _spaceRequest.value = route }
    fun consumeSpaceRequest() { _spaceRequest.value = null }

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

    /** The working banner's label and quiet window from the agent's latest message; see
     *  [chat.keryx.core.model.WorkState.read]. */
    private fun updateWorkStateFrom(last: Message): Long {
        val r = chat.keryx.core.model.WorkState.read(last)
        _workLabel.value = r.label
        return if (r.settled) QUIET_SHORT_MS else QUIET_LONG_MS
    }

    // A room the user asked to open (e.g. by tapping a notification) before the room list loaded.
    private var pendingOpenRoomId: String? = null

    init {
        // Before anything restores a room: last launch's temporary sessions die first, and the
        // synchronous lastRoomId clear inside keeps a dead temp from being this launch's room.
        sessions.sweepTemporarySessions()
        // What the last process wanted said after its relaunch (a pairing that didn't take).
        settingsRepository.pendingNotice.takeIf { it.isNotBlank() }?.let { notice ->
            settingsRepository.pendingNotice = ""
            // The toast lane has no replay: give the screen time to start listening.
            viewModelScope.launch { kotlinx.coroutines.delay(1_500); toast(notice) }
        }
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
                // Restore the last open conversation once rooms are available — unless the
                // user asked every launch to start fresh (Settings → Agent).
                if (_currentRoom.value == null && settingsRepository.resumeLastRoom) {
                    val lastId = settingsRepository.lastRoomId
                    // A conversation compacted since it was last open continues under its tip.
                    val wanted = lastId?.let { direct?.forwarded(it) ?: it }
                    val room = roomList.firstOrNull { it.id == wanted }
                    if (room != null) setCurrentRoom(room)
                    else if (wanted != null && roomList.isNotEmpty()) reopenUnlisted(wanted)
                }
            }
        }
        // Each room is its own session with its own brain: the model catalog and the
        // reasoning dial both belong to the room you left, so drop one and re-probe the other.
        viewModelScope.launch {
            _currentRoom.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id == null) return@collect
                models.enterRoom(id)
                models.refreshReasoningCaps()
            }
        }
        // A gateway that restarted forgets nothing we can't re-ask for, but the composer's model
        // and reasoning pills hide while their caps are unknown, and nothing re-probed them on
        // a reconnect: a restart left the pills gone until the app was reopened (2.17.1).
        // The status dot on the direct door is the gateway link itself (2.19.1): it only ever
        // moved for the Matrix side-channel, so here it sat grey on "Not tested yet" for good.
        direct?.let { d ->
            viewModelScope.launch {
                d.linkState().collect { s ->
                    _linkHealth.value = when (s) {
                        chat.keryx.core.model.LinkState.CONNECTED -> LinkHealth.OK
                        chat.keryx.core.model.LinkState.CONNECTING -> LinkHealth.UNKNOWN
                        else -> LinkHealth.UNREACHABLE
                    }
                }
            }
        }
        // linkState, not one client's state: it outlives a rebuilt client (the old loop stayed on
        // the dead one's flow), and the Matrix door has no direct link to poll at all.
        direct?.let { d ->
            viewModelScope.launch {
                var wasReady = true
                d.linkState().collect { s ->
                    val ready = s == chat.keryx.core.model.LinkState.CONNECTED
                    if (ready && !wasReady) {
                        models.clear(); models.refreshReasoningCaps()
                        // Panels that failed while the link was down showed their error until
                        // reopened (QA 2.19.1): the link is back, so ask again.
                        bots.refresh(force = true)
                        missions.refreshKanbanQuietly()
                    }
                    wasReady = ready
                }
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
        // Compaction continues a session under a new id. The transport has announced it since
        // the direct door was written (efa7908) and nothing listened, so the open room kept the
        // dead parent (2.13.11).
        direct?.let { d ->
            viewModelScope.launch {
                d.sessionRotations().collect { (old, new) -> followRotation(old, new) }
            }
        }
        // A thinking level the MODEL won't render, walked back instead of died over. The
        // transport has republished these refusals from three places since the day the channel
        // was written — "so the level that killed the turn can be walked back instead of killing
        // the next one too" — and nothing collected them, so the turn just quietly failed, and
        // the next one, and the next.
        direct?.let { d ->
            viewModelScope.launch {
                d.reasoningRejections().collect { models.walkBackReasoning() }
            }
        }
        // The gateway's `message.complete` is the turn's end, whatever the last row looks
        // like. A stopped turn folds as a thought with no answer under it, and the message
        // walk reads that shape as mid-run — more is coming — so the banner and the
        // thinking disclosure hung for the whole long quiet window after every Stop
        // (device, 2026-09-23; "a long-running bug"). The end is authoritative: settle on it,
        // for the room on screen. Any door with instruments says it (3.0): the demo's last
        // word does too, or its answers sat under a "Working" row through the quiet window.
        instruments?.let { ins ->
            viewModelScope.launch {
                ins.turnEnds().collect { id -> if (id == _currentRoom.value?.id) settleTurnNow() }
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
                // The direct door just said this turn ended (`message.complete` → settleTurnNow):
                // its fold is a finished turn, however mid-run its last row reads. A stopped turn
                // folds as a thought, or a trailing tool call, with no answer under it; read as
                // mid-run, this sign kept the composer on "steer" for every message after a Stop
                // (device, 2026-09-27). The banner had settled on the End since 09-23, but the
                // composer counts every live-turn sign, and this one was never stood down — and a
                // later telemetry row (the post-turn review) re-walks to the same folded message,
                // so the turn's work row is remembered as ended, not just the 2 s after.
                val justEnded = System.currentTimeMillis() - turnEndedAt < TURN_END_GRACE_MS
                if (justEnded && window == QUIET_LONG_MS) endedTurnWorkIds.add(stateMessage.id)
                // Opening a room (launch, switch) whose last row is an old tool call or bare thought
                // is an ENDED turn, not a live one — a stopped session re-read as "steer" on every
                // relaunch. Same recency rule the banner's openedMidRun uses; a run that really is
                // in flight from elsewhere still shows through the typing signal and the overlay.
                val recentWork = System.currentTimeMillis() - stateMessage.timestamp < WORKING_RECENT_MS
                val midRun = window == QUIET_LONG_MS && stateMessage.id !in endedTurnWorkIds &&
                    (!firstEval || recentWork)
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

                // `justEnded` (above): the direct door's own fold is not a run that started elsewhere.
                when {
                    _awaitingReply.value -> scheduleClearAwaiting(window)
                    midRun && (liveActivity || openedMidRun) && !justEnded -> {
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
        models.refreshReasoningCaps()
    }

    private fun workStateMessage(messages: List<Message>, latest: Message): Message =
        chat.keryx.core.model.WorkState.subject(messages, latest)

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
        streamCarriedTurn = false
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
            // The character rate (EMA of the per-frame rate; core StreamRateMeter). Answer and
            // reasoning both count: the model generated both.
            val meter = chat.keryx.core.transport.StreamRateMeter()
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
                    charsPerSec = meter.cps,
                    lastCharsAt = meter.lastAtMs,
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
                if (ev !is chat.keryx.app.data.remote.HermesStreamClient.Event.Opened) streamCarriedTurn = true
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
                        meter.onChars(ev.text.length, now)
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
                        meter.onChars(ev.text.length, now)
                        if (now - lastDispatch >= STREAM_DISPATCH_MS || charsSinceDispatch >= STREAM_DISPATCH_CHARS) {
                            dispatch(LiveStreamStatus.STREAMING)
                        }
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Tool -> {
                        // Rare next to token deltas (a handful per turn), so it never waits for
                        // the throttle — a tool starting is exactly the beat you want on screen.
                        theater = chat.keryx.core.model.Theater.reduce(theater, ev.event, System.currentTimeMillis())
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

    /** When the direct door's turn ended, by the gateway's own word (`message.complete`). */
    @Volatile private var turnEndedAt = 0L

    /**
     * The gateway said the turn is over: settle at once, no window. A scheduled clear could be
     * cancelled by the fold that lands in the same instant (the message walk reads a thought
     * with no answer as mid-run and re-arms the long window — the post-Stop hang), so this
     * clears directly and stamps the moment, and the walk's "run started elsewhere" relight
     * stands down for [TURN_END_GRACE_MS] after it.
     */
    private fun settleTurnNow() {
        turnEndedAt = System.currentTimeMillis()
        answerLanded = true
        quietJob?.cancel()
        _awaitingReply.value = false
        _workStartedAt.value = null
        // Every live-turn sign stands down, not just the banner's: the composer reads this one
        // too, and a stopped turn's fold would otherwise keep it lit (steer-after-Stop, 09-27).
        _liveTurnSigns.value = false
        workStateId?.let { settledWorkIds.add(it); endedTurnWorkIds.add(it) }
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
                } else if (StreamHandoff.answeredWithoutStreaming(
                        carried = streamCarriedTurn,
                        isNewAgentMessage = isNewMsg && last.sender == SenderType.HERMES,
                        body = last.content,
                    )
                ) {
                    clearStream(); settleTurn()
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

    /**
     * Cold start, and the last open conversation is not on the list's first page — a scheduled
     * run you were reading, a bot's side session, an old chat (2.19). It used to land you on an
     * empty screen. Asked once per process: the gateway confirms the session still exists (one
     * small row), then it is adopted and opened like a project's session.
     */
    private var reopenTried = false
    private fun reopenUnlisted(sessionId: String) {
        val d = direct ?: return
        if (reopenTried) return
        reopenTried = true
        viewModelScope.launch {
            if (!d.sessionExists(sessionId)) return@launch
            if (_currentRoom.value != null) return@launch // you picked something meanwhile
            openSessionById(sessionId, settingsRepository.lastRoomTitle.orEmpty())
        }
    }

    fun openRoomById(requestedId: String) {
        // A notification for a session that has since compacted opens its continuation.
        val roomId = direct?.forwarded(requestedId) ?: requestedId
        val room = _rooms.value.firstOrNull { it.id == roomId }
        if (room != null) selectRoom(room)
        else pendingOpenRoomId = roomId
    }

    /**
     * Tap-In asked for from outside the chat (2.12) — the run notice in the shade. A flag,
     * not an event: the chat screen may not be composed yet when the tap lands, and the flag
     * waits for it. The screen clears it the moment it opens the door.
     */
    private val _tapInRequested = MutableStateFlow(false)
    val tapInRequested: StateFlow<Boolean> = _tapInRequested.asStateFlow()

    fun requestTapIn(roomId: String) {
        // Re-selecting the open room would reset the wait it is in the middle of showing.
        if (_currentRoom.value?.id != roomId) openRoomById(roomId)
        _tapInRequested.value = true
    }

    fun consumeTapIn() { _tapInRequested.value = false }

    /** The guided tour asked for from a place that can't run it (3.0): Settings' "Take the
     *  tour". A flag like Tap-In's: the app root starts the tour and clears it. */
    private val _tourRequested = MutableStateFlow<chat.keryx.core.tour.TourPreset?>(null)
    val tourRequested: StateFlow<chat.keryx.core.tour.TourPreset?> = _tourRequested.asStateFlow()
    fun requestTour(preset: chat.keryx.core.tour.TourPreset) { _tourRequested.value = preset }
    fun consumeTour() { _tourRequested.value = null }

    /** The agent pointing at the phone (`keryx_tour`, keryx-stream 0.6): each call as it starts.
     *  The app root runs it on the tour on screen and answers with [answerAgentTour]. The direct
     *  door only: Matrix never sees the call, and the demo has no agent. */
    val agentTourCalls: kotlinx.coroutines.flow.Flow<chat.keryx.core.tour.AgentTourCall>
        get() = direct?.tourCalls ?: kotlinx.coroutines.flow.emptyFlow()

    /** Still waiting on the phone: a call replayed after it finished is not shown again. */
    fun agentTourCallOpen(call: chat.keryx.core.tour.AgentTourCall): Boolean = direct?.tourCallOpen(call.callId) == true

    /** What the tour did with [call], back to the tool waiting on Hermes Link. No Link, no
     *  answer: the tool's own deadline tells the agent the phone didn't respond. */
    fun answerAgentTour(call: chat.keryx.core.tour.AgentTourCall, result: kotlinx.serialization.json.JsonObject) {
        viewModelScope.launch {
            val client = deps.client() ?: return@launch
            client.tourRespond(call.sessionId, call.callId, result)
                .onFailure { e -> android.util.Log.w("KeryxTour", "answer for ${call.callId} failed: ${e.message}") }
        }
    }

    /** What Tap-In reads (2.16, now a nav layer): the chat's reconciled render items and the
     *  side-channel's record of the newest run, as the chat screen last drew them. */
    data class TapInFeed(
        val items: List<chat.keryx.app.presentation.ui.components.ChatRenderItem> = emptyList(),
        val structured: List<chat.keryx.core.model.ToolCall> = emptyList(),
    )
    private val _tapInFeed = MutableStateFlow(TapInFeed())
    val tapInFeed: StateFlow<TapInFeed> = _tapInFeed.asStateFlow()
    fun publishTapInFeed(
        items: List<chat.keryx.app.presentation.ui.components.ChatRenderItem>,
        structured: List<chat.keryx.core.model.ToolCall>,
    ) { _tapInFeed.value = TapInFeed(items, structured) }

    /** The working cloud's last bounds in the window (2.16): Tap-In grows out of them. A plain
     *  field, not state — it is read inside a draw layer, and must not recompose anything. */
    @Volatile var cloudBounds: androidx.compose.ui.geometry.Rect? = null

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
        // The timeline observer re-derives this from the new room's messages — but a fresh
        // session HAS no messages, so the observer never runs and the old room's "mid-run"
        // stood until an ended room was visited (2.11 walk: new session read "steer").
        _liveTurnSigns.value = false
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
        media.onRoomSwitched()
        settingsRepository.lastRoomId = room.id
        settingsRepository.lastRoomTitle = room.name
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

    // --- The Call (2.9.4): owned here, not by the screen ---------------------------------------
    // A rotation recreates the Activity and every `remember` in it; a call that lived in the
    // screen's composition hung up on every turn of the phone. The screen attaches to this one.
    private var call: chat.keryx.app.audio.CallController? = null

    fun callController(context: android.content.Context): chat.keryx.app.audio.CallController =
        call ?: chat.keryx.app.audio.CallController(context.applicationContext, this).also { call = it }

    fun endCall() {
        call?.end()
        call = null
    }

    override fun onCleared() {
        call?.end()
        call = null
        media.dispose()
        super.onCleared()
    }

    fun sendAttachment(bytes: ByteArray, fileName: String, contentType: String, caption: String? = null) {
        val session = _currentRoom.value ?: return
        viewModelScope.launch {
            runCatching { transport.sendAttachment(session.id, bytes, fileName, contentType, caption) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) toast("Couldn't send: ${it.message?.take(80)}") }
        }
    }

    /**
     * Several attachments as one message (2.16). The direct door stages every one and submits a
     * single prompt, so three photos are one turn, not three; the Matrix door sends them in
     * order with the caption on the first.
     */
    fun sendAttachments(files: List<OutgoingFile>, caption: String?) {
        val session = _currentRoom.value ?: return
        if (files.isEmpty()) return
        val d = direct
        viewModelScope.launch {
            runCatching {
                if (d != null) d.sendAttachments(session.id, files, caption)
                else files.forEachIndexed { i, f ->
                    transport.sendAttachment(session.id, f.bytes, f.name, f.contentType, caption.takeIf { i == 0 })
                }
            }.onFailure { toast("Couldn't send: ${it.message?.take(80)}") }
        }
    }

    fun markRoomRead(roomId: String, eventId: String) {
        viewModelScope.launch { transport.markRead(roomId, eventId) }
    }

    /** The bot whose Bot Chat just got a bare `/new` — the floor asks what that meant. */
    private val _botNewChoice = MutableStateFlow<chat.keryx.core.model.BotProfile?>(null)
    val botNewChoice: StateFlow<chat.keryx.core.model.BotProfile?> = _botNewChoice.asStateFlow()

    /** Answer the `/new` question: [fresh] = retire this Bot Chat and start a new one; false =
     *  compact it (same conversation, clean working context); null = dismissed, nothing sent. */
    fun resolveBotNew(fresh: Boolean?) {
        val bot = _botNewChoice.value ?: return
        _botNewChoice.value = null
        when (fresh) {
            null -> Unit
            true -> bots.startFresh(bot)
            false -> sendMessage("/compact")
        }
    }

    // One-shot user-facing messages (e.g. avatar set result) — collected once at the app root.
    /** Refresh the "/" palette for the open chat: the gateway's own catalog (skills included,
     *  for this chat's profile) plus keryx-stream's list. */
    fun refreshCommands() {
        val room = _currentRoom.value?.id
        val d = direct
        hub.refreshGatewayCommands(scopeKey = room, catalog = d?.let { dt -> { dt.commandCatalog(room) } })
    }

    /** The voice call screen is up. Survives a rotation, never a process death. */
    val callShown = androidx.compose.runtime.mutableStateOf(false)

    /** A send that failed hands its words back: (room id, text) for the composer to refill. */
    private val _unsent = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val unsent: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> = _unsent
    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts

    // The same lane for a notice that can be acted on (2.16) — an Undo above all. Plain strings
    // keep going through [toasts]; both land on the one themed notice line (KeryxSnack).
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<KeryxNotice>(extraBufferCapacity = 8)
    val notices: kotlinx.coroutines.flow.SharedFlow<KeryxNotice> = _notices
    fun notice(notice: KeryxNotice) { _notices.tryEmit(notice) }

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
        // `/new` here is a question, not a reset (2.17.3): compact this chat, or start a
        // separate session with the same bot. Nothing is sent until you pick.
        if (chat.keryx.core.model.BotRoster.reroute(rawContent, inBotChat) != null) {
            _botNewChoice.value = bots.botForSession(session.id)
            return
        }
        // `/branch` sent as text met Hermes' command worker, which has no copy of this chat.
        chat.keryx.core.model.BranchCommand.parse(rawContent)?.let { name ->
            val d = direct
            if (d != null) {
                composer.recordCommandUse("/branch")
                settingsRepository.setDraft(session.id, "")
                viewModelScope.launch {
                    d.branchWhole(session.id, name)
                        .onSuccess { (stored, title) ->
                            openSessionById(stored, title)
                            toast("Branched — a copy of this whole conversation")
                        }
                        .onFailure { toast("Branch failed: ${it.message?.take(80)}") }
                }
                return
            }
        }
        val content = run {
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
            try {
                if (replyTo != null) transport.sendReply(session.id, content, replyTo.id)
                else transport.sendMessage(session.id, content)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Link down, the session busy on another client, a refused command: say so and
                // put the words back, instead of a crash or a bubble that never answers.
                pendingSendClearJob?.cancel()
                _pendingSend.value = null
                _awaitingReply.value = false
                settingsRepository.setDraft(session.id, rawContent)
                _unsent.tryEmit(session.id to rawContent)
                toast("Not sent. ${chat.keryx.core.model.FriendlyError.of(e.message)}")
            }
        }
        _replyTarget.value = null
        settingsRepository.setDraft(session.id, "")
        composer.stopTypingBroadcast(session.id)
    }

    /** If [roomId] is the open room, open the next one (or none): it was left or deleted. */
    private fun moveOffRoom(roomId: String) {
        if (_currentRoom.value?.id != roomId) return
        val next = _rooms.value.firstOrNull { it.id != roomId }
        if (next != null) selectRoom(next) else setCurrentRoom(null)
    }

    /** Send the last thing you said again — the failure card's Retry. */
    fun retryLastTurn() {
        val last = messages.value.lastOrNull { it.sender == SenderType.ME && it.content.isNotBlank() } ?: return
        sendMessage(last.content)
    }

    /** The open session's window, itemised (direct door; null elsewhere). */
    suspend fun contextBreakdown(): Result<chat.keryx.core.model.ContextBreakdown>? {
        val room = _currentRoom.value ?: return null
        return direct?.contextBreakdown(room.id)
    }

    /**
     * The open room's live generation rate (2.16 honest tok/s): the direct door's meter, or the
     * side-channel's character rate read with the ratio the direct door calibrated (0 = never,
     * and the label uses the default ratio). Null between turns.
     */
    val liveRate: StateFlow<chat.keryx.core.model.LiveRate?> =
        combine(
            _liveStream,
            _currentRoom.flatMapLatest { r ->
                val d = transport as? chat.keryx.app.transport.direct.DirectTransport
                if (r == null || d == null) flowOf(null) else d.liveRate(r.id)
            },
        ) { side, direct ->
            direct ?: side?.takeIf {
                it.roomId == _currentRoom.value?.id && it.status == LiveStreamStatus.STREAMING && it.charsPerSec > 0f
            }?.let { chat.keryx.core.model.LiveRate(it.charsPerSec, it.lastCharsAt, settingsRepository.charsPerToken) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settled real tok/s of finished turns (direct door), keyed by message id and answer text. */
    val turnRates: StateFlow<Map<String, Double>> =
        (transport as? chat.keryx.app.transport.direct.DirectTransport)?.turnRates
            ?: MutableStateFlow(emptyMap())

    /** The open session's run numbers (2.16) for the same sheet: cache hit, speed, compactions,
     *  totals — live while the sheet collects it. Direct door only; a lone null elsewhere. */
    fun sessionNumbers(): kotlinx.coroutines.flow.Flow<chat.keryx.core.model.SessionMeta?> {
        val room = _currentRoom.value ?: return flowOf(null)
        return direct?.sessionMeta(room.id) ?: flowOf(null)
    }

    /** Take back the last exchange in the open session (direct door). */
    fun undoLastTurn() {
        val room = _currentRoom.value ?: return
        val d = direct ?: return
        viewModelScope.launch {
            d.undoLastTurn(room.id)
                .onSuccess { n -> _toasts.tryEmit(if (n > 0) "Took back the last exchange" else "Nothing to take back") }
                .onFailure { e ->
                    val why = e.message.orEmpty()
                    _toasts.tryEmit(
                        if ("busy" in why) "Still working — stop the turn first" else "Couldn't undo: ${why.take(80)}",
                    )
                }
        }
    }

    /**
     * The long-press bar's Retry (2.11.9) — the Desktop's /retry: take the last exchange back,
     * then say the same thing again. The prompt is captured BEFORE the fold, because the undo
     * drops exactly that message; re-reading after it would land on the one before. Same gate
     * as the undo at the call site (direct door, newest reply, idle).
     */
    fun retryExchange() {
        val room = _currentRoom.value ?: return
        val d = direct ?: return
        val prompt = retryPromptOf(messages.value) ?: return
        viewModelScope.launch {
            d.undoLastTurn(room.id)
                .onSuccess { removed ->
                    // Nothing taken back means the exchange is still there: re-sending would
                    // ask the same thing twice.
                    if (removed > 0) sendMessage(prompt)
                    else _toasts.tryEmit("Nothing to retry — the gateway had no turn to take back")
                }
                .onFailure { e ->
                    val why = e.message.orEmpty()
                    _toasts.tryEmit(
                        if ("busy" in why) "Still working — stop the turn first" else "Couldn't retry: ${why.take(80)}",
                    )
                }
        }
    }

    /**
     * Edit & resend (2.16): [message] back in the composer to change and send again. With
     * [takeBack] (direct door, your newest message, idle) the exchange it started is taken back
     * first, so the resend replaces it rather than following it; anywhere else it is a copy to
     * edit, and the chat it came from stays as it was.
     */
    fun editAndResend(message: Message, takeBack: Boolean) {
        val text = chat.keryx.app.senses.KeryxSenses.stripMarker(message.content).trim()
        val room = _currentRoom.value
        val d = direct
        if (!takeBack || room == null || d == null) {
            prefillComposer(text)
            return
        }
        viewModelScope.launch {
            d.undoLastTurn(room.id)
                .onSuccess { removed ->
                    prefillComposer(text)
                    if (removed > 0) toast("Taken back — edit it and send again")
                }
                .onFailure {
                    prefillComposer(text)
                    toast("Couldn't take it back — your words are in the composer to send again")
                }
        }
    }

    /** This process rides the direct gateway door (login screen adapts; Matrix chrome hides). */
    val transportIsDirect: Boolean get() = transport.matrix == null

    /** This process is the demo (3.0 herald): no gateway yet, a scripted one on the phone. */
    val isDemo: Boolean get() = transport is chat.keryx.app.transport.demo.DemoTransport

    /** Login-screen prefill for the direct door (its own keys — never Hermes Link's). */
    val directGatewayUrl: String get() = settingsRepository.directGatewayUrl
    val directApiKey: String get() = settingsRepository.directApiKey

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

    /** The Link's keys changed under this process (a pairing that claimed without a relaunch,
     *  3.0): read them back, or every Link client went on with the pair this started with. */
    private fun reloadLink() {
        _gatewayUrl.value = settingsRepository.gatewayUrl
        _gatewayApiKey.value = settingsRepository.gatewayApiKey
        _sideChannelEnabled.value = settingsRepository.sideChannelEnabled
        _linkHealth.value = if (_gatewayUrl.value.isBlank()) LinkHealth.OFF else LinkHealth.UNKNOWN
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
            val client = chat.keryx.app.data.remote.HermesStreamClient(url, _gatewayApiKey.value, settingsRepository.allowInsecure)
            client.health()
                .onSuccess {
                    _linkHealth.value = LinkHealth.OK
                    _toasts.tryEmit("Hermes Link OK — $it")
                }
                .onFailure { failed ->
                    _linkHealth.value = LinkHealth.UNREACHABLE
                    // A relay error with the Link itself up is the API server behind it: off on a
                    // fresh Hermes, where this said a bare "HTTP 502" (stock-Hermes pair, 10-16).
                    val relay = failed.message?.let { Regex("HTTP 50[234]").matches(it) } == true
                    val link = if (relay) client.linkHealth().getOrNull() else null
                    _toasts.tryEmit(
                        if (link != null) LINK_UP_API_DOWN
                        else "Hermes Link failed: ${(failed.message ?: "connection error").take(80)}",
                    )
                }
        }
    }


}
