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
        private const val QUIET_SHORT_MS = 3_000L
        // Absolute cap covering time-to-first-response if the agent never says anything at all.
        private const val NO_REPLY_MS = 240_000L
        // On opening a room, treat the agent as still working if its last (mid-run) message landed
        // within this window — so the cloud/quips appear when you open the app mid-run, not only when
        // you were the one who sent the message.
        private const val WORKING_RECENT_MS = 150_000L
        // Bridge after Hermes stops typing — long enough to hand off to the final message's settle,
        // short enough that the banner doesn't loiter once it's genuinely done.
        private const val TYPING_STOP_GRACE_MS = 8_000L
        // Typing stopped and the answer is already rendered: the turn is over — settle fast.
        private const val ANSWER_SETTLED_MS = 800L

        // --- Side-channel stream tuning ---
        // UI dispatch throttle for the live token buffer: flush to state when either trips.
        // ~10 dispatches/s keeps recomposition (and the markdown re-parse) far off the frame budget
        // even at high token rates, while still reading as a live stream.
        private const val STREAM_DISPATCH_MS = 100L
        private const val STREAM_DISPATCH_CHARS = 240
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

    private val _showTelemetry = MutableStateFlow(settingsRepository.showTelemetry)
    val showTelemetry: StateFlow<Boolean> = _showTelemetry.asStateFlow()

    /** The transient live response overlay (null = nothing streaming over the side-channel). */
    private val _liveStream = MutableStateFlow<LiveStream?>(null)
    val liveStream: StateFlow<LiveStream?> = _liveStream.asStateFlow()
    private var streamJob: Job? = null
    private var streamClearJob: Job? = null

    // True while the agent is working (drives the waiting animation). Stays up through the agent's
    // thinking / tool-call / streaming phases and only clears after activity goes quiet.
    private val _awaitingReply = MutableStateFlow(false)
    val awaitingReply: StateFlow<Boolean> = _awaitingReply.asStateFlow()
    private var quietJob: Job? = null

    // Tracks the last message we evaluated, so we can tell genuine live activity (a new message, or a
    // streamed m.replace edit growing the current one) from merely re-observing the same timeline.
    private var lastSeenId: String? = null
    private var lastSeenLen: Int = -1

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

    // A short label of what the agent is currently doing, for the top counter ("Reasoning",
    // "Running terminal", …). Derived from the latest agent message.
    private val _workLabel = MutableStateFlow("Working")
    val workLabel: StateFlow<String> = _workLabel.asStateFlow()

    // One-shot text to drop into the composer (e.g. from the Steer quick-action).
    private val _composerPrefill = MutableStateFlow<String?>(null)
    val composerPrefill: StateFlow<String?> = _composerPrefill.asStateFlow()

    private fun scheduleClearAwaiting(delayMs: Long) {
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            delay(delayMs)
            if (agentTyping) return@launch // still actively working — typing owns the lifecycle
            _awaitingReply.value = false
            _workStartedAt.value = null
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
            tools.isNotEmpty() -> "Running ${tools.last().name}"
            isTelemetry -> "Working"
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
                // Classify this emission relative to the last one we saw.
                val firstEval = lastSeenId == null
                val isNewMsg = !firstEval && last != null && last.id != lastSeenId
                val grew = last != null && last.id == lastSeenId && last.content.length > lastSeenLen
                lastSeenId = last?.id
                lastSeenLen = last?.content?.length ?: -1
                android.util.Log.i(
                    "KeryxFlow",
                    "emission n=${msgs.size} last=${last?.id?.take(12)} sender=${last?.sender} len=${last?.content?.length} " +
                        "new=$isNewMsg grew=$grew stream=${_liveStream.value?.status} awaiting=${_awaitingReply.value}"
                )

                if (last == null) return@collect

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
                val window = updateWorkStateFrom(stateMessage)
                val midRun = window == QUIET_LONG_MS
                answerLanded = !midRun
                // Live activity = a brand-new message or a streamed edit growing the current one.
                val liveActivity = isNewMsg || grew
                // On first open, fall back to recency so opening the app mid-run still lights up,
                // without falsely firing for an old conversation that merely ended on a tool call.
                val openedMidRun = firstEval && (System.currentTimeMillis() - stateMessage.timestamp) < WORKING_RECENT_MS

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
            var lastDispatch = 0L
            var charsSinceDispatch = 0
            fun dispatch(status: LiveStreamStatus, finalText: String? = null) {
                val cur = _liveStream.value ?: LiveStream(roomId, "", status, System.currentTimeMillis())
                _liveStream.value = cur.copy(
                    text = MessageParser.sanitizeStreamingTail(buf.toString()),
                    status = status,
                    finalText = finalText ?: cur.finalText,
                )
                lastDispatch = System.currentTimeMillis()
                charsSinceDispatch = 0
            }
            _liveStream.value = LiveStream(roomId, "", LiveStreamStatus.CONNECTING, System.currentTimeMillis())
            client.stream(roomId).collect { ev ->
                if (ev !is chat.keryx.app.data.remote.HermesStreamClient.Event.Delta)
                    android.util.Log.i("KeryxSSE", "event=$ev bufLen=${buf.length}")
                when (ev) {
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Opened -> {
                        _linkHealth.value = LinkHealth.LIVE
                        dispatch(LiveStreamStatus.STREAMING)
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.Event.Delta -> {
                        buf.append(ev.text)
                        charsSinceDispatch += ev.text.length
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

    private fun clearStream() {
        android.util.Log.i("KeryxHandoff", "clearStream (was ${_liveStream.value?.status})")
        streamClearJob?.cancel()
        streamJob?.cancel()
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
                    if (matched) clearStream()
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
                if (matched || (isNewMsg && hasCommittedAnswer)) clearStream()
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
        clearStream() // the overlay is room-scoped; never carry it across a room switch
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
