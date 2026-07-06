package chat.keryx.app.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.data.repository.SettingsRepositoryImpl
import java.util.concurrent.TimeUnit

/**
 * The mission watcher (1.6 Phase E): an opt-in periodic poll of the gateway's kanban event feed
 * (`GET /keryx/kanban/events?since=<cursor>`) that notifies when a mission completes, blocks, or
 * gives up — "hand SILAS a mission from the couch" only pays off if the couch hears back.
 *
 * WorkManager at its 15-minute floor, network-constrained: survives process death and reboots,
 * defers through Doze, and does nothing while disabled (the toggle cancels the work; the guard
 * here covers a stale enqueue racing the toggle).
 */
class MissionAlertsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepositoryImpl(applicationContext)
        if (!settings.missionAlertsEnabled || !settings.sideChannelEnabled) return Result.success()
        val url = settings.gatewayUrl.trim()
        if (url.isBlank()) return Result.success()
        val client = HermesStreamClient(url, settings.gatewayApiKey, settings.allowInsecure)

        val since = settings.missionEventsCursor
        if (since < 0) {
            // First run after enabling: walk to the feed's head WITHOUT notifying, so history
            // (143 events deep already on a busy board) never lands as an alert storm.
            var cursor = 0L
            while (true) {
                val page = client.kanbanEvents(cursor).getOrElse { return Result.retry() }
                cursor = maxOf(cursor, page.cursor)
                if (page.events.size < FULL_PAGE) break
            }
            settings.missionEventsCursor = cursor
            return Result.success()
        }

        val page = client.kanbanEvents(since).getOrElse { return Result.retry() }
        val alerts = page.events.filter { it.kind in ALERT_KINDS }
        // Cap a burst: if a swarm finished 20 tasks since the last check, the freshest few tell
        // the story — the board itself has the rest.
        for (event in alerts.takeLast(MAX_ALERTS_PER_CHECK)) {
            val title = client.kanbanTask(event.taskId).getOrNull()?.task?.title
                ?.takeIf { it.isNotBlank() } ?: event.taskId
            val line = when (event.kind) {
                "completed" -> "Mission complete"
                "blocked" -> "Mission blocked — it needs something from you"
                else -> "Mission gave up"
            }
            KeryxNotifications.notifyMission(applicationContext, event.taskId, title, line)
        }
        settings.missionEventsCursor = page.cursor
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "mission_alerts"
        private const val FULL_PAGE = 200
        private const val MAX_ALERTS_PER_CHECK = 5
        private val ALERT_KINDS = setOf("completed", "blocked", "gave_up")

        /** Schedule or cancel the watcher; call from the settings toggle with any Context. */
        fun setEnabled(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<MissionAlertsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
