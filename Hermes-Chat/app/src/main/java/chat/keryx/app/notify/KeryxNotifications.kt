package chat.keryx.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import chat.keryx.app.MainActivity
import chat.keryx.app.R
import chat.keryx.core.model.AgentNotice
import chat.keryx.core.model.Heralds

/**
 * Local notifications for messages that land while you are elsewhere (in another room, or
 * with the app backgrounded). Tapping one opens Keryx straight to that conversation.
 *
 * 2.8 — oriented for an AGENT sending messages. A notification is a conversation now, not
 * a room alert: the speaker is a [Person] (the bot, in its own light; a relayed bot-to-bot
 * message names the bot that sent it), successive lines stack in one conversation instead
 * of replacing each other, the conversation title says whose chat it is, and every one of
 * them is grouped under Keryx so the shade shows one summary instead of a pile. Actions:
 * an inline **Reply** (answer from the lock screen), one button per ⟦keryx:ask⟧ option when
 * the agent is blocking on a decision, and **Mark read** on the direct door (the gateway's
 * own watermark). Actions dispatch through [NotificationActionReceiver].
 */
object KeryxNotifications {

    const val CHANNEL_ID = "keryx_messages"
    const val GROUP_KEY = "keryx.messages"
    private const val SUMMARY_ID = 0x4B53 // "KS"
    const val EXTRA_ROOM_ID = "keryx.roomId"
    const val EXTRA_ROOM_NAME = "keryx.roomName"
    const val EXTRA_QUICK_TEXT = "keryx.quickText"
    const val KEY_REMOTE_REPLY = "keryx.remoteReply"

    /** Android renders at most 3 action buttons; one is always Reply. */
    private const val MAX_NOTIFICATION_OPTIONS = 2

    /** How many lines one conversation's notification keeps stacked. */
    private const val HISTORY_MAX = 6

