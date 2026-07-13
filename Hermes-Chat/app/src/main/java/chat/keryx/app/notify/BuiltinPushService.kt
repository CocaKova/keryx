package chat.keryx.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import chat.keryx.app.KeryxApp
import chat.keryx.app.MainActivity
import chat.keryx.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Built-in push transport: Keryx acting as its own UnifiedPush distributor, so real push never
 * requires a companion app (an installed distributor is auto-detected by [PushManager] and takes
 * over; this service then never runs).
 *
 * Mechanics: [PushManager] registers the Matrix pusher with pushkey `<ntfy>/<topic>?up=1` — the
 * exact endpoint shape a distributor would have issued for an ntfy server — so Synapse pushes
 * land on the private topic via ntfy's built-in Matrix gateway. This foreground service holds a
 * WebSocket on `<ntfy>/<topic>/ws` and feeds each pushed room into the same [PushSyncWorker]
 * funnel the distributor path uses. Payloads are `event_id_only`, so the socket never carries
 * message content — just "wake up and sync".
 *
 * Lifecycle: START_STICKY + a boot/app-update receiver; reconnects with exponential backoff and
 * snaps back instantly on network change. On reconnect it replays missed topic messages
 * (`?since=<last-id>` — ntfy caches published messages server-side), so a push that fired during
 * a dead zone still lands. The foreground notification is IMPORTANCE_MIN: a collapsed one-liner,
 * the honest Android price for a persistent socket.
 */
class BuiltinPushService : Service() {

    private lateinit var client: OkHttpClient
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var stopped = false
    private var retries = 0
    private var lastMessageId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val allowInsecure =
            (applicationContext as? KeryxApp)?.settingsRepository?.allowInsecure == true
        client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // Long-lived socket: no read timeout; pings surface a dead NAT hole instead.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(60, TimeUnit.SECONDS)
            .apply {
                if (allowInsecure) {
                    val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    })
                    val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                    sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildStatusNotification("Connecting…"), type)
        watchNetwork()
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        networkCallback?.let {
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(it)
            }
        }
        socket?.close(1000, "service stopped")
        super.onDestroy()
    }

    private fun connect() {
        if (stopped) return
        val settings = (applicationContext as? KeryxApp)?.settingsRepository ?: return stopSelf()
        val gateway = settings.pushGatewayUrl.trim().trimEnd('/')
        val topic = settings.builtinPushTopic
        if (gateway.isBlank() || topic.isBlank()) {
            android.util.Log.w("KeryxPush", "builtin: no gateway/topic configured; stopping")
            return stopSelf()
        }
        // http→ws, https→wss; ?since replays what the topic cached while we were disconnected.
        val since = lastMessageId?.let { "?since=$it" } ?: ""
        val url = gateway.replaceFirst("http", "ws") + "/$topic/ws$since"
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retries = 0
            android.util.Log.i("KeryxPush", "builtin: connected")
            updateStatusNotification("Connected — built-in push")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // ntfy frame: {"id":"…","time":…,"event":"open|keepalive|message","message":"…"}.
            val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if ((frame["event"] as? JsonPrimitive)?.content != "message") return
            (frame["id"] as? JsonPrimitive)?.content?.let { lastMessageId = it }
            val body = (frame["message"] as? JsonPrimitive)?.content ?: return
            val roomId = pushedRoomId(body)
            android.util.Log.i("KeryxPush", "builtin: push received (room=${roomId?.take(12)})")
            enqueuePushSync(applicationContext, roomId)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return // an old socket we already replaced
            android.util.Log.w("KeryxPush", "builtin: socket failed: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = (3_000L shl retries.coerceAtMost(6)).coerceAtMost(300_000L)
        retries++
        updateStatusNotification("Reconnecting…")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect() }, delay)
    }

    /** A network coming back is the moment to reconnect NOW, not at the end of a long backoff. */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                retries = 0
                handler.removeCallbacksAndMessages(null)
                handler.post { connect() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { networkCallback = cb }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Push connection",
                // MIN: lives collapsed at the bottom of the shade, no icon in the status bar.
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Keryx's own connection to your push server (no distributor app needed)" },
        )
    }

    private fun buildStatusNotification(status: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle("Keryx push")
            .setContentText(status)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun updateStatusNotification(status: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildStatusNotification(status))
        }
    }

    companion object {
        private const val CHANNEL_ID = "keryx_push_link"
        private const val NOTIF_ID = 0x4B50 // "KP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BuiltinPushService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BuiltinPushService::class.java))
        }
    }
}

/** Brings built-in push back after a reboot or an app update — a distributor app survives those
 *  on its own; without one, that job is ours. */
class BuiltinPushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (PushManager.builtinModeWanted(context)) BuiltinPushService.start(context)
            }
        }
    }
}
