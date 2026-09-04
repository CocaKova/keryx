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
import kotlinx.coroutines.launch

/**
 * Handles the buttons on a message notification: inline **Reply** (RemoteInput) and the one-tap
 * ⟦keryx:ask⟧ decision options. Either way the answer is just a normal Matrix message into the
 * room — the agent is already waiting on room input (Hermes queues mid-task messages), so no
 * dedicated response channel is needed. The actual send runs in a WorkManager worker: a receiver
 * gets ~10s and no process guarantees, while the worker can restore the Matrix client cold.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_GATE_CHOICE || intent.action == ACTION_GATE_REPLY) {
            onGateAnswer(context, intent)
            return
        }
        val roomId = intent.getStringExtra(KeryxNotifications.EXTRA_ROOM_ID) ?: return
        val roomName = intent.getStringExtra(KeryxNotifications.EXTRA_ROOM_NAME) ?: "Keryx"
        if (intent.action == ACTION_MARK_READ) {
            // The gateway's own watermark (direct door): the row's dot clears everywhere,
            // Desktop included. Fire-and-forget on the app scope; the shade entry goes now.
            val app = context.applicationContext as? KeryxApp
            KeryxNotifications.clear(context, roomId)
            app?.appScope?.launch {
                runCatching { app.transport.gateway?.markSessionRead(roomId, true) }
            }
            return
        }
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

    /**
     * The Gate's buttons: a stopped agent answered from the shade. Unlike a message reply this
     * is NOT a message into the room — it resolves a specific request the gateway is blocked
     * on, by id, over the direct door's RPC. Handed to a worker for the same reason as the
     * others: a receiver gets ~10s and no process guarantees, and the socket may be cold.
     */
    private fun onGateAnswer(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(KeryxNotifications.EXTRA_GATE_SESSION) ?: return
        val kind = intent.getStringExtra(KeryxNotifications.EXTRA_GATE_KIND) ?: return
        val sessionName = intent.getStringExtra(KeryxNotifications.EXTRA_ROOM_NAME) ?: "Keryx"
        val answer = when (intent.action) {
            ACTION_GATE_CHOICE -> intent.getStringExtra(KeryxNotifications.EXTRA_GATE_VALUE)
            ACTION_GATE_REPLY -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KeryxNotifications.KEY_GATE_REPLY)?.toString()
            else -> null
        }?.trim()
        if (answer.isNullOrEmpty()) return

        KeryxNotifications.notifyGateResult(context, sessionId, sessionName, "→ Answering…")

        val work = OneTimeWorkRequestBuilder<GateAnswerWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    GateAnswerWorker.KEY_SESSION to sessionId,
                    GateAnswerWorker.KEY_SESSION_NAME to sessionName,
                    GateAnswerWorker.KEY_KIND to kind,
                    GateAnswerWorker.KEY_REQUEST to intent.getStringExtra(KeryxNotifications.EXTRA_GATE_REQUEST),
                    GateAnswerWorker.KEY_ANSWER to answer,
                ),
            )
            .build()
        // KEEP, not APPEND: the first answer to a request wins. Two taps on one notice (or a
        // tap racing a RemoteInput) must not send two answers to a gateway that has already
        // moved on — the second would resolve nothing, or worse, the NEXT request.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "keryx-gate-answer-$sessionId", ExistingWorkPolicy.KEEP, work,
        )
    }

    companion object {
        const val ACTION_REPLY = "chat.keryx.app.notify.REPLY"
        const val ACTION_QUICK = "chat.keryx.app.notify.QUICK"
        const val ACTION_MARK_READ = "chat.keryx.app.notify.MARK_READ"
        const val ACTION_GATE_CHOICE = "chat.keryx.app.notify.GATE_CHOICE"
        const val ACTION_GATE_REPLY = "chat.keryx.app.notify.GATE_REPLY"
    }
}

/**
 * Sends one Gate answer over the direct door and settles the notification into the outcome.
 *
 * The socket is the interesting part: the process may have been killed since the notice was
 * posted, so the WS is reconnected and waited for rather than assumed. A gateway that cannot be
 * reached is a RETRY, not a failure — the request is still waiting on the other side, and the
 * one thing this must never do is report an answer that never arrived.
 */
class GateAnswerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? KeryxApp ?: return Result.failure()
        val direct = app.transport as? chat.keryx.app.transport.direct.DirectTransport
            ?: return Result.failure()
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val name = inputData.getString(KEY_SESSION_NAME) ?: "Keryx"
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val answer = inputData.getString(KEY_ANSWER) ?: return Result.failure()

        direct.connectIfConfigured()
        if (!direct.awaitConnected(CONNECT_WAIT_MS)) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            KeryxNotifications.notifyGateResult(
                applicationContext, sessionId, name, "⚠️ Couldn't reach the gateway — tap to answer",
            )
            return Result.failure()
        }

        val outcome: kotlin.Result<String> =
            if (kind == KeryxNotifications.GATE_KIND_APPROVAL) {
                direct.respondApproval(sessionId, answer).map { resolved ->
                    // resolved=0 means the wait had already failed closed. Saying "approved"
                    // there would be a lie about a command that is not going to run.
                    if (resolved) "Answered — the agent is running again"
                    else "That request had already timed out"
                }
            } else {
                val blockingKind = chat.keryx.core.model.BlockingKind.entries.firstOrNull { it.name == kind }
                val requestId = inputData.getString(KEY_REQUEST)
                if (blockingKind == null || requestId.isNullOrBlank()) return Result.failure()
                direct.respondBlocking(sessionId, requestId, blockingKind, answer)
                    .map { "Answered — the agent is running again" }
            }

        return outcome.fold(
            onSuccess = { text ->
                // Usually cancelled a moment later by the Gate watcher, when the request leaves
                // the pending map — which is the real evidence it landed.
                KeryxNotifications.notifyGateResult(applicationContext, sessionId, name, text)
                Result.success()
            },
            onFailure = { err ->
                android.util.Log.w("KeryxGate", "answer failed for $sessionId", err)
                if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
                KeryxNotifications.notifyGateResult(
                    applicationContext, sessionId, name, "⚠️ Couldn't answer — tap to open",
                )
                Result.failure()
            },
        )
    }

    companion object {
        const val KEY_SESSION = "sessionId"
        const val KEY_SESSION_NAME = "sessionName"
        const val KEY_KIND = "kind"
        const val KEY_REQUEST = "requestId"
        const val KEY_ANSWER = "answer"
        private const val CONNECT_WAIT_MS = 12_000L
        private const val MAX_ATTEMPTS = 2
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

        val sent = try {
            runCatching {
                app.matrixService.restore(allowInsecure = app.settingsRepository.allowInsecure)
                // restore() hands back a warm client without restarting a parked sync loop —
                // wake it so the outbox actually flushes, then park it again below.
                app.matrixService.syncWake()
                app.transport.sendMessage(roomId, text)
            }
        } finally {
            if (!app.isForeground) app.matrixService.syncStandby(SYNC_JOB_LINGER_MS)
        }
        return if (sent.isSuccess) {
            KeryxNotifications.notifyActionResult(applicationContext, roomId, roomName, text, mine = true)
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