    /** The lines shown per conversation, newest last — what MessagingStyle stacks. */
    private val history = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    fun ensureChannel(context: Context) {
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

    /** The reader — "you" in the conversation, so replies you send from the shade file right. */
    private val me: Person = Person.Builder().setName("You").setKey("me").build()

    /**
     * Post (or extend) the notification for [roomId] with [notice]: one conversation per
     * room, lines stacking as they land. [quickActions] adds a one-tap button per agent-
     * offered option (⟦keryx:ask⟧); [markReadable] offers the gateway read mark.
     */
    fun notifyMessage(
        context: Context,
        roomId: String,
        notice: AgentNotice,
        quickActions: List<String> = emptyList(),
        markReadable: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
        /** ⟦keryx:do⟧ actions (2.8.1 hands): a button per Intent-shaped action when no decision
         *  is pending — "Navigate" from the lock screen, with the tap as the consent. */
        hands: List<chat.keryx.core.model.PhoneAction> = emptyList(),
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) {
            android.util.Log.w("KeryxNotify", "notifications disabled at OS level; skipping $roomId")
            return
        }

        val speaker = personFor(context, notice)
        val line = NotificationCompat.MessagingStyle.Message(notice.line, timestamp, speaker)
        val lines = history.getOrPut(roomId) { ArrayDeque() }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > HISTORY_MAX) lines.removeFirst()
        }
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(notice.conversation)
            // A relayed line is a group of voices by definition; a bot's own chat is a 1:1,
            // which the system draws as a plain conversation with the bot's face.
            .setGroupConversation(notice.relayed || synchronized(lines) { lines.map { it.person?.key }.distinct().size > 1 })
        synchronized(lines) { lines.forEach(style::addMessage) }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(notice.title)
            .setContentText(notice.line)
            .setStyle(style)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setShortcutId(roomId)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setContentIntent(tapIntent(context, roomId))

        // Decision buttons first (they're the point when present), then the universal Reply.
        quickActions.take(MAX_NOTIFICATION_OPTIONS).forEach { option ->
            val quick = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_QUICK
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, notice.conversation)
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
        // Hands next (2.8.1), only when no decision is pending: a decision is the point of its
        // notification, and three buttons is the shade's honest cap.
        if (quickActions.isEmpty()) hands.mapNotNull { a ->
            chat.keryx.app.hands.PhoneHands.intentFor(context, a)?.takeIf { it.component != null || a.kind != chat.keryx.core.model.PhoneAction.Kind.OPEN }?.let { a to it }
        }.take(MAX_NOTIFICATION_OPTIONS).forEach { (a, intent) ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_keryx,
                    a.label,
                    PendingIntent.getActivity(
                        context,
                        "hand:$roomId:${a.kind.token}:${a.primary}".hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setShowsUserInterface(true).build(),
            )
        }
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_ROOM_NAME, notice.conversation)
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
                .addRemoteInput(RemoteInput.Builder(KEY_REMOTE_REPLY).setLabel("Reply to ${notice.speaker}").build())
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build(),
        )
        if (markReadable && quickActions.isEmpty()) {
            val readIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_READ
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_NAME, notice.conversation)
            }
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_keryx,
                    "Mark read",
                    PendingIntent.getBroadcast(
                        context,
                        "read:$roomId".hashCode(),
                        readIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }

        // Stable id per room so a room's notification updates in place instead of stacking.
        runCatching { nm.notify(roomId.hashCode(), builder.build()) }
            .onSuccess { android.util.Log.i("KeryxNotify", "posted for $roomId: ${notice.title} — ${notice.line}") }
            .onFailure { android.util.Log.w("KeryxNotify", "notify failed for $roomId: ${it.message}") }
        postSummary(context, nm)
    }

    /** One summary under every conversation, so the shade collapses them into Keryx. */
    private fun postSummary(context: Context, nm: NotificationManagerCompat) {
        val conversations = history.size
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle("Keryx")
            .setContentText(if (conversations == 1) "1 conversation" else "$conversations conversations")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, SUMMARY_ID, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { nm.notify(SUMMARY_ID, summary) }
    }

    private fun tapIntent(context: Context, roomId: String): PendingIntent {
        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        return PendingIntent.getActivity(
            context,
            roomId.hashCode(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The speaker as a Person: name, a stable key, and the sigil in that agent's light. */
    private fun personFor(context: Context, notice: AgentNotice): Person =
        Person.Builder()
            .setName(notice.speaker)
            .setKey(notice.speakerKey)
            .setBot(true)
            .setIcon(sigilIcon(context, notice.speakerKey, notice.speaker))
            .build()

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, IconCompat>()

    /**
     * The herald's staff on a disc of its own colour — the same palette slot the transcript
     * gives this sender (stable hash of its key), so the shade and the chat agree on who is
     * who. Drawn once per speaker per process.
     */
    private fun sigilIcon(context: Context, key: String, name: String): IconCompat =
        iconCache.getOrPut(key) {
            val density = context.resources.displayMetrics.density
            val px = (48 * density).toInt().coerceAtLeast(48)
            // "bot:theo" / "agent:juno" hash as "theo" / "juno" — the roster's own key — so a
            // bot wears one colour in the shade and in the app.
            val bare = key.substringAfter(':', key).ifBlank { name }
            val slot = Math.floorMod(Heralds.stableHash(bare), Heralds.PALETTE.size)
            val (accent, accent2) = Heralds.PALETTE[slot]
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2.toInt() }
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, disc)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent.toInt(); style = Paint.Style.STROKE; strokeWidth = px * 0.06f
            }
            canvas.drawCircle(px / 2f, px / 2f, px / 2f - ring.strokeWidth / 2f, ring)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent.toInt(); textSize = px * 0.58f; textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val y = px / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(Heralds.SIGIL, px / 2f, y, text)
            IconCompat.createWithBitmap(bmp)
        }

    /**
     * Repost a room's notification after a notification-action send. Required, not cosmetic: once a
     * RemoteInput action fires, the system pins a spinner on the notification until it is updated
     * with the same id. Quiet (no re-alert); the agent's next message replaces it as usual. The
     * line you sent joins the conversation as you, so the stack reads as the exchange it is.
     */
    fun notifyActionResult(context: Context, roomId: String, title: String, body: String, mine: Boolean = false) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val lines = history.getOrPut(roomId) { ArrayDeque() }
        if (mine) synchronized(lines) {
            lines.addLast(NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), me))
            while (lines.size > HISTORY_MAX) lines.removeFirst()
        }
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(title)
        synchronized(lines) { lines.forEach(style::addMessage) }
        if (!mine) style.addMessage(NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), null as Person?))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(style)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP_KEY)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(tapIntent(context, roomId))
            .build()
        runCatching { nm.notify(roomId.hashCode(), notification) }
    }

    fun clear(context: Context, roomId: String) {
        history.remove(roomId)
        runCatching { NotificationManagerCompat.from(context).cancel(roomId.hashCode()) }
        if (history.isEmpty()) runCatching { NotificationManagerCompat.from(context).cancel(SUMMARY_ID) }
    }

    // --- Mission alerts (kanban watcher) --------------------------------------------------------

    // --- The Gate: a stopped agent, answerable from the shade ---------------------------------
    //
    // The decision half of this has been in :core (ShadeNotices) since G16, unit-tested, with
    // nothing on Android reading it. Until now an approval or a question raised while you were
    // in another session — or not in the app at all — made no sound whatsoever: the gateway
    // waited out `approvals.timeout` and failed CLOSED while the phone sat silent, and the only
    // evidence was a turn that had quietly refused to happen.
    //
    // Its own channel, not the messages one: this is the agent stopped and waiting on you, it
    // wants to be able to interrupt, and it must be silenceable on its own if it ever gets noisy.

    const val GATE_CHANNEL_ID = "keryx_gate"
    const val EXTRA_GATE_SESSION = "keryx.gate.session"
    const val EXTRA_GATE_KIND = "keryx.gate.kind"
    const val EXTRA_GATE_REQUEST = "keryx.gate.request"
    const val EXTRA_GATE_VALUE = "keryx.gate.value"
    const val KEY_GATE_REPLY = "keryx.gate.reply"

    /** "approval" or a [chat.keryx.core.model.BlockingKind] name — which respond call answers it. */
    const val GATE_KIND_APPROVAL = "approval"

    // A separate id space from the message notification for the same session: the Gate and the
    // conversation are two different things to be told, and one must never replace the other.
    private const val GATE_ID_SALT = 0x6A7E

    fun gateId(sessionId: String): Int = sessionId.hashCode() xor GATE_ID_SALT

    fun ensureGateChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(GATE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            GATE_CHANNEL_ID,
            "Approvals & questions",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "An agent is stopped, waiting on your answer"
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Post the shade's view of a stopped agent. [notice] is rendered VERBATIM — which buttons
     * exist, whether free text is offered, and how long the notice stays honest are decisions
     * [chat.keryx.core.model.ShadeNotices] already made and tested; nothing here second-guesses
     * them. In particular the absence of buttons on a sudo/secret notice is a rule, not an
     * oversight: a credential typed into a lock-screen RemoteInput is a password echoed onto
     * the most shoulder-surfable surface the OS has, so those are tap-through to the masked
     * in-app field.
     */
    fun notifyGate(
        context: Context,
        sessionId: String,
        sessionName: String,
        notice: chat.keryx.core.model.ShadeNotice,
        kind: String,
        requestId: String?,
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureGateChannel(context)

        fun gateIntent(action: String, value: String?): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_GATE_SESSION, sessionId)
                putExtra(EXTRA_GATE_KIND, kind)
                putExtra(EXTRA_GATE_REQUEST, requestId)
                putExtra(EXTRA_ROOM_NAME, sessionName)
                value?.let { putExtra(EXTRA_GATE_VALUE, it) }
            }
            return PendingIntent.getBroadcast(
                context,
                // Distinct per (session, value) or the system reuses one extras bundle for
                // every button and each of them answers whatever the first one said.
                (sessionId + "|" + action + "|" + (value ?: "")).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, GATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body).setSummaryText(sessionName))
            .setSubText(sessionName)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            // A notice must die honestly: the gateway fails closed when its wait runs out, and a
            // button that outlives the wait resolves nothing. The system drops the whole notice.
            .setTimeoutAfter(notice.timeoutMs)

        notice.actions.forEach { act ->
            builder.addAction(
                NotificationCompat.Action.Builder(0, act.label, gateIntent(NotificationActionReceiver.ACTION_GATE_CHOICE, act.wireValue))
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
        if (notice.freeTextReply) {
            val input = RemoteInput.Builder(KEY_GATE_REPLY).setLabel("Answer").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Answer", gateIntent(NotificationActionReceiver.ACTION_GATE_REPLY, null))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )
        }
        runCatching { nm.notify(gateId(sessionId), builder.build()) }
    }

    /**
     * Settle the Gate notification into the outcome of an answer sent from the shade. Required,
     * not cosmetic: a fired RemoteInput pins a spinner on the notification until it is updated
     * under the SAME id, so this reuses [gateId]. On success the watcher usually cancels this a
     * moment later (the request left the pending map, which is the real "it landed"); on failure
     * nothing clears it, which is exactly where it should stay.
     */
    fun notifyGateResult(context: Context, sessionId: String, sessionName: String, text: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureGateChannel(context)
        val n = NotificationCompat.Builder(context, GATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(text)
            .setSubText(sessionName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, sessionId))
            .build()
        runCatching { nm.notify(gateId(sessionId), n) }
    }

    /** The request is answered, expired, or now on screen — the shade lets it go. */
    fun clearGate(context: Context, sessionId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(gateId(sessionId)) }
    }

    const val MISSIONS_CHANNEL_ID = "keryx_missions"

    fun ensureMissionsChannel(context: Context) {
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
