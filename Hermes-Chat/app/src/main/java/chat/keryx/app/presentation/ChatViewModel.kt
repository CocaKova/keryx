package chat.keryx.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.MessageReaction
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.domain.model.Session
import chat.keryx.app.domain.repository.ChatRepository
import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.app.presentation.ui.components.MessageParser
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class ChatViewModel(
    private val repository: ChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val INITIAL_LIMIT = 50
        private const val PAGE = 50
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

        // --- Side-channel stream tuning ---
        // UI dispatch throttle for the live token buffer: flush to state when either trips.
        // ~10 dispatches/s keeps recomposition (and the markdown re-parse) far off the frame budget
        // even at high token rates, while still reading as a live stream.
        private const val STREAM_DISPATCH_MS = 100L
        private const val STREAM_DISPATCH_CHARS = 240
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
                m.mediaKind == chat.keryx.app.domain.model.MediaKind.IMAGE -> "🖼 Photo"
                m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "Attachment" }}"
                MessageParser.isTelemetryMessage(m.content) -> "⏳ status check-in"
                else -> {
                    val prose = StreamHandoff.normalize(m.content)
                    if (prose.isNotBlank()) prose
                    else {
                        val tools = MessageParser.parse(m.content)
                            .filterIsInstance<MessageParser.Segment.Tools>()
                            .flatMap { it.calls }
                        if (tools.isNotEmpty()) "🛠 ${tools.last().name}" else "💭 thinking…"
                    }
                }
            }
            return (who + body).take(140)
        }
    }

    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedIn()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentUserId: StateFlow<String?> = repository.currentUserId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _rooms = MutableStateFlow<List<RoomProfile>>(emptyList())
    val rooms: StateFlow<List<RoomProfile>> = _rooms.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    // How many timeline events to load for the open room; grows as the user scrolls into history.
    private val _timelineLimit = MutableStateFlow(INITIAL_LIMIT)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<Message>> =
        combine(_currentSession.filterNotNull(), _timelineLimit) { session, limit -> session.id to limit }
            .flatMapLatest { (sessionId, limit) -> repository.getMessages(sessionId, limit) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    /** Active brain's reasoning dial (null until fetched; refreshed when the menu opens). */
    private val _reasoningCaps = MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?>(null)
    val reasoningCaps: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?> = _reasoningCaps.asStateFlow()

    /** Fetch what the active brain supports for /reasoning, so the menu can adapt (binary vs
     *  effort scale, current level, model name). Silent on failure — the menu falls back to the
     *  generic effort list. */
    fun refreshReasoningCaps() {
        val url = _gatewayUrl.value.trim()
        if (!_sideChannelEnabled.value || url.isBlank()) return
        viewModelScope.launch {
            chat.keryx.app.data.remote.HermesStreamClient(url, _gatewayApiKey.value, settingsRepository.allowInsecure)
                .capabilities()
                .onSuccess { _reasoningCaps.value = it }
        }
    }

    /** The gateway's live slash-command registry (empty until fetched; palette falls back to
     *  its preset list). Refreshed at most once a minute, when the "/" palette opens. */
    private val _gatewayCommands =
        MutableStateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>>(emptyList())
    val gatewayCommands: StateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>> =
        _gatewayCommands.asStateFlow()
    private var gatewayCommandsFetchedAt = 0L

    fun refreshGatewayCommands() {
        val url = _gatewayUrl.value.trim()
        if (!_sideChannelEnabled.value || url.isBlank()) return
        val now = System.currentTimeMillis()
        if (_gatewayCommands.value.isNotEmpty() && now - gatewayCommandsFetchedAt < 60_000L) return
        gatewayCommandsFetchedAt = now
        viewModelScope.launch {
            chat.keryx.app.data.remote.HermesStreamClient(url, _gatewayApiKey.value, settingsRepository.allowInsecure)
                .commands()
                .onSuccess { if (it.isNotEmpty()) _gatewayCommands.value = it }
        }
    }

    private val _showTelemetry = MutableStateFlow(settingsRepository.showTelemetry)
    val showTelemetry: StateFlow<Boolean> = _showTelemetry.asStateFlow()

    // --- Missions (kanban board over the gateway) ----------------------------------------------

    /** A configured gateway client, or null when Hermes Link is off/unconfigured. */
    private fun gatewayClient(): chat.keryx.app.data.remote.HermesStreamClient? {
        val url = _gatewayUrl.value.trim()
        if (!_sideChannelEnabled.value || url.isBlank()) return null
        return chat.keryx.app.data.remote.HermesStreamClient(
            url, _gatewayApiKey.value, settingsRepository.allowInsecure,
            snapshotStore = settingsRepository::putHubSnapshot,
        )
    }

    private val _kanbanBoard =
        MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.KanbanBoard?>(null)
    val kanbanBoard: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.KanbanBoard?> =
        _kanbanBoard.asStateFlow()
    private val _kanbanRefreshing = MutableStateFlow(false)
    val kanbanRefreshing: StateFlow<Boolean> = _kanbanRefreshing.asStateFlow()
    private val _kanbanError = MutableStateFlow<String?>(null)
    val kanbanError: StateFlow<String?> = _kanbanError.asStateFlow()

    fun refreshKanban() {
        val client = gatewayClient() ?: run {
            _kanbanError.value = "Hermes Link is off — enable it in Settings"
            return
        }
        _kanbanRefreshing.value = true
        viewModelScope.launch {
            client.kanbanBoard()
                .onSuccess { _kanbanBoard.value = it; _kanbanError.value = null }
                .onFailure { _kanbanError.value = it.message?.take(120) ?: "board unavailable" }
            _kanbanRefreshing.value = false
        }
    }

    suspend fun kanbanTaskDetail(taskId: String): Result<chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail> =
        gatewayClient()?.kanbanTask(taskId)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    /** Create a mission and refresh the board; toasts the outcome either way. */
    fun kanbanCreate(title: String, assignee: String, body: String, triage: Boolean) {
        val client = gatewayClient() ?: return
        viewModelScope.launch {
            client.kanbanCreate(title, assignee, body, triage)
                .onSuccess { _toasts.tryEmit("Mission created${if (triage) " (triage)" else ""}"); refreshKanban() }
                .onFailure { _toasts.tryEmit("Create failed: ${it.message?.take(80)}") }
        }
    }

    fun kanbanComment(taskId: String, body: String, onDone: () -> Unit = {}) {
        val client = gatewayClient() ?: return
        viewModelScope.launch {
            client.kanbanComment(taskId, body)
                .onSuccess { onDone() }
                .onFailure { _toasts.tryEmit("Comment failed: ${it.message?.take(80)}") }
        }
    }

    // --- Agent Hub — gateway console panels ------------------------------------------------------

    /** One hub panel's fetch state. A failed refresh keeps the last good [data] so the panel
     *  degrades to a stale-but-visible snapshot with the error line on top, never a blank. */
    data class HubPanel<T>(
        val data: T? = null,
        val error: String? = null,
        val refreshing: Boolean = false,
    )

    private fun <T> MutableStateFlow<HubPanel<T>>.refreshFrom(
        fetch: suspend chat.keryx.app.data.remote.HermesStreamClient.() -> Result<T>,
    ) {
        val client = gatewayClient() ?: run {
            value = value.copy(error = "Hermes Link is off — enable it in Settings", refreshing = false)
            return
        }
        value = value.copy(refreshing = true)
        viewModelScope.launch {
            client.fetch()
                .onSuccess { value = HubPanel(data = it) }
                .onFailure {
                    value = value.copy(error = it.message?.take(120) ?: "unavailable", refreshing = false)
                }
        }
    }

    /** A panel seeded from the offline cache: the last gateway answer renders instantly (even
     *  cold-start offline), then the first real refresh replaces it. Parse failures = empty seed. */
    private fun <T> seededPanel(
        path: String,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): MutableStateFlow<HubPanel<T>> = MutableStateFlow(
        HubPanel(
            data = settingsRepository.hubSnapshot(path)?.let { cached ->
                runCatching {
                    parse(kotlinx.serialization.json.Json.parseToJsonElement(cached).jsonObject)
                }.getOrNull()
            },
        ),
    )

    private val _hubHealth = seededPanel("/health/detailed", chat.keryx.app.data.remote.HubJson::health)
    val hubHealth: StateFlow<HubPanel<chat.keryx.app.data.remote.HermesStreamClient.HubHealth>> =
        _hubHealth.asStateFlow()
    private val _hubJobs = seededPanel("/api/jobs", chat.keryx.app.data.remote.HubJson::jobs)
    val hubJobs: StateFlow<HubPanel<List<chat.keryx.app.data.remote.HermesStreamClient.HubJob>>> =
        _hubJobs.asStateFlow()
    private val _hubSessions = seededPanel("/api/sessions", chat.keryx.app.data.remote.HubJson::sessions)
    val hubSessions: StateFlow<HubPanel<List<chat.keryx.app.data.remote.HermesStreamClient.HubSession>>> =
        _hubSessions.asStateFlow()
    private val _hubSkills = seededPanel("/v1/skills", chat.keryx.app.data.remote.HubJson::skills)
    val hubSkills: StateFlow<HubPanel<List<chat.keryx.app.data.remote.HermesStreamClient.HubSkill>>> =
        _hubSkills.asStateFlow()
    private val _hubToolsets = seededPanel("/v1/toolsets", chat.keryx.app.data.remote.HubJson::toolsets)
    val hubToolsets: StateFlow<HubPanel<List<chat.keryx.app.data.remote.HermesStreamClient.HubToolset>>> =
        _hubToolsets.asStateFlow()

    fun refreshHubHealth() = _hubHealth.refreshFrom { healthDetailed() }
    fun refreshHubJobs() = _hubJobs.refreshFrom { jobs() }
    fun refreshHubSessions() = _hubSessions.refreshFrom { sessions() }
    fun refreshHubSkills() = _hubSkills.refreshFrom { skills() }
    fun refreshHubToolsets() = _hubToolsets.refreshFrom { toolsets() }

    /** Pause/resume/run a scheduled job, then re-pull the list so the card reflects reality. */
    fun hubJobAction(jobId: String, action: String) {
        val client = gatewayClient() ?: return
        viewModelScope.launch {
            client.jobAction(jobId, action)
                .onSuccess { refreshHubJobs() }
                .onFailure { _toasts.tryEmit("Job $action failed: ${it.message?.take(80)}") }
        }
    }

    fun hubJobDelete(jobId: String) {
        val client = gatewayClient() ?: return
        viewModelScope.launch {
            client.jobDelete(jobId)
                .onSuccess { _toasts.tryEmit("Job deleted"); refreshHubJobs() }
                .onFailure { _toasts.tryEmit("Delete failed: ${it.message?.take(80)}") }
        }
    }

    fun hubJobCreate(name: String, schedule: String, prompt: String, deliver: String) {
        val client = gatewayClient() ?: return
        viewModelScope.launch {
            client.jobCreate(name, schedule, prompt, deliver)
                .onSuccess { _toasts.tryEmit("Job scheduled"); refreshHubJobs() }
                .onFailure { _toasts.tryEmit("Schedule failed: ${it.message?.take(80)}") }
        }
    }

    suspend fun hubSessionMessages(
        sessionId: String,
    ): Result<List<chat.keryx.app.data.remote.HermesStreamClient.HubMessage>> =
        gatewayClient()?.sessionMessages(sessionId)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    // --- Mission alerts -------------------------------------------------------------------------

    private val _missionAlertsEnabled = MutableStateFlow(settingsRepository.missionAlertsEnabled)
    val missionAlertsEnabled: StateFlow<Boolean> = _missionAlertsEnabled.asStateFlow()

    /** Persist the toggle; the caller schedules/cancels the actual worker (it needs a Context).
     *  Enabling resets the event cursor so the first check baselines quietly instead of dumping
     *  every historical completion as a notification. */
    fun setMissionAlertsEnabled(enabled: Boolean) {
        settingsRepository.missionAlertsEnabled = enabled
        if (enabled) settingsRepository.missionEventsCursor = -1L
        _missionAlertsEnabled.value = enabled
    }

    /** The transient live response overlay (null = nothing streaming over the side-channel). */
    private val _liveStream = MutableStateFlow<LiveStream?>(null)
    val liveStream: StateFlow<LiveStream?> = _liveStream.asStateFlow()
    private var streamJob: Job? = null
    private var streamClearJob: Job? = null

    // Set by openSideChannel for the lifetime of one SSE turn. Hermes commits each text segment
    // of a multi-segment turn (text → tool → text) as its OWN Matrix message mid-run; when one of
    // those lands, the overlay must shed the already-committed part and KEEP STREAMING — killing
    // the whole SSE job there is what made every post-tool reasoning phase invisible until commit.
    private var consumeStreamedSegment: (() -> Unit)? = null

    // True while the agent is working (drives the waiting animation). Stays up through the agent's
    // thinking / tool-call / streaming phases and only clears after activity goes quiet.
    private val _awaitingReply = MutableStateFlow(false)
    val awaitingReply: StateFlow<Boolean> = _awaitingReply.asStateFlow()
    private var quietJob: Job? = null

    // Tracks the last message we evaluated, so we can tell genuine live activity (a new message, or a
    // streamed m.replace edit growing the current one) from merely re-observing the same timeline.
    private var lastSeenId: String? = null
    private var lastSeenLen: Int = -1
    private var lastSeenRoomId: String? = null

    // True while Hermes' typing indicator is up. Authoritative: a pending quiet-timeout will NOT
    // clear the banner while this is set, so a long silent tool call (curl) can't make it vanish.
    @Volatile private var agentTyping = false
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

    private fun scheduleClearAwaiting(delayMs: Long, force: Boolean = false) {
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            delay(delayMs)
            // Typing owns the lifecycle — except when the caller KNOWS the turn ended (the
            // streamed answer's committed copy just handed off), where lingering typing EDUs
            // must not keep the cloud up.
            if (!force && agentTyping) return@launch
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
        val tools = segs.filterIsInstance<MessageParser.Segment.Tools>().flatMap { it.calls }
        val hasReasoning = segs.any { it is MessageParser.Segment.Thinking }
        val isTelemetry = MessageParser.isTelemetryMessage(last.content)
        val hasAnswer = !isTelemetry &&
            segs.any { it is MessageParser.Segment.Text && (it as MessageParser.Segment.Text).text.isNotBlank() }
        _workLabel.value = when {
            // Telemetry first: a "⏳ Working…" heartbeat parses as a tool-shaped line too, and
            // "Running Working" is not a label.
            isTelemetry -> "Working"
            tools.isNotEmpty() -> "Running ${tools.last().name}"
            hasReasoning && !hasAnswer -> "Reasoning"
            else -> "Working"
        }
        return if (tools.isNotEmpty() || !hasAnswer) QUIET_LONG_MS else QUIET_SHORT_MS
    }

    // A room the user asked to open (e.g. by tapping a notification) before the room list loaded.
    private var pendingOpenRoomId: String? = null

    init {
        viewModelScope.launch {
            repository.getRooms().collectLatest { roomList ->
                _rooms.value = roomList
                // A pending notification-tap target takes priority once its room is available.
                val pending = pendingOpenRoomId
                if (pending != null) {
                    val room = roomList.firstOrNull { it.id == pending }
                    if (room != null) {
                        pendingOpenRoomId = null
                        selectSession(Session(room.id, room.id, room.name, 0L))
                        return@collectLatest
                    }
                }
                // Restore the last open conversation once rooms are available.
                if (_currentSession.value == null) {
                    val lastId = settingsRepository.lastRoomId
                    val room = roomList.firstOrNull { it.id == lastId }
                    if (room != null) _currentSession.value = Session(room.id, room.id, room.name, 0L)
                }
            }
        }
        viewModelScope.launch {
            messages.collect { msgs ->
                val last = msgs.lastOrNull()
                android.util.Log.i(
                    "KeryxFlow",
                    "emission n=${msgs.size} last=${last?.id?.take(12)} sender=${last?.sender} len=${last?.content?.length} " +
                        "stream=${_liveStream.value?.status} awaiting=${_awaitingReply.value}"
                )
                // An empty emission (room switch reset, transient flow restart) must NOT touch the
                // classification state: nulling lastSeenId made the NEXT emission look like a first
                // open, and the openedMidRun recency check re-lit the working banner for a message
                // that had already settled.
                if (last == null) return@collect
                // Classify this emission relative to the last one we saw. Crossing into another
                // room gets first-open semantics (recency-guarded openedMidRun), NOT "new message"
                // — an old conversation that merely ended on a tool call must not light the banner.
                val roomChanged = lastSeenRoomId != last.sessionId
                val firstEval = lastSeenId == null || roomChanged
                val isNewMsg = !firstEval && last.id != lastSeenId
                val grew = !firstEval && last.id == lastSeenId && last.content.length > lastSeenLen
                lastSeenRoomId = last.sessionId
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
                    if (pending != null && last.sessionId == pending.roomId &&
                        pendingEchoMatches(last.content, pending.text)
                    ) clearPendingSend()
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
                // Live activity = a brand-new message or a streamed edit growing the current one.
                val liveActivity = isNewMsg || grew
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
        // placeholder that landed after it. Sender filter is "not mine" (rather than HERMES) so a
        // misclassified agent id can never blind this again.
        if (!MessageParser.isRuntimeFooterMessage(latest.content) && latest.content.isNotBlank()) return latest
        return messages.asReversed()
            .asSequence()
            .dropWhile { it.id == latest.id }
            .firstOrNull {
                it.sessionId == latest.sessionId &&
                    it.sender != SenderType.ME &&
                    it.content.isNotBlank() &&
                    !MessageParser.isTelemetryMessage(it.content)
            }
            ?: latest
    }

    /** Drive the working banner from Hermes' Matrix typing indicator — the authoritative "busy"
     *  signal. It stays true (Hermes refreshes it) through long single tool calls that emit nothing,
     *  fixing the "banner vanished while it was still working on a curl" case. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeTyping() {
        viewModelScope.launch {
            _currentSession
                .flatMapLatest { s -> if (s == null) flowOf(false) else repository.othersTyping(s.id) }
                .collect { typing ->
                    android.util.Log.i("KeryxTyping", "typing=$typing awaiting=${_awaitingReply.value} answerLanded=$answerLanded")
                    agentTyping = typing
                    if (typing) {
                        quietJob?.cancel() // never time out while it's actively typing/working
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
            val buf = StringBuilder()
            val reasoningBuf = StringBuilder()
            var lastDispatch = 0L
            var charsSinceDispatch = 0
            var firstDeltaAt = 0L
            var lastDeltaAt = 0L
            // EMA of the instantaneous delta rate (chars/s); see TPS_* constants.
            var emaCps = 0f
            fun dispatch(status: LiveStreamStatus, finalText: String? = null) {
                val cur = _liveStream.value ?: LiveStream(roomId, "", status, System.currentTimeMillis())
                _liveStream.value = cur.copy(
                    text = MessageParser.sanitizeStreamingTail(buf.toString()),
                    status = status,
                    finalText = finalText ?: cur.finalText,
                    charsPerSec = emaCps,
                    reasoning = reasoningBuf.toString(),
                )
                lastDispatch = System.currentTimeMillis()
                charsSinceDispatch = 0
            }
            _liveStream.value = LiveStream(roomId, "", LiveStreamStatus.CONNECTING, System.currentTimeMillis())
            // Mid-turn segment commit: everything streamed so far is now a committed Matrix
            // message. Shed it from the overlay (text AND its reasoning — the commit carries its
            // own folded 💭 block) but leave the SSE subscription running for the next phase.
            consumeStreamedSegment = {
                android.util.Log.i("KeryxHandoff", "consume segment (${buf.length}ch text, ${reasoningBuf.length}ch reasoning) — stream stays live")
                buf.setLength(0)
                reasoningBuf.setLength(0)
                if (_liveStream.value != null) dispatch(LiveStreamStatus.STREAMING)
            }
            client.stream(roomId).collect { ev ->
                if (ev !is chat.keryx.app.data.remote.HermesStreamClient.Event.Delta)
                    android.util.Log.i("KeryxSSE", "event=$ev bufLen=${buf.length}")
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
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.SegmentBreak -> {
                        if (buf.isNotEmpty() && !buf.endsWith("\n\n")) buf.append("\n\n")
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Stop -> {
                        _linkHealth.value = LinkHealth.OK
                        if (ev.finalText.isNullOrBlank() && buf.isBlank() && reasoningBuf.isBlank()) {
                            // Everything this turn produced was already consumed by mid-turn
                            // segment commits — nothing left to hand off; don't hold an invisible
                            // overlay through the 20 s sync grace.
                            clearStream(); settleTurn()
                            return@collect
                        }
                        dispatch(LiveStreamStatus.AWAITING_SYNC, finalText = ev.finalText ?: buf.toString())
                        // The committed event may have synced BEFORE stop arrived; nothing else
                        // re-triggers the handoff check (the messages flow won't emit again), so
                        // evaluate against the current timeline right now — otherwise the overlay
                        // sits beside its own committed copy until the sync-grace timeout.
                        messages.value.lastOrNull()?.let { maybeHandOffStream(messages.value, it, isNewMsg = false) }
                        if (_liveStream.value != null) scheduleStreamClear(STREAM_SYNC_GRACE_MS)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Failed -> {
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

    private fun clearStream() {
        android.util.Log.i("KeryxHandoff", "clearStream (was ${_liveStream.value?.status})")
        streamClearJob?.cancel()
        streamJob?.cancel()
        consumeStreamedSegment = null
        _liveStream.value = null
    }

    /** Drop the overlay when its committed Matrix counterpart is in the timeline. */
    private fun maybeHandOffStream(messages: List<Message>, last: Message, isNewMsg: Boolean) {
        val s = _liveStream.value ?: return
        android.util.Log.i(
            "KeryxHandoff",
            "check status=${s.status} room=${last.sessionId == s.roomId} new=$isNewMsg " +
                "target=${(s.finalText ?: s.text).length}ch last=${last.sender}/${last.content.length}ch"
        )
        if (last.sessionId != s.roomId) return
        when (s.status) {
            LiveStreamStatus.STREAMING -> {
                val target = s.finalText ?: s.text
                if (target.isNotBlank()) {
                    val matched = messages.asReversed()
                        .asSequence()
                        .filter { it.sessionId == s.roomId && it.sender != SenderType.ME }
                        .filterNot { MessageParser.isTelemetryMessage(it.content) }
                        .take(8)
                        .any { StreamHandoff.matches(it.content, target) }
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
                    .filter { it.sessionId == s.roomId && it.sender != SenderType.ME }
                    .take(8)
                    .toList()
                val target = s.finalText ?: s.text
                val matched = target.isNotBlank() && recentHermes
                    .asSequence()
                    .filterNot { MessageParser.isTelemetryMessage(it.content) }
                    .any { StreamHandoff.matches(it.content, target) }
                val hasCommittedAnswer = recentHermes.any {
                    !MessageParser.isTelemetryMessage(it.content) &&
                        StreamHandoff.normalize(it.content).isNotBlank()
                }
                android.util.Log.i("KeryxHandoff", "awaitSync matched=$matched newMsg=$isNewMsg committed=$hasCommittedAnswer recent=${recentHermes.size}")
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
        if (room != null) selectSession(Session(room.id, room.id, room.name, 0L))
        else pendingOpenRoomId = roomId
    }

    fun selectSession(session: Session) {
        _currentSession.value = session
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
        settingsRepository.lastRoomId = session.id
    }

    /** Load an older page of history (called when the user scrolls to the top of the timeline). */
    fun loadOlderMessages() {
        if (_isLoadingMore.value || !_hasMoreHistory.value) return
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

    fun setReplyTarget(message: Message?) { _replyTarget.value = message }
    fun clearReplyTarget() { _replyTarget.value = null }

    // Media/avatar downloads are owned by viewModelScope (NOT the Compose produceState coroutine),
    // so a recomposition or a brief scroll-off no longer cancels an in-flight fetch ("The coroutine
    // scope left the composition"). Results are cached so returning to a chat is instant; failed
    // (null) loads are evicted so they can be retried.
    private val mediaCache = java.util.concurrent.ConcurrentHashMap<String, Deferred<ByteArray?>>()
    private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, Deferred<ByteArray?>>()

    suspend fun loadMessageMedia(sessionId: String, eventId: String): ByteArray? {
        val deferred = mediaCache.getOrPut(eventId) {
            viewModelScope.async(Dispatchers.IO) { repository.mediaBytes(sessionId, eventId) }
        }
        val result = deferred.await() // cancellation propagates to the caller, not the download
        if (result == null) mediaCache.remove(eventId)
        return result
    }

    // Drawer previews: the latest meaningful message per room, fetched lazily (only when a drawer
    // row actually composes) and cached against the room's last-event timestamp so re-opening the
    // drawer is free until new activity lands. Deliberately NOT folded into the rooms flow — that
    // would keep a timeline flow alive per room for the whole session just to serve a snippet.
    private val previewCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    suspend fun roomPreview(roomId: String, stamp: Long): String? {
        previewCache[roomId]?.let { (cachedStamp, preview) -> if (cachedStamp == stamp) return preview }
        val msgs = withTimeoutOrNull(5_000L) {
            repository.getMessages(roomId, 8).first { it.isNotEmpty() }
        } ?: return previewCache[roomId]?.second
        // Skip runtime footers so the preview reads as the conversation, not plumbing.
        val last = msgs.lastOrNull { !MessageParser.isRuntimeFooterMessage(it.content) } ?: msgs.lastOrNull() ?: return null
        val preview = previewOf(last)
        previewCache[roomId] = stamp to preview
        return preview
    }

    // Live reactions: a cold flow per event that updates the instant a reaction is added/redacted by
    // anyone. Cached by event id so re-subscribing the same bubble during scroll reuses the running
    // flow (shareIn keeps it hot briefly) instead of spinning up a fresh Matrix subscription.
    private val reactionFlows = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.flow.Flow<List<MessageReaction>>>()

    fun reactionsFlow(sessionId: String, eventId: String): kotlinx.coroutines.flow.Flow<List<MessageReaction>> =
        reactionFlows.getOrPut(eventId) {
            repository.reactionsFlow(sessionId, eventId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    fun sendReaction(eventId: String, emoji: String) {
        val session = _currentSession.value ?: return
        viewModelScope.launch { repository.react(session.id, eventId, emoji) }
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
        _currentSession.value?.id?.let { settingsRepository.setDraft(it, text) }
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

    fun sendAttachment(bytes: ByteArray, fileName: String, contentType: String) {
        val session = _currentSession.value ?: return
        viewModelScope.launch { repository.sendAttachment(session.id, bytes, fileName, contentType) }
    }

    fun markRoomRead(roomId: String, eventId: String) {
        viewModelScope.launch { repository.markRead(roomId, eventId) }
    }

    suspend fun loadAvatar(mxc: String): ByteArray? {
        val deferred = avatarCache.getOrPut(mxc) {
            viewModelScope.async(Dispatchers.IO) { repository.avatarBytes(mxc) }
        }
        val result = deferred.await()
        if (result == null) avatarCache.remove(mxc)
        return result
    }

    // One-shot user-facing messages (e.g. avatar set result) — collected once at the app root.
    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts

    fun setRoomAvatar(roomId: String, bytes: ByteArray, contentType: String) {
        viewModelScope.launch {
            repository.setRoomAvatar(roomId, bytes, contentType)
                .onSuccess { _toasts.tryEmit("Room photo updated") }
                .onFailure {
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
        val session = _currentSession.value ?: return
        val replyTo = _replyTarget.value
        _awaitingReply.value = true
        _workStartedAt.value = System.currentTimeMillis()
        _workLabel.value = "Working"
        answerLanded = false
        // Clear if the agent never responds at all; agent activity resets this to a shorter quiet timeout.
        scheduleClearAwaiting(NO_REPLY_MS)
        // Subscribe the side-channel BEFORE the command lands, so the gateway already sees a
        // live subscriber when it decides how to deliver this turn's tokens.
        openSideChannel(session.id)
        // Optimistic echo: the bubble appears the moment Send is tapped instead of waiting for the
        // homeserver round-trip. Retired by the echo match in the messages collector; the timeout
        // is only a safety net (the real event still renders normally if matching ever misses).
        pendingSendClearJob?.cancel()
        _pendingSend.value = PendingSend(session.id, content, System.currentTimeMillis())
        pendingSendClearJob = viewModelScope.launch {
            delay(PENDING_SEND_TIMEOUT_MS)
            _pendingSend.value = null
        }
        viewModelScope.launch {
            if (replyTo != null) repository.sendReply(session.id, content, replyTo.id)
            else repository.sendMessage(session.id, content)
        }
        _replyTarget.value = null
        settingsRepository.setDraft(session.id, "")
    }

    fun loginToMatrix(username: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = repository.login(username, password)
            result.fold(
                onSuccess = { onResult(true, "Logged in") },
                onFailure = { onResult(false, it.message ?: "Login failed. Check URL or credentials.") },
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            settingsRepository.lastRoomId = null
            _currentSession.value = null
            repository.logout()
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
