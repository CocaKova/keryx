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
    val currentSession by viewModel.currentSession.collectAsState()
    val linkHealth by viewModel.linkHealth.collectAsState()
    val heraldIds by viewModel.agentMatrixId.collectAsState()
    val heraldAccents by viewModel.heraldAccents.collectAsState()

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
    CompositionLocalProvider(
        LocalHeraldConfig provides HeraldConfig(
            ids = chat.keryx.app.domain.model.Heralds.parseIds(heraldIds),
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
                    onSessionSelected = { session ->
                        viewModel.selectSession(session)
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
                            currentSession?.let { session ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = session.title,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // Which agent profile answers in this room (from the gateway's
                                    // room→profile routing map): a small tinted chip by the name.
                                    val caps by viewModel.reasoningCaps.collectAsState()
                                    caps?.roomProfiles?.get(session.id)
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
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        // Hermes Link health, whispered: a tiny dot that breathes while tokens flow,
                        // dims when idle, warms red when the gateway is unreachable. Tap opens the
                        // Agent Hub — the live who/what/how of the system Keryx is pointed at.
                        LinkHealthDot(health = linkHealth, onClick = {
                            viewModel.refreshReasoningCaps()
                            openSpace(KeryxDest.Hub)
                        })
                        if (currentSession != null) {
                            // New session: one tap sends /new — same auto-send the command palette
                            // does, so the gateway's fresh-session reply lands in the chat itself.
                            IconButton(onClick = {
                                viewModel.recordCommandUse("/new")
                                viewModel.sendMessage("/new")
                            }) {
                                Icon(
                                    Icons.Default.AddComment,
                                    contentDescription = "New session",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            // Reasoning moved to the composer footer (2.2, the Talaria
                            // treatment) — the dial now lives where the thinking happens.
                            // The Call (1.22): a voice conversation with this room's agent. Needs
                            // both voice endpoints; a missing one gets a pointer, not a dead mic.
                            var showCall by remember { mutableStateOf(false) }
                            IconButton(onClick = {
                                if (viewModel.voiceCallReady()) showCall = true
                                else viewModel.toast("Set the STT and TTS endpoints in Settings → Voice first")
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "Call",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            if (showCall) {
                                chat.keryx.app.presentation.ui.components.CallScreen(
                                    viewModel = viewModel,
                                    roomName = currentSession?.title ?: "Keryx",
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
            // Skill Forge opens from two places — Agent Hub Skills rows (direct ViewModel call)
            // and in-chat SkillDistilled pills (via this CompositionLocal, since the render chain
            // doesn't carry the ViewModel). One shared target keeps a single hosted sheet.
            androidx.compose.runtime.CompositionLocalProvider(
                chat.keryx.app.presentation.ui.components.LocalSkillForgeOpener provides viewModel::openSkillForge,
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
            val skillForgeTarget by viewModel.skillForgeTarget.collectAsState()
            skillForgeTarget?.let { name ->
                chat.keryx.app.presentation.ui.components.SkillForgeSheet(
                    skillName = name,
                    viewModel = viewModel,
                    onDismiss = viewModel::closeSkillForge,
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
                KeryxDest.Missions -> chat.keryx.app.presentation.ui.components.MissionsScreen(
                    viewModel = viewModel,
                    onDismissRequest = nav::back,
                )
                KeryxDest.Hub -> chat.keryx.app.presentation.ui.components.AgentHubSheet(
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
    val alpha = if (health == chat.keryx.app.presentation.LinkHealth.LIVE) {
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
        else -> Color(0xFFE0524D).copy(alpha = 0.85f)
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
    onSteer: () -> Unit,
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
        val entries: List<Triple<String, String, String>> = if (!caps?.levels.isNullOrEmpty()) {
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
                onClick = { onCommand("$arg --global") },
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
        HorizontalDivider(
            color = accent.copy(alpha = 0.12f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        DropdownMenuItem(
            text = {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Explore, contentDescription = null,
                        tint = accent.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 10.dp).size(16.dp),
                    )
                    Text("Steer the agent…", fontSize = 14.sp)
                }
            },
            onClick = onSteer,
        )
    }
}
