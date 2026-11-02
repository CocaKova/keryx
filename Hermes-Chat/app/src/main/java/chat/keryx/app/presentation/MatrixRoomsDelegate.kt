package chat.keryx.app.presentation

import chat.keryx.app.R
import chat.keryx.app.KeryxText
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
                ?.onSuccess { toast(KeryxText.get(R.string.app_room_joined)) }
                ?.onFailure { toast(KeryxText.get(R.string.app_join_failed, it.message?.take(80).orEmpty())) }
        }
    }

    fun declineInvite(roomId: String) {
        scope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess { toast(KeryxText.get(R.string.app_invite_declined)) }
                ?.onFailure { toast(KeryxText.get(R.string.app_decline_failed, it.message?.take(80).orEmpty())) }
        }
    }

    // --- Starting conversations (NewChatSheet) --------------------------------------------------
    // Each [onDone] gets null on success (the sheet closes; the room opens via openRoomById as
    // soon as sync surfaces it) or the gateway/homeserver's own error text to show inline.

    fun startDirectMessage(userId: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.startDirectMessage(userId)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: KeryxText.get(R.string.app_start_chat_failed)) }
        }
    }

    fun createRoom(name: String, invitee: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.createRoom(name, listOf(invitee))
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: KeryxText.get(R.string.app_create_room_failed)) }
        }
    }

    fun joinRoomByAddress(address: String, onDone: (String?) -> Unit) {
        scope.launch {
            matrix?.joinRoomByAddress(address)
                ?.onSuccess { roomId -> openRoomById(roomId); onDone(null) }
                ?.onFailure { onDone(it.message?.take(120) ?: KeryxText.get(R.string.app_join_failed_short)) }
        }
    }

    fun inviteUser(roomId: String, userId: String) {
        scope.launch {
            matrix?.inviteUser(roomId, userId)
                ?.onSuccess { toast(KeryxText.get(R.string.app_invite_sent)) }
                ?.onFailure { toast(KeryxText.get(R.string.app_invite_failed, it.message?.take(80).orEmpty())) }
        }
    }

    fun leaveRoom(roomId: String) {
        scope.launch {
            matrix?.leaveRoom(roomId)
                ?.onSuccess {
                    toast(KeryxText.get(R.string.app_left_room))
                    if (settings.lastRoomId == roomId) settings.lastRoomId = null
                    // Move off the dead room so the chat pane never points at a membership we lost.
                    moveOffRoom(roomId)
                }
                ?.onFailure { toast(KeryxText.get(R.string.app_leave_failed, it.message?.take(80).orEmpty())) }
        }
    }

    /** Delete (redact) a message. The event body vanishes for everyone on sync. */
    fun deleteMessage(sessionId: String, eventId: String) {
        scope.launch {
            matrix?.redactMessage(sessionId, eventId)
                ?.onFailure { toast(KeryxText.get(R.string.app_delete_failed, it.message?.take(80).orEmpty())) }
        }
    }

    /** Message redaction is a Matrix power; the gateway keeps its transcript. */
    val canDeleteMessages: Boolean get() = matrix != null
}
