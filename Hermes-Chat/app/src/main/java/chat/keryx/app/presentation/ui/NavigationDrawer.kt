package chat.keryx.app.presentation.ui

import chat.keryx.app.presentation.ui.components.keryxRaised
import chat.keryx.app.presentation.ui.components.keryxSunken
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import chat.keryx.app.R
import chat.keryx.app.presentation.tour.tourTarget
import chat.keryx.app.presentation.tour.KeryxTour
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import chat.keryx.app.presentation.ui.components.KeryxMotion
import chat.keryx.app.presentation.ui.components.keryxPop
import chat.keryx.app.presentation.ui.components.keryxVanish
import chat.keryx.app.presentation.ui.components.keryxPressScale
import chat.keryx.app.presentation.ui.components.keryxPressable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.RoomInvite
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomSigil
import chat.keryx.core.model.RoomSigils
import chat.keryx.core.model.RoomType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.contrastColorFor
import chat.keryx.app.presentation.ui.components.RoomSigilAvatar
import chat.keryx.app.theme.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import chat.keryx.app.presentation.ui.components.keryxLightSweep
import androidx.compose.runtime.setValue
import chat.keryx.app.presentation.ui.components.KeryxType

/** The deck rows that are cron tiles, not roster sessions — see [chat.keryx.core.model.CronTiles]. */
private const val CRON_TILE_SOURCE = "cron-tile"

/**
 * Roster rows the session list leaves to the door that owns them: a bot's forever-chat (the
 * Bots door lists it under its bot) and a scheduled run (the Runs door is where you read
 * reports). Both are real rows everywhere else in the app — this is a list, not a ledger.
 */
private val HIDDEN_ROSTER_SOURCES = setOf(
    chat.keryx.app.presentation.BotsDelegate.BOT_SOURCE,
    chat.keryx.app.transport.direct.DirectTransport.CRON_SOURCE,
)

/** The drawer's lenses (2.10) — saved by name across recreation, never across a process. */
private const val LENS_NEEDS_YOU = "needs"
private const val LENS_RUNNING = "running"
private const val LENS_UNREAD = "unread"
private const val LENS_ARCHIVED = "archived"

/** A palette hit that is not a room: a place or a command. Same row height as a room row's
 *  first line, so a mixed result list reads as one list. */
@Composable
private fun PaletteRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    mono: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .keryxPressable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = KeryxType.title,
                fontWeight = FontWeight.Medium,
                fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(
                subtitle,
                color = chat.keryx.app.theme.quietInk(alpha = 0.75f),
                fontSize = KeryxType.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            KeryxGlyphs.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Open what a cron tile points at. Direct door: the run as a real session (the full renderer),
 * marked read. Matrix door: runs read in the Runs door, so the tile goes there. A job tile with
 * nothing to open yet goes to the Runs door too — never a dead tap.
 */
private fun openCronTile(
    tile: chat.keryx.core.model.CronTile,
    viewModel: ChatViewModel,
    onRoomSelected: (RoomProfile) -> Unit,
    onOpenSpace: (chat.keryx.app.presentation.ui.nav.KeryxDest) -> Unit,
) {
    val runId = tile.runId
    if (runId == null || !viewModel.transportIsDirect) {
        onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Runs)
        return
    }
    viewModel.hub.cronMarkSeen(runId)
    viewModel.openSessionById(runId, tile.runTitle ?: tile.name)
    // The run is now a roster row (adopted); hand it to the host so the drawer closes the
    // same way it does for any selected session.
    viewModel.rooms.value.firstOrNull { it.id == runId }?.let(onRoomSelected)
}

/**
 * A bot tile: its forever-chat when one exists (adopted as a roster row already, so the
 * host closes the drawer the usual way), else the Bots door — never a dead tap.
 */
private fun openBotTile(
    room: RoomProfile,
    viewModel: ChatViewModel,
    onRoomSelected: (RoomProfile) -> Unit,
    onOpenSpace: (chat.keryx.app.presentation.ui.nav.KeryxDest) -> Unit,
) {
    val bot = viewModel.bots.botForSession(room.id)
    if (bot == null || bot.canonical == null) {
        onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Bots)
        return
    }
    viewModel.bots.open(bot)
    viewModel.rooms.value.firstOrNull { it.id == room.id }?.let(onRoomSelected)
}

