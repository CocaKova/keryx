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
import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * The mission watcher (1.6 Phase E): an opt-in periodic poll of the gateway's kanban event feed
 * (`GET /keryx/kanban/events?since=<cursor>`) that notifies when a mission completes, blocks, or
 * gives up — "hand SILAS a mission from the couch" only pays off if the couch hears back.
 *
 * Since 1.7 this is the COARSE FALLBACK, not the primary path: per-mission notify subscriptions
 * (the detail sheet's "Alert when this ends" toggle) make the gateway push a real Matrix message
 * into the subscribed room the moment a task ends — instant, no polling. This worker stays for
 * what subscriptions don't cover: missions nobody subscribed, non-terminal events, and gateways
 * with the kanban notifier loop disabled.
 *
 * 2.14.1: no longer the only ear. While Keryx is on screen the in-process pulse runs the very
 * same check ([checkAndNotify]) every minute, so a mission that ends while you are in another
 * chat rings within a minute instead of within fifteen; this worker covers the app being away.
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
        return if (checkAndNotify(applicationContext, client, settings)) Result.success() else Result.retry()
    }

    companion object {
        /**
         * One pass over the event feed, shared by BOTH watchers (2.14.1): this worker at its
         * 15-minute floor, and the in-process pulse ([chat.keryx.app.presentation.MissionsDelegate.pulse])
         * that polls every minute while Keryx is on screen. One function so there is one
         * policy — which kinds ring, how a burst is capped, how the first check baselines, when
         * to stay quiet — and one cursor both advance. The lock serializes them inside the
         * process (the worker runs in it too), so a check that overlaps the other's can never
         * read the same cursor and ring the same completion twice.
         *
         * Returns false when the feed was unreachable (the worker retries; the pulse just waits
         * for its next beat). The cursor only ever moves forward.
         */
        suspend fun checkAndNotify(
            context: Context,
            client: HermesStreamClient,
            settings: SettingsRepository,
        ): Boolean = checkLock.withLock {
            if (!settings.missionAlertsEnabled) return@withLock true
            val since = settings.missionEventsCursor
            if (since < 0) {
                // First run after enabling: walk to the feed's head WITHOUT notifying, so history
                // (143 events deep already on a busy board) never lands as an alert storm.
                var cursor = 0L
                while (true) {
                    val page = client.kanbanEvents(cursor).getOrElse { return@withLock false }
                    cursor = maxOf(cursor, page.cursor)
                    if (page.events.size < FULL_PAGE) break
                }
                settings.missionEventsCursor = cursor
                return@withLock true
            }

            val page = client.kanbanEvents(since).getOrElse { return@withLock false }
            for (event in alertsToRing(page.events, boardOnScreen)) {
                val task = client.kanbanTask(event.taskId).getOrNull()?.task
                val title = task?.title?.takeIf { it.isNotBlank() } ?: event.taskId
                KeryxNotifications.notifyMission(context, event.taskId, title, alertLine(event, task))
            }
            settings.missionEventsCursor = maxOf(since, page.cursor)
            true
        }

        private val checkLock = Mutex()

        /**
         * The Missions board is on screen right now (composed AND resumed) — the board already
         * shows every card move, so a shade alert for one would be the phone telling you what
         * you are looking at. Process-wide because the worker has no ViewModel to ask; the
         * board's own RESUMED loop sets it and clears it in a finally, so a backgrounded or
         * closed board can never leave it stuck on.
         */
        @Volatile
        var boardOnScreen: Boolean = false

        /**
         * Which events in a page ring the phone: terminal kinds only, the freshest
         * [MAX_ALERTS_PER_CHECK] of a burst (a swarm finishing 20 tasks tells its story in the
         * newest few; the board has the rest), and none at all while the board is on screen —
         * those are still consumed (the cursor moves past them), never saved up for later.
         */
        internal fun alertsToRing(
            events: List<HermesStreamClient.KanbanEvent>,
            boardOnScreen: Boolean,
        ): List<HermesStreamClient.KanbanEvent> =
            if (boardOnScreen) emptyList()
            else events.filter { it.kind in ALERT_KINDS }.takeLast(MAX_ALERTS_PER_CHECK)

        /**
         * The shade's one line, in the worker's own words where it left any: a block says WHY
         * ("needs your yes: publish …") instead of "it needs something from you", which sent
         * Jonny to a terminal to find out. The event's own payload first (it is what fired),
         * then the card's current ask, then the fixed fallback. A block that clears itself
         * (waiting on a run, a parent) says so, so it never reads as a summons.
         */
        internal fun alertLine(
            event: HermesStreamClient.KanbanEvent,
            task: HermesStreamClient.KanbanTask?,
        ): String {
            val words = event.detail.ifBlank {
                if (event.kind == "blocked") task?.ask.orEmpty() else task?.latestSummary.orEmpty()
            }.trim().replace(Regex("\\s+"), " ")
            val selfClearing = task?.blockKind in setOf("dependency", "transient")
            val head = when (event.kind) {
                "completed" -> "Mission complete"
                "blocked" -> if (selfClearing) "Mission paused" else "Mission blocked — needs you"
                else -> "Mission gave up"
            }
            if (words.isEmpty()) {
                return if (event.kind == "blocked" && !selfClearing) "Mission blocked — it needs something from you" else head
            }
            return "$head: ${words.take(ALERT_WORDS)}${if (words.length > ALERT_WORDS) "…" else ""}"
        }

        private const val ALERT_WORDS = 180
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
