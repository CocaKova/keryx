package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail
import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import chat.keryx.app.presentation.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Section order + display names for the board's raw statuses. Anything the gateway invents
 *  later lands in a trailing section named after itself instead of vanishing. */
private val SECTION_ORDER = listOf(
    "running" to "Running",
    "ready" to "Ready",
    "triage" to "Triage",
    "todo" to "Waiting",
    "scheduled" to "Scheduled",
    "blocked" to "Blocked",
    "review" to "Review",
    "done" to "Done",
)

/** The pinned lane: cards waiting on their owner, whatever their raw status. */
internal const val NEEDS_YOU = "needs_you"

/** One rendered lane: its key (a raw status, or [NEEDS_YOU]), its label, its cards. */
internal data class MissionSection(val key: String, val label: String, val cards: List<KanbanTask>)

/**
 * The board, in render order (2.14): a "Needs you" lane pinned on top holding every card that
 * waits on its owner — a review, or a block no worker will clear by itself — then the status
 * lanes without those cards, so nothing shows twice. Unknown statuses trail, named after
 * themselves. Empty lanes are dropped. Pure so the lane math is unit-testable.
 */
internal fun missionSections(tasks: Map<String, List<KanbanTask>>): List<MissionSection> {
    val needsYou = (SECTION_ORDER.map { it.first } + tasks.keys.sorted())
        .distinct()
        .flatMap { tasks[it].orEmpty() }
        .filter { it.needsYou }
    val pinned = needsYou.map { it.id }.toSet()
    val known = SECTION_ORDER.map { it.first }.toSet()
    val lanes = SECTION_ORDER + tasks.keys.filter { it !in known }.sorted().map { it to it }
    return buildList {
        if (needsYou.isNotEmpty()) add(MissionSection(NEEDS_YOU, "Needs you", needsYou))
        lanes.forEach { (status, label) ->
            val cards = tasks[status].orEmpty().filter { it.id !in pinned }
            if (cards.isNotEmpty()) add(MissionSection(status, label, cards))
        }
    }
}

/** The plain words for a block kind, as the card and the sheet say it. */
internal fun blockKindLabel(kind: String, status: String): String = when {
    status == "review" -> "waiting on your review"
    kind == "needs_input" -> "needs your input"
    kind == "capability" -> "needs something it can't do"
    kind == "dependency" -> "waiting on a parent card"
    kind == "transient" -> "paused — clears itself"
    status == "blocked" -> "blocked"
    else -> status
}

/** Event rows worth reading: newest first, heartbeats folded into a count. */
internal fun readableEvents(
    events: List<chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent>,
): Pair<List<chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent>, Int> {
    val (beats, rest) = events.partition { it.kind == "heartbeat" }
    return rest.sortedByDescending { it.id } to beats.size
}

/** "31m", "1h 04m", "45s" — a run's length at a glance. */
internal fun runLength(seconds: Long?): String? = when {
    seconds == null || seconds < 0 -> null
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${"%02d".format((seconds % 3600) / 60)}m"
}

/** LazyColumn start index of each section's header, given (status, cardCount) in render order:
 *  one header item + N card items per section. Pure so the lane-jump math is unit-testable. */
internal fun sectionStartIndices(sections: List<Pair<String, Int>>): Map<String, Int> {
    var index = 0
    val starts = LinkedHashMap<String, Int>()
    for ((status, count) in sections) {
        starts[status] = index
        index += 1 + count
    }
    return starts
}

