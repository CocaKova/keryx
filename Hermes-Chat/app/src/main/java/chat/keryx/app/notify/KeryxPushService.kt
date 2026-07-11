package chat.keryx.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.keryx.app.KeryxApp
import chat.keryx.app.domain.model.MediaKind
import chat.keryx.app.domain.model.SenderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * The UnifiedPush entry point. The distributor wakes this service when the push gateway (ntfy's
 * built-in `/_matrix/push/v1/notify`) forwards a Synapse push. The payload is `event_id_only` —
 * just event/room ids, never content — so E2EE costs nothing extra: we wake, let Trixnity sync
 * (which decrypts), and raise the same per-room notification the in-process watcher uses.
 */
class KeryxPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        android.util.Log.i("KeryxPush", "new endpoint from distributor")
        PushManager.onNewEndpoint(applicationContext, endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // ntfy's Matrix gateway forwards Synapse's HTTP-pusher body verbatim:
        // {"notification": {"event_id": "...", "room_id": "...", "counts": {...}, ...}}
        val roomId = runCatching {
            val obj = Json.parseToJsonElement(message.content.decodeToString()).jsonObject
            ((obj["notification"] ?: obj).jsonObject["room_id"] as? JsonPrimitive)?.content
        }.getOrNull()
        android.util.Log.i("KeryxPush", "push received (room=${roomId?.take(12)})")
        val work = OneTimeWorkRequestBuilder<PushSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(PushSyncWorker.KEY_ROOM_ID to roomId))
            .build()
        // One queue per room so a burst of pushes coalesces instead of stacking workers.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "keryx-push-sync-${roomId ?: "any"}", ExistingWorkPolicy.REPLACE, work,
        )
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        android.util.Log.w("KeryxPush", "registration failed: $reason")
        PushManager.onRegistrationGone(applicationContext)
    }

    override fun onUnregistered(instance: String) {
        android.util.Log.i("KeryxPush", "distributor unregistered us")
        PushManager.onRegistrationGone(applicationContext)
    }
}

/**
 * Wakes the Matrix client and waits for the pushed room's timeline to yield its newest message,
 * then posts through [KeryxNotifications]. Uses the same notification id space (room-id hash) as
 * the in-process watcher, so whichever path fires second replaces — never stacks.
 */
class PushSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KeryxApp ?: return Result.failure()
        val roomId = inputData.getString(KEY_ROOM_ID)
        // Idempotent restore: no-ops when the process was already alive with a live client.
        runCatching { app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure) }
            ?: return Result.retry()

        if (roomId == null) return Result.success() // badge-only push: the sync poke was the point
        if (app.isForeground && app.openRoomId == roomId) return Result.success()

        val message = withTimeoutOrNull(SYNC_WAIT_MS) {
            app.repository.getMessages(roomId, 1).first { it.isNotEmpty() }.lastOrNull()
        }
        if (message == null || message.sender == SenderType.ME) return Result.success()
        val roomName = withTimeoutOrNull(2_000L) {
            app.repository.getRooms().first().firstOrNull { it.id == roomId }?.name
        } ?: "New message"
        KeryxNotifications.notifyMessage(
            context = applicationContext,
            roomId = roomId,
            title = roomName,
            body = when {
                message.mediaKind == MediaKind.IMAGE -> "🖼 Photo"
                message.mediaKind != null -> "📎 ${message.fileName.ifBlank { "Attachment" }}"
                message.content.isNotBlank() ->
                    message.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160)
                        ?: "New message"
                else -> "New message"
            },
        )
        return Result.success()
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        // Long enough for a cold process + initial sync round-trip on mobile data.
        private const val SYNC_WAIT_MS = 25_000L
    }
}
