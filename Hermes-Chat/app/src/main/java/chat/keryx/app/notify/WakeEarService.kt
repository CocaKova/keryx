package chat.keryx.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.keryx.app.MainActivity
import chat.keryx.app.R
import chat.keryx.app.KeryxApp

/**
 * The ear's body: a MICROPHONE-type foreground service that exists so the open mic (and the
 * socket it feeds) survive the screen going dark. Deliberately dumb like [TurnLinkService] —
 * no audio work happens here; [chat.keryx.app.audio.WakeWordController] owns the
 * feeder and tells this service to come up, update its one-liner, or go away.
 *
 * Android facts this encodes: since 12 a foreground service may only START while the app is
 * visible (and since 14 only a mic-type one may keep recording once it isn't), so [start]
 * refuses from the background instead of throwing; the ongoing notification is the honest price and
 * doubles as desktop's wake-indicator (parity §H) with a "Stop listening" action.
 *
 * It also owns the "heard you" notification: a full-screen intent that lights the screen and
 * brings the Call up when the phrase fires with Keryx in the background — the whole point
 * of a wake word on a phone lying on the desk.
 */
class WakeEarService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            (application as? KeryxApp)?.wakeWord?.setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        // The controller may have moved on (feeder already up) before this command lands —
        // the latest desired state wins over the intent's snapshot.
        val phrase = desired?.first ?: intent?.getStringExtra(EXTRA_PHRASE) ?: "hey hermes"
        val listening = desired?.second ?: false
        val detail = desired?.third
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildOngoing(this, phrase, listening, detail), type)
            running = true
        }.onFailure {
            android.util.Log.w("KeryxWake", "ear service refused foreground: ${it.message}")
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "keryx_wake_ear"
        private const val HEARD_CHANNEL_ID = "keryx_wake_heard"
        private const val NOTIF_ID = 0x4541 // "EA"
        private const val HEARD_NOTIF_ID = 0x4548 // "EH"
        private const val EXTRA_PHRASE = "keryx.wakePhrase"
        private const val ACTION_STOP = "chat.keryx.app.wake.STOP"
        /** MainActivity: the launch is a wake summon — light the screen, show over the lock. */
        const val EXTRA_WAKE_SUMMON = "keryx.wakeSummon"

        @Volatile var running: Boolean = false
            private set
        /** Latest (phrase, listening) the controller asked us to show. */
        @Volatile private var desired: Triple<String, Boolean, String?>? = null

        /**
         * Bring the service up (or refresh it). False when Android would refuse: on 14+ a
         * mic service cannot start from the background — the controller then waits for the
         * app to become visible ([chat.keryx.app.audio.WakeWordController.appVisible]).
         */
        fun start(context: Context, phrase: String): Boolean {
            if (running) return true
            desired = Triple(phrase, false, null)
            val app = context.applicationContext as? KeryxApp
            // Android 12+: a foreground service may not START from the background (14+ adds
            // that a mic-type one cannot record unless started visible). Refuse, don't throw.
            if (Build.VERSION.SDK_INT >= 31 && app != null && !app.isForeground) return false
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WakeEarService::class.java).putExtra(EXTRA_PHRASE, phrase),
                )
                true
            }.getOrElse {
                android.util.Log.w("KeryxWake", "ear service start refused: ${it.message}")
                false
            }
        }

        /** Refresh the ongoing one-liner without touching startForeground. */
        /** Refresh the ongoing one-liner. [detail] = why it is not listening (resting reason). */
        fun update(context: Context, phrase: String, listening: Boolean, detail: String? = null) {
            desired = Triple(phrase, listening, detail)
            if (!running) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching { mgr.notify(NOTIF_ID, buildOngoing(context, phrase, listening, detail)) }
        }

        fun stop(context: Context) {
            running = false
            desired = null
            runCatching { context.stopService(Intent(context, WakeEarService::class.java)) }
            clearHeard(context)
        }

        /** The phrase fired while Keryx was in the background: light up and open the Call. */
        fun notifyHeard(context: Context, phrase: String) {
            ensureChannels(context)
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_WAKE_SUMMON, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, HEARD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_keryx)
                .setContentTitle("Heard \"$phrase\"")
                .setContentText("Opening the call…")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setContentIntent(open)
                // Full-screen: the one sanctioned way for a background app to put a screen up
                // (and wake the display). Where the OS withholds the permission (14+ decides
                // per app), this degrades to a heads-up the user taps.
                .setFullScreenIntent(open, true)
                .build()
            runCatching { mgr.notify(HEARD_NOTIF_ID, n) }
        }

        fun clearHeard(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(HEARD_NOTIF_ID) }
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Wake word ear", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while Keryx keeps the microphone open for \"hey hermes\""
                        setShowBadge(false)
                    },
                )
            }
            if (mgr.getNotificationChannel(HEARD_CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(HEARD_CHANNEL_ID, "Wake word heard", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Opens the call when the wake phrase is heard in the background"
                        setSound(null, null) // the chime already played
                    },
                )
            }
        }

        private fun buildOngoing(context: Context, phrase: String, listening: Boolean, detail: String?): android.app.Notification {
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, WakeEarService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_keryx)
                .setContentTitle(
                    when {
                        listening -> "Listening for \"$phrase\""
                        detail != null -> "Ear ${detail.substringBefore(" —").ifBlank { "resting" }}"
                        else -> "Ear on — waiting for the gateway"
                    },
                )
                .setContentText(
                    if (listening) "Say it to start a call. Streams only while the room has sound."
                    else detail ?: "Microphone closed until the gateway is back.",
                )
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .addAction(0, "Stop listening", stop)
                .build()
        }
    }
}
