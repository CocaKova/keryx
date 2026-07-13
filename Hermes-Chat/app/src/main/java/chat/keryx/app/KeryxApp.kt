package chat.keryx.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.data.repository.ChatRepositoryImpl
import chat.keryx.app.data.repository.SettingsRepositoryImpl
import chat.keryx.app.domain.model.MediaKind
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.notify.KeryxNotifications
import chat.keryx.app.presentation.ui.components.MessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide singletons. The Matrix client MUST live here, not in MainActivity: the activity is
 * recreated on every configuration change (theme/accent toggle, rotation, …) and a per-activity
 * client meant a new MatrixClient — and a new sync loop — was started on the SAME database each
 * time. Multiple Trixnity clients sharing one store fight over the sync token and corrupt it, which
 * showed up as messages silently going missing / not arriving. One client, restored once, fixes it.
 */
class KeryxApp : Application() {

    lateinit var settingsRepository: SettingsRepositoryImpl
        private set
    lateinit var matrixService: MatrixService
        private set
    lateinit var repository: ChatRepositoryImpl
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    // Foreground + currently-open-room tracking, so we only notify for messages the user isn't
    // already looking at. Updated by the activity lifecycle / the chat screen.
    @Volatile private var foregroundCount = 0
    val isForeground: Boolean get() = foregroundCount > 0

    @Volatile var openRoomId: String? = null

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(applicationContext)
        settingsRepository = SettingsRepositoryImpl(applicationContext)
        matrixService = MatrixService(applicationContext)
        repository = ChatRepositoryImpl(matrixService, settingsRepository)

        KeryxNotifications.ensureChannel(applicationContext)
        registerActivityLifecycleCallbacks(ForegroundTracker())

        // Restore an existing Matrix session exactly once for the whole process.
        appScope.launch {
            runCatching { matrixService.restore(allowInsecure = settingsRepository.allowInsecure) }
        }

        // Keep the UnifiedPush registration fresh across app updates / distributor restarts —
        // idempotent, and a rotated endpoint comes back through onNewEndpoint → pusher update.
        if (settingsRepository.pushEnabled) {
            runCatching { chat.keryx.app.notify.PushManager.enable(this) }
        }

        observeForNotifications()
    }

    /**
     * Watch the room list and raise a notification when a room gains a newer message than we last
     * saw — unless the user is already looking at that room in the foreground. The room list carries
     * timestamps + unread counts; we fetch just the single latest message for the body/sender.
     */
    private fun observeForNotifications() {
        appScope.launch {
            // Only notify for messages that actually arrive after we start watching. During initial
            // sync a room's timestamp jumps from empty→its real (historical) value, which otherwise
            // looks like "new activity" and fires a burst of notifications for OLD messages on launch.
            val watchStart = System.currentTimeMillis()
            val historyGrace = 15_000L
            var baseline: Map<String, Long>? = null
            repository.getRooms().collect { rooms ->
                val current = rooms.associate { it.id to it.timestamp }
                val prev = baseline
                if (prev == null) {
                    // First emission after launch is the existing state — don't notify for history.
                    baseline = current
                    return@collect
                }
                for (room in rooms) {
                    val before = prev[room.id] ?: 0L
                    // A newer last-event timestamp means new activity in the room. (We don't gate on
                    // unreadCount — Trixnity's unread accounting proved unreliable here and was
                    // swallowing every notification.) Own messages + the room you're actively viewing
                    // are filtered below.
                    if (room.timestamp <= before) continue
                    if (isForeground && openRoomId == room.id) {
                        android.util.Log.i("KeryxNotify", "skip ${room.id}: foreground & open")
                        continue
                    }
                    val last = withTimeoutOrNull(4_000L) {
                        repository.getMessages(room.id, 1).first { it.isNotEmpty() }.lastOrNull()
                    }
                    if (last == null) {
                        android.util.Log.w("KeryxNotify", "no last message resolved for ${room.id}")
                        continue
                    }
                    if (last.sender == SenderType.ME) continue
                    // Skip historical messages surfacing during initial sync settle.
                    if (last.timestamp < watchStart - historyGrace) {
                        android.util.Log.i("KeryxNotify", "skip ${room.id}: historical (${last.timestamp} < $watchStart)")
                        continue
                    }
                    android.util.Log.i("KeryxNotify", "new activity in ${room.id} (${room.name}); notifying")
                    KeryxNotifications.notifyMessage(
                        context = applicationContext,
                        roomId = room.id,
                        title = room.name,
                        body = notificationSnippet(last),
                        quickActions = quickActionsFor(last),
                    )
                }
                baseline = current
            }
        }
    }

    private fun notificationSnippet(m: Message): String = when {
        m.mediaKind == MediaKind.IMAGE -> "🖼 Photo"
        m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "Attachment" }}"
        // extractKeryx first: raw ⟦…⟧ markers (ask options, citations) must never show in a banner.
        m.content.isNotBlank() ->
            MessageParser.extractKeryx(m.content).text
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: "New message"
        else -> "New message"
    }

    /** ⟦keryx:ask⟧ decision options for a notification — agent messages only: a human quoting the
     *  marker must not sprout buttons (same sender gate the chat render applies via agentChrome). */
    private fun quickActionsFor(m: Message): List<String> =
        if (m.sender == SenderType.HERMES) MessageParser.quickActions(m.content) else emptyList()

    /** Counts started activities so [isForeground] reflects whether Keryx is on screen. */
    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { foregroundCount++ }
        override fun onActivityStopped(activity: Activity) { foregroundCount = (foregroundCount - 1).coerceAtLeast(0) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