/**
 * Missions — the agent's kanban board, phone-shaped: vertical status sections instead of
 * horizontal swimlanes. Reads + additive writes only (create missions, comment); the dispatcher
 * owns state transitions, so cards move columns on refresh, never by drag.
 *
 * 1.23: the board becomes a true Keryx space — KeryxSpace scaffold, lane-jump chips, status
 * rails on every card, running cards breathing. Behavior contracts unchanged: 20s RESUMED-gated
 * poll, unknown statuses keep their trailing sections, the board is never cache-seeded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsScreen(
    viewModel: ChatViewModel,
    onDismissRequest: () -> Unit,
) {
    val board by viewModel.missions.kanbanBoard.collectAsState()
    val refreshing by viewModel.missions.kanbanRefreshing.collectAsState()
    val error by viewModel.missions.kanbanError.collectAsState()
    val caps by viewModel.hub.reasoningCaps.collectAsState()
    val subs by viewModel.missions.kanbanSubs.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var openTaskId by remember { mutableStateOf<String?>(null) }

    // Fresh on open, then a gentle poll while (and only while) this screen is composed AND the
    // app is actually on screen — the dispatcher moves cards without us, and 20s is plenty for a
    // board humans look at. repeatOnLifecycle suspends the loop while backgrounded and refreshes
    // immediately on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            viewModel.missions.refreshKanban()
            while (true) {
                delay(20_000L)
                viewModel.missions.refreshKanban()
            }
        }
    }

    val tasks = board?.tasks.orEmpty()
    val sections = missionSections(tasks)
    val runningCount = tasks["running"]?.size ?: 0
    val needsCount = sections.firstOrNull { it.key == NEEDS_YOU }?.cards?.size ?: 0

    KeryxSpace(
        title = "Missions",
        onClose = onDismissRequest,
        standalone = false,
        liveSlot = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxBreathingDot(
                    color = if (runningCount > 0) MaterialTheme.colorScheme.primary else KeryxStatus.idle,
                    alive = runningCount > 0,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        needsCount > 0 -> "$needsCount need${if (needsCount == 1) "s" else ""} you" +
                            (if (runningCount > 0) " · $runningCount running" else "")
                        runningCount > 0 -> "$runningCount running" +
                            (board?.board?.let { " · $it" } ?: "")
                        board?.board != null -> "board: ${board?.board}"
                        else -> "the agent's board"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        actions = {
            IconButton(onClick = { viewModel.missions.refreshKanban() }, enabled = !refreshing) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = if (refreshing) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.primary,
                )
            }
        },
        floating = {
            // The sunset-thread action: the app's two accents on the one create affordance.
            val accent = MaterialTheme.colorScheme.primary
            val accent2 = MaterialTheme.colorScheme.tertiary
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(accent, accent2)))
                    .clickable { createOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New mission",
                    tint = contrastColorFor(accent),
                )
            }
        },
    ) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val starts = sectionStartIndices(sections.map { it.key to it.cards.size })

        // Lane-jump chips: one per non-empty section, tap scrolls to that lane's header.
        if (sections.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sections.forEach { section ->
                    val status = section.key
                    val color = statusColor(status)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(KeryxRadius.chip))
                            .background(color.copy(alpha = 0.10f))
                            .clickable {
                                starts[status]?.let { scope.launch { listState.animateScrollToItem(it) } }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${section.label} ${section.cards.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        when {
            error != null && board == null -> MissionsEmptyState(
                line1 = "Board unreachable",
                line2 = error ?: "",
            )
            sections.isEmpty() -> MissionsEmptyState(
                line1 = "No missions on the board",
                line2 = "Give SILAS a mission with the + button — triage parks it for spec-first, otherwise the dispatcher picks it up.",
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    val status = section.key
                    val cards = section.cards
                    item(key = "hdr-$status") {
                        KeryxSectionHeader(
                            label = section.label,
                            dotColor = statusColor(status),
                            count = cards.size,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                    }
                    items(cards, key = { it.id }) { task ->
                        MissionCard(
                            task = task,
                            subscribed = subs[task.id]?.isNotEmpty() == true,
                            onClick = { openTaskId = task.id },
                        )
                    }
                }
            }
        }
    }

    if (createOpen) {
        val createCtx = androidx.compose.ui.platform.LocalContext.current
        MissionCreateDialog(
            profiles = missionAssignees(caps?.roomProfiles.orEmpty()),
            canNotify = viewModel.missions.alertRoom() != null,
            notifyUnavailableReason = viewModel.missions.alertUnavailableReason,
            onCreate = { title, assignee, body, triage, notify ->
                viewModel.missions.kanbanCreate(title, assignee, body, triage, notify)
                if (notify) viewModel.missions.armPhoneAlerts(createCtx)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }

    openTaskId?.let { tid ->
        MissionDetailSheet(
            taskId = tid,
            viewModel = viewModel,
            onOpenTask = { openTaskId = it },
            onDismiss = { openTaskId = null },
        )
    }
}

/** Assignee choices: the routing map's named profiles plus the home profile. */
internal fun missionAssignees(roomProfiles: Map<String, String>): List<String> =
    (roomProfiles.values.toSortedSet() + "default").toList()

