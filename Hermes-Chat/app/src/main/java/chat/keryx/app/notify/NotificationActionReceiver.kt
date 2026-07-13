package chat.keryx.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chat.keryx.app.KeryxApp

/**
 * Handles the buttons on a message notification: inline **Reply** (RemoteInput) and the one-tap
 * ⟦keryx:ask⟧ decision options. Either way the answer is just a normal Matrix message into the
 * room — the agent is already waiting on room input (Hermes queues mid-task messages), so no
 * dedicated response channel is needed. The actual send runs in a WorkManager worker: a receiver
 * gets ~10s and no process guarantees, while the worker can restore the Matrix client cold.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(KeryxNotifications.EXTRA_ROOM_ID) ?: return
        val roomName = intent.getStringExtra(KeryxNotifications.EXTRA_ROOM_NAME) ?: "Keryx"
        val text = when (intent.action) {
            ACTION_QUICK -> intent.getStringExtra(KeryxNotifications.EXTRA_QUICK_TEXT)
            ACTION_REPLY -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KeryxNotifications.KEY_REMOTE_REPLY)?.toString()
            else -> null
        }?.trim()
        if (text.isNullOrEmpty()) return

        // Immediate repost: a fired RemoteInput pins a spinner on the notification until it's
        // updated with the same id — the worker then overwrites this with the sent/failed state.
        KeryxNotifications.notifyActionResult(context, roomId, roomName, "→ Sending: $text")

        val work = OneTimeWorkRequestBuilder<SendTextWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    SendTextWorker.KEY_ROOM_ID to roomId,
                    SendTextWorker.KEY_ROOM_NAME to roomName,
                    SendTextWorker.KEY_TEXT to text,
                ),
            )
            .build()
        // APPEND, not REPLACE: two rapid actions (a reply while an option send is in flight) must
        // both land, in order.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "keryx-notif-send-$roomId", ExistingWorkPolicy.APPEND_OR_REPLACE, work,
        )
    }

    companion object {
        const val ACTION_REPLY = "chat.keryx.app.notify.REPLY"
        const val ACTION_QUICK = "chat.keryx.app.notify.QUICK"
    }
}

/** Restores the Matrix client if needed and sends [KEY_TEXT] into [KEY_ROOM_ID], then settles the
 *  notification into a quiet sent/failed state. */
class SendTextWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KeryxApp ?: return Result.failure()
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val roomName = inputData.getString(KEY_ROOM_NAME) ?: "Keryx"
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()

        val sent = runCatching {
            app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure)
            app.repository.sendMessage(roomId, text)
        }
        return if (sent.isSuccess) {
            KeryxNotifications.notifyActionResult(applicationContext, roomId, roomName, "✓ You: $text")
            Result.success()
        } else {
            android.util.Log.w("KeryxNotify", "action send failed: ${sent.exceptionOrNull()?.message}")
            // One retry for a flaky network, then surface the failure instead of dropping it.
            if (runAttemptCount < 1) {
                Result.retry()
            } else {
                KeryxNotifications.notifyActionResult(
                    applicationContext, roomId, roomName, "⚠️ Couldn't send \"$text\" — tap to open",
                )
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ROOM_ID = "roomId"
        const val KEY_ROOM_NAME = "roomName"
        const val KEY_TEXT = "text"
    }
}
