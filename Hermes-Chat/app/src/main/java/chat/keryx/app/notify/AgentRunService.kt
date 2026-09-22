package chat.keryx.app.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.keryx.core.model.RunNotice

/**
 * The run notice's home: a foreground service that lives exactly as long as a turn is in flight.
 *
 * Two jobs, and the second is why this is a service and not just a notification. The first is
 * the notice itself — one silent line saying who is working and on what, in place of an alert
 * per event. The second is the keep-alive `DirectTransport.anyAgentBusy` was always meant to
 * drive and never did: a backgrounded process is frozen within a minute or so, its socket goes
 * quiet, and the turn's answer — the one alert that matters — arrives whenever the phone is
 * next unlocked. While this runs, the process and its socket stay up, and the answer lands
 * when it is said.
 *
 * It holds no state of its own: [sync] is handed each decided [RunNotice] (or null) by the app's
 * watcher, and this draws it. A start the system refuses (a turn begun elsewhere while Keryx
 * is in the background — Android 12+ allows no foreground-service start from there) falls
 * back to the same notice posted plainly: still one quiet line, without the keep-alive.
 */
class AgentRunService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        val notice = current
        // startForeground is owed within seconds of the start whatever has happened since, so a
        // run that already ended still gets its moment — and is then taken straight down.
        val shown = notice ?: RunNotice(
            title = "Keryx", text = "Finishing…", lines = emptyList(),
            sessionId = null, colorKey = null, startedAt = System.currentTimeMillis(),
        )
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, KeryxNotifications.RUN_NOTIFICATION_ID, KeryxNotifications.buildRun(this, shown), type)
        }.onFailure {
            android.util.Log.w("KeryxRun", "startForeground refused: ${it.message}")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (notice == null) stop()
        // Not sticky: a run is owned by a live socket, and a process the system restarts cold has
        // neither — the watcher starts this again the moment a turn is seen.
        return START_NOT_STICKY
    }

    private fun stop() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: RunNotice? = null
        @Volatile private var running = false

        /** Asked to start, not yet in onStartCommand. */
        @Volatile private var starting = false

        /** Draw [notice], or take the run notice down on null. Safe from any thread, any state. */
        fun sync(context: Context, notice: RunNotice?) {
            val app = context.applicationContext
            current = notice
            // The home-screen widget (2.13) follows the run: it floors its own repaints, so a
            // burst of notices costs one draw, and a run's end is never the one it drops.
            chat.keryx.app.widget.KeryxWidget.refresh(app)
            val nm = NotificationManagerCompat.from(app)
            if (notice == null) {
                // ⚠️ Never stopService between startForegroundService and startForeground: the
                // system kills the whole app for the broken promise. A start still in the air
                // finds `current` null when it lands and takes itself down.
                if (starting) return
                app.stopService(Intent(app, AgentRunService::class.java))
                running = false
                runCatching { nm.cancel(KeryxNotifications.RUN_NOTIFICATION_ID) }
                return
            }
            if (starting) return // the start in the air draws `current` when it lands
            if (!running) {
                starting = true
                val started = runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, AgentRunService::class.java))
                }
                if (started.isSuccess) return // onStartCommand draws `current`
                starting = false
                android.util.Log.i("KeryxRun", "no foreground start from here (${started.exceptionOrNull()?.javaClass?.simpleName}); plain notice")
            }
            if (!nm.areNotificationsEnabled()) return
            runCatching { nm.notify(KeryxNotifications.RUN_NOTIFICATION_ID, KeryxNotifications.buildRun(app, notice)) }
        }
    }
}
