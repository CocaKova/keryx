package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.data.remote.HermesStreamClient.HubMessage
import chat.keryx.app.data.remote.HermesStreamClient.HubSession
import chat.keryx.app.data.remote.HubJson
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.LinkHealth

private val TABS = listOf("Status", "Console", "Jobs", "Sessions", "Skills", "Tools")

/** Tabs whose data moves under you (gateway state, job runs, session activity) — these re-poll
 *  every 10 s while their tab is visible AND the app is resumed. Skills/Tools stay fetch-once:
 *  they change on operator action, not on their own. */
private val LIVE_TABS = setOf(0, 2, 3)
private const val HUB_POLL_MS = 10_000L

/**
 * A hard fling that runs a tab's LazyColumn into its edge used to spill the leftover velocity into
 * the ModalBottomSheet's own nested-scroll handling — the sheet dragged a few px and sprang back,
 * over and over (the "scroll down hard and the UI glitches up and down" stutter). Swallow
 * everything a fling leaves unconsumed before it reaches the sheet; real finger drags
 * (UserInput) pass through untouched, so swipe-down-to-dismiss still works.
 */
private val FlingTamer = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.SideEffect) available else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * The Agent Hub, grown into the gateway console (1.6 Phase D): the hermes-agent-desktop "at a
 * glance" panel, phone-sized and tabbed. Status keeps the 1.3.0 identity view (brain, reasoning,
 * quick actions) and adds per-platform connection state; Jobs / Sessions / Skills / Tools ride the
 * gateway's admin API. Everything fetches on first open of its tab plus the explicit refresh
 * button — no background polling, this sheet is a place you look, not a service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHubSheet(
    viewModel: ChatViewModel,
    health: LinkHealth,
    onDismiss: () -> Unit,
) {
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary

    // Per-tab fetch on first visit per sheet-opening. Panels may already hold the offline-cache
    // seed (or last opening's snapshot) — that renders instantly while this refresh runs behind
    // it, so the sheet is never blank and never silently stale.
    val fetchedTabs = remember { mutableSetOf<Int>() }
    LaunchedEffect(tab) {
        if (!fetchedTabs.add(tab)) return@LaunchedEffect
        when (tab) {
            0 -> { viewModel.refreshHubHealth(); viewModel.refreshHubModels() }
            1 -> viewModel.consoleReconcile()
            2 -> viewModel.refreshHubJobs()
            3 -> viewModel.refreshHubSessions()
            4 -> viewModel.refreshHubSkills()
            5 -> viewModel.refreshHubToolsets()
        }
    }

    // Live refresh (1.20): the visible tab re-polls while the sheet is open — gateway state,
    // job runs and session activity move without us. repeatOnLifecycle suspends the loop when
    // the app backgrounds (same discipline as the Missions board poll); switching tabs restarts
    // the effect, so only the tab actually on screen polls.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(tab, lifecycleOwner) {
        if (tab !in LIVE_TABS) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(HUB_POLL_MS)
                when (tab) {
                    0 -> { viewModel.refreshHubHealth(); viewModel.refreshHubModels() }
                    2 -> viewModel.refreshHubJobs()
                    3 -> viewModel.refreshHubSessions()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(bottom = 12.dp),
        ) {
            // Header: emblem + title + health line + refresh-current-tab.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Box(modifier = Modifier.size(38.dp)) {
                    BrailleSnakeAnimation(
                        modifier = Modifier.fillMaxSize(),
                        color = accent,
                        color2 = accent2,
                        snakeLength = 12,
                        periodMillis = 3600,
                        glyphSize = 7f,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Agent Hub", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = linkHealthLabel(health) +
                            (gatewayUrl.takeIf { it.isNotBlank() }?.let { url ->
                                " · " + url.removePrefix("https://").removePrefix("http://").trimEnd('/')
                            } ?: ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    when (tab) {
                        0 -> { viewModel.refreshHubHealth(); viewModel.refreshHubModels(); viewModel.refreshReasoningCaps() }
                        1 -> viewModel.consoleReconcile()
                        2 -> viewModel.refreshHubJobs()
                        3 -> viewModel.refreshHubSessions()
                        4 -> viewModel.refreshHubSkills()
                        5 -> viewModel.refreshHubToolsets()
                    }
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))

            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 12.dp,
                containerColor = Color.Transparent,
                indicator = { positions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(positions[tab]),
                        color = accent,
                    )
                },
                divider = { HorizontalDivider(color = accent.copy(alpha = 0.12f)) },
            ) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = {
                            Text(label, fontSize = 12.sp,
                                fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        selectedContentColor = accent,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).nestedScroll(FlingTamer)) {
                when (tab) {
                    0 -> StatusTab(viewModel, health, onDismiss, onOpenConsole = { tab = 1 })
                    1 -> RunConsoleTab(viewModel)
                    2 -> JobsTab(viewModel)
                    3 -> SessionsTab(viewModel, onResume = { session ->
                        viewModel.consoleSetSessionTarget(session)
                        tab = 1
                    })
                    4 -> SkillsTab(viewModel)
                    5 -> ToolsTab(viewModel)
                }
            }
        }
    }
}