@Composable
fun NavigationDrawerContent(
    viewModel: ChatViewModel,
    onRoomSelected: (RoomProfile) -> Unit,
    // Full-screen places (Missions, Archive) open on the nav stack owned by the host — the
    // drawer only asks; it never composes a space itself (2.0 Phase 1).
    onOpenSpace: (chat.keryx.app.presentation.ui.nav.KeryxDest) -> Unit,
    // ModalNavigationDrawer composes its drawer content even while closed (just translated
    // offscreen), so anything permanently animated in here would burn frames invisibly. The host
    // passes the drawer's real visibility so ornament only runs while it can be seen.
    drawerVisible: Boolean = true,
    // The drawer made a new conversation and the ViewModel is moving onto it: the host
    // closes the drawer so the new room is on screen, not behind the sheet you left.
    onConversationCreated: () -> Unit = {},
    // The palette put something in the composer (2.10): the drawer steps aside so you can
    // see it there.
    onDismissDrawer: () -> Unit = onConversationCreated,
) {
    // The roster publishes rows for the floor's sake — select, restore, notify — and two
    // kinds of them are read somewhere else in the app: bot chats and scheduled runs
    // ([HIDDEN_ROSTER_SOURCES]). Opening a report adopts its session, so without this the
    // session list kept every brief you read, and a busy gateway writes more briefs than
    // you write messages. Filtered here and nowhere deeper: [viewModel.rooms] still carries
    // the row, so selecting, restoring and notifying on an open run all still work.
    val allRooms by viewModel.rooms.collectAsState()
    val rooms = remember(allRooms) { allRooms.filterNot { it.source in HIDDEN_ROSTER_SOURCES } }
    val pinnedRoomIds by viewModel.sessions.pinnedRoomIds.collectAsState()
    val tempSessionIds by viewModel.sessions.temporarySessionIds.collectAsState()
    // "Move to project…" — explicit projects with a folder (membership is cwd).
    val moveTargets by viewModel.projects.projectMoveTargets.collectAsState()
    val moveCtx = LocalContext.current
    val currentUserId by viewModel.currentUserId.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    val isDarkTheme by viewModel.prefs.isDarkTheme.collectAsState()
    // The door's own words for its rows — "room" on Matrix, "session" on the gateway.
    val lexicon = viewModel.lexicon
    // The doors' gates, read once: the footer draws by them and the palette (2.10) offers
    // by them, so a door you cannot see is never a door you can type your way into.
    val caps by viewModel.hub.reasoningCaps.collectAsState()
    val hasProjects by viewModel.projects.hasProjects.collectAsState()
    val cronBoard by viewModel.hub.cron.collectAsState()
    // Runs reads stock routes only (/api/jobs + /api/sessions?source=cron), so its door opens
    // on a stock API server too: keryx-stream's capabilities probe OR a board that loaded.
    // Gating on the probe alone hid Runs from every Hermes without the plugin (11-03).
    val hasRuns = caps != null || cronBoard.data != null
    val botsUnread by viewModel.bots.unreadCount.collectAsState()
    val direct = viewModel.transportIsDirect

    // Image picker for setting a Quick Room's avatar (server-side m.room.avatar).
    val context = LocalContext.current
    var pendingAvatarRoomId by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val roomId = pendingAvatarRoomId
        if (uri != null && roomId != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) viewModel.setRoomAvatar(roomId, bytes, type)
        }
        pendingAvatarRoomId = null
    }
    // Export (2.16): the session whose file is being made — one dialog for the whole drawer.
    var exportRoom by remember { mutableStateOf<RoomProfile?>(null) }
    val exportSupported by viewModel.exports.supported.collectAsState()
    exportRoom?.let { r ->
        chat.keryx.app.presentation.ui.components.SessionExportDialog(viewModel, r.id, r.name) { exportRoom = null }
    }
    
    // Settings is a nav destination now (2.0 Phase 4) — see SettingsPlace.
    
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerShape = DrawerShape,
        // The sheet itself runs edge to edge — under the status bar and the gesture bar — so its
        // surface reaches the top of the screen instead of stopping short of the notification bar
        // (which left a strip of dimmed chat showing above it). The CONTENT steps down by the
        // system-bar insets below, so nothing sits under the clock.
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, 0.06f),
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
        ) {
            // Profile / identity header — the animated Keryx emblem as the brand/identity mark.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            ) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        color2 = MaterialTheme.colorScheme.tertiary,
                        running = drawerVisible,
                        snakeLength = 18,
                        periodMillis = 5200,
                        glyphSize = 8f,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                // weight(1f): the identity column must yield to the buttons that follow it.
                // Unweighted, a long user id (a Tailscale hostname on the direct door) claims
                // the row's full width, the trailing spacer collapses, and the theme + new-chat
                // buttons are laid out past the drawer's edge — the plus vanished entirely.
                // Weighted, the id ellipsises and the buttons keep their seats.
                Column(modifier = Modifier.weight(1f)) {
                    // The petdex mascot (Hermes desktop's floating pet, drawer-header sized).
                    // Fetched lazily on first drawer open; absent entirely when the gateway has
                    // no pet configured, so the header stays exactly as before for those setups.
                    val petInfo by viewModel.pet.petInfo.collectAsState()
                    val awaitingReply by viewModel.awaitingReply.collectAsState()
                    var petGreeting by remember { mutableStateOf(false) }
                    LaunchedEffect(drawerVisible) {
                        if (drawerVisible) {
                            viewModel.pet.refreshPet()
                            // The Projects door's probe (no-op on Matrix).
                            viewModel.projects.refreshProjects()
                            // The roster itself (no-op on Matrix, where sync keeps it live):
                            // pins and read marks set from Desktop, a cron continuation, a
                            // compaction's new id — the drawer opening is the moment to know.
                            viewModel.refreshRoster()
                            // The Runs door's badge AND its stock-gateway probe: the board is
                            // otherwise only fetched while the Runs place polls, so a drawer opened
                            // cold would show no count (and, without keryx-stream, no door at all).
                            if (viewModel.gatewayUrl.value.isNotBlank()) viewModel.hub.refreshCron()
                            // The palette's commands (2.10) — rate-limited inside, so this is
                            // one fetch a minute at most.
                            viewModel.refreshCommands()
                            // Wave hello when the drawer opens, then settle into the idle loop.
                            petGreeting = true
                            kotlinx.coroutines.delay(2200)
                            petGreeting = false
                        }
                    }
                    var showPetPicker by remember { mutableStateOf(false) }
                    if (showPetPicker) {
                        chat.keryx.app.presentation.ui.components.PetPickerSheet(
                            viewModel = viewModel,
                            onDismiss = { showPetPicker = false },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = KeryxType.display)
                        petInfo?.let { pet ->
                            Spacer(modifier = Modifier.width(10.dp))
                            chat.keryx.app.presentation.ui.components.PetSprite(
                                info = pet,
                                pose = when {
                                    awaitingReply -> chat.keryx.app.presentation.ui.components.PetPose.RUN
                                    petGreeting -> chat.keryx.app.presentation.ui.components.PetPose.WAVE
                                    else -> chat.keryx.app.presentation.ui.components.PetPose.IDLE
                                },
                                running = drawerVisible,
                                // Native frames are 192×208 — keep the aspect so the pet isn't squashed.
                                modifier = Modifier
                                    .size(width = 26.dp, height = 28.dp)
                                    // Tap your pet to adopt a different one.
                                    .clickable { viewModel.pet.refreshPetGallery(); showPetPicker = true },
                            )
                        }
                    }
                    currentUserId?.let {
                        Text(
                            text = it,
                            fontSize = KeryxType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Theme lives up here with the identity, not among the doors: the door grid is
                // destinations only (2.6.2), and a switch styled as a door was the one tile
                // that took you nowhere.
                val themeGlyph = when (isDarkTheme) {
                    true -> KeryxGlyphs.Sun
                    false -> KeryxGlyphs.AutoTheme
                    null -> KeryxGlyphs.Moon
                }
                val themeLabel = when (isDarkTheme) {
                    true -> stringResource(R.string.chat_theme_to_light)
                    false -> stringResource(R.string.chat_theme_follow_system)
                    null -> stringResource(R.string.chat_theme_to_dark)
                }
                IconButton(onClick = {
                    viewModel.prefs.toggleTheme(
                        when (isDarkTheme) {
                            null -> true
                            true -> false
                            false -> null
                        }
                    )
                }) {
                    // The glyph turns over rather than snapping: sun to moon is a change of light.
                    AnimatedContent(
                        targetState = themeGlyph,
                        transitionSpec = { keryxPop().togetherWith(keryxVanish()) },
                        label = "themeGlyph",
                    ) { glyph ->
                        Icon(
                            glyph,
                            contentDescription = themeLabel,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // Matrix's new-conversation entry point: DM / create / join, in a sheet. On the
                // direct door there is exactly one "new session" and it lives in the top bar over
                // the chat (2.9.4) — a second plus up here made two glyphs for one act.
                var showNewChat by remember { mutableStateOf(false) }
                if (!viewModel.transportIsDirect) IconButton(onClick = { showNewChat = true }) {
                    Icon(
                        KeryxGlyphs.Plus,
                        contentDescription = stringResource(R.string.chat_new_chat),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (showNewChat) {
                    chat.keryx.app.presentation.ui.components.NewChatSheet(
                        viewModel = viewModel,
                        onDismiss = { showNewChat = false },
                        onCreated = onConversationCreated,
                    )
                }
            }


            var query by remember { mutableStateOf("") }
            // A filled well rather than an outlined box (3.0): the outline drew the heaviest line
            // in the drawer around its least important control. Focus still rings it.
            // 3.0 depth: the well is sunk (keryxSunken below), so its fill is the drawer's own
            // ground darkened, no longer a lighter patch laid on it.
            val well = Color.Transparent

            // Deep search rides the same field on the direct door: after a typing pause the
            // gateway's server-side FTS answers over message CONTENT, which the local title
            // filter can never see. Debounced so scrolling through a query doesn't spray
            // requests; cleared with the query so stale hits never linger.
            var deepHits by remember { mutableStateOf<List<chat.keryx.core.transport.SessionSearchHit>>(emptyList()) }
            LaunchedEffect(query) {
                if (viewModel.transportIsDirect && query.trim().length >= 2) {
                    kotlinx.coroutines.delay(350)
                    deepHits = viewModel.sessions.searchGatewaySessions(query.trim())
                } else {
                    deepHits = emptyList()
                }
            }

            // The filters (2.10, direct door): what needs you, what is running, what is unread,
            // what you archived. One at a time — a chip is a lens, not a query language — and
            // they narrow the deck and the shelves alike, so a pinned row the lens excludes is
            // excluded, not shown twice. The Desktop sidebar's filter menu, as chips.
            var lens by rememberSaveable { mutableStateOf<String?>(null) }
            val needsYou by viewModel.sessions.needsYouIds.collectAsState()
            val busy by viewModel.sessions.busySessionIds.collectAsState()
            val archivedRooms by viewModel.sessions.archivedRooms.collectAsState()
            LaunchedEffect(lens, drawerVisible) {
                if (lens == LENS_ARCHIVED && drawerVisible) viewModel.sessions.loadArchivedSessions()
            }
            val lensRows = if (lens == LENS_ARCHIVED && query.isBlank()) archivedRooms else rooms
            val filtered = when {
                query.isNotBlank() -> rooms.filter {
                    it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
                }
                lens == LENS_NEEDS_YOU -> lensRows.filter { it.id in needsYou }
                lens == LENS_RUNNING -> lensRows.filter { it.id in busy }
                lens == LENS_UNREAD -> lensRows.filter { it.hasUnread }
                else -> lensRows
            }
            val lensed = lens != null && query.isBlank()
            // A session a project claimed lives in Projects (2.10): the flat list is the Desktop's
            // recents, and its rule is "scoped sessions stay under their project". The open one
            // stays visible wherever it is; search and the lenses still see everything.
            // ⚠️ Not the tree's scopedSessionIds — the gateway counts EVERY session as scoped
            // (auto repo buckets and the "no project" home bucket included), and the first
            // build of this hid the whole roster. Only projects you made count.
            val explicitIds by viewModel.projects.explicitSessionIds.collectAsState()
            val scoped = if (direct && !lensed && query.isBlank()) explicitIds else emptySet()
            val unscoped = if (scoped.isEmpty()) filtered
                else filtered.filter { it.id !in scoped || it.id == currentRoom?.id || it.id in pinnedRoomIds }
            val pinned = if (lensed) emptyList() else unscoped.filter { it.id in pinnedRoomIds }
            // Scheduled work at the top of the list, Quick-Room style: a pinned JOB is a tile
            // that follows its newest run; a run kept on the gateway is a tile that IS that
            // run. Both sit in the deck beside the pinned conversations (feedback: "pinning the
            // output of a certain cron job to the top of the session list like on the Matrix
            // side"). They are rows the roster never carries, so they arrive from the hub.
            // ⚠️ Gateway rows only: a cron tile opens a gateway session and a bot tile a
            // gateway bot chat. The hub keeps them across doors (its state is the gateway's),
            // so without this gate a pinned job sat at the top of the MATRIX roster too — a
            // tile whose tap goes nowhere on that door (found on device, 2026-09-24).
            val cronTiles by viewModel.hub.cronTiles.collectAsState()
            val tileRooms = if (direct && query.isBlank() && !lensed) cronTiles.map { t ->
                RoomProfile(
                    id = t.id,
                    name = t.label,
                    type = chat.keryx.core.model.RoomType.DIRECT_MESSAGE,
                    timestamp = t.timestamp,
                    source = CRON_TILE_SOURCE,
                    preview = t.runTitle.orEmpty(),
                    unread = t.unread,
                )
            } else emptyList()
            val tileById = cronTiles.associateBy { it.id }
            // Pinned bots sit in the same deck: a tile that opens the bot's forever-chat (or
            // the Bots door for one never opened) — "at the top of the list", same grammar.
            val botTiles by viewModel.bots.tiles.collectAsState()
            // distinctBy: a run tile's id IS its session id, so a run that is both kept on
            // the gateway and answered by you (now a roster row) reaches the deck from both
            // sides. One row per id, the roster's copy first — the tap still routes through
            // the tile (tileById is keyed the same way), so nothing about opening it changes.
            val deck = (pinned + tileRooms + (if (direct && query.isBlank() && !lensed) botTiles else emptyList()))
                .distinctBy { it.id }
            // Pinned rooms live in the Quick Rooms deck — don't list them twice.
            // (While searching or under a lens, show everything that matches.)
            val listRooms = if (query.isBlank() && !lensed) unscoped.filter { it.id !in pinnedRoomIds } else filtered

            val invites by viewModel.invites.collectAsState()

            // The shelves (2.10): Today · Yesterday · This week · Older, each a header you can
            // fold. Searching flattens them — a result is a result, whenever it happened.
            // Midnight is measured when the drawer opens, so a shelf boundary the phone
            // crosses while you sleep is right the next time you look.
            val startOfToday = remember(drawerVisible) { localMidnightMs() }
            val folded by viewModel.collapsedRosterGroups.collectAsState()
            val commands by viewModel.hub.gatewayCommands.collectAsState()
            val hasMore by viewModel.sessions.hasMoreSessions.collectAsState()
            val loadingMore by viewModel.sessions.loadingMoreSessions.collectAsState()
            val sections = remember(listRooms, startOfToday, query) {
                // Forks sit under the conversation they came from (2.19), and shelve with it.
                if (query.isBlank() && !lensed) {
                    chat.keryx.core.model.RosterGroups.split(chat.keryx.core.model.SessionTree.nest(listRooms), startOfToday)
                }
                else listOf(chat.keryx.core.model.RosterSection(chat.keryx.core.model.RosterGroup.TODAY, listRooms))
            }

            // The palette's place names, resolved here: the list below is built outside composition.
            val placeMissions = stringResource(R.string.chat_place_missions)
            val placeRuns = stringResource(R.string.chat_place_runs)
            val placeBots = stringResource(R.string.chat_place_bots)
            val placeProjects = stringResource(R.string.chat_place_projects)
            val placeShipyard = stringResource(R.string.chat_place_shipyard)
            val placeArchive = stringResource(R.string.chat_place_archive)
            val placeGateway = stringResource(R.string.chat_place_gateway)
            val placeSettings = stringResource(R.string.chat_place_settings)
            // The fleet (2.11): with several gateways the drawer leads with the Desktop's one named
            // gateway selector. A switch moves the whole workspace, so it relaunches onto the
            // chosen gateway — the door toggle's own path.
            val fleet by viewModel.doors.fleet.collectAsState()
            val fleetContext = LocalContext.current
            // The drawer opens at its top, where the lights and the places are.
            val drawerList = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(drawerVisible) { if (drawerVisible) drawerList.scrollToItem(0) }
            LazyColumn(state = drawerList, modifier = Modifier.weight(1f)) {
                // 3.0: the drawer's head scrolls with the list. The agent, the bot lights, the
                // places, search and the lenses open the drawer at the top; scroll into your
                // sessions and the whole height is theirs (it was two rows on a phone).
                item(key = "drawer-head") {
                    Column {
                        // Which gateway (2.11, several on the fleet): first, since everything
                        // under it is that gateway's. Scrolls away with the rest (3.0).
                        if (viewModel.transportIsDirect) chat.keryx.app.presentation.ui.components.FleetSelector(
                            fleet = fleet,
                            onSwitch = { id ->
                                if (viewModel.doors.switchGateway(id)) chat.keryx.app.presentation.ui.components.relaunchApp(fleetContext)
                            },
                            onManage = {
                                viewModel.jumpToSetting(chat.keryx.app.presentation.ui.components.SettingsRow.CONNECTION_GATEWAY.id)
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Settings)
                            },
                        )
                        // The agent's own card (makeover): the visible door to its page. Direct door only —
                        // Matrix has no dashboard behind it, and the demo keeps its strip's "connect" offer.
                        if (direct && !viewModel.isDemo) chat.keryx.app.presentation.ui.components.AgentDoorCard(viewModel) {
                            viewModel.hub.refreshReasoningCaps()
                            onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Gateway)
                        }

                        // The bot lights (3.0): every bot one tap from wherever you are. A bot's chat is
                        // a chat chosen on purpose, so it closes the drawer the way a new conversation does.
                        if (direct) chat.keryx.app.presentation.ui.components.BotLights(
                            viewModel = viewModel,
                            visible = drawerVisible,
                            onOpenBot = { bot -> viewModel.bots.open(bot); onConversationCreated() },
                            onManageBot = { bot ->
                                viewModel.bots.requestManage(bot.name)
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Bots)
                            },
                            onOpenGroup = { room ->
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Group(room.roomId, room.name))
                            },
                            modifier = Modifier.padding(bottom = 8.dp).tourTarget(KeryxTour.BOT_LIGHTS),
                        )
                        // The places (2.10). Two kinds of thing used to share one grid of nine: the places
                        // you read daily and the machine you visit on purpose. The grid keeps the places —
                        // icon over a small label, the phone-launcher grammar, three to a row so six make
                        // two even rows and a seventh still costs a list entry, not a re-layout. The
                        // machine went to the strip below.
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            maxItemsInEachRow = 3,
                        ) {
                            // The needs-you orb (2.14.1): cards waiting on you, board-wide — warm and
                            // breathing, so it never reads as one more unread count. Fresh on every drawer
                            // open (one quiet GET), and kept current meanwhile by the on-screen pulse.
                            val missionsNeedYou by viewModel.missions.needsYouCount.collectAsState()
                            LaunchedEffect(drawerVisible) {
                                if (drawerVisible) viewModel.missions.refreshKanbanQuietly()
                            }
                            DrawerDoor(
                                KeryxGlyphs.Board, stringResource(R.string.chat_place_missions), Modifier.weight(1f).tourTarget(KeryxTour.MISSIONS),
                                badge = missionsNeedYou,
                                urgent = true,
                                // The drawer composes while closed: the glow only breathes while it's seen.
                                alive = drawerVisible,
                            ) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Missions)
                            }
                            // Scheduled work reads at floor level. Gated on [hasRuns]: keryx-stream's probe
                            // or the stock board having loaded over the same Link. Badged with what has
                            // landed since you last looked — the same ledger the Runs place's arrivals
                            // rail reads, so door and rail never disagree.
                            if (hasRuns) DrawerDoor(
                                KeryxGlyphs.Watch, stringResource(R.string.chat_place_runs), Modifier.weight(1f).tourTarget(KeryxTour.RUNS),
                                badge = cronBoard.data?.unread?.total ?: 0,
                            ) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Runs)
                            }
                            // Bot Mode (2.8): the roster of profiles, badged with the bots that have news
                            // since you last looked. Direct door only — on Matrix a room already is a
                            // profile, so the door would open onto the drawer you are standing in.
                            if (direct) DrawerDoor(
                                KeryxGlyphs.Robot, stringResource(R.string.chat_place_bots), Modifier.weight(1f).tourTarget(KeryxTour.BOTS),
                                badge = botsUnread,
                            ) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Bots)
                            }
                            // Only where the gateway serves projects.* — the probe is the overview fetch.
                            if (hasProjects) DrawerDoor(KeryxGlyphs.Folder, stringResource(R.string.chat_place_projects), Modifier.weight(1f)) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Projects)
                            }
                            // Only where the gateway declares `git` (keryx.git.enabled) — a capability, not a probe.
                            if (caps?.git == true) DrawerDoor(KeryxGlyphs.GitBranch, stringResource(R.string.chat_place_shipyard), Modifier.weight(1f)) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Shipyard)
                            }
                            DrawerDoor(KeryxGlyphs.Archive, stringResource(R.string.chat_place_archive), Modifier.weight(1f).tourTarget(KeryxTour.ARCHIVE)) {
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Archive)
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.chat_drawer_jump_hint)) },
                            leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedContainerColor = well,
                                focusedContainerColor = well,
                            ),
                            // Sunk into the drawer (3.0 depth): a well you type into.
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                .keryxSunken(RoundedCornerShape(14.dp)),
                        )
                        if (direct && query.isBlank()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tourTarget(KeryxTour.LENSES)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 8.dp),
                            ) {
                                listOf(
                                    LENS_NEEDS_YOU to stringResource(R.string.chat_lens_needs_you),
                                    LENS_RUNNING to stringResource(R.string.chat_lens_running),
                                    LENS_UNREAD to stringResource(R.string.chat_lens_unread),
                                    LENS_ARCHIVED to stringResource(R.string.chat_lens_archived),
                                ).forEach { (id, label) ->
                                    val count = when (id) {
                                        LENS_NEEDS_YOU -> rooms.count { it.id in needsYou }
                                        LENS_RUNNING -> rooms.count { it.id in busy }
                                        LENS_UNREAD -> rooms.count { it.hasUnread }
                                        else -> 0
                                    }
                                    LensChip(
                                        label = label,
                                        count = count,
                                        selected = lens == id,
                                        mark = when (id) {
                                            LENS_NEEDS_YOU -> LensMark.NEEDS_YOU
                                            LENS_RUNNING -> if (count > 0) LensMark.LIVE else LensMark.NONE
                                            else -> LensMark.NONE
                                        },
                                        live = drawerVisible,
                                        onClick = { lens = if (lens == id) null else id },
                                    )
                                }
                            }
                        }
                    }
                }
                // Pending invitations first — they need a decision, not a scroll hunt.
                if (invites.isNotEmpty() && query.isBlank()) {
                    item { DrawerSectionHeader(stringResource(R.string.chat_drawer_invites)) }
                    items(invites, key = { "invite-${it.id}" }) { invite ->
                        InviteRow(
                            invite = invite,
                            onAccept = { viewModel.matrixRooms.acceptInvite(invite.id) },
                            onDecline = { viewModel.matrixRooms.declineInvite(invite.id) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(14.dp)) }
                }

                if (deck.isNotEmpty() && query.isBlank()) {
                    item { DrawerSectionHeader(lexicon.pinnedHeader) }
                    item {
                        chat.keryx.app.presentation.ui.components.QuickRoomsDeck(
                            rooms = deck,
                            // A job tile is "here" while its newest run is the open session.
                            selectedRoomId = currentRoom?.id?.let { open ->
                                cronTiles.firstOrNull { it.job && it.runId == open }?.id ?: open
                            },
                            onRoomClick = { room ->
                                val tile = tileById[room.id]
                                when {
                                    room.source == chat.keryx.app.presentation.BotsDelegate.BOT_TILE_SOURCE ->
                                        openBotTile(room, viewModel, onRoomSelected, onOpenSpace)
                                    tile == null -> onRoomSelected(room)
                                    else -> openCronTile(tile, viewModel, onRoomSelected, onOpenSpace)
                                }
                            },
                            avatarLoader = { viewModel.media.loadAvatar(it) },
                            // Long-press a Quick Room to pin/unpin it — consistent with the
                            // room list below. Setting a room photo lives on the avatar
                            // long-press in the main list, so the two no longer collide.
                            onRoomLongPress = { room ->
                                val tile = tileById[room.id]
                                when {
                                    room.source == chat.keryx.app.presentation.BotsDelegate.BOT_TILE_SOURCE ->
                                        viewModel.bots.botForSession(room.id)?.let { viewModel.bots.setPinned(it.name, false) }
                                            ?: viewModel.bots.setPinned(room.id.removePrefix(chat.keryx.app.presentation.BotsDelegate.BOT_TILE_PREFIX), false)
                                    tile == null -> viewModel.sessions.togglePin(room.id)
                                    tile.job -> viewModel.hub.cronSetJobPinned(tile.name, false)
                                    else -> viewModel.hub.cronSetPinned(tile.id, false)
                                }
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }

                // The palette (2.10): the one field reaches everything. Places and commands
                // answer first because there are few of them and they are exact; the rooms
                // follow. The Desktop's ⌘K, without the keyboard.
                if (query.isNotBlank()) {
                    val q = query.trim()
                    val places = buildList {
                        add(Triple(placeMissions, KeryxGlyphs.Board, chat.keryx.app.presentation.ui.nav.KeryxDest.Missions))
                        if (hasRuns) add(Triple(placeRuns, KeryxGlyphs.Watch, chat.keryx.app.presentation.ui.nav.KeryxDest.Runs))
                        if (direct) add(Triple(placeBots, KeryxGlyphs.Robot, chat.keryx.app.presentation.ui.nav.KeryxDest.Bots))
                        if (hasProjects) add(Triple(placeProjects, KeryxGlyphs.Folder, chat.keryx.app.presentation.ui.nav.KeryxDest.Projects))
                        if (caps?.git == true) add(Triple(placeShipyard, KeryxGlyphs.GitBranch, chat.keryx.app.presentation.ui.nav.KeryxDest.Shipyard))
                        add(Triple(placeArchive, KeryxGlyphs.Archive, chat.keryx.app.presentation.ui.nav.KeryxDest.Archive))
                        add(Triple(placeGateway, KeryxGlyphs.Pulse, chat.keryx.app.presentation.ui.nav.KeryxDest.Gateway))
                        add(Triple(placeSettings, KeryxGlyphs.Sliders, chat.keryx.app.presentation.ui.nav.KeryxDest.Settings))
                    }.filter { it.first.contains(q, ignoreCase = true) }
                    if (places.isNotEmpty()) {
                        item(key = "palette-places") { DrawerSectionHeader(stringResource(R.string.chat_drawer_places)) }
                        items(places, key = { "place-" + it.first }) { (label, glyph, dest) ->
                            PaletteRow(icon = glyph, title = label, subtitle = null) { onOpenSpace(dest) }
                        }
                    }
                    val cq = q.removePrefix("/")
                    val hits = if (cq.length < 2) emptyList() else commands.filter { c ->
                        c.cmd.removePrefix("/").contains(cq, ignoreCase = true) ||
                            c.aliases.any { it.removePrefix("/").contains(cq, ignoreCase = true) } ||
                            c.description.contains(cq, ignoreCase = true)
                    }.take(6)
                    if (hits.isNotEmpty()) {
                        item(key = "palette-commands") { DrawerSectionHeader(stringResource(R.string.chat_drawer_commands)) }
                        items(hits, key = { "cmd-" + it.cmd }) { c ->
                            PaletteRow(
                                icon = KeryxGlyphs.Routes,
                                title = c.cmd + (if (c.argsHint.isNotBlank()) " …" else ""),
                                subtitle = c.description.takeIf { it.isNotBlank() },
                                mono = true,
                            ) {
                                // Into the composer, never straight to the wire: the send is
                                // the consent, the same law as every other tap in the app.
                                viewModel.prefillComposer(c.cmd + (if (c.argsHint.isNotBlank()) " " else ""))
                                onDismissDrawer()
                            }
                        }
                    }
                    // Settings rows by name (2.10): the hit opens Settings on that row, lit.
                    val settingHits = chat.keryx.app.presentation.ui.components.SettingsCatalog.search(q, direct) { chat.keryx.app.KeryxText.get(it.label) }.take(5)
                    if (settingHits.isNotEmpty()) {
                        item(key = "palette-settings") { DrawerSectionHeader(stringResource(R.string.chat_place_settings)) }
                        items(settingHits, key = { "setting-" + it.id }) { e ->
                            val sectionKey = e.section(direct)
                            PaletteRow(
                                icon = KeryxGlyphs.Sliders,
                                title = stringResource(e.label),
                                subtitle = chat.keryx.app.presentation.ui.components.settingsSectionLabel(sectionKey)?.let { stringResource(it) } ?: sectionKey,
                            ) {
                                viewModel.jumpToSetting(e.id)
                                onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Settings)
                            }
                        }
                    }
                    item(key = "results-header") { DrawerSectionHeader(lexicon.listHeader) }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                query.isNotBlank() -> stringResource(R.string.chat_drawer_no_matches)
                                lens == LENS_ARCHIVED -> stringResource(R.string.chat_drawer_nothing_archived)
                                lens == LENS_NEEDS_YOU -> stringResource(R.string.chat_drawer_nothing_waiting)
                                lens == LENS_RUNNING -> stringResource(R.string.chat_drawer_nothing_running)
                                lens == LENS_UNREAD -> stringResource(R.string.chat_drawer_all_read)
                                rooms.isEmpty() -> lexicon.emptyList
                                else -> stringResource(R.string.chat_drawer_no_matches)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.bodyLarge,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                val direct = viewModel.transportIsDirect
                sections.forEach { section ->
                    val grouped = query.isBlank() && !lensed
                    val open = !grouped || section.group.name !in folded
                    if (grouped) item(key = "shelf-${section.group.name}") {
                        DrawerShelfHeader(
                            title = section.group.label,
                            count = section.rows.size,
                            unread = section.unread,
                            expanded = open,
                            onToggle = { viewModel.toggleRosterGroup(section.group.name) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (open) items(section.rows, key = { it.id }) { room ->
                    RoomRow(
                        room = room,
                        running = room.id in busy,
                        breathe = drawerVisible,
                        isSelected = currentRoom?.id == room.id,
                        isPinned = room.id in pinnedRoomIds,
                        isTemporary = room.id in tempSessionIds,
                        onClick = { onRoomSelected(room) },
                        onTogglePin = { viewModel.sessions.togglePin(room.id) },
                        lexicon = lexicon,
                        // Direct door only: the gateway's read watermark can be pushed back by
                        // hand (Desktop's "Mark as unread"). The open session is exempt — you
                        // are looking at it, and the next newest message would stamp it read
                        // again before you saw the mark.
                        onMarkUnread = if (direct && !room.hasUnread && currentRoom?.id != room.id) {
                            { viewModel.sessions.markSessionUnread(room.id) }
                        } else null,
                        // Each transport brings its own verbs to the long-press menu: Matrix
                        // rooms have membership and a server-side avatar; gateway sessions
                        // rename and delete (there is nothing to "leave").
                        onSetAvatar = if (direct) null else {
                            {
                                pendingAvatarRoomId = room.id
                                avatarPicker.launch("image/*")
                            }
                        },
                        onLeave = if (direct) null else {
                            { viewModel.matrixRooms.leaveRoom(room.id) }
                        },
                        onInvite = if (direct) null else {
                            { userId -> viewModel.matrixRooms.inviteUser(room.id, userId) }
                        },
                        onRename = if (direct) {
                            { title -> viewModel.sessions.renameSession(room.id, title) }
                        } else null,
                        onDelete = if (direct) {
                            { viewModel.sessions.deleteSession(room.id) }
                        } else null,
                        // Archive (2.10): out of the list, still on the gateway; the Archived
                        // lens brings it back. A row already under that lens offers the reverse.
                        onArchive = if (direct && lens != LENS_ARCHIVED) {
                            { viewModel.sessions.archiveSession(room.id) }
                        } else null,
                        onUnarchive = if (direct && lens == LENS_ARCHIVED) {
                            { viewModel.sessions.unarchiveSession(room.id) }
                        } else null,
                        onExport = if (direct && exportSupported) {
                            { exportRoom = room }
                        } else null,
                        onCopyId = if (direct) {
                            {
                                val cm = moveCtx.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("session id", room.id))
                                viewModel.toast(moveCtx.getString(R.string.chat_session_id_copied))
                            }
                        } else null,
                        moveTargets = if (direct) moveTargets else emptyList(),
                        onMoveToProject = if (direct) {
                            { target ->
                                viewModel.projects.moveSessionToProject(room.id, target) { err ->
                                    viewModel.toast(err ?: moveCtx.getString(R.string.chat_moved_to_project, target.name))
                                }
                            }
                        } else null,
                        avatarLoader = { viewModel.media.loadAvatar(it) },
                        previewLoader = { viewModel.media.roomPreview(room.id, room.timestamp) },
                        // Rows glide to their new place when activity reorders the list.
                        modifier = Modifier.animateItem(),
                    )
                    }
                }

                // The next page (2.10): the roster is fifty deep by default and a gateway you
                // have talked to for weeks holds hundreds. One row, at the end, that asks.
                if (direct && query.isBlank() && !lensed && hasMore) item(key = "show-older") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !loadingMore) { viewModel.sessions.loadMoreSessions() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            KeryxGlyphs.ChevronDown, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            if (loadingMore) stringResource(R.string.chat_drawer_reaching_back) else stringResource(R.string.chat_drawer_show_older, lexicon.plural),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            fontSize = KeryxType.body,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Deep search (direct door): the gateway's FTS over transcript CONTENT — the
                // thing the title filter above can never answer. Only sessions the local list
                // didn't already match, so the two sections never show the same row twice.
                if (deepHits.isNotEmpty()) {
                    val shown = filtered.mapTo(HashSet()) { it.id }
                    val extras = deepHits.filterNot { it.sessionId in shown }.distinctBy { it.sessionId }
                    if (extras.isNotEmpty()) {
                        item { DrawerSectionHeader(stringResource(R.string.chat_drawer_in_transcripts)) }
                        items(extras, key = { "hit-${it.sessionId}" }) { hit ->
                            SearchHitRow(hit = hit, onClick = {
                                val room = rooms.firstOrNull { it.id == hit.sessionId }
                                    ?: RoomProfile(
                                        id = hit.sessionId,
                                        name = hit.title,
                                        type = RoomType.DIRECT_MESSAGE,
                                        timestamp = hit.lastActive,
                                    )
                                onRoomSelected(room)
                            })
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // The status strip (2.10): the machine, in one line — the link's dot, the brain,
            // the gateway's version — and Settings at its end. The strip IS the Gateway door:
            // tap the words and you are in the place that is about them (the dashboard's
            // sidebar footer, with the Desktop's status bar folded into it).
            val hubHealth by viewModel.hub.health.collectAsState()
            val linkHealth by viewModel.linkHealth.collectAsState()
            val stripContext = androidx.compose.ui.platform.LocalContext.current
            DrawerStatusStrip(
                health = linkHealth,
                model = if (viewModel.isDemo) stringResource(R.string.chat_demo_gateway) else caps?.model.orEmpty(),
                version = hubHealth.data?.version.orEmpty(),
                onOpenGateway = {
                    // The demo has no gateway page to show; the offer is the real one.
                    if (viewModel.isDemo) viewModel.toast(stripContext.getString(R.string.chat_demo_gateway_page), stripContext.getString(R.string.chat_connect)) {
                        viewModel.doors.leaveDemo()
                        chat.keryx.app.presentation.ui.components.relaunchApp(stripContext)
                    } else {
                        viewModel.hub.refreshReasoningCaps()
                        onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Gateway)
                    }
                },
                onOpenSettings = { onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Settings) },
            )
        }
    }
}

/**
 * One thin row: dot · brain · version, then the settings glyph. The words are a button to the
 * Gateway (a 44dp-tall target the width of the drawer, not a dot); the glyph is a button to
 * Settings. With no link at all it still says what it knows — the link's own state — so the
 * strip never reads blank.
 */
@Composable
internal fun DrawerStatusStrip(
    health: chat.keryx.app.presentation.LinkHealth,
    model: String,
    version: String,
    onOpenGateway: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val words = listOfNotNull(
        model.takeIf { it.isNotBlank() },
        version.takeIf { it.isNotBlank() }?.let { "hermes $it" },
    ).joinToString(" · ").ifBlank {
        chat.keryx.app.presentation.ui.components.linkHealthLabel(health)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).tourTarget(KeryxTour.MACHINE),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = stringResource(R.string.chat_open_gateway), onClick = onOpenGateway)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            chat.keryx.app.presentation.ui.components.LinkHealthDot(health = health, compact = true)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = words,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = KeryxType.micro,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                KeryxGlyphs.Sliders,
                contentDescription = stringResource(R.string.chat_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The phone's local midnight, in epoch ms — the one number the shelves need from a clock. */
internal fun localMidnightMs(now: Long = System.currentTimeMillis()): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * A shelf's header: the section voice, then what the shelf holds — its count, and how many of
 * those are unread — and a chevron that says whether it is open. Folded, the rows are put away,
 * not hidden: the numbers stay, so nothing waits behind a header you cannot read.
 */
@Composable
internal fun DrawerShelfHeader(
    title: String,
    count: Int,
    unread: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevron by animateFloatAsState(if (expanded) 0f else -90f, label = "shelfChevron")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // A tap target, so 48dp at least: the padding alone came to 46 (3.0 accessibility pass).
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 8.dp, start = 8.dp, end = 4.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = chat.keryx.app.presentation.ui.components.keryxAccentInk(),
            fontFamily = chat.keryx.app.theme.CinzelFamily,
            fontSize = KeryxType.caption,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = count.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = KeryxType.micro,
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Folded with unread inside: the same pill the doors wear, so "something new here"
        // reads the same on a shelf as on a door.
        AnimatedVisibility(visible = !expanded && unread > 0, enter = keryxPop(), exit = keryxVanish()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .requiredHeight(16.dp)
                    .requiredWidthIn(min = 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = DoorBadge.label(unread),
                    color = contrastColorFor(MaterialTheme.colorScheme.primary),
                    fontSize = KeryxType.micro,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Icon(
            KeryxGlyphs.ChevronDown,
            contentDescription = if (expanded) stringResource(R.string.chat_shelf_fold, title) else stringResource(R.string.chat_shelf_open, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = chevron },
        )
    }
}

@Composable
fun DrawerSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = chat.keryx.app.presentation.ui.components.keryxAccentInk(),
        fontFamily = chat.keryx.app.theme.CinzelFamily,
        fontSize = KeryxType.caption,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, start = 8.dp)
    )
}

/** What a lens chip wears before its label. */
private enum class LensMark { NONE, NEEDS_YOU, LIVE }

/**
 * One of the drawer's lenses (3.0): a filled chip with its count in a pill, ink-filled when it is
 * the lens in force. Was Material's outlined FilterChip with "Label · 3" text.
 */
@Composable
private fun LensChip(
    label: String,
    count: Int,
    selected: Boolean,
    mark: LensMark,
    live: Boolean,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accentInk = chat.keryx.app.presentation.ui.components.keryxAccentInk()
    val fill by animateColorAsState(
        if (selected) ink else MaterialTheme.colorScheme.surfaceVariant, label = "lensFill",
    )
    val text = if (selected) MaterialTheme.colorScheme.surface else ink
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        when (mark) {
            LensMark.NEEDS_YOU -> Icon(
                KeryxGlyphs.Warning,
                contentDescription = null,
                tint = if (selected) text else accentInk,
                modifier = Modifier.size(15.dp),
            )
            LensMark.LIVE -> chat.keryx.app.presentation.ui.components.KeryxBreathingDot(
                MaterialTheme.colorScheme.primary, alive = live, size = 7.dp,
            )
            LensMark.NONE -> {}
        }
        Text(label, color = text, fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (count > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = DoorBadge.label(count),
                    // Its pill is an accent wash on the chip's own fill, a ground deeper than the
                    // one accentInk is tuned for: 4.15:1 before (3.0 accessibility pass).
                    color = if (selected) contrastColorFor(MaterialTheme.colorScheme.primary)
                    else chat.keryx.app.theme.KeryxInk.readable(
                        accentInk, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f).compositeOver(fill),
                    ),
                    fontSize = KeryxType.micro,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun RoomRow(
    room: RoomProfile,
    isSelected: Boolean,
    isPinned: Boolean,
    /** A session living on borrowed time — the next cold start deletes it (direct door). */
    isTemporary: Boolean = false,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    lexicon: chat.keryx.core.model.DoorLexicon = chat.keryx.core.model.DoorLexicon.MATRIX,
    // Gateway-only: push the read watermark back (null = not offered for this row).
    onMarkUnread: (() -> Unit)? = null,
    // Matrix-only affordances (m.room.avatar / membership) — null on the direct door.
    onSetAvatar: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onInvite: ((String) -> Unit)? = null,
    // Gateway-only affordances (the row IS a session) — null on Matrix.
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    onCopyId: (() -> Unit)? = null,
    /** Export and share out (2.16) — the gateway's export as Markdown or JSON. */
    onExport: (() -> Unit)? = null,
    /** Projects that can claim this session (they have a folder); empty = no menu entry. */
    moveTargets: List<chat.keryx.core.model.ProjectInfo> = emptyList(),
    onMoveToProject: ((chat.keryx.core.model.ProjectInfo) -> Unit)? = null,
    avatarLoader: suspend (String) -> ByteArray?,
    previewLoader: (suspend () -> String?)? = null,
    /** A turn is running in this session right now (2.16): the row breathes in its own light. */
    running: Boolean = false,
    /** The drawer is on screen: the breath only animates then. The drawer stays composed while
     *  shut, and a breathing row behind it repainted every frame of every turn (3.0 perf). */
    breathe: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
    // 2.16: the row's own light, for the running breath and the unread sweep. The drawer is
    // composed while shut too, so the breath is gated on [breathe] and read while drawing.
    val light = chat.keryx.app.presentation.ui.components.roomLight(room.name)
    val breath = chat.keryx.app.presentation.ui.components.breathingAlphaState(active = running && breathe, low = 0.0f, periodMillis = 2200)
    val unreadSweep = remember { androidx.compose.animation.core.Animatable(0f) }
    var wasUnread by remember { mutableStateOf(room.hasUnread) }
    LaunchedEffect(room.hasUnread) {
        // One pass of light the moment a row turns unread — not on first show, not again.
        if (room.hasUnread && !wasUnread) {
            unreadSweep.snapTo(0f)
            unreadSweep.animateTo(1f, androidx.compose.animation.core.tween(720))
        }
        wasUnread = room.hasUnread
    }
    // Long-press menu (pin/unpin + the transport's own verbs). Replaced the instant pin toggle
    // once leaving rooms became possible — two destructive-adjacent actions can't share one
    // blind gesture.
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    val hasMenu = onLeave != null || onInvite != null || onRename != null || onDelete != null || onMarkUnread != null ||
        onArchive != null || onUnarchive != null
    // Last-message snippet, resolved lazily per row (cached in the VM keyed on room.timestamp so
    // it only refetches after new activity). Keyed on the timestamp so a new message refreshes it.
    val preview by produceState<String?>(initialValue = null, room.id, room.timestamp) {
        value = previewLoader?.invoke()
    }
    // The selection wash fades between rows instead of jumping, and the row sinks under the
    // finger (2.8.1) — the ripple and the scale read the same press.
    val rowFill by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        label = "roomFill",
    )
    // On the void the raw accent read 4.41:1 on that wash over the drawer: held to it there too.
    val selectedInk = chat.keryx.app.theme.KeryxInk.readable(
        chat.keryx.app.presentation.ui.components.keryxAccentInk(),
        listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant)
            .map { MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(it) },
    )
    val rowPress = remember { MutableInteractionSource() }
    // A fork nested under its parent (2.19): stepped in, with a quiet rail that turns into it,
    // so the family reads as one without the rows merging.
    val forkIndent = (room.forkDepth * 16).dp
    val railColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Box(
        modifier.then(
            if (room.forkDepth <= 0) Modifier
            else Modifier
                .drawBehind {
                    val x = forkIndent.toPx() - 8.dp.toPx()
                    val mid = size.height / 2f
                    val w = 1.25.dp.toPx()
                    drawLine(railColor, Offset(x, 0f), Offset(x, mid), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(railColor, Offset(x, mid), Offset(forkIndent.toPx() - 2.dp.toPx(), mid), strokeWidth = w, cap = StrokeCap.Round)
                }
                .padding(start = forkIndent)
        )
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // The open chat stands up out of the list (3.0 depth); the rest lie in the drawer.
            .then(
                if (isSelected) Modifier.keryxRaised(
                    RoundedCornerShape(10.dp), height = chat.keryx.app.presentation.ui.components.KeryxDepth.Height.Lifted,
                    tint = MaterialTheme.colorScheme.primary,
                ) else Modifier,
            )
            .clip(RoundedCornerShape(10.dp))
            .background(rowFill)
            .then(
                if (running) Modifier.drawBehind { drawRect(light.copy(alpha = 0.04f + 0.08f * breath.value)) } else Modifier
            )
            .keryxLightSweep(
                light, MaterialTheme.colorScheme.tertiary,
                core = chat.keryx.app.presentation.ui.components.keryxSweepCore(),
            ) { unreadSweep.value }
            .keryxPressScale(rowPress)
            .combinedClickable(
                interactionSource = rowPress,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
                onLongClick = {
                    haptics.press()
                    if (hasMenu) menuOpen = true else onTogglePin()
                },
            )
            .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
    ) {
        // Long-press the avatar (specifically) to set a room photo — Matrix only.
        Box(
            modifier = if (onSetAvatar != null) Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onSetAvatar,
            ) else Modifier
        ) {
            RoomAvatar(room = room, selected = isSelected, avatarLoader = avatarLoader, running = running, live = breathe)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shownRoomName(room.name),
                    // The open room's name in the accent's ink: the raw accent on its own 12% wash
                    // read 2.74:1 on parchment (3.0 accessibility pass).
                    color = if (isSelected) selectedInk else MaterialTheme.colorScheme.onSurface,
                    fontSize = KeryxType.titleLarge,
                    fontWeight = when {
                        isSelected -> FontWeight.Bold
                        room.hasUnread -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (room.forkOf != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = KeryxGlyphs.Fork,
                        contentDescription = stringResource(R.string.chat_room_forked),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (isTemporary) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = KeryxGlyphs.Hourglass,
                        contentDescription = stringResource(R.string.chat_room_temporary),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (isPinned) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = KeryxGlyphs.Star,
                        contentDescription = stringResource(R.string.chat_room_pinned),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            // The most recent message, like a real chat client's room list. Unread rooms read a
            // touch brighter so "something new here" is visible before the count even registers.
            preview?.let {
                Text(
                    text = it,
                    // Quieter than an unread one, never below 4.5:1 on the row's own ground: a
                    // read preview at 65% measured 2.7:1 on parchment (3.0 accessibility pass).
                    color = chat.keryx.app.theme.quietInk(alpha = if (room.hasUnread) 0.95f else 0.65f, on = rowFill),
                    fontSize = KeryxType.caption,
                    fontWeight = if (room.hasUnread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (room.timestamp > 0L) {
                Text(
                    text = relativeTimeLabel(room.timestamp),
                    color = chat.keryx.app.theme.quietInk(on = rowFill),
                    fontSize = KeryxType.micro,
                )
            }
            // Unread arrives as itself — a pop, not a pixel switch — and leaves the same way.
            AnimatedVisibility(visible = room.unreadCount > 0L, enter = keryxPop(), exit = keryxVanish()) {
                Column {
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (room.unreadCount > 99) "99+" else room.unreadCount.toString(),
                        // NOT onPrimary. The scheme's onPrimary follows the THEME (white on the
                        // void), but this pill's ground is the ACCENT, which does not change with
                        // the theme — so in dark mode the count was white on amber at 3.62:1,
                        // under the 4.5 a 10sp figure needs. Ask the ground.
                        color = contrastColorFor(MaterialTheme.colorScheme.primary),
                        fontSize = KeryxType.micro,
                        fontWeight = FontWeight.Bold,
                    )
                }
                }
            }
            // The gateway knows THAT something happened since you looked, not how much:
            // a watermark, not a count. A dot says exactly that; a "1" would be a lie.
            AnimatedVisibility(visible = room.unreadCount <= 0L && room.unread, enter = keryxPop(), exit = keryxVanish()) {
                Column {
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                }
            }
        }
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(if (isPinned) lexicon.unpinVerb else lexicon.pinVerb)
                    // The gateway's pin is a "keep": say so once, under the verb, so the
                    // star means the same thing here as in the Desktop sidebar.
                    if (lexicon.pinHint.isNotBlank() && !isPinned) {
                        Text(
                            lexicon.pinHint,
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            leadingIcon = {
                Icon(
                    KeryxGlyphs.Star,
                    contentDescription = null,
                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            },
            onClick = { menuOpen = false; onTogglePin() },
        )
        if (onMarkUnread != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_mark_unread)) },
                onClick = { menuOpen = false; onMarkUnread() },
            )
        }
        if (onArchive != null) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.chat_archive))
                        Text(stringResource(R.string.chat_archive_hint), fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                leadingIcon = { Icon(KeryxGlyphs.Archive, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { menuOpen = false; onArchive() },
            )
        }
        if (onUnarchive != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_restore_to_list)) },
                leadingIcon = { Icon(KeryxGlyphs.Archive, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { menuOpen = false; onUnarchive() },
            )
        }
        if (onExport != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_export_ellipsis)) },
                leadingIcon = { Icon(KeryxGlyphs.Share, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { menuOpen = false; onExport() },
            )
        }
        if (onCopyId != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_session_id)) },
                leadingIcon = { Icon(KeryxGlyphs.Copy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { menuOpen = false; onCopyId() },
            )
        }
        if (onInvite != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_invite_user)) },
                onClick = { menuOpen = false; inviteOpen = true },
            )
        }
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_rename_ellipsis)) },
                onClick = { menuOpen = false; renameOpen = true },
            )
        }
        if (onMoveToProject != null && moveTargets.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_move_to_project)) },
                onClick = { menuOpen = false; moveOpen = true },
            )
        }
        if (onLeave != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_leave_room), color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmLeave = true },
            )
        }
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_delete_session_ellipsis), color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmDelete = true },
            )
        }
    }
    // The project roster, as a second menu on the same anchor (a submenu would be a
    // nested popup; this reads as "which one?" and dismisses like the first).
    DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
        moveTargets.forEach { target ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(target.name)
                        target.anchorPath?.let {
                            Text(it, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                },
                onClick = { moveOpen = false; onMoveToProject?.invoke(target) },
            )
        }
    }
    } // end anchor Box
    if (inviteOpen && onInvite != null) {
        var inviteId by remember { mutableStateOf("") }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { inviteOpen = false },
            title = { Text(stringResource(R.string.chat_invite_to, room.name), fontSize = KeryxType.titleLarge) },
            text = {
                OutlinedTextField(
                    value = inviteId,
                    onValueChange = { inviteId = it },
                    placeholder = { Text("@user:server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = inviteId.isNotBlank(),
                    onClick = { inviteOpen = false; onInvite(inviteId.trim()) },
                ) { Text(stringResource(R.string.chat_invite)) }
            },
            dismissButton = {
                TextButton(onClick = { inviteOpen = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.chat_leave_title, shownRoomName(room.name)), fontSize = KeryxType.titleLarge) },
            text = { Text(stringResource(R.string.chat_leave_body), fontSize = KeryxType.body) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onLeave?.invoke() }) {
                    Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
    if (renameOpen && onRename != null) {
        var newTitle by remember { mutableStateOf(room.name) }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { renameOpen = false },
            title = { Text(stringResource(R.string.chat_rename_session), fontSize = KeryxType.titleLarge) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank() && newTitle.trim() != room.name,
                    onClick = { renameOpen = false; onRename(newTitle.trim()) },
                ) { Text(stringResource(R.string.chat_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.chat_delete_title, shownRoomName(room.name)), fontSize = KeryxType.titleLarge) },
            text = { Text(stringResource(R.string.chat_delete_session_body), fontSize = KeryxType.body) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) {
                    Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

/** One pending invitation: room name + the accept/decline decision, right in the drawer. */
@Composable
private fun InviteRow(
    invite: RoomInvite,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = invite.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = KeryxType.title,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.chat_invited_to_room),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = KeryxType.micro,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.chat_decline), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = KeryxType.body)
            }
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.chat_accept), color = MaterialTheme.colorScheme.primary, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * One gateway deep-search hit: the session title over the transcript line that matched, with
 * the server's `>>>…<<<` match markers rendered as emphasis instead of shown raw.
 */
@Composable
private fun SearchHitRow(
    hit: chat.keryx.core.transport.SessionSearchHit,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val snippet = remember(hit.snippet, accent) {
        androidx.compose.ui.text.buildAnnotatedString {
            var rest = hit.snippet.replace('\n', ' ')
            while (true) {
                val s = rest.indexOf(">>>")
                val e = if (s >= 0) rest.indexOf("<<<", s + 3) else -1
                if (s < 0 || e < 0) { append(rest); break }
                append(rest.substring(0, s))
                withStyle(
                    androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)
                ) { append(rest.substring(s + 3, e)) }
                rest = rest.substring(e + 3)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hit.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = KeryxType.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hit.lastActive > 0L) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = relativeTimeLabel(hit.lastActive),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = KeryxType.micro,
                )
            }
        }
        Text(
            text = snippet,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = KeryxType.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * A circular room/DM avatar: the real room photo when one is set, otherwise a tasteful colored
 * monogram derived from the name (so every row reads like a real chat client).
 */
@Composable
private fun RoomAvatar(
    room: RoomProfile,
    selected: Boolean,
    avatarLoader: suspend (String) -> ByteArray?,
    /** A turn is running here: a ring turns around the plate (3.0; was a dot by the title). */
    running: Boolean = false,
    /** The drawer is on screen: only then does the ring turn (see RoomRow's breathe). */
    live: Boolean = true,
) {
    val ringInk = chat.keryx.app.presentation.ui.components.roomTint(room.name).ink
    // Reduced motion (and Battery Saver) hold the ring still: it still says "running".
    val reducedRing by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    val spin = if (running && live && !reducedRing) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "roomRing").animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            ),
            label = "roomRingSpin",
        )
    } else null
    val runningLabel = stringResource(R.string.chat_running)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
            .then(if (running) Modifier.semantics { contentDescription = runningLabel } else Modifier)
            .drawWithContent {
                drawContent()
                if (!running) return@drawWithContent
                // Read in draw: the turning ring repaints, nothing recomposes.
                val a = spin?.value ?: 0f
                val stroke = 2.dp.toPx()
                val inset = stroke / 2f
                drawArc(
                    ringInk.copy(alpha = 0.22f), 0f, 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    ringInk, a - 90f, 110f, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
    ) {
        RoomAvatarPlate(room, selected, avatarLoader)
    }
}

