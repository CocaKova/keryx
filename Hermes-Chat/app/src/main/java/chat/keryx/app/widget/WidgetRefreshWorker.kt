package chat.keryx.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The widget's slow heartbeat: a redraw every 15 minutes (WorkManager's floor), so a card left
 * alone on the home screen still says something true after a reboot, a process death, or a
 * night in Doze. The fast path is [KeryxWidget.refresh], called from the run notice as the
 * turn moves; this exists for every moment that path is not running.
 *
 * Unconstrained on purpose: the read is against the process's own state and a bounded REST
 * peek, and an OFFLINE card is a correct card — it should be drawn, not deferred until the
 * network is back.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { KeryxWidget.repaint(applicationContext) }
            .onFailure { android.util.Log.w("KeryxWidget", "periodic refresh failed: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "keryx_widget_refresh"

        /** Enqueued when the first widget is placed; KEEP so a re-place never resets the clock. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The last widget is gone: nothing left to draw for. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
