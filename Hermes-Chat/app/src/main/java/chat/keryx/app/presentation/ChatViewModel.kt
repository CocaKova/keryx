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
        // How long after the agent's last activity to keep the "working" animation up. Short enough
        // that it doesn't linger once the reply has landed, long enough to bridge thinking/tool gaps.
        private const val QUIET_MS = 2_500L
        // Absolute cap if the agent never responds at all.
        private const val NO_REPLY_MS = 120_000L
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

    // True while the agent is working (drives the waiting animation). Stays up through the agent's
    // thinking / tool-call / streaming phases and only clears after activity goes quiet.
    private val _awaitingReply = MutableStateFlow(false)
    val awaitingReply: StateFlow<Boolean> = _awaitingReply.asStateFlow()
    private var quietJob: Job? = null

    // One-shot text to drop into the composer (e.g. from the Steer quick-action).
    private val _composerPrefill = MutableStateFlow<String?>(null)
    val composerPrefill: StateFlow<String?> = _composerPrefill.asStateFlow()

    private fun scheduleClearAwaiting(delayMs: Long) {
        quietJob?.cancel()
        quietJob = viewModelScope.launch {
            delay(delayMs)
            _awaitingReply.value = false
        }
    }

    init {
        viewModelScope.launch {
            repository.getRooms().collectLatest { roomList ->
                _rooms.value = roomList
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
                // Each agent edit (thinking -> tool -> text via m.replace) re-emits here; keep the
                // animation alive and push the "quiet" timeout back, so it persists across phases.
                val last = msgs.lastOrNull()
                if (_awaitingReply.value && last != null && last.sender != SenderType.ME) {
                    scheduleClearAwaiting(QUIET_MS)
                }
            }
        }
    }

    fun prefillComposer(text: String) { _composerPrefill.value = text }
    fun consumeComposerPrefill() { _composerPrefill.value = null }

    fun selectSession(session: Session) {
        _currentSession.value = session
        _timelineLimit.value = INITIAL_LIMIT
        _hasMoreHistory.value = true
        _isLoadingMore.value = false
        _replyTarget.value = null
        quietJob?.cancel()
        _awaitingReply.value = false
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
    }

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
        // Clear if the agent never responds at all; agent activity resets this to a shorter quiet timeout.
        scheduleClearAwaiting(NO_REPLY_MS)
        viewModelScope.launch {
            if (replyTo != null) repository.sendReply(session.id, content, replyTo.id)
            else repository.sendMessage(session.id, content)
        }
        _replyTarget.value = null
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
}