@Composable
private fun RoomAvatarPlate(
    room: RoomProfile,
    selected: Boolean,
    avatarLoader: suspend (String) -> ByteArray?,
) {
    val url = room.avatarUrl
    val cached = remember(url) { url?.let { chat.keryx.app.presentation.ui.components.KeryxBitmapCache.get(it) } }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = cached, url) {
        if (cached != null || url == null) return@produceState
        value = avatarLoader(url)?.let { bytes ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()?.also { chat.keryx.app.presentation.ui.components.KeryxBitmapCache.put(url, it) }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = room.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(CircleShape),
        )
    } else {
        val base = roomAvatarColor(room.name)
        val sigil = RoomSigils.of(room.heraldIds)
        if (sigil != RoomSigil.None) {
            RoomSigilAvatar(sigil = sigil, base = base, size = 34.dp, highlighted = selected)
        } else {
            // The room's tinted plate (3.0): its hue family, a soft ground and a deep Cinzel
            // monogram on paper (light on the void), every pair past 4.5:1.
            val tint = chat.keryx.app.presentation.ui.components.roomTint(room.name)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(tint.ground),
            ) {
                Text(
                    text = room.name.trimStart('@', '#', '!').trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                    color = tint.ink,
                    fontFamily = chat.keryx.app.theme.CinzelFamily,
                    fontSize = KeryxType.title,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// The palette lives in RoomLight.kt — it was duplicated here and in the Quick Rooms deck.
private fun roomAvatarColor(name: String): Color =
    chat.keryx.app.presentation.ui.components.roomLightRaw(name)

/** [formatRelativeTime], in the user's language. */
@Composable
internal fun relativeTimeLabel(ts: Long): String {
    if (ts <= 0L) return ""
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> stringResource(R.string.chat_age_now)
        diff < 3_600_000L -> stringResource(R.string.chat_age_minutes, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(R.string.chat_age_hours, (diff / 3_600_000L).toInt())
        diff < 604_800_000L -> stringResource(R.string.chat_age_days, (diff / 86_400_000L).toInt())
        else -> stringResource(R.string.chat_age_weeks, (diff / 604_800_000L).toInt())
    }
}

/** Compact relative timestamp: now, 5m, 3h, 2d, 1w. English only: prefer [relativeTimeLabel] in composables. */
internal fun formatRelativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 604_800_000L -> "${diff / 86_400_000L}d"
        else -> "${diff / 604_800_000L}w"
    }
}

/**
 * One door in the drawer's footer: icon over a small label, launcher-style. Was a horizontal
 * icon+text pill, three to a row — legible, but nine doors made the footer a third of the
 * drawer. The auto-sizing label survives from the pill era: a fixed size clips ("Mission"
 * lost its s at large font scales) and wrapping makes the cells different heights.
 */
@Composable
private fun DrawerDoor(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    /** Unread count pinned to the icon's corner; zero draws nothing. */
    badge: Int = 0,
    /**
     * The count is a summons, not news (2.14.1 — the Missions door's "needs you"): the badge
     * becomes a warm [chat.keryx.app.presentation.ui.components.KeryxStatus.warn] orb with a
     * slow glow breathing behind it, so "3 cards wait on you" never reads as "3 unread".
     */
    urgent: Boolean = false,
    /** Whether the urgent glow may move; the host passes the drawer's real visibility.
     *  Reduced motion stills it regardless (breathingAlpha's own rule). */
    alive: Boolean = true,
    onClick: () -> Unit,
) {
    val orb = if (urgent) chat.keryx.app.presentation.ui.components.KeryxStatus.warn
        else MaterialTheme.colorScheme.primary
    // Only the urgent door asks for a breath: breathingAlpha registers a power-saver listener,
    // and the other doors have no use for one. (`urgent` is fixed per call site.)
    val glow = if (urgent) chat.keryx.app.presentation.ui.components.breathingAlpha(
        active = alive && badge > 0, low = 0.15f, periodMillis = 2400,
    ) else 1f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // Each door stands up from the drawer as a small tile (3.0 depth): a row of bare
            // icons read as labels, not as places you can step into.
            .padding(vertical = 3.dp)
            .keryxRaised(RoundedCornerShape(12.dp))
            .keryxPressable(onClick = onClick)
            .padding(vertical = 9.dp),
    ) {
        // The box is the icon's exact size: the badge overflows its corner and never adds
        // height, so a badged door's label sits level with its neighbours.
        Box(modifier = Modifier.size(22.dp)) {
            Icon(
                icon,
                contentDescription = when {
                    badge <= 0 -> label
                    urgent -> pluralStringResource(R.plurals.chat_door_needs_you, badge, label, DoorBadge.label(badge))
                    else -> stringResource(R.string.chat_door_unread, label, DoorBadge.label(badge))
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(),
            )
            // The badge pops in and out as itself; the count inside rolls up as it changes.
            androidx.compose.animation.AnimatedVisibility(
                visible = badge > 0,
                enter = keryxPop(), exit = keryxVanish(),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp),
            ) {
                // The room list's unread pill, shrunk to a corner: same colour, same cap, so
                // one grammar says "unread" everywhere in the drawer. `required*` sizes ignore
                // the 22dp box's constraints — "99+" may run wider than the icon.
                var shown by remember { mutableStateOf(badge) }
                if (badge > 0) shown = badge // keep the last real count through the exit
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .requiredHeight(16.dp)
                        .requiredWidthIn(min = 16.dp)
                        // The urgent orb's halo: drawn behind the pill and outside its bounds
                        // (nothing up the chain clips), so the glow breathes without moving
                        // the count or the label under it.
                        .then(
                            if (urgent) Modifier.drawBehind {
                                drawCircle(
                                    color = orb.copy(alpha = 0.45f * glow),
                                    radius = size.minDimension / 2f + 3.5.dp.toPx(),
                                )
                            } else Modifier,
                        )
                        .clip(CircleShape)
                        .background(orb)
                        .padding(horizontal = 4.dp),
                ) {
                    AnimatedContent(
                        targetState = DoorBadge.label(shown),
                        transitionSpec = {
                            (fadeIn(KeryxMotion.settle) + slideInVertically(KeryxMotion.settleInt) { it })
                                .togetherWith(fadeOut(KeryxMotion.leave) + slideOutVertically(KeryxMotion.leaveInt) { -it })
                        },
                        label = "doorBadge",
                    ) { label ->
                    Text(
                        text = label,
                        // Same ground as the room list's pill, so the same rule: the accent
                        // decides, not the theme (see RoomRow). The urgent orb: its own hue.
                        color = contrastColorFor(orb),
                        fontSize = KeryxType.micro,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        // Font padding trimmed, or a 9sp glyph rides a 14dp line and the pill
                        // is a lozenge rather than a circle.
                        style = androidx.compose.ui.text.TextStyle(
                            lineHeight = 10.sp,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                            ),
                        ),
                    )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.5.sp, stepSize = 0.25.sp),
            maxLines = 1, softWrap = false,
        )
    }
}

/** The door badge's text: a count, capped where a wider pill would swallow the icon. */
internal object DoorBadge {
    fun label(count: Int): String = if (count > 99) "99+" else count.toString()
}

/** The drawer's own edge: a soft sheet-radius on the side that meets the room, flat at the screen edge. */
internal val DrawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
