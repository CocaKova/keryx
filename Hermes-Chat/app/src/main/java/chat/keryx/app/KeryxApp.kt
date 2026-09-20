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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /**
     * Teach coil to animate. Inline `![](…gif)` images in agent prose render through the
     * markdown renderer's coil3 transformer, which decodes a still first frame unless a GIF
     * decoder is registered — so a linked reaction GIF arrived as a frozen picture.
     *
     * Built ON TOP of the default loader rather than replacing it: `.components {}` prepends,
     * so the network fetcher and every other default stays exactly where it was. The platform
     * decoder arrives at API 28; below it coil brings its own.
     */
    private fun installAnimatedImageDecoder() {
        coil3.SingletonImageLoader.setSafe { ctx ->
            coil3.ImageLoader.Builder(ctx)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        add(coil3.gif.AnimatedImageDecoder.Factory())
                    } else {
                        add(coil3.gif.GifDecoder.Factory())
                    }
                }
                .build()
        }
    }

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
    private val _foreground = MutableStateFlow(false)
    val isForeground: Boolean get() = _foreground.value

    // Backed by a flow, not a plain field, for the Gate: a request you can see must not also
    // buzz the shade, and — the half a plain field cannot express — one you walk away from
    // without answering must. Every existing reader and writer is unchanged.
    private val _openRoom = MutableStateFlow<String?>(null)
    var openRoomId: String?
        get() = _openRoom.value
        set(value) { _openRoom.value = value }

    /** The room being looked at right now: open AND on screen, or nothing. */
    private val attention: kotlinx.coroutines.flow.Flow<String?> =
        combine(_openRoom, _foreground) { room, fg -> room.takeIf { fg } }

    /** Session id → display name, kept fresh by the roster watch below so the shade can name
     *  the room a request came from without a round trip. */
    @Volatile private var roomNames: Map<String, String> = emptyMap()

    /** The roster as last seen, by id — the end-of-turn alert names its room from here. */
    @Volatile private var roomsById: Map<String, chat.keryx.core.model.RoomProfile> = emptyMap()

    /** Sessions with a turn in flight. Their words are not final, and the run notice is already
     *  saying they are working — nothing of theirs alerts until the turn ends. */
    @Volatile private var busySessions: Set<String> = emptySet()

    /** Room id → [chat.keryx.core.model.AlertPolicy.keyOf] the last message alerted for it. */
    private val lastAlerted = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(applicationContext)
        installAnimatedImageDecoder()
        settingsRepository = SettingsRepositoryImpl(applicationContext)
        matrixService = MatrixService(applicationContext)
        // The login screen's chosen door decides the spine for this whole process life —
        // a transport is not hot-swappable under a ViewModel, so switching doors restarts.
        // The fleet (2.11): which gateway this process life is on is decided BEFORE the
        // transport is built — every credential and ledger read below is the active gateway's.
        val gatewayId = if (settingsRepository.transportMode == "direct") bootGateway() else ""
        transport = if (settingsRepository.transportMode == "direct") {
            DirectTransport(
                settingsRepository, appScope,
                cacheDir = transcriptCacheDir(gatewayId),
            ).also { it.connectIfConfigured() }
        } else {
            MatrixTransport(matrixService, settingsRepository)
        }
        archiveStore = chat.keryx.app.data.archive.ArchiveStore(
            applicationContext,
            chat.keryx.app.data.archive.ArchiveStore.nameFor(gatewayId, settingsRepository.legacyGatewayId),
        )
        // One store, two producers: the Matrix timeline walk, or the gateway's REST pages.
        archiveIndexer = (transport as? DirectTransport)
            ?.let { direct -> chat.keryx.app.data.archive.RestArchiveIndexer({ direct.restClient }, archiveStore, direct::profileForSession) }
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
        observeShadeGate()
        observeRuns()
    }

    /**
     * The gateway a direct-door boot lands on, its id committed as the fleet's active row.
     * A relaunch the app did to itself (a switch, an added gateway) lands on the row it just
     * chose; a cold start follows the fleet's start-up rule — the last-used gateway by
     * default on a phone, Primary when the user turned that off. Blank = no fleet (a direct
     * door never signed in): the login screen.
     */
    private fun bootGateway(): String {
        val fleet = settingsRepository.fleet
        if (fleet.isEmpty) return ""
        val target = (if (settingsRepository.consumeFleetSwitch()) fleet.active else null)
            ?: fleet.bootTarget() ?: return ""
        if (target.id != fleet.activeId) settingsRepository.commitFleet(fleet.setActive(target.id))
        return target.id
    }

    /** Where a gateway's transcript pages sleep: one folder per gateway, so a switch never
     *  reads another gateway's cached tail for a same-named session. */
    fun transcriptCacheDir(gatewayId: String): java.io.File =
        applicationContext.cacheDir.resolve("transcripts").let { if (gatewayId.isBlank()) it else it.resolve(gatewayId) }

    /** A gateway left the fleet: its archive index, its saved messages and its transcript cache
     *  go with it. The pre-fleet file is shared with Matrix and is never deleted here. */
    fun purgeGatewayFiles(gatewayId: String) {
        if (gatewayId.isBlank()) return
        val db = chat.keryx.app.data.archive.ArchiveStore.nameFor(gatewayId, settingsRepository.legacyGatewayId)
        if (db != chat.keryx.app.data.archive.ArchiveStore.DEFAULT_DB) runCatching { applicationContext.deleteDatabase(db) }
        runCatching { transcriptCacheDir(gatewayId).deleteRecursively() }
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
                roomNames = rooms.associate { it.id to it.name }
                roomsById = rooms.associateBy { it.id }
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
                    // Turn traffic re-stamps the row every few seconds. That is a turn RUNNING,
                    // which the run notice already says; the alert is the turn's end
                    // (observeTurnEnds). Checked before the peek — no round trip for a no.
                    if (room.id in busySessions) continue
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
                    // Asked again AFTER the peek: the peek is a network round trip, and the
                    // user may have opened this very room while it was in flight (tapping a
                    // run adopts its session into the roster — the emission that got us here).
                    if (isForeground && openRoomId == room.id) {
                        android.util.Log.i("KeryxNotify", "skip ${room.id}: opened while peeking")
                        continue
                    }
                    // Skip historical messages surfacing during initial sync settle.
                    if (last.sender != SenderType.ME && last.timestamp < watchStart - historyGrace) {
                        android.util.Log.i("KeryxNotify", "skip ${room.id}: historical (${last.timestamp} < $watchStart)")
                        continue
                    }
                    alertFor(room, last)
                }
                baseline = current
            }
        }
        // The turn's end is the alert. The roster cannot be trusted to say so on time — its
        // stamp is slack-floored and the server's own catches up seconds later — so the
        // transport's End event drives it, and the roster pass above then finds it already said.
        val direct = transport as? DirectTransport ?: return
        appScope.launch {
            direct.turnEvents().collect { ev ->
                if (ev !is chat.keryx.core.model.TurnEvent.End) return@collect
                if (isForeground && openRoomId == ev.sessionId) return@collect
                // A turn stopped with nothing said (Stop from the shade, an interrupt) is not news.
                if (ev.finalText.isBlank() && !ev.error) return@collect
                val room = roomsById[ev.sessionId] ?: return@collect
                val last = withTimeoutOrNull(4_000L) { direct.peekLatest(ev.sessionId, 2) }?.lastOrNull() ?: return@collect
                alertFor(room, last, failed = ev.error, turnOver = true)
            }
        }
    }

    /**
     * Alert for [last] in [room] — if [chat.keryx.core.model.AlertPolicy] says it has earned one.
     * [turnOver] is the End path's knowledge that the busy mark (cleared in the same breath, on
     * another thread's clock) no longer applies.
     */
    private fun alertFor(room: chat.keryx.core.model.RoomProfile, last: Message, failed: Boolean = false, turnOver: Boolean = false) {
        val direct = transport as? DirectTransport
        val verdict = chat.keryx.core.model.AlertPolicy.decide(
            last = last,
            busy = !turnOver && room.id in busySessions,
            lastAlertedKey = lastAlerted[room.id],
        )
        when (verdict) {
            // You spoke: whatever the agent says next is new, even word for word.
            chat.keryx.core.model.AlertPolicy.Verdict.SILENT_MINE -> { lastAlerted.remove(room.id); return }
            chat.keryx.core.model.AlertPolicy.Verdict.ALERT -> Unit
            else -> { android.util.Log.i("KeryxNotify", "skip ${room.id}: $verdict"); return }
        }
        lastAlerted[room.id] = chat.keryx.core.model.AlertPolicy.keyOf(last)
        // The notice is agent-shaped (2.8): the speaker is the bot (a Bot Chat row
        // carries its bot's label as the name), or the herald by name on Matrix,
        // and a relayed bot-to-bot line names the bot that sent it. An unprompted
        // turn (2.3 §3 arrival) still reads as *who* walked in — the speaker.
        val isBotChat = room.source == chat.keryx.app.presentation.BotsDelegate.BOT_SOURCE
        // On the direct door the speaker is the agent of the profile that owns the session, by
        // the name THIS install gave it — a plain session and that profile's Bot Chat are the
        // same voice, in the same colour.
        val agent = direct?.agentFor(room.id)
        val notice = chat.keryx.core.model.AgentNotices.compose(
            message = (if (last.senderName.isBlank() && last.sender == SenderType.HERMES)
                last.copy(senderName = agent?.label ?: heraldName(last)) else last)
                // A turn that died wordlessly still says why.
                .let { m -> if (failed && m.content.isBlank()) m.copy(content = m.failure?.message?.ifBlank { null } ?: "The turn failed") else m },
            conversation = room.name,
            botLabel = if (isBotChat) room.name else null,
            botHandle = if (isBotChat) (room.heraldIds.firstOrNull() ?: room.id) else agent?.handle,
        )
        android.util.Log.i("KeryxNotify", "new activity in ${room.id} (${room.name}); notifying as ${notice.title}")
        KeryxNotifications.notifyMessage(
            context = applicationContext,
            roomId = room.id,
            notice = notice,
            quickActions = quickActionsFor(last),
            hands = if (last.sender == SenderType.HERMES) MessageParser.phoneActions(last.content) else emptyList(),
            markReadable = direct != null,
            timestamp = last.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis(),
            failed = failed,
        )
    }

    /**
     * The run notice: every turn in flight, told as ONE silent line that changes in place.
     * Repaints are floored to one a second — the system drops a notice updated faster than it
     * can draw, and a burst of tool calls is not worth a repaint each.
     */
    private fun observeRuns() {
        val direct = transport as? DirectTransport ?: return
        appScope.launch { direct.busySessionIds().collect { busySessions = it } }
        // The roster is what names the agent. Usually the Bots door has fetched it already; if
        // a turn is running and nobody has, ask once — the notice re-draws when it answers.
        appScope.launch {
            direct.runActivities().first { it.isNotEmpty() }
            if (direct.agents().value.isEmpty()) runCatching { direct.botRoster() }
        }
        appScope.launch {
            combine(direct.runActivities(), direct.agents()) { runs, _ -> runs }
                .map { runs ->
                    chat.keryx.core.model.RunNotices.compose(runs.values.map { a ->
                        val room = roomsById[a.sessionId]
                        val isBot = room?.source == chat.keryx.app.presentation.BotsDelegate.BOT_SOURCE
                        val agent = direct.agentFor(a.sessionId)
                        val session = room?.name?.takeIf { it.isNotBlank() } ?: "Keryx"
                        chat.keryx.core.model.RunSubject(
                            sessionId = a.sessionId,
                            agent = agent?.label ?: if (isBot) session else "",
                            session = session,
                            // The key the message alert hashes for this same speaker, so one agent is one colour.
                            colorKey = when {
                                isBot -> "bot:" + (room?.heraldIds?.firstOrNull() ?: a.sessionId)
                                agent != null -> "bot:" + agent.handle
                                else -> "conversation:$session"
                            },
                            activity = a,
                            plan = direct.todoPlanOf(a.sessionId),
                        )
                    })
                }
                .distinctUntilChanged()
                .conflate()
                .collect { notice ->
                    chat.keryx.app.notify.AgentRunService.sync(applicationContext, notice)
                    if (notice != null) kotlinx.coroutines.delay(1_000L)
                }
        }
    }

    /**
     * The Gate: an agent stopped on an approval, a question or a credential, said out loud.
     *
     * The decision layer for this has been in :core since G16 — which buttons a request earns,
     * whether free text is safe, how long the notice stays honest — fully tested and, until now,
     * read by nothing. The consequence was silence: a request raised while you were in another
     * session or out of the app made no sound at all, the gateway waited out its timeout and
     * failed CLOSED, and all you ever saw was a turn that had declined to happen.
     *
     * Direct door only, and correctly so: `shadePending` is the gateway's own blocked state.
     * On Matrix the agent asks in the room and the message notification already carries it.
     */
    private fun observeShadeGate() {
        val direct = transport as? DirectTransport ?: return
        KeryxNotifications.ensureGateChannel(applicationContext)
        appScope.launch {
            // Re-evaluated on BOTH inputs: a request arriving is one reason to post, and the
            // user leaving the room it is in is the other.
            var posted = emptyMap<String, String>()
            combine(direct.shadePending(), attention) { pending, looking -> pending to looking }
                .collect { (pending, looking) ->
                    val next = mutableMapOf<String, String>()
                    for ((sessionId, entry) in pending) {
                        // The in-app card owns the answer while you are looking at it — and it
                        // has the masked field a credential must be typed into.
                        if (sessionId == looking) continue
                        val notice = chat.keryx.core.model.ShadeNotices.forEntry(entry) ?: continue
                        // Same precedence the notice was built with: a blocking request outranks
                        // an approval, so the buttons and the respond call cannot disagree.
                        val blocking = entry.blocking
                        val kind = blocking?.kind?.name ?: KeryxNotifications.GATE_KIND_APPROVAL
                        // Data classes: the whole entry IS the identity of what is being asked.
                        val signature = entry.toString()
                        next[sessionId] = signature
                        if (posted[sessionId] == signature) continue
                        KeryxNotifications.notifyGate(
                            context = applicationContext,
                            sessionId = sessionId,
                            sessionName = roomNames[sessionId] ?: "Keryx",
                            notice = notice,
                            kind = kind,
                            requestId = blocking?.requestId,
                        )
                    }
                    // Answered anywhere, expired, or now on screen — all one thing to the shade.
                    for (gone in posted.keys - next.keys) {
                        KeryxNotifications.clearGate(applicationContext, gone)
                    }
                    posted = next
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
            _foreground.value = true
        }
        override fun onActivityStopped(activity: Activity) {
            foregroundCount = (foregroundCount - 1).coerceAtLeast(0)
            if (foregroundCount == 0) matrixService.syncStandby(SYNC_BACKGROUND_GRACE_MS)
            _foreground.value = foregroundCount > 0
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
