package chat.keryx.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import chat.keryx.app.transport.matrix.MatrixService
import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.app.transport.matrix.MatrixTransport
import chat.keryx.core.transport.ChatTransport
import chat.keryx.app.data.repository.SettingsRepositoryImpl
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.app.notify.KeryxNotifications
import chat.keryx.core.protocol.MessageParser
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

    private companion object {
        /** How long sync may keep running after the last activity leaves the screen. Long
         *  enough to cover a quick app switch or a share sheet's upload finishing; short
         *  enough that a pocketed phone stops long-polling within the next Doze window. */
        const val SYNC_BACKGROUND_GRACE_MS = 90_000L
    }

    lateinit var settingsRepository: SettingsRepositoryImpl
        private set
    lateinit var matrixService: MatrixService
        private set
    lateinit var transport: ChatTransport
        private set

    /** True when this process is riding the direct gateway door (no homeserver anywhere). */
    val isDirectTransport: Boolean get() = transport is DirectTransport
    lateinit var archiveStore: chat.keryx.app.data.archive.ArchiveStore
        private set
    lateinit var archiveIndexer: chat.keryx.app.data.archive.ArchiveSweeper
        private set

    val appScope = CoroutineScope(Dispatchers.IO)

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
        // The login screen's chosen door decides the spine for this whole process life —
        // a transport is not hot-swappable under a ViewModel, so switching doors restarts.
        transport = if (settingsRepository.transportMode == "direct") {
            DirectTransport(settingsRepository, appScope).also { it.connectIfConfigured() }
        } else {
            MatrixTransport(matrixService, settingsRepository)
        }
        archiveStore = chat.keryx.app.data.archive.ArchiveStore(applicationContext)
        // One store, two producers: the Matrix timeline walk, or the gateway's REST pages.
        archiveIndexer = (transport as? DirectTransport)
            ?.let { direct -> chat.keryx.app.data.archive.RestArchiveIndexer({ direct.restClient }, archiveStore) }
            ?: chat.keryx.app.data.archive.ArchiveIndexer(matrixService, archiveStore)

        KeryxNotifications.ensureChannel(applicationContext)
        registerActivityLifecycleCallbacks(ForegroundTracker())

        // Restore an existing Matrix session exactly once for the whole process.
        if (!isDirectTransport) appScope.launch {
            runCatching { matrixService.restore(allowInsecure = settingsRepository.allowInsecure) }
                // Log.e survives release stripping ON PURPOSE: a swallowed restore failure
                // renders as a silent logout (the login screen with settings intact) and is
                // undiagnosable without this line — exactly how the first minified build failed.
                .onFailure { android.util.Log.e("KeryxAuth", "session restore failed", it) }
        }

        // Keep the UnifiedPush registration fresh across app updates / distributor restarts —
        // idempotent, and a rotated endpoint comes back through onNewEndpoint → pusher update.
        if (settingsRepository.pushEnabled && !isDirectTransport) {
            runCatching { chat.keryx.app.notify.PushManager.enable(this) }
        }

        observeForNotifications()
    }

    /** Memory pressure → every registered session cache sheds weight (media bytes, decoded
     *  bitmaps, pet thumbs — all re-fetchable). Backgrounded or worse drops everything; milder
     *  levels shed roughly half. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        chat.keryx.app.util.CacheRegistry.trimAll(aggressive = level >= TRIM_MEMORY_BACKGROUND)
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
            transport.getRooms().collect { rooms ->
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
                    // Two, not one: the arrival test (2.3 §3) needs the message before this one to
                    // know whether anybody actually asked for it. On the direct door this is a
                    // REST peek — getMessages would hydrate AND session.resume the row, one live
                    // agent on the gateway per notification (the drawer's old trap, 2.6.2).
                    val direct = transport as? DirectTransport
                    val tail = withTimeoutOrNull(4_000L) {
                        if (direct != null) direct.peekLatest(room.id, 2)
                        else transport.getMessages(room.id, 2).first { it.isNotEmpty() }
                    }
                    val last = tail?.lastOrNull()
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
                    // The notice is agent-shaped (2.8): the speaker is the bot (a Bot Chat row
                    // carries its bot's label as the name), or the herald by name on Matrix,
                    // and a relayed bot-to-bot line names the bot that sent it. An unprompted
                    // turn (2.3 §3 arrival) still reads as *who* walked in — the speaker.
                    val isBotChat = room.source == chat.keryx.app.presentation.BotsDelegate.BOT_SOURCE
                    val notice = chat.keryx.core.model.AgentNotices.compose(
                        message = if (last.senderName.isBlank() && last.sender == SenderType.HERMES)
                            last.copy(senderName = heraldName(last)) else last,
                        conversation = room.name,
                        botLabel = if (isBotChat) room.name else null,
                        botHandle = if (isBotChat) (room.heraldIds.firstOrNull() ?: room.id) else null,
                    )
                    android.util.Log.i("KeryxNotify", "new activity in ${room.id} (${room.name}); notifying as ${notice.title}")
                    KeryxNotifications.notifyMessage(
                        context = applicationContext,
                        roomId = room.id,
                        notice = notice,
                        quickActions = quickActionsFor(last),
                        markReadable = direct != null,
                        timestamp = last.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                }
                baseline = current
            }
        }
    }

    /** A herald's display name, falling back to its MXID localpart. */
    private fun heraldName(m: Message): String = m.senderName
        .takeIf { it.isNotBlank() && !it.startsWith("@") }
        ?: chat.keryx.core.model.Heralds.localpart(m.senderId).ifEmpty { "Herald" }

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

    /** Counts started activities so [isForeground] reflects whether Keryx is on screen — and
     *  drives the sync governor: on screen = sync runs; off screen = the long-poll parks after
     *  a grace window (push + the workers cover everything that arrives while parked). Both
     *  calls are no-ops on the direct door, where the Matrix client never exists. */
    private inner class ForegroundTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (++foregroundCount == 1) matrixService.syncWake()
        }
        override fun onActivityStopped(activity: Activity) {
            foregroundCount = (foregroundCount - 1).coerceAtLeast(0)
            if (foregroundCount == 0) matrixService.syncStandby(SYNC_BACKGROUND_GRACE_MS)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
