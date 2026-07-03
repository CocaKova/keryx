package chat.keryx.app.presentation.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import chat.keryx.app.presentation.ChatViewModel
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                NavigationDrawerContent(
                    viewModel = viewModel,
                    onSessionSelected = { session ->
                        viewModel.selectSession(session)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        // The whole-screen background gradient: a warm amber aurora at the top, deepening to OLED
        // black, with a faint lift behind the composer. This is the *app* background — bubbles and
        // bars sit on top of it (transparent), so the gradient reads as the actual backdrop.
        val bg = MaterialTheme.colorScheme.background
        val accent = MaterialTheme.colorScheme.primary
        val appGradient = Brush.verticalGradient(
            0.0f to lerp(bg, accent, 0.26f),
            0.30f to bg,
            0.82f to bg,
            1.0f to lerp(bg, accent, 0.12f),
        )
        Box(modifier = Modifier.fillMaxSize().background(appGradient)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = 18.sp)
                            currentSession?.title?.let { roomName ->
                                Text(
                                    text = roomName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
                        // dims when idle, warms red when the gateway is unreachable. Tap explains.
                        val linkHealth by viewModel.linkHealth.collectAsState()
                        LinkHealthDot(health = linkHealth)
                        if (currentSession != null) {
                            // Dynamic reasoning control via Hermes' native /reasoning command.
                            // Effort levels persist with --global; show/hide persists per-platform
                            // on the server side already; reset clears this session's override.
                            var reasoningMenu by remember { mutableStateOf(false) }
                            val reasoningCaps by viewModel.reasoningCaps.collectAsState()
                            Box {
                                IconButton(onClick = { reasoningMenu = true; viewModel.refreshReasoningCaps() }) {
                                    Icon(
                                        Icons.Default.Psychology,
                                        contentDescription = "Reasoning",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                ReasoningMenu(
                                    expanded = reasoningMenu,
                                    caps = reasoningCaps,
                                    onDismiss = { reasoningMenu = false },
                                    onCommand = { arg -> reasoningMenu = false; viewModel.sendReasoningCommand(arg) },
                                )
                            }
                            // Steer: quick-prefill the composer with "/steer " to redirect the agent mid-task.
                            IconButton(onClick = { viewModel.prefillComposer("/steer ") }) {
                                Icon(Icons.Default.Explore, contentDescription = "Steer", tint = MaterialTheme.colorScheme.primary)
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
            ChatScreen(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        }
        }
    }
}

/**
 * Hermes Link health as a single quiet dot: accent and breathing while tokens flow, steady when the
 * last turn/probe reached the gateway, dim when untested, warm red when unreachable, gone when the
 * side-channel is off. Tapping it toasts the state in words.
 */
@Composable
private fun LinkHealthDot(health: chat.keryx.app.presentation.LinkHealth) {
    if (health == chat.keryx.app.presentation.LinkHealth.OFF) return
    val accent = MaterialTheme.colorScheme.primary
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
        chat.keryx.app.presentation.LinkHealth.LIVE -> accent.copy(alpha = alpha)
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
                    android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
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
private fun ReasoningMenu(
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
                    else -> "${caps.model.ifBlank { "model" }} · effort scale"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                fontSize = 9.sp,
            )
        }
        val entries: List<Triple<String, String, String>> = if (caps?.mode == "binary") {
            caps.levels.map { arg ->
                val label = caps.labels[arg] ?: arg.replaceFirstChar { it.uppercase() }
                Triple(arg, label, if (arg == "none") "·" else "▁▃▅▇")
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
    }
}
