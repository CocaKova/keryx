package chat.keryx.app.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    val currentSession by viewModel.currentSession.collectAsState()

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
                        if (currentSession != null) {
                            // Dynamic reasoning control: effort + visibility via Hermes' native
                            // /reasoning command (session-scoped — nothing persisted server-side).
                            var reasoningMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { reasoningMenu = true }) {
                                    Icon(
                                        Icons.Default.Psychology,
                                        contentDescription = "Reasoning",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                DropdownMenu(expanded = reasoningMenu, onDismissRequest = { reasoningMenu = false }) {
                                    Text(
                                        "REASONING EFFORT",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 10.sp,
                                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                    )
                                    listOf("low", "medium", "high").forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text(level.replaceFirstChar { it.uppercase() }) },
                                            onClick = { reasoningMenu = false; viewModel.sendReasoningCommand(level) },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Show reasoning") },
                                        onClick = { reasoningMenu = false; viewModel.sendReasoningCommand("show") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Hide reasoning") },
                                        onClick = { reasoningMenu = false; viewModel.sendReasoningCommand("hide") },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Reset override") },
                                        onClick = { reasoningMenu = false; viewModel.sendReasoningCommand("reset") },
                                    )
                                }
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
