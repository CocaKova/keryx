package chat.keryx.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import chat.keryx.app.MainActivity
import chat.keryx.app.R

/**
 * Lightweight local notifications for incoming chat messages. These fire from the in-process Matrix
 * sync (which keeps running while the app is alive / battery-exempt) — not push — so they appear for
 * messages that land while you're in another room or have the app backgrounded. Tapping one opens
 * Keryx straight to that room.
 */
object KeryxNotifications {

    const val CHANNEL_ID = "keryx_messages"
    const val EXTRA_ROOM_ID = "keryx.roomId"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New messages from your Hermes agents and rooms"
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /** Post (or update) a notification for [roomId]; one per room, replaced as newer messages land. */
    fun notifyMessage(context: Context, roomId: String, title: String, body: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            android.util.Log.w("KeryxNotify", "notifications disabled at OS level; skipping $roomId")
            return
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        val pending = PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .build()

        // Stable id per room so a room's notification updates in place instead of stacking.
        runCatching { nm.notify(roomId.hashCode(), notification) }
            .onSuccess { android.util.Log.i("KeryxNotify", "posted for $roomId: $title — $body") }
            .onFailure { android.util.Log.w("KeryxNotify", "notify failed for $roomId: ${it.message}") }
    }

    fun clear(context: Context, roomId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(roomId.hashCode()) }
    }
}