/** One-line description of a link-health state, shared by the header and the Status tab. */
private fun linkHealthLabel(health: LinkHealth): String = when (health) {
    LinkHealth.LIVE -> "Streaming live"
    LinkHealth.OK -> "Connected"
    LinkHealth.UNKNOWN -> "Not tested yet"
    LinkHealth.OFF -> "Side-channel off"
    else -> "Unreachable — using Matrix sync"
}

/** The panel-degradation row every tab shares: stale data stays visible, the error rides on top. */
@Composable
private fun PanelErrorLine(error: String?) {
    if (error == null) return
    Text(
        "⚠ $error",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun PanelLoading() {
    Text(
        "Asking the gateway…",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        letterSpacing = 2.0.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun connStateColor(state: String): Color = when (state) {
    "connected" -> Color(0xFF4CAF50)
    "retrying", "connecting" -> Color(0xFFE8A33D)
    "disconnected" -> Color(0x66FFFFFF)
    else -> Color(0xFFE0524D)
}

// --- Status ------------------------------------------------------------------------------------

@Composable
private fun StatusTab(
    viewModel: ChatViewModel,
    health: LinkHealth,
    onDismiss: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val panel by viewModel.hubHealth.collectAsState()
    val models by viewModel.hubModels.collectAsState()
    val console by viewModel.console.collectAsState()
    val caps by viewModel.reasoningCaps.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val accent = MaterialTheme.colorScheme.primary

    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(
        start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        item { PanelErrorLine(panel.error) }

        // An app-launched run is live — surface it wherever the user lands first.
        if (console.live) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .clickable(onClick = onOpenConsole)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("▶", fontSize = 12.sp, color = accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "run live — " + console.prompt.take(60),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("open ›", fontSize = 11.sp, color = accent)
                }
            }
        }

        // The live brain, from the capabilities probe.
        item {
            SectionLabel("Brain")
            val rows = listOfNotNull(
                caps?.model?.takeIf { it.isNotBlank() }?.let { "Model" to it },
                caps?.let {
                    "Reasoning" to (it.labels[it.current] ?: it.current.ifBlank { "—" }) +
                        if (it.mode == "binary") " (on/off brain)" else " (effort scale)"
                },
                panel.data?.version?.takeIf { it.isNotBlank() }?.let { "Gateway" to "hermes-agent $it" },
                panel.data?.gatewayState?.takeIf { it.isNotBlank() }?.let { "State" to it },
                panel.data?.takeIf { it.activeAgents > 0 || it.busy }?.let {
                    "Activity" to "${it.activeAgents} agent turn${if (it.activeAgents == 1) "" else "s"} in flight"
                },
            )
            if (rows.isEmpty()) {
                Text(
                    if (health == LinkHealth.UNREACHABLE) "Gateway unreachable — info unavailable"
                    else "Probing gateway…",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else rows.forEach { (k, v) ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(86.dp))
                    Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Per-platform adapter states from /health/detailed.
        val platforms = panel.data?.platforms.orEmpty()
        if (platforms.isNotEmpty()) {
            item { SectionLabel("Platforms") }
            items(platforms, key = { it.name }) { p ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(connStateColor(p.state)))
                    Spacer(Modifier.width(9.dp))
                    Text(p.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(120.dp))
                    Text(
                        p.state + (p.errorMessage.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                        fontSize = 11.sp,
                        color = if (p.errorMessage.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Models the gateway serves (`/v1/models`): its own name plus configured route aliases.
        val modelRows = models.data.orEmpty()
        if (modelRows.isNotEmpty()) {
            item { SectionLabel("Models") }
            items(modelRows, key = { "model:" + it.id }) { m ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(m.id, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (m.resolvesTo.isNotBlank() && m.resolvesTo != m.id) {
                        Text("→ ${m.resolvesTo}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Quick actions: the session-management commands you reach for most, one tap away.
        item {
            SectionLabel("Quick actions")
            val actions = listOf(
                Triple("/status", "System status", "Agent, brain and platform health"),
                Triple("/new", "Fresh session", "Start a clean conversation"),
                Triple("/compress", "Compress", "Summarize this thread to free context"),
                Triple("/handoff", "Handoff", "Carry context into a new session"),
            )
            val haveRoom = currentSession != null
            actions.forEach { (cmd, title, desc) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = haveRoom) {
                            viewModel.recordCommandUse(cmd)
                            viewModel.sendMessage(cmd)
                            onDismiss()
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(cmd, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (haveRoom) accent else accent.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(96.dp))
                    Column {
                        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = if (haveRoom) 1f else 0.45f))
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!haveRoom) {
                Text("Open a room to use quick actions", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { viewModel.testGatewayLink() }, modifier = Modifier.fillMaxWidth()) {
                Text("Test Hermes Link")
            }
        }
    }
}

// --- Jobs --------------------------------------------------------------------------------------

@Composable
private fun JobsTab(viewModel: ChatViewModel) {
    val panel by viewModel.hubJobs.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HubJob?>(null) }
    var editTarget by remember { mutableStateOf<HubJob?>(null) }

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text(
                "Scheduled jobs the gateway runs on its own",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { createOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New job", fontSize = 12.sp)
            }
        }
        val jobs = panel.data
        when {
            jobs == null -> PanelLoading()
            jobs.isEmpty() -> Text(
                "No scheduled jobs — create one and the agent runs it on the cron you set.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onToggle = { viewModel.hubJobAction(job.id, if (job.enabled) "pause" else "resume") },
                        onRunNow = { viewModel.hubJobAction(job.id, "run") },
                        onDelete = { deleteTarget = job },
                        onEdit = { editTarget = job },
                    )
                }
            }
        }
    }

    if (createOpen) {
        JobCreateDialog(
            currentRoomId = currentSession?.id,
            onCreate = { name, schedule, prompt, deliver ->
                viewModel.hubJobCreate(name, schedule, prompt, deliver)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }
    editTarget?.let { job ->
        JobEditDialog(
            job = job,
            onSave = { name, schedule, prompt, deliver ->
                viewModel.hubJobEdit(job.id, name, schedule, prompt, deliver)
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }
    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${job.name}\"?", fontSize = 16.sp) },
            text = { Text("The schedule and its run history go with it. This can't be undone.",
                fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.hubJobDelete(job.id); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun JobCard(
    job: HubJob,
    onToggle: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tap the job's identity to edit it (PATCH — the 1.20 verb); the icons keep their jobs.
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(job.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.scheduleDisplay, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary)
                    nextRunEta(job)?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onRunNow) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run now",
                    tint = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = job.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = job.lastStatus == "ok"
            if (!job.lastStatus.isNullOrBlank()) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(
                    if (ok) Color(0xFF4CAF50) else Color(0xFFE0524D)))
                Spacer(Modifier.width(6.dp))
                Text(
                    "last run ${job.lastStatus}" +
                        (job.lastError?.takeIf { it.isNotBlank() }?.let { " — ${it.take(60)}" } ?: "") +
                        if (job.repeatCompleted > 0) " · ${job.repeatCompleted} runs" else "",
                    fontSize = 10.sp,
                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!job.enabled) {
                Spacer(Modifier.width(8.dp))
                Text("paused", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "next in 8h 12m" from the gateway's ISO timestamp; null (hidden) when unparsable or paused. */
private fun nextRunEta(job: HubJob): String? {
    if (!job.enabled) return null
    val at = HubJson.isoToMillis(job.nextRunAt) ?: return job.nextRunAt?.take(16)
    val mins = (at - System.currentTimeMillis()) / 60_000L
    return when {
        mins < 0 -> "due"
        mins < 1 -> "next <1m"
        mins < 60 -> "next in ${mins}m"
        mins < 60 * 24 -> "next in ${mins / 60}h ${mins % 60}m"
        else -> "next in ${mins / (60 * 24)}d"
    }
}

@Composable
private fun JobCreateDialog(
    currentRoomId: String?,
    onCreate: (name: String, schedule: String, prompt: String, deliver: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    // Where the run's output lands: this room's chat, or quietly in the gateway log.
    var toRoom by remember { mutableStateOf(currentRoomId != null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("New scheduled job", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = schedule, onValueChange = { schedule = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Schedule (cron, e.g. 0 7 * * *)") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = { Text("Prompt (what the agent does each run)") },
                )
                if (currentRoomId != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Deliver to this room", fontSize = 13.sp)
                            Text(
                                if (toRoom) "Each run's result posts into the open chat"
                                else "Runs quietly — results stay in the gateway log",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = toRoom, onCheckedChange = { toRoom = it })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = name.isNotBlank() && schedule.isNotBlank() && prompt.isNotBlank(),
                        onClick = {
                            val deliver = if (toRoom && currentRoomId != null) "matrix:$currentRoomId" else "local"
                            onCreate(name.trim(), schedule.trim(), prompt.trim(), deliver)
                        },
                    ) { Text("Schedule") }
                }
            }
        }
    }
}

/** Edit an existing job (1.20): prefilled from the list row (the gateway sends `prompt` there),
 *  saved via PATCH. Deliver stays a raw string field — it can point anywhere ("local",
 *  "matrix:<room>"), and second-guessing an operator's target helps nobody. */
@Composable
private fun JobEditDialog(
    job: HubJob,
    onSave: (name: String, schedule: String, prompt: String, deliver: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(job.name) }
    var schedule by remember { mutableStateOf(job.scheduleDisplay) }
    var prompt by remember { mutableStateOf(job.prompt) }
    var deliver by remember { mutableStateOf(job.deliver.ifBlank { "local" }) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Edit job", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = schedule, onValueChange = { schedule = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Schedule (cron)") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = { Text("Prompt") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = deliver, onValueChange = { deliver = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Deliver to (\"local\" or \"matrix:<room>\")") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = name.isNotBlank() && schedule.isNotBlank() && prompt.isNotBlank(),
                        onClick = { onSave(name.trim(), schedule.trim(), prompt.trim(), deliver.trim()) },
                    ) { Text("Save") }
                }
            }
        }
    }
}

// --- Sessions ----------------------------------------------------------------------------------

@Composable
private fun SessionsTab(viewModel: ChatViewModel, onResume: (HubSession) -> Unit) {
    val panel by viewModel.hubSessions.collectAsState()
    var open by remember { mutableStateOf<HubSession?>(null) }

    open?.let { session ->
        SessionTranscript(
            session = session,
            viewModel = viewModel,
            onBack = { open = null },
            onResume = onResume,
        )
        return
    }

    var pruneOpen by remember { mutableStateOf(false) }
    if (pruneOpen) {
        SessionPruneDialog(viewModel = viewModel, onDismiss = { pruneOpen = false })
    }

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What the agent has been doing — every persisted session, newest first",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )
            // The 1.8 pruner: dry-run preview + count-restating confirm + app-lock gate.
            TextButton(onClick = { pruneOpen = true }) { Text("Prune…", fontSize = 12.sp) }
        }
        val sessions = panel.data
        when {
            sessions == null -> PanelLoading()
            sessions.isEmpty() -> Text(
                "No sessions on record.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    sessions.sortedByDescending { it.lastActive },
                    key = { it.id },
                ) { s -> SessionCard(s, onClick = { open = s }) }
            }
        }
    }
}

@Composable
private fun SessionCard(s: HubSession, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.title?.takeIf { it.isNotBlank() } ?: s.model.ifBlank { s.id },
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                s.source,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                "${s.messageCount} msgs",
                "${s.toolCallCount} tools".takeIf { s.toolCallCount > 0 },
                "${s.apiCallCount} calls",
                "${compactCount(s.inputTokens)} in · ${compactCount(s.outputTokens)} out",
                epochAgo(s.lastActive),
            ).joinToString(" · "),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (s.preview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(s.preview, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SessionTranscript(
    session: HubSession,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onResume: (HubSession) -> Unit,
) {
    var messages by remember { mutableStateOf<List<HubMessage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) {
        viewModel.hubSessionMessages(session.id)
            .onSuccess { messages = it }
            .onFailure { error = it.message }
    }

    if (renameOpen) {
        var title by remember { mutableStateOf(session.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename session", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        viewModel.hubSessionRename(session.id, title.trim())
                        renameOpen = false
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("Cancel") } },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete this session?", fontSize = 16.sp) },
            text = { Text(
                "The transcript (${session.messageCount} messages) goes with it. This can't be undone.",
                fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.hubSessionDelete(session.id) { ok -> if (ok) onBack() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Keep") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 20.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions")
            }
            Column {
                Text(session.title?.takeIf { it.isNotBlank() } ?: session.model,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(session.id, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The 1.20 verbs: this console can now DO things to a session, not just read it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            TextButton(onClick = { onResume(session) }) { Text("Resume", fontSize = 12.sp) }
            TextButton(onClick = { viewModel.hubSessionFork(session.id) }) { Text("Fork", fontSize = 12.sp) }
            TextButton(onClick = { renameOpen = true }) { Text("Rename", fontSize = 12.sp) }
            TextButton(onClick = { deleteOpen = true }) {
                Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        when {
            error != null -> PanelErrorLine(error)
            messages == null -> PanelLoading()
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages.orEmpty()) { m ->
                    Column {
                        Text(
                            when {
                                m.role == "tool" && m.toolName.isNotBlank() -> "tool · ${m.toolName}"
                                else -> m.role
                            },
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = when (m.role) {
                                "user" -> MaterialTheme.colorScheme.primary
                                "tool" -> MaterialTheme.colorScheme.tertiary
                                "system" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (m.content.isNotBlank()) {
                            Text(m.content, fontSize = 12.sp, maxLines = 8,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (m.toolCallCount > 0) {
                            Text("→ ${m.toolCallCount} tool call${if (m.toolCallCount > 1) "s" else ""}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}

private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

private fun epochAgo(epochSec: Double): String? {
    if (epochSec <= 0) return null
    val mins = ((System.currentTimeMillis() / 1000L) - epochSec.toLong()) / 60
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

// --- Skills ------------------------------------------------------------------------------------

@Composable
private fun SkillsTab(viewModel: ChatViewModel) {
    val panel by viewModel.hubSkills.collectAsState()
    var filter by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        val skills = panel.data
        when {
            skills == null -> PanelLoading()
            else -> {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    placeholder = { Text("Filter ${skills.size} skills…", fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    singleLine = true,
                )
                val shown = skills.filter {
                    filter.isBlank() || it.name.contains(filter, ignoreCase = true) ||
                        it.description.contains(filter, ignoreCase = true)
                }
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.name }) { s ->
                        // Tap opens the Skill Forge (1.8): full SKILL.md, edit, save.
                        Column(Modifier.fillMaxWidth().clickable { viewModel.openSkillForge(s.name) }) {
                            Text(s.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (s.description.isNotBlank()) {
                                Text(s.description, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Tools -------------------------------------------------------------------------------------

@Composable
private fun ToolsTab(viewModel: ChatViewModel) {
    val panel by viewModel.hubToolsets.collectAsState()

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        val hub = panel.data
        Text(
            if (hub?.canToggle == true) "Tool groups for the agent's chat platform — switches persist"
            // Legacy gateway: read-only, and only its own (api_server) platform surface exists.
            else "Tool groups as the API platform sees them",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        when {
            hub == null -> PanelLoading()
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hub.toolsets, key = { it.name }) { t ->
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!hub.canToggle) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(
                                    if (t.enabled) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)))
                                Spacer(Modifier.width(9.dp))
                            }
                            Text(t.label.ifBlank { t.name }, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (!t.configured) {
                                Text("needs keys", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            if (hub.canToggle) {
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = t.enabled,
                                    onCheckedChange = { viewModel.hubToolsetToggle(t.name, it) },
                                    // Operator-pinned (config-guard invariants): show state, refuse input.
                                    enabled = !t.locked && !panel.refreshing,
                                )
                            }
                        }
                        if (t.tools.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t.tools.joinToString(", "),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
