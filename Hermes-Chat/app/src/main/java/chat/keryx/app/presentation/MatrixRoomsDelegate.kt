package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.launch

/**
 * Matrix room membership (split out of [ChatViewModel] in 3.0): answering invites, starting
 * a DM or a room, joining by address, inviting, leaving, and redacting a message. A Matrix
 * power throughout; every verb is a no-op on the direct door. The invite LIST stays in the
 * ViewModel, next to the init collector that fills it synchronously.
 */
class MatrixRoomsDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val openRoomById: (String) -> Unit,
    /** Leave [roomId] if it is the open one: the next room, or none. */
    private val moveOffRoom: (roomId: String) -> Unit,
) {
    private val scope = deps.scope
    private val settings: SettingsRepository = deps.settings
    private val toast = deps.toast
    private val matrix get() = transport.matrix

    fun acceptInvite(roomId: String) {
        scope.launch {
            matrix?.acceptInvite(roomId)
                ?.onSuccess { toast("Joined — the room appears on the next sync") }
                ?.onFailure { toast("Join failed: ${it.message?.take(80)}") }
        }
    }

    fun declineInvite(roomId: String) {
        scope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess { toast("Invite declined") }
                ?.onFailure { toast("Decline failed: ${it.message?.take(80)}") }
        }
    }

    // --- Starting conversations (NewChatSheet) --------------------------------------------------
    // Each [onDone] gets null on success (the sheet closes; the room opens via openRoomById as
    // soon as sync surfaces it) or the gateway/homeserver's own error text to show inline.

    fun startDirectMessage(userId: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.startDirectMessage(userId)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't start the chat") }
        }
    }

    fun createRoom(name: String, invitee: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.createRoom(name, listOf(invitee))
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't create the room") }
        }
    }

    fun joinRoomByAddress(address: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.joinRoomByAddress(address)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: "couldn't join") }
        }
    }

    fun inviteUser(roomId: String, userId: String) {
        scope.launch {
            matrix?.inviteUser(roomId, userId)
                ?.onSuccess { toast("Invite sent") }
                ?.onFailure { toast("Invite failed: ${it.message?.take(80)}") }
        }
    }

    fun leaveRoom(roomId: String) {
        scope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess {
                    toast("Left room")
                    if (settings.lastRoomId == roomId) settings.lastRoomId = null
                    // Move off the dead room so the chat pane never points at a membership we lost.
                    moveOffRoom(roomId)
                }
                ?.onFailure { toast("Leave failed: ${it.message?.take(80)}") }
        }
    }

    /** Delete (redact) a message. The event body vanishes for everyone on sync. */
    fun deleteMessage(sessionId: String, eventId: String) {
        scope.launch {
            matrix?.redactMessage(sessionId, eventId)
                ?.onFailure { toast("Delete failed: ${it.message?.take(80)}") }
        }
    }

    /** Message redaction is a Matrix power; the gateway keeps its transcript. */
    val canDeleteMessages: Boolean get() = matrix != null
}
