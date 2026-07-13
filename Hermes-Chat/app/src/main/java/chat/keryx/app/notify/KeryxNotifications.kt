package chat.keryx.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import chat.keryx.app.MainActivity
import chat.keryx.app.R

/**
 * Lightweight local notifications for incoming chat messages. These fire from the in-process Matrix
 * sync (which keeps running while the app is alive / battery-exempt) — not push — so they appear for
 * messages that land while you're in another room or have the app backgrounded. Tapping one opens
 * Keryx straight to that room.
 *
 * Every message notification is actionable: an inline **Reply** (RemoteInput — answer from the
 * lock screen without opening the app), plus one button per ⟦keryx:ask⟧ option when the agent is
 * blocking on a decision. Actions dispatch through [NotificationActionReceiver].
 */
object KeryxNotifications {

    const val CHANNEL_ID = "keryx_messages"
    const val EXTRA_ROOM_ID = "keryx.roomId"
    const val EXTRA_ROOM_NAME = "keryx.roomName"
    const val EXTRA_QUICK_TEXT = "keryx.quickText"
    const val KEY_REMOTE_REPLY = "keryx.remoteReply"

    /** Android renders at most 3 action buttons; one is always Reply. */
    private const val MAX_NOTIFICATION_OPTIONS = 2

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

    /** Post (or update) a notification for [roomId]; one per room, replaced as newer messages land.
     *  [quickActions] adds a one-tap button per agent-offered option (⟦keryx:ask⟧). */
    fun notifyMessage(
        context: Context,
        roomId: String,
        title: String,
        body: String,
        quickActions: List<String> = emptyList(),
    ) {
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)

        // Decision buttons first (they're the point when present), then the universal Reply.
        quickActions.take(MAX_NOTIFICATION_OPTIONS).forEach { option ->
            val quick = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_QUICK
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, title)
                putExtra(EXTRA_QUICK_TEXT, option)
            }
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_keryx,
                    option,
                    PendingIntent.getBroadcast(
                        context,
                        "qa:$roomId:$option".hashCode(),
                        quick,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).build(),
            )
        }
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_ROOM_NAME, title)
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_stat_keryx,
                "Reply",
                // FLAG_MUTABLE: the system must write the RemoteInput result into this intent.
                PendingIntent.getBroadcast(
                    context,
                    "reply:$roomId".hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
                .addRemoteInput(RemoteInput.Builder(KEY_REMOTE_REPLY).setLabel("Reply").build())
                .setAllowGeneratedReplies(false)
                .build(),
        )

        // Stable id per room so a room's notification updates in place instead of stacking.
        runCatching { nm.notify(roomId.hashCode(), builder.build()) }
            .onSuccess { android.util.Log.i("KeryxNotify", "posted for $roomId: $title — $body") }
            .onFailure { android.util.Log.w("KeryxNotify", "notify failed for $roomId: ${it.message}") }
    }

    /**
     * Repost a room's notification after a notification-action send. Required, not cosmetic: once a
     * RemoteInput action fires, the system pins a spinner on the notification until it is updated
     * with the same id. Quiet (no re-alert); the agent's next message replaces it as usual.
     */
    fun notifyActionResult(context: Context, roomId: String, title: String, body: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, roomId.hashCode(), tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { nm.notify(roomId.hashCode(), notification) }
    }

    fun clear(context: Context, roomId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(roomId.hashCode()) }
    }

    // --- Mission alerts (kanban watcher) --------------------------------------------------------

    const val MISSIONS_CHANNEL_ID = "keryx_missions"

    fun ensureMissionsChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(MISSIONS_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            MISSIONS_CHANNEL_ID,
            "Missions",
            // Default, not high: a finished background task is news, not an interruption.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Missions completing, blocking, or giving up on the agent's board"
        }
        mgr.createNotificationChannel(channel)
    }

    /** Post a mission-transition alert; one per task, replaced as the task moves again. */
    fun notifyMission(context: Context, taskId: String, title: String, body: String) {
        ensureMissionsChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MISSIONS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending)
            .build()
        // Offset from the message-notification id space so a task never clobbers a room.
        runCatching { nm.notify("mission:$taskId".hashCode(), notification) }
    }
}