@Composable
private fun MissionsEmptyState(line1: String, line2: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrailleSnakeAnimation(
            modifier = Modifier.size(84.dp),
            color = MaterialTheme.colorScheme.primary,
            color2 = MaterialTheme.colorScheme.tertiary,
            running = true,
        )
        Spacer(Modifier.height(18.dp))
        Text(line1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            line2,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    NEEDS_YOU, "review" -> KeryxStatus.warn
    "running" -> MaterialTheme.colorScheme.primary
    "ready" -> MaterialTheme.colorScheme.tertiary
    "blocked" -> KeryxStatus.bad
    "done" -> KeryxStatus.good
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun MissionCard(task: KanbanTask, subscribed: Boolean, onClick: () -> Unit) {
    val color = if (task.needsYou) statusColor(NEEDS_YOU) else statusColor(task.status)
    val running = task.status == "running"
    KeryxCard(
        onClick = onClick,
        tint = if (running || task.needsYou || task.status == "blocked") color else null,
        breathing = running,
        modifier = if (task.status == "done") Modifier.alpha(0.55f) else Modifier,
    ) {
        // The lane rail: every card carries its status color on the left edge, so a lane reads
        // as a lane even while scrolling past section boundaries.
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = if (running) 0.9f else 0.5f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        KeryxBreathingDot(color = color, alive = true, size = 8.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        task.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    if (subscribed) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = "Alerts on",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (task.priority > 0) {
                        Text(
                            "P${task.priority}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.assignee.isNotBlank()) {
                        Text(
                            task.assignee.replaceFirstChar { it.uppercase() },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (task.consecutiveFailures > 0) {
                        Text(
                            "⚠ ${task.consecutiveFailures} fail${if (task.consecutiveFailures > 1) "s" else ""}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        missionAge(task),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    DiagBadge(task)
                }
                if (task.ask.isNotBlank() && (task.status == "blocked" || task.status == "review")) {
                    // The ask, not the brief: what the card is waiting on is the one line that
                    // decides whether to open it. The kind pill says whose move it is.
                    Spacer(Modifier.height(6.dp))
                    StatusPill(blockKindLabel(task.blockKind, task.status), color)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.ask,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (task.needsYou) 4 else 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else if (task.bodyExcerpt.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.bodyExcerpt,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** A status word with its dot: the dot carries the hue, the words stay in body ink so they
 *  read at 10sp on either ground (contrast is the text's job, not the wash's). */
@Composable
private fun StatusPill(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun severityColor(severity: String): Color = when (severity) {
    "critical", "error" -> KeryxStatus.bad
    "warning" -> KeryxStatus.warn
    else -> KeryxStatus.idle
}

/** The card's diagnostics at a glance: "⚠ 1", in the worst live severity's hue — or faded
 *  idle when every one of them is history a later run already outlived. */
@Composable
private fun DiagBadge(task: KanbanTask) {
    if (task.diagCount <= 0) return
    val color = if (task.diagStale) KeryxStatus.idle else severityColor(task.diagSeverity)
    Text(
        "⚠ ${task.diagCount}",
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun missionAge(task: KanbanTask): String {
    val ref = task.completedAt ?: task.startedAt ?: task.createdAt
    val mins = ((System.currentTimeMillis() / 1000L) - ref) / 60
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MissionDetailSheet(
    taskId: String,
    viewModel: ChatViewModel,
    onOpenTask: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var detail by remember(taskId) { mutableStateOf<KanbanDetail?>(null) }
    var loadError by remember(taskId) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(taskId, reload) {
        viewModel.missions.kanbanTaskDetail(taskId)
            .onSuccess { detail = it; loadError = null }
            .onFailure { loadError = it.message }
    }

    KeryxSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
            val d = detail
            when {
                loadError != null -> Text(
                    "Couldn't load task: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                d == null -> Text(
                    "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                else -> {
                    val t = d.task
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeryxSectionHeader(
                            label = t.status,
                            dotColor = statusColor(t.status),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (t.assignee.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "@${t.assignee}",
                                fontSize = 11.sp,
                                color = keryxAccentInk(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        DiagBadge(t)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t.id,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The hero: what the card is waiting on, in the worker's words, and the
                        // answer box right under it — the whole reason to open a blocked card.
                        if (t.ask.isNotBlank() || t.needsYou) item(key = "ask") {
                            AskPanel(
                                task = t,
                                onReply = { text, unblock ->
                                    viewModel.missions.kanbanReply(taskId, text, unblock) { reload++ }
                                },
                                onApprove = { note -> viewModel.missions.kanbanApprove(taskId, note) { reload++ } },
                                onRequestChanges = { reason ->
                                    viewModel.missions.kanbanRequestChanges(taskId, reason) { reload++ }
                                },
                            )
                        }
                        item(key = "alert") { AlertToggle(taskId, viewModel) }
                        if (d.parents.isNotEmpty() || d.children.isNotEmpty()) item(key = "links") {
                            Column {
                                if (d.parents.isNotEmpty()) LinkRow("Waits on", d.parents, onOpenTask)
                                if (d.parents.isNotEmpty() && d.children.isNotEmpty()) Spacer(Modifier.height(6.dp))
                                if (d.children.isNotEmpty()) LinkRow("Unlocks", d.children, onOpenTask)
                            }
                        }
                        if (d.diagnostics.isNotEmpty()) {
                            item(key = "diag-hdr") {
                                KeryxSectionHeader(
                                    label = "Diagnostics",
                                    count = d.diagnostics.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(d.diagnostics.size, key = { "diag-$it" }) { i -> DiagnosticRow(d.diagnostics[i]) }
                        }
                        if (d.runs.isNotEmpty()) item(key = "runs") { RunDeck(d.runs) }
                        if (t.latestSummary.isNotBlank() && !sameWords(t.latestSummary, t.ask)) item(key = "summary") {
                            Column {
                                KeryxSectionHeader(label = "Latest handoff", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                MissionProse(t.latestSummary)
                            }
                        }
                        if (t.result.isNotBlank()) item(key = "result") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(KeryxRadius.field))
                                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    "Result",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = keryxAccentInk(MaterialTheme.colorScheme.tertiary),
                                )
                                Spacer(Modifier.height(2.dp))
                                MissionProse(t.result)
                            }
                        }
                        if (t.lastFailureError.isNotBlank()) item(key = "fail") {
                            Text(
                                "⚠ ${t.lastFailureError}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (t.body.isNotBlank()) item(key = "brief") { BriefSection(t.body) }
                        if (d.attachments.isNotEmpty()) item(key = "files") {
                            Column {
                                KeryxSectionHeader(
                                    label = "Attachments",
                                    count = d.attachments.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                d.attachments.forEach { a ->
                                    Text(
                                        "📎 ${a.filename}" + (if (a.size > 0) "  ·  ${a.size / 1024} KB" else ""),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                        item(key = "steer") { SteeringSection(taskId, t, viewModel) { reload++ } }
                        if (d.comments.isNotEmpty()) {
                            item(key = "c-hdr") {
                                KeryxSectionHeader(
                                    label = "Comments",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    count = d.comments.size,
                                )
                            }
                            items(d.comments.size, key = { "c-$it" }) { i ->
                                val c = d.comments[i]
                                // Comments wear the chat voice: author chip over a soft bubble.
                                Column {
                                    Text(
                                        c.author,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = keryxAccentInk(),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(KeryxRadius.chip))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(KeryxRadius.field))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                    ) { MissionProse(c.body) }
                                }
                            }
                        }
                        if (d.events.isNotEmpty()) item(key = "events") { EventsSection(d.events) }
                    }
                    // A card that waits on its owner answers from the hero; everywhere else the
                    // plain comment box stays at the foot, as it always was.
                    if (!t.needsYou) {
                        Spacer(Modifier.height(10.dp))
                        var comment by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Comment — lands in the next worker's context", fontSize = 12.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            shape = RoundedCornerShape(KeryxRadius.field),
                            trailingIcon = {
                                TextButton(
                                    enabled = comment.isNotBlank(),
                                    onClick = {
                                        viewModel.missions.kanbanComment(taskId, comment.trim()) { reload++ }
                                        comment = ""
                                    },
                                ) { Text("Send") }
                            },
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/** Two texts saying the same thing (the block reason is often the run's summary verbatim). */
internal fun sameWords(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    if (x.isEmpty() || y.isEmpty()) return x == y
    return x == y || x.startsWith(y.removeSuffix("…")) || y.startsWith(x.removeSuffix("…"))
}

/** Worker prose — a reason, a summary, a comment — set the way a chat bubble sets it: GFM,
 *  the chat's heading scale, parsed in composition so the sheet never flashes an empty box. */
@Composable
private fun MissionProse(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val source = remember(text) { chat.keryx.core.protocol.MessageParser.extractKeryx(text).text.trim() }
    val state = com.mikepenz.markdown.model.rememberMarkdownState(
        content = source,
        flavour = org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor(),
        immediate = true,
    )
    com.mikepenz.markdown.m3.Markdown(
        markdownState = state,
        colors = com.mikepenz.markdown.m3.markdownColor(text = color),
        typography = chatMarkdownTypography(),
    )
}

/**
 * The hero panel: whose move it is (the kind pill), how often it has bounced, the ask in full,
 * and the answer box. On a blocked card the answer can send it back to work in one tap. On a
 * review card the box holds the verdict's words: Approve completes it (the words, if any,
 * become the closing note); Request changes sends it back and needs the words as its reason.
 */
@Composable
private fun AskPanel(
    task: KanbanTask,
    onReply: (String, Boolean) -> Unit,
    onApprove: (String) -> Unit,
    onRequestChanges: (String) -> Unit,
) {
    val hue = if (task.needsYou) KeryxStatus.warn else statusColor(task.status)
    val canUnblock = task.status == "blocked" || task.status == "scheduled"
    var reply by remember(task.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(hue.copy(alpha = 0.09f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeryxSectionHeader(
                label = when {
                    task.status == "review" -> "Waiting on your review"
                    task.needsYou -> "What it needs from you"
                    else -> "Why it's paused"
                },
                dotColor = hue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (task.blockRecurrences > 0) {
                Text(
                    "blocked ${task.blockRecurrences + 1}×",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        StatusPill(blockKindLabel(task.blockKind, task.status), hue)
        if (task.ask.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            MissionProse(task.ask)
        }
        val review = task.status == "review"
        if (task.needsYou) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (review) "A note to approve with, or what has to change"
                        else "Your answer — the next run reads it",
                        fontSize = 12.sp,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                shape = RoundedCornerShape(KeryxRadius.field),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            if (review) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = reply.isNotBlank(),
                    onClick = { onRequestChanges(reply.trim()); reply = "" },
                ) { Text("Request changes", fontSize = 12.sp, color = KeryxStatus.bad) }
                Spacer(Modifier.weight(1f))
                VerdictButton("Approve", enabled = true) { onApprove(reply.trim()); reply = "" }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                if (canUnblock) {
                    TextButton(
                        enabled = reply.isNotBlank(),
                        onClick = { onReply(reply.trim(), false); reply = "" },
                    ) { Text("Reply only", fontSize = 12.sp) }
                }
                Spacer(Modifier.weight(1f))
                VerdictButton(if (canUnblock) "Reply & unblock" else "Reply", enabled = reply.isNotBlank()) {
                    onReply(reply.trim(), canUnblock); reply = ""
                }
            }
        }
    }
}

/** The panel's one filled action: accent ground, ink chosen for contrast against it. */
@Composable
private fun VerdictButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = contrastColorFor(accent),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(KeryxRadius.field))
            .background(accent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun AlertToggle(taskId: String, viewModel: ChatViewModel) {
    val subs by viewModel.missions.kanbanSubs.collectAsState()
    val subscribed = subs[taskId]?.isNotEmpty() == true
    val roomName = viewModel.missions.alertRoomName()
    val alertCtx = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = null,
            tint = if (subscribed) MaterialTheme.colorScheme.tertiary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Alert when this ends", fontSize = 13.sp)
            Text(
                when {
                    subscribed -> "SILAS pushes a message the moment it completes or blocks"
                    roomName != null -> "Lands in $roomName as a real message — no polling"
                    else -> viewModel.missions.alertUnavailableReason
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = subscribed,
            enabled = subscribed || roomName != null,
            onCheckedChange = { on ->
                viewModel.missions.kanbanSetAlert(taskId, on)
                // "Alert me" on the gateway door means the phone, not only the
                // chat: arm the background watcher too (no-op when already on).
                if (on) viewModel.missions.armPhoneAlerts(alertCtx)
            },
        )
    }
}

/** Parents and children as chips: status dot, title, tap to walk the graph. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinkRow(
    label: String,
    links: List<chat.keryx.app.data.remote.HermesStreamClient.KanbanLink>,
    onOpen: (String) -> Unit,
) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            links.forEach { link ->
                val color = statusColor(link.status)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(KeryxRadius.chip))
                        .background(color.copy(alpha = 0.10f))
                        .clickable { onOpen(link.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        link.title,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    Text(
                        "  ${link.status}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One diagnostic: severity rail, title, the suggested first step. A streak a later run has
 *  already outlived is history — dimmed and labelled, never a red alarm. */
@Composable
private fun DiagnosticRow(diag: chat.keryx.app.data.remote.HermesStreamClient.KanbanDiagnostic) {
    val color = if (diag.stale) KeryxStatus.idle else severityColor(diag.severity)
    KeryxCard(
        tint = if (diag.stale) null else color,
        modifier = if (diag.stale) Modifier.alpha(0.6f) else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (diag.stale) "outlived" else diag.severity,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                diag.kind.replace('_', ' '),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(diag.title, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (diag.stale) {
            Spacer(Modifier.height(4.dp))
            Text(
                "A later run ended some other way — this is history, not the current state.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (diag.suggestedAction.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "→ ${diag.suggestedAction}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The run history as a crew deck, newest first — the same card Tap-In draws a helper with. */
@Composable
private fun RunDeck(runs: List<chat.keryx.app.data.remote.HermesStreamClient.KanbanRun>) {
    val ink = MaterialTheme.colorScheme.onSurface
    val newestFirst = runs.sortedByDescending { it.id }
    Column {
        KeryxSectionHeader(label = "Runs", count = runs.size, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(newestFirst, key = { it.id }) { run ->
                val live = run.endedAt == null
                val outcome = run.outcome.ifBlank { run.status }
                val failed = outcome in setOf("crashed", "timed_out", "spawn_failed", "gave_up")
                val tint = when {
                    failed -> KeryxStatus.bad
                    live -> MaterialTheme.colorScheme.primary
                    outcome == "blocked" -> KeryxStatus.warn
                    outcome == "completed" -> KeryxStatus.good
                    else -> null
                }
                val summary = run.summary.ifBlank { run.error }
                KeryxRunCard(
                    glyph = runGlyph(outcome, live),
                    title = "#${run.id} · ${run.profile.ifBlank { "worker" }}",
                    ink = ink,
                    modifier = Modifier.width(236.dp),
                    tint = tint,
                    breathing = live,
                    meta = listOfNotNull(
                        outcome.replace('_', ' '),
                        runLength(run.durationSeconds ?: if (live) (System.currentTimeMillis() / 1000L) - run.startedAt else null),
                        missionClock(run.startedAt),
                    ),
                    activity = if (live) "working…" else null,
                    alive = live,
                    summary = chat.keryx.core.protocol.MessageParser.extractKeryx(summary).text.trim(),
                    summaryColor = if (failed) KeryxStatus.bad else ink.copy(alpha = 0.78f),
                )
            }
        }
    }
}

internal fun runGlyph(outcome: String, live: Boolean): String = when {
    live -> "●"
    outcome == "completed" -> "✓"
    outcome == "blocked" -> "⊘"
    outcome in setOf("crashed", "spawn_failed", "gave_up") -> "✕"
    outcome == "timed_out" -> "⏱"
    outcome == "reclaimed" -> "↺"
    outcome == "review_requested" -> "◎"
    else -> "·"
}

/** "Sep 24 18:38" — when a run started, in the phone's own zone. */
private fun missionClock(epochSeconds: Long): String? {
    if (epochSeconds <= 0) return null
    return java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochSeconds * 1000L))
}

/** The brief, folded to its first lines until asked — it's the card's past, not its present. */
@Composable
private fun BriefSection(body: String) {
    var open by remember(body) { mutableStateOf(false) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { open = !open },
        ) {
            KeryxSectionHeader(label = "Brief", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (open) "▾" else "▸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        if (open) MissionProse(body) else Text(
            body,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.clickable { open = true },
        )
    }
}

/** The event log, collapsed by default: newest first, heartbeats folded into a count. */
@Composable
private fun EventsSection(events: List<chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent>) {
    var open by remember { mutableStateOf(false) }
    val (rows, beats) = remember(events) { readableEvents(events) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        ) {
            KeryxSectionHeader(label = "Events", count = rows.size, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (open) "▾" else "▸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (beats > 0) Text(
                "$beats heartbeat${if (beats == 1) "" else "s"} folded",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            rows.forEach { e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(
                        missionClock(e.createdAt).orEmpty(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(84.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.kind.replace('_', ' ') + (e.runId?.let { "  · run $it" } ?: ""),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (e.detail.isNotBlank()) Text(
                            e.detail,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** v0.20 per-task steering: pin the thinking depth / model this mission runs with. Applies on
 *  the NEXT dispatch, so it's settable mid-run — repinning a rate-limited running task is the
 *  primary recovery flow. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SteeringSection(taskId: String, task: KanbanTask, viewModel: ChatViewModel, onChanged: () -> Unit) {
    Column {
        KeryxSectionHeader(
            label = "Steering",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text("Thinking depth", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val current = task.reasoningEffort
            (listOf("" to "inherit", "none" to "off") +
                listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                    .map { it to it }).forEach { (value, label) ->
                val selected = value == current
                Text(
                    label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(KeryxRadius.chip))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                        .clickable(enabled = !selected) {
                            viewModel.missions.kanbanSetReasoning(taskId, value) { onChanged() }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        var modelDraft by remember(task.modelOverride) {
            mutableStateOf(task.modelOverride)
        }
        OutlinedTextField(
            value = modelDraft,
            onValueChange = { modelDraft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model pin (blank = profile's own)", fontSize = 12.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(KeryxRadius.field),
            singleLine = true,
            trailingIcon = {
                if (modelDraft.trim() != task.modelOverride) {
                    TextButton(onClick = {
                        viewModel.missions.kanbanSetModel(taskId, modelDraft.trim()) { onChanged() }
                    }) { Text(if (modelDraft.isBlank()) "Clear" else "Pin") }
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MissionCreateDialog(
    profiles: List<String>,
    canNotify: Boolean,
    notifyUnavailableReason: String = "Open a room first — alerts land in a Matrix room",
    onCreate: (title: String, assignee: String, body: String, triage: Boolean, notify: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf(profiles.firstOrNull() ?: "default") }
    // Default parked: a phone tap should not spawn a worker until the mission says it should.
    var triage by remember { mutableStateOf(true) }
    var notify by remember { mutableStateOf(canNotify) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                KeryxSectionHeader("New mission")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(KeryxRadius.field),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    label = { Text("Brief (what done looks like)") },
                    shape = RoundedCornerShape(KeryxRadius.field),
                )
                Spacer(Modifier.height(10.dp))
                Text("Assignee", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                // FlowRow, not Row: five profiles overflow a phone-width dialog, and a plain Row
                // squeezes the last chip to letter-per-line confetti instead of wrapping.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    profiles.forEach { p ->
                        val selected = p == assignee
                        Text(
                            p.replaceFirstChar { it.uppercase() },
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                                .clickable { assignee = p }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Park in triage", fontSize = 13.sp)
                        Text(
                            if (triage) "Spec-first — nothing runs until promoted"
                            else "Dispatcher spawns a worker for it",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = triage, onCheckedChange = { triage = it })
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify me when it ends", fontSize = 13.sp)
                        Text(
                            if (canNotify) "A real message lands in your room on completion"
                            else notifyUnavailableReason,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notify && canNotify,
                        enabled = canNotify,
                        onCheckedChange = { notify = it },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = title.isNotBlank(),
                        onClick = { onCreate(title.trim(), assignee, body.trim(), triage, notify && canNotify) },
                    ) { Text("Create") }
                }
            }
        }
    }
}
