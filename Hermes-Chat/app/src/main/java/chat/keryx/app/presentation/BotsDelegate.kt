package chat.keryx.app.presentation

import chat.keryx.core.model.BotChatRef
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRoster
import chat.keryx.core.model.BotRosterSnapshot
import chat.keryx.core.model.BotsJson
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bot Mode (2.8) — the roster of Bots and the way into each one's forever-chat, for the
 * direct door. A Bot is a profile; the delegate holds `profiles.list`, knows which open
 * session is which bot's canonical chat, keeps the phone's own "looked at" stamps (the
 * gateway's read watermark never sees a hidden cross-profile row), and publishes the bot
 * chats as rooms so the floor can select and restore them and the notification watcher can
 * see them move. Everything that can be decided without Android is decided in
 * [chat.keryx.core.model.BotRoster]; this is plumbing and state.
 */
class BotsDelegate(
    private val deps: GatewayDeps,
    private val transport: ChatTransport,
    private val hub: HubDelegate,
    /** Open a session on the floor by stored id, titled — the roster row's own name. */
    private val openSession: (String, String) -> Unit,
) {
    private val scope get() = deps.scope
    private val settings get() = deps.settings
    private val gateway get() = transport.gateway

    /** Whether this door has a roster at all: the direct transport only. */
    val available: Boolean get() = gateway != null

    private val _roster = MutableStateFlow<HubDelegate.PanelState<BotRosterSnapshot>>(HubDelegate.PanelState())
    val roster: StateFlow<HubDelegate.PanelState<BotRosterSnapshot>> = _roster.asStateFlow()

    private val _seenAt = MutableStateFlow(settings.botSeenAt)
    val seenAt: StateFlow<Map<String, Long>> = _seenAt.asStateFlow()

    private val _pinned = MutableStateFlow(settings.pinnedBots)
    /** Profile names pinned to the top of the session list, in pin order. */
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()
    fun setShowHidden(show: Boolean) { _showHidden.value = show }

    /** Avatar bytes by profile name; null = probed and none. Bounded by the roster's size. */
    private val avatars = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val avatarMisses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Bot names with a turn in flight: busy stored ids mapped through the canonical chats. */
    val busyNames: StateFlow<Set<String>> = combine(
        gateway?.busySessionIds() ?: MutableStateFlow(emptySet()),
        _roster,
    ) { busy, panel ->
        val bots = panel.data?.bots ?: return@combine emptySet<String>()
        bots.filter { b -> b.canonical?.let { it.id in busy || it.openId in busy } == true }
            .mapTo(HashSet()) { it.name }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** How many bots have news — the drawer door's badge. */
    val unreadCount: StateFlow<Int> = combine(_roster, _seenAt) { panel, seen ->
        panel.data?.bots?.count { !it.hidden && BotRoster.unread(it, seen) } ?: 0
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * The pinned deck's bot tiles, as rooms: a pinned bot with a chat opens that chat; one
     * without (never opened) still gets a tile that goes to the Bots door. Same grammar as
     * the cron tiles — pinned means "at the top of the list" for every kind of row.
     */
    val tiles: StateFlow<List<RoomProfile>> = combine(_roster, _pinned, _seenAt) { panel, pins, seen ->
        val bots = panel.data?.bots ?: emptyList()
        pins.mapNotNull { name ->
            val bot = bots.firstOrNull { it.name == name } ?: return@mapNotNull null
            RoomProfile(
                id = bot.canonical?.openId ?: BOT_TILE_PREFIX + bot.name,
                name = bot.label,
                type = RoomType.DIRECT_MESSAGE,
                timestamp = bot.canonical?.lastActive ?: 0L,
                source = BOT_TILE_SOURCE,
                preview = bot.canonical?.preview.orEmpty(),
                unread = BotRoster.unread(bot, seen),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val refreshMutex = Mutex()
    private var lastRefreshAt = 0L

    init {
        // The roster is what makes bot chats openable (a notification tap, the last-open
        // restore after a relaunch), so it loads the moment the door is in — and keeps a
        // slow pulse after that: bot chats live in other profiles' stores, which the
        // gateway's sessions.changed broadcast never covers, so a bot's new word is only
        // ever learned by asking. A place that shows the roster polls faster on its own.
        if (gateway != null) scope.launch {
            // collectLatest: a sign-out cancels the pulse, a sign-in restarts it.
            transport.isLoggedIn().collectLatest { ok ->
                if (!ok) return@collectLatest
                refresh(force = true)
                while (isActive) {
                    delay(BACKGROUND_POLL_MS)
                    refresh()
                }
            }
        }
    }

    /** Pull the roster. Coalesced: a burst of callers (drawer open + place open) is one call. */
    fun refresh(force: Boolean = false) {
        val gw = gateway ?: return
        scope.launch {
            refreshMutex.withLock {
                val now = System.currentTimeMillis()
                if (!force && now - lastRefreshAt < MIN_REFRESH_MS) return@withLock
                _roster.value = _roster.value.copy(refreshing = true)
                gw.botRoster()
                    .onSuccess { snap ->
                        lastRefreshAt = System.currentTimeMillis()
                        _roster.value = HubDelegate.PanelState(data = snap)
                        publishRows(snap)
                    }
                    .onFailure { e ->
                        _roster.value = _roster.value.copy(
                            error = e.message?.take(120) ?: "roster unavailable", refreshing = false,
                        )
                    }
            }
        }
    }

    /** Poll while a surface that shows the roster is on screen (the place's own cadence). */
    fun poll(intervalMs: Long): Job = scope.launch {
        while (isActive) {
            refresh()
            delay(intervalMs)
        }
    }

    /**
     * Bot chats as rooms. They are hidden rows in other profiles' stores, so nothing else
     * lists them; publishing them is what lets the floor select one, restore it as the last
     * open room after a relaunch, and lets the notification watcher notice it moved.
     */
    private fun publishRows(snap: BotRosterSnapshot) {
        val seen = _seenAt.value
        val rows = snap.bots.mapNotNull { bot ->
            val chat = bot.canonical ?: return@mapNotNull null
            RoomProfile(
                id = chat.openId,
                name = bot.label,
                type = RoomType.DIRECT_MESSAGE,
                timestamp = chat.lastActive,
                messageCount = chat.messageCount,
                source = BOT_SOURCE,
                preview = chat.preview,
                unread = BotRoster.unread(bot, seen),
                // The bot's key rides as the row's one herald: the notification watcher
                // names the speaker by it, and the light it wears matches the roster's.
                heraldIds = listOf(bot.name),
            )
        }
        gateway?.publishBotRows(rows)
    }

    /** The bot whose canonical chat is [sessionId], or null for any other session. */
    fun botForSession(sessionId: String?): BotProfile? {
        sessionId ?: return null
        val bots = _roster.value.data?.bots ?: return null
        return bots.firstOrNull { b -> b.canonical?.let { it.id == sessionId || it.openId == sessionId } == true }
            ?: pendingOpens[sessionId]?.let { name -> bots.firstOrNull { it.name == name } }
    }

    /** Whether [sessionId] is a Bot's forever-chat — the sessions the /new rule guards. */
    fun isCanonicalChat(sessionId: String?): Boolean = botForSession(sessionId) != null

    /** Chats opened this process life before the roster caught up: stored id → profile name. */
    private val pendingOpens = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Tap a bot: find or create its chat, mark it looked at, and land on the floor in it. */
    fun open(bot: BotProfile, kickoff: Boolean = false) {
        val gw = gateway ?: return
        scope.launch {
            gw.openBotChat(bot, kickoff)
                .onSuccess { ref ->
                    pendingOpens[ref.openId] = bot.name
                    markSeen(bot.name)
                    // Show the row at once under the bot's name; the next roster pull
                    // replaces it with the gateway's own facts.
                    if (_roster.value.data?.byName(bot.name)?.canonical == null) {
                        _roster.value.data?.let { snap ->
                            val patched = snap.copy(bots = snap.bots.map {
                                if (it.name == bot.name) it.copy(canonical = ref) else it
                            })
                            _roster.value = _roster.value.copy(data = patched)
                            publishRows(patched)
                        }
                    }
                    openSession(ref.openId, bot.label)
                }
                .onFailure { e ->
                    deps.toast("Couldn't open ${bot.label}: ${e.message?.take(100) ?: "try again"}")
                }
        }
    }

    /** Open by profile name (a deck tile, a notification) — resolves against the roster. */
    fun openByName(name: String) {
        val bot = _roster.value.data?.byName(name) ?: run {
            refresh(force = true)
            return
        }
        open(bot)
    }

    /** Looking at a bot's chat IS reading it: stamp now, badge recomputes. */
    fun markSeen(name: String) {
        val next = _seenAt.value + (name to System.currentTimeMillis())
        _seenAt.value = next
        settings.botSeenAt = next
        _roster.value.data?.let(::publishRows)
    }

    /** The open session moved (you sent, the bot answered while you watched): keep it read. */
    fun touchOpenSession(sessionId: String?) {
        botForSession(sessionId)?.let { markSeen(it.name) }
    }

    fun setPinned(name: String, pinned: Boolean) {
        val now = _pinned.value
        val next = if (pinned) (if (name in now) now else now + name) else now - name
        if (next == now) return
        _pinned.value = next
        settings.pinnedBots = next
    }

    fun isPinned(name: String): Boolean = name in _pinned.value

    /**
     * Title, role or hidden flag → `profiles.configure`. The `hermes-bots` block is written
     * WHOLE from the bot's current block (the gateway replaces the key), so the desktop's
     * shape, colour and groups survive a Keryx edit. Setting anything here also makes the
     * profile Bot-Mode-managed, which is the gateway's gate for `message_agent`.
     */
    fun configure(bot: BotProfile, title: String? = null, description: String? = null, hidden: Boolean? = null, onDone: (String?) -> Unit = {}) {
        val gw = gateway ?: return onDone("not on the direct door")
        scope.launch {
            val meta = BotsJson.metaPatch(bot.meta, title, hidden, System.currentTimeMillis())
            gw.configureBot(bot.name, meta, description?.trim())
                .onSuccess { refresh(force = true); onDone(null) }
                .onFailure { onDone(it.message?.take(120) ?: "couldn't save") }
        }
    }

    /**
     * Arm agent-to-agent messaging: every unmanaged profile gets its `hermes-bots` block
     * (title = its current label, nothing else), which is exactly the flag the gateway
     * reads before injecting `message_agent` into canonical Bot Chats.
     */
    fun enableMessaging(onDone: (String?) -> Unit = {}) {
        val gw = gateway ?: return onDone("not on the direct door")
        val bots = _roster.value.data?.bots?.filter { !it.managed } ?: return onDone(null)
        scope.launch {
            var failed: String? = null
            for (bot in bots) {
                val meta = BotsJson.metaPatch(bot.meta, bot.label, null, System.currentTimeMillis())
                gw.configureBot(bot.name, meta, null).onFailure { failed = "${bot.label}: ${it.message?.take(80)}" }
            }
            refresh(force = true)
            onDone(failed)
        }
    }

    /**
     * New Agent: create the profile, give it its Bot Mode block, then open its forever-chat
     * with the kickoff so it introduces itself as the first message.
     */
    fun create(name: String, title: String, description: String, cloneFrom: String?, onDone: (String?) -> Unit) {
        val gw = gateway ?: return onDone("not on the direct door")
        val slug = name.trim().lowercase()
        if (!BotRoster.validName(slug)) return onDone("Name must be a slug: lowercase letters, digits, - or _")
        scope.launch {
            gw.createBot(slug, description.trim().ifBlank { null }, cloneFrom)
                .onFailure { onDone(it.message?.take(140) ?: "couldn't create the profile"); return@launch }
            val meta = BotsJson.metaPatch(null, title.trim().ifBlank { null }, null, System.currentTimeMillis())
            gw.configureBot(slug, meta, null).onFailure {
                android.util.Log.w("KeryxBots", "new bot $slug: meta write failed: ${it.message}")
            }
            val bot = BotProfile(name = slug, title = title.trim(), description = description.trim(), managed = true, meta = meta)
            onDone(null)
            open(bot, kickoff = true)
        }
    }

    /** The bot's uploaded avatar, fetched once per process; null when it has none. */
    suspend fun avatar(bot: BotProfile): ByteArray? {
        if (!bot.hasAvatar) return null
        avatars[bot.name]?.let { return it }
        if (bot.name in avatarMisses) return null
        val gw = gateway ?: return null
        val bytes = gw.botAvatar(bot.name).getOrNull()
        if (bytes == null) avatarMisses += bot.name else avatars[bot.name] = bytes
        return bytes
    }

    /** Job names on the cron page that belong to [bot] (`[bot:<name>] …`). */
    fun routines(bot: BotProfile): List<chat.keryx.app.data.remote.HermesStreamClient.HubJob> {
        val jobs = hub.jobs.value.data ?: return emptyList()
        return jobs.filter { BotRoster.routineOwner(it.name) == bot.name.lowercase() }
    }

    /** Routine counts by bot name, for the roster rows. */
    val routineCounts: StateFlow<Map<String, Int>> = hub.jobs.let { panel ->
        combine(panel, _roster) { jobs, _ ->
            (jobs.data ?: emptyList()).mapNotNull { BotRoster.routineOwner(it.name) }
                .groupingBy { it }.eachCount()
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    companion object {
        /** Roster-published bot chats — the drawer's session list skips these (the Bots door lists them). */
        const val BOT_SOURCE = "bot"
        /** Pinned-deck tiles for bots — see [tiles]. */
        const val BOT_TILE_SOURCE = "bot-tile"
        const val BOT_TILE_PREFIX = "bot:"
        private const val MIN_REFRESH_MS = 4_000L
        private const val BACKGROUND_POLL_MS = 60_000L
    }
}
