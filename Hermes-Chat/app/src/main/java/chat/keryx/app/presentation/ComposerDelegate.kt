package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the composer does as you type (split out of [ChatViewModel] in 3.0): the "/" palette's
 * visibility and filter, recent commands, the per-room draft, and the throttled typing notice
 * other clients see. Sending stays in the ViewModel.
 */
class ComposerDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val currentRoom: StateFlow<RoomProfile?>,
) {
    private val scope = deps.scope
    private val settings: SettingsRepository = deps.settings

    // --- The "/" palette: shown while the command token itself is typed ---
    private val _commandMenuVisible = MutableStateFlow(false)
    val commandMenuVisible: StateFlow<Boolean> = _commandMenuVisible.asStateFlow()

    private val _commandFilter = MutableStateFlow("")
    val commandFilter: StateFlow<String> = _commandFilter.asStateFlow()

    private val _recentCommands = MutableStateFlow(settings.recentCommands)
    val recentCommands: StateFlow<List<String>> = _recentCommands.asStateFlow()

    fun onComposerTextChanged(text: String) {
        // Show the palette only while typing the command token itself (before the first space),
        // so it auto-dismisses once a command is chosen and the user moves on to its arguments.
        _commandMenuVisible.value = text.startsWith("/") && !text.contains(' ')
        if (text.startsWith("/")) _commandFilter.value = text.removePrefix("/").substringBefore(' ')
        // Keep the per-room draft current so an app kill / room switch never loses typed text.
        currentRoom.value?.id?.let { settings.setDraft(it, text) }
        broadcastTyping(text)
    }

    /** The saved (unsent) composer text for [roomId] — restored when the room opens. */
    fun draftFor(roomId: String): String = settings.getDraft(roomId)

    /** Remember a slash command the user picked, so the palette can surface recents first. */
    fun recordCommandUse(command: String) {
        val cmd = command.trim().substringBefore(' ')
        if (cmd.isBlank()) return
        val updated = (listOf(cmd) + _recentCommands.value.filter { it != cmd }).take(8)
        _recentCommands.value = updated
        settings.recentCommands = updated
    }

    // --- Outgoing typing (m.typing) — so other clients see us composing ------------------------

    private var typingSentAt = 0L
    private var typingRoomId: String? = null

    /** Throttled m.typing broadcast driven by composer text changes. A refresh every ~4s keeps the
     *  30s server-side timeout alive while composing; clearing the composer stops it eagerly. */
    private fun broadcastTyping(text: String) {
        val room = currentRoom.value?.id ?: return
        val now = System.currentTimeMillis()
        if (text.isBlank()) {
            stopTypingBroadcast(room)
        } else if (now - typingSentAt > 4_000L || typingRoomId != room) {
            typingSentAt = now
            typingRoomId = room
            scope.launch { transport.setTyping(room, true) }
        }
    }

    /** Stop saying we are composing in [roomId] (a send, a cleared composer). */
    fun stopTypingBroadcast(roomId: String) {
        if (typingRoomId == null) return
        typingSentAt = 0L
        typingRoomId = null
        scope.launch { transport.setTyping(roomId, false) }
    }
}
