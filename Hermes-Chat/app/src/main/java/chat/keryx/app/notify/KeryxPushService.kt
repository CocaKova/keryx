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
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.SenderType
import chat.keryx.core.protocol.MessageParser
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
        val roomId = pushedRoomId(message.content.decodeToString())
        android.util.Log.i("KeryxPush", "push received (room=${roomId?.take(12)})")
        enqueuePushSync(applicationContext, roomId)
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

/** Grace for background workers: how long sync may linger once a push fetch or a
 *  notification-action send finishes with the app still off screen. */
internal const val SYNC_JOB_LINGER_MS = 15_000L

/**
 * room_id from a pushed Synapse HTTP-pusher body — ntfy's Matrix gateway forwards it verbatim:
 * `{"notification": {"event_id": "...", "room_id": "...", "counts": {...}, ...}}`. Shared by both
 * transports (distributor message / built-in WebSocket frame).
 */
internal fun pushedRoomId(body: String): String? = runCatching {
    val obj = Json.parseToJsonElement(body).jsonObject
    ((obj["notification"] ?: obj).jsonObject["room_id"] as? JsonPrimitive)?.content
}.getOrNull()

/** Wake the sync for a pushed room. One queue per room so a burst coalesces instead of stacking. */
internal fun enqueuePushSync(context: Context, roomId: String?) {
    val work = OneTimeWorkRequestBuilder<PushSyncWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(workDataOf(PushSyncWorker.KEY_ROOM_ID to roomId))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "keryx-push-sync-${roomId ?: "any"}", ExistingWorkPolicy.REPLACE, work,
    )
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
        try {
        // Idempotent restore: no-ops when the process was already alive with a live client.
        // The retry guard has to read the CLIENT, not the Result wrapping it: `runCatching {}`
        // is never null, so the elvis below it could never fire and a restore that failed
        // walked on to wait out the full sync window against a client that does not exist —
        // the push was then dropped as a success and never came back.
        val client = runCatching {
            app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure)
        }.getOrNull()
        if (client == null) {
            // A store on disk means the restore FAILED (a cold-start DB race, a transient
            // lock) and this push is worth another attempt; no store at all means the install
            // is signed out of Matrix, where retrying could only spin. Bounded either way.
            android.util.Log.w("KeryxPush", "no Matrix client for push sync (room=${roomId?.take(12)})")
            val worthRetrying = app.settingsRepository.matrixSessionOnFile && runAttemptCount < 2
            return if (worthRetrying) Result.retry() else Result.success()
        }
        // A warm process may hold a client whose sync the governor already parked — restore()
        // returns that client untouched, so the loop must be woken explicitly for this fetch.
        app.matrixService.syncWake()

        if (roomId == null) return Result.success() // badge-only push: the sync poke was the point
        if (app.isForeground && app.openRoomId == roomId) return Result.success()

        val message = withTimeoutOrNull(SYNC_WAIT_MS) {
            app.transport.getMessages(roomId, 1).first { it.isNotEmpty() }.lastOrNull()
        }
        if (message == null || message.sender == SenderType.ME) return Result.success()
        val roomName = withTimeoutOrNull(2_000L) {
            app.transport.getRooms().first().firstOrNull { it.id == roomId }?.name
        } ?: "New message"
        KeryxNotifications.notifyMessage(
            context = applicationContext,
            roomId = roomId,
            // Agent-shaped (2.8): the speaker is the sender, the room is the conversation;
            // the line is marker-free (⟦…⟧ never leaks into a banner) and media-aware.
            notice = chat.keryx.core.model.AgentNotices.compose(message, conversation = roomName),
            timestamp = message.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
            quickActions = if (message.sender == SenderType.HERMES) {
                MessageParser.quickActions(message.content)
            } else {
                emptyList()
            },
        )
        return Result.success()
        } finally {
            // The fetch is done. If nobody is looking, the loop goes back to sleep — a short
            // linger lets a burst of pushes (and this push's own ingest) reuse the warm loop.
            if (!app.isForeground) app.matrixService.syncStandby(SYNC_JOB_LINGER_MS)
        }
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        // Long enough for a cold process + initial sync round-trip on mobile data.
        private const val SYNC_WAIT_MS = 25_000L
    }
}
