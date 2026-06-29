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
        settingsRepository = SettingsRepositoryImpl(applicationContext)
        matrixService = MatrixService(applicationContext)
        repository = ChatRepositoryImpl(matrixService, settingsRepository)

        KeryxNotifications.ensureChannel(applicationContext)
        registerActivityLifecycleCallbacks(ForegroundTracker())

        // Restore an existing Matrix session exactly once for the whole process.
        appScope.launch {
            runCatching { matrixService.restore(allowInsecure = settingsRepository.allowInsecure) }
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
                    val isNew = room.timestamp > before && room.unreadCount > 0
                    if (!isNew) continue
                    // Skip what the user is already reading.
                    if (isForeground && openRoomId == room.id) continue
                    val last = withTimeoutOrNull(4_000L) {
                        repository.getMessages(room.id, 1).first { it.isNotEmpty() }.lastOrNull()
                    } ?: continue
                    if (last.sender == SenderType.ME) continue
                    KeryxNotifications.notifyMessage(
                        context = applicationContext,
                        roomId = room.id,
                        title = room.name,
                        body = notificationSnippet(last),
                    )
                }
                baseline = current
            }
        }
    }

    private fun notificationSnippet(m: Message): String = when {
        m.mediaKind == MediaKind.IMAGE -> "🖼 Photo"
        m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "Attachment" }}"
        m.content.isNotBlank() ->
            m.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: "New message"
        else -> "New message"
    }

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
