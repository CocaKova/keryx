package chat.keryx.app.presentation.ui

import chat.keryx.app.presentation.ui.components.HeraldConfig
import chat.keryx.app.presentation.ui.components.LocalHeraldConfig
import chat.keryx.app.presentation.ui.components.keryxDuskSky
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.app.presentation.ui.nav.KeryxNavHost
import chat.keryx.app.presentation.ui.nav.rememberKeryxNav
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HermesApp(viewModel: ChatViewModel) {
    val loggedIn by viewModel.isLoggedIn.collectAsState()
    if (!loggedIn) {
        LoginScreen(viewModel)
        return
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val currentRoom by viewModel.currentRoom.collectAsState()
    val linkHealth by viewModel.linkHealth.collectAsState()
    val heraldIds by viewModel.agentMatrixId.collectAsState()
    val heraldAccents by viewModel.heraldAccents.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val awaitingReply by viewModel.awaitingReply.collectAsState()

    // The navigation spine (2.0): full-screen places live on this stack above the chat floor.
    val nav = rememberKeryxNav()

    // An assist summon walks the app home: whatever place was open sinks away, the drawer
    // closes, and ChatScreen's own collector focuses the composer.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var seen = viewModel.assistSummon.value
        viewModel.assistSummon.collect { n ->
            if (n > seen) {
                seen = n
                nav.home()
                drawerState.close()
            }
        }
    }
    val openSpace: (KeryxDest) -> Unit = { dest ->
        focusManager.clearFocus()
        keyboard?.hide()
        scope.launch { drawerState.close() }
        nav.open(dest)
    }

    // The drawer can be opened by swipe, not just the menu button — the moment the gesture commits
    // (targetValue flips to Open) drop focus and hide the IME so the keyboard never sits on top of
    // the drawer blocking the room list.
    androidx.compose.runtime.LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    // Surface one-shot status messages (e.g. room-photo set result) regardless of which screen
    // triggered them, so failures are never silent.
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toasts.collect { msg ->
            android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Drawer-open assist for gestures that start on a horizontal scrollable (code block, wide
    // table). Those consume the pointer themselves, so the drawer's own drag never starts — but
    // their *unconsumed* leftover delta flows here through nested scroll. A rightward drag on a
    // block already at its left edge accumulates and, past a thumb-sized threshold, opens the
    // drawer: swiping right anywhere finally means "open the drawer", even over code.
    val drawerAssistThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val drawerAssist = remember(drawerState, drawerAssistThresholdPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput ||
                    drawerState.currentValue != DrawerValue.Closed || available.x <= 0f
                ) return androidx.compose.ui.geometry.Offset.Zero
                pulled += available.x
                if (pulled >= drawerAssistThresholdPx) {
                    pulled = 0f
                    scope.launch { drawerState.open() }
                }
                return androidx.compose.ui.geometry.Offset(available.x, 0f)
            }
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    // 2.3 §1: the configured heralds, resolved once for the whole app — every bubble, sigil and
    // spinner below reads its sender's light out of this. (Body left at its original indent so the
    // wrapper stays a two-line diff.)
    // 2.5: the tick vocabulary, resolved once. Provided here rather than built at each call site
    // so that Settings ▸ Interface ▸ "Haptic Feedback" has exactly one place to be obeyed.
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    // NOT keyed on hapticsEnabled: the instance must outlive every flip of the switch. Consumers
    // capture it inside blocks that never restart (pointerInput keyed on a message id), so handing
    // out a fresh instance per flip strands them on the old one — which on device looks like a
    // switch that is backwards. One permanent instance, reading the flag live.
    val hapticsOn = androidx.compose.runtime.rememberUpdatedState(hapticsEnabled)
    val keryxHaptics = remember(hapticFeedback, scope) {
        chat.keryx.app.presentation.ui.components.KeryxHaptics(
            hapticFeedback, { hapticsOn.value }, scope,
        )
    }

    // The completion tick: the agent stopped working. Fired on the awaiting -> idle edge and only
    // while the app is actually resumed — a phone in a pocket gets a notification for a finished
    // turn, and buzzing twice more for the same event is one signal too many.
    val hapticLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(hapticLifecycle, keryxHaptics) {
        hapticLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var wasAwaiting = viewModel.awaitingReply.value
            viewModel.awaitingReply.collect { awaiting ->
                if (wasAwaiting && !awaiting) keryxHaptics.completion()
                wasAwaiting = awaiting
            }
        }
    }

    CompositionLocalProvider(
        chat.keryx.app.presentation.ui.components.LocalKeryxHaptics provides keryxHaptics,
        LocalHeraldConfig provides HeraldConfig(
            ids = chat.keryx.core.model.Heralds.parseIds(heraldIds),
            overrides = heraldAccents,
        )
    ) {
    KeryxNavHost(
        nav = nav,
        root = {
    ModalNavigationDrawer(
        modifier = Modifier.nestedScroll(drawerAssist),
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerContent(
                    viewModel = viewModel,
                    onRoomSelected = { room ->
                        viewModel.selectRoom(room)
                        scope.launch { drawerState.close() }
                    },
                    onOpenSpace = openSpace,
                    // Visible while open OR mid-swing, so the emblem is alive as it slides in.
                    drawerVisible = drawerState.currentValue == DrawerValue.Open ||
                        drawerState.targetValue == DrawerValue.Open,
                )
            }
        }
    ) {
        // The whole-screen backdrop: the living dusk sky (AGSL on 13+, static aurora before) —
        // bubbles and bars sit on top of it (transparent), so the sky reads as the actual room.
        Box(modifier = Modifier.fillMaxSize().keryxDuskSky()) {
        // The ambient void: vast accent glows adrift behind the chat, minutes per pass.
        chat.keryx.app.presentation.ui.components.AmbientVoid()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = 18.sp)
                            currentRoom?.let { room ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = room.name,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // Which agent profile answers in this room (from the gateway's
                                    // room→profile routing map): a small tinted chip by the name.
                                    val caps by viewModel.hub.reasoningCaps.collectAsState()
                                    caps?.roomProfiles?.get(room.id)
                                        // "default" is the unnamed home profile — a "Default" chip
                                        // is noise; only the named secondaries earn the badge.
                                        ?.takeIf { !it.equals("default", ignoreCase = true) }
                                        ?.let { profile ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = profile.replaceFirstChar { it.uppercase() },
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { focusManager.clearFocus(); scope.launch { drawerState.open() } }) {
                            Icon(
                                chat.keryx.app.presentation.ui.components.KeryxGlyphs.Sidebar,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    actions = {
                        // Hermes Link health, whispered: a tiny dot that breathes while tokens flow,
                        // dims when idle, warms red when the gateway is unreachable. Tap opens the
                        // Gateway — the live who/what/how of the system Keryx is pointed at. The dot
                        // reports on the link, so it lands on the space that is about the link; the
                        // Workshop is reached from the drawer, where you go looking for it on purpose.
                        LinkHealthDot(health = linkHealth, onClick = {
                            viewModel.hub.refreshReasoningCaps()
                            openSpace(KeryxDest.Gateway)
                        })
                        // Direct door: the rows ARE sessions, so "new session" means a new row —
                        // the same sheet the drawer's plus opens, reachable without the drawer.
                        // (It used to send /new here, which resets the OPEN session in place: the
                        // glyph said "new" and the roster gained nothing.) Offered even with no
                        // session open — it is how the first one gets made.
                        if (viewModel.transportIsDirect) {
                            var showNewSession by remember { mutableStateOf(false) }
                            IconButton(onClick = { showNewSession = true }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.NewChat,
                                    contentDescription = "New session",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (showNewSession) {
                                chat.keryx.app.presentation.ui.components.NewChatSheet(
                                    viewModel = viewModel,
                                    onDismiss = { showNewSession = false },
                                )
                            }
                        }
                        // A scheduled run, open as a room: the pin is right here, where you
                        // finished reading it. Only over a run the Runs board knows (never over
                        // a conversation — those pin from the drawer), and it reads the run's
                        // own flag so the glyph and the shelf never disagree.
                        currentRoom?.let { room ->
                            val cronBoard by viewModel.hub.cron.collectAsState()
                            val board = cronBoard.data
                            if (board != null && board.isRun(room.id)) {
                                val pinned = board.isPinned(room.id)
                                IconButton(onClick = { viewModel.hub.cronSetPinned(room.id, !pinned) }) {
                                    Icon(
                                        if (pinned) chat.keryx.app.presentation.ui.components.KeryxGlyphs.PinFilled
                                        else chat.keryx.app.presentation.ui.components.KeryxGlyphs.Pin,
                                        contentDescription = if (pinned) "Unpin this run" else "Pin this run",
                                        tint = if (pinned) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                        if (currentRoom != null) {
                            // Matrix: a room is a profile, and "new session" there means /new —
                            // one tap sends it, same auto-send the command palette does, so the
                            // gateway's fresh-session reply lands in the chat itself.
                            // While a turn is LIVE, /new invalidates the running generation on the
                            // gateway (the run's result is discarded), so an accidental tap here
                            // was a silent run-killer (08-25 diagnosis) — confirm exactly when
                            // there is something to lose; idle taps stay one-tap.
                            var confirmNewSession by remember { mutableStateOf(false) }
                            val liveTurn by viewModel.liveTurnSigns.collectAsState()
                            if (!viewModel.transportIsDirect) IconButton(onClick = {
                                if (liveTurn || awaitingReply) confirmNewSession = true
                                else {
                                    viewModel.recordCommandUse("/new")
                                    viewModel.sendMessage("/new")
                                }
                            }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.NewChat,
                                    contentDescription = "New session",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (confirmNewSession) {
                                AlertDialog(
                                    shape = RoundedCornerShape(
                                        chat.keryx.app.presentation.ui.components.KeryxRadius.sheet
                                    ),
                                    onDismissRequest = { confirmNewSession = false },
                                    title = { Text("Start a new session?", fontSize = 16.sp) },
                                    text = {
                                        Text(
                                            "The agent is still working — /new ends the current run and its result is discarded.",
                                            fontSize = 13.sp,
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmNewSession = false
                                            viewModel.recordCommandUse("/new")
                                            viewModel.sendMessage("/new")
                                        }) { Text("End run & start new", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmNewSession = false }) { Text("Cancel") }
                                    },
                                )
                            }
                            // Reasoning moved to the composer footer (2.2, the Talaria
                            // treatment) — the dial now lives where the thinking happens.
                            // The Call (1.22): a voice conversation with this room's agent. Needs
                            // both voice endpoints; a missing one gets a pointer, not a dead mic.
                            var showCall by remember { mutableStateOf(false) }
                            IconButton(onClick = {
                                if (viewModel.voice.callReady()) showCall = true
                                else viewModel.toast("Set the STT and TTS endpoints in Settings → Voice first")
                            }) {
                                Icon(
                                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Phone,
                                    contentDescription = "Call",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (showCall) {
                                chat.keryx.app.presentation.ui.components.CallScreen(
                                    viewModel = viewModel,
                                    roomName = currentRoom?.name ?: "Keryx",
                                    onDismiss = { showCall = false },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )
            }
        ) { paddingValues ->
            // Skill Forge opens from two places — the Workshop's Skills rows (direct ViewModel call)
            // and in-chat SkillDistilled pills (via this CompositionLocal, since the render chain
            // doesn't carry the ViewModel). One shared target keeps a single hosted sheet.
            androidx.compose.runtime.CompositionLocalProvider(
                chat.keryx.app.presentation.ui.components.LocalSkillForgeOpener provides viewModel.hub::openSkillForge,
                // Quick-action chips (⟦keryx:ask⟧) send their option text as a normal message.
                chat.keryx.app.presentation.ui.components.LocalQuickActionSender provides viewModel::sendMessage,
            ) {
                ChatScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
            val skillForgeTarget by viewModel.hub.skillForgeTarget.collectAsState()
            skillForgeTarget?.let { name ->
                chat.keryx.app.presentation.ui.components.SkillForgeSheet(
                    skillName = name,
                    viewModel = viewModel,
                    onDismiss = viewModel.hub::closeSkillForge,
                )
            }
        }
        }
    }
        },
        content = { dest ->
            when (dest) {
                KeryxDest.Archive -> chat.keryx.app.presentation.ui.components.ArchiveScreen(
                    viewModel = viewModel,
                    onDismissRequest = nav::back,
                )
                KeryxDest.Projects -> chat.keryx.app.presentation.ui.components.ProjectsSpace(
                    viewModel = viewModel,
                    // Project sessions can live outside the roster (cron runs, desktop work) —
                    // the name rides along so the floor can title them.
                    onOpenSession = { id, name -> viewModel.openSessionById(id, name); nav.back() },
                    onClose = nav::back,
                )
                KeryxDest.Shipyard -> chat.keryx.app.presentation.ui.components.ShipyardSpace(
                    viewModel = viewModel,
                    onClose = nav::back,
                )
                KeryxDest.Runs -> chat.keryx.app.presentation.ui.components.RunsSpace(
                    viewModel = viewModel,
                    // A cron run lives outside every roster — the title travels so the floor
                    // can name the room it adopts (direct door; Matrix reads in-space).
                    onOpenSession = { id, name -> viewModel.openSessionById(id, name); nav.back() },
                    onClose = nav::back,
                )
                KeryxDest.Missions -> chat.keryx.app.presentation.ui.components.MissionsScreen(
                    viewModel = viewModel,
                    onDismissRequest = nav::back,
                )
                KeryxDest.Gateway -> chat.keryx.app.presentation.ui.components.GatewaySpace(
                    viewModel = viewModel,
                    health = linkHealth,
                    onDismiss = nav::back,
                )
                KeryxDest.Workshop -> chat.keryx.app.presentation.ui.components.WorkshopSpace(
                    viewModel = viewModel,
                    health = linkHealth,
                    onDismiss = nav::back,
                )
                KeryxDest.Settings -> chat.keryx.app.presentation.ui.components.SettingsPlace(
                    viewModel = viewModel,
                    onClose = nav::back,
                )
            }
        },
    )
    }
}

/**
 * Hermes Link health as a single quiet dot: accent and breathing while tokens flow, steady when the
 * last turn/probe reached the gateway, dim when untested, warm red when unreachable, gone when the
 * side-channel is off. Tapping it toasts the state in words.
 */
@Composable
private fun LinkHealthDot(
    health: chat.keryx.app.presentation.LinkHealth,
    onClick: (() -> Unit)? = null,
) {
    if (health == chat.keryx.app.presentation.LinkHealth.OFF) return
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // Stilled, LIVE holds the top of its breath — full-strength accent, still a step clear of OK's
    // 75% — so "tokens are flowing" survives Battery Saver as a state you can read at a glance.
    val reduced by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    val alpha = if (health == chat.keryx.app.presentation.LinkHealth.LIVE && !reduced) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "linkBreath")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "linkBreathAlpha",
        ).value
    } else 1f
    val color = when (health) {
        // Tokens flowing: the dot breathes BETWEEN the two accents, not just in alpha.
        chat.keryx.app.presentation.LinkHealth.LIVE ->
            androidx.compose.ui.graphics.lerp(accent2, accent, alpha).copy(alpha = 0.5f + 0.5f * alpha)
        chat.keryx.app.presentation.LinkHealth.OK -> accent.copy(alpha = 0.75f)
        chat.keryx.app.presentation.LinkHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> chat.keryx.app.presentation.ui.components.KeryxStatus.bad.copy(alpha = 0.85f)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = when (health) {
        chat.keryx.app.presentation.LinkHealth.LIVE -> "Hermes Link: streaming live"
        chat.keryx.app.presentation.LinkHealth.OK -> "Hermes Link: connected"
        chat.keryx.app.presentation.LinkHealth.UNKNOWN -> "Hermes Link: not tested yet"
        else -> "Hermes Link: unreachable — replies fall back to Matrix sync"
    }
    Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier.size(24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
                .clickable {
                    onClick?.invoke()
                        ?: android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
                },
        )
    }
}

/**
 * The reasoning control, dream-styled: a frosted rounded panel with a soft accent-gradient border
 * (same vocabulary as the reaction bar), effort levels drawn with rising intensity glyphs, and the
 * display/override actions tucked below a hairline. Effort selections persist via `--global`.
 */
@Composable
internal fun ReasoningMenu(
    expanded: Boolean,
    caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?,
    onDismiss: () -> Unit,
    onCommand: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = shape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 16.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent2.copy(alpha = 0.22f))),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                "REASONING",
                color = accent,
                fontSize = 10.sp,
                letterSpacing = 2.4.sp,
            )
            Text(
                // The menu adapts to what the active brain actually supports (via
                // /keryx/capabilities): a local vLLM brain is a binary thinking switch, cloud
                // models take the full effort scale. Until the probe answers, show the generic
                // scale with a neutral subtitle.
                text = when {
                    caps == null -> "effort persists across sessions"
                    caps.mode == "binary" -> "${caps.model.ifBlank { "local brain" }} · on/off switch"
                    caps.mode == "none" -> "${caps.model.ifBlank { "model" }} · no reasoning dial"
                    caps.scope == "session" -> "${caps.model.ifBlank { "model" }} · this session's ladder"
                    else -> "${caps.model.ifBlank { "model" }} · effort scale"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                fontSize = 9.sp,
            )
        }
        // Render exactly the levels the gateway declares for the active brain (it knows what
        // the serving stack validates — e.g. the local qwen stack takes low/medium/xhigh, not
        // the whole generic scale). The full generic ladder is only the no-caps fallback.
        val glyphFor = mapOf(
            "none" to "·", "minimal" to "▁", "low" to "▁▃", "medium" to "▁▃▅",
            "high" to "▁▃▅▇", "xhigh" to "▁▃▅▇█", "max" to "█████", "ultra" to "█████",
        )
        // Scope of a pick: this session (the gateway's own default for /reasoning) or every
        // session (--global, written to config.yaml). The old menu sent --global for every
        // tap, so one pick in a scratch room rewrote the profile default for good.
        var everySession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        val noDial = caps?.mode == "none"
        val entries: List<Triple<String, String, String>> = if (noDial) {
            emptyList()
        } else if (!caps?.levels.isNullOrEmpty()) {
            caps!!.levels.map { arg ->
                val label = caps.labels[arg] ?: arg.replaceFirstChar { it.uppercase() }
                val glyph = glyphFor[arg] ?: if (caps.mode == "binary" && arg != "none") "▁▃▅▇" else "·"
                Triple(arg, label, glyph)
            }
        } else {
            listOf(
                Triple("none", "Off", "·"),
                Triple("minimal", "Minimal", "▁"),
                Triple("low", "Low", "▁▃"),
                Triple("medium", "Medium", "▁▃▅"),
                Triple("high", "High", "▁▃▅▇"),
                Triple("xhigh", "X-High", "▁▃▅▇█"),
            )
        }
        entries.forEach { (arg, label, glyph) ->
            val isCurrent = caps?.current == arg
            DropdownMenuItem(
                text = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            glyph,
                            color = accent.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(end = 10.dp).width(38.dp),
                        )
                        Text(
                            label,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) accent else Color.Unspecified,
                        )
                    }
                },
                onClick = { onCommand(if (everySession) "$arg --global" else arg) },
            )
        }
        if (noDial) {
            Text(
                "This model takes no effort level — Hermes sends none.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            DropdownMenuItem(
                text = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            if (everySession) "Every session" else "This session only",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = everySession,
                            onCheckedChange = { everySession = it },
                            modifier = Modifier.padding(start = 12.dp).height(24.dp),
                        )
                    }
                },
                onClick = { everySession = !everySession },
            )
        }
        HorizontalDivider(
            color = accent.copy(alpha = 0.12f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        DropdownMenuItem(
            text = { Text("Show reasoning", fontSize = 14.sp) },
            onClick = { onCommand("show") },
        )
        DropdownMenuItem(
            text = { Text("Hide reasoning", fontSize = 14.sp) },
            onClick = { onCommand("hide") },
        )
        DropdownMenuItem(
            text = {
                Text("Reset session override", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            onClick = { onCommand("reset") },
        )
        // Steering left this menu in 2.6.2: mid-turn the composer's send button IS the steer
        // (tap steers, hold queues) — a verb hidden behind the reasoning pill was the
        // affordance nobody found.
    }
}
