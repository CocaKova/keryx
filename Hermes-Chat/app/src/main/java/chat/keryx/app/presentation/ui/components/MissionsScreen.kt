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
    val board by viewModel.kanbanBoard.collectAsState()
    val refreshing by viewModel.kanbanRefreshing.collectAsState()
    val error by viewModel.kanbanError.collectAsState()
    val caps by viewModel.reasoningCaps.collectAsState()
    val subs by viewModel.kanbanSubs.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var openTaskId by remember { mutableStateOf<String?>(null) }

    // Fresh on open, then a gentle poll while (and only while) this screen is composed AND the
    // app is actually on screen — the dispatcher moves cards without us, and 20s is plenty for a
    // board humans look at. repeatOnLifecycle suspends the loop while backgrounded and refreshes
    // immediately on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            viewModel.refreshKanban()
            while (true) {
                delay(20_000L)
                viewModel.refreshKanban()
            }
        }
    }

    val tasks = board?.tasks.orEmpty()
    val known = SECTION_ORDER.map { it.first }.toSet()
    val sections =
        SECTION_ORDER.filter { (s, _) -> tasks[s]?.isNotEmpty() == true } +
            tasks.keys.filter { it !in known }.sorted().map { it to it }
    val runningCount = tasks["running"]?.size ?: 0

    KeryxSpace(
        title = "Missions",
        onClose = onDismissRequest,
        liveSlot = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxBreathingDot(
                    color = if (runningCount > 0) MaterialTheme.colorScheme.primary else KeryxStatus.idle,
                    alive = runningCount > 0,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
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
            IconButton(onClick = { viewModel.refreshKanban() }, enabled = !refreshing) {
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
        val sectionCounts = sections.map { (s, _) -> s to (tasks[s]?.size ?: 0) }
        val starts = sectionStartIndices(sectionCounts)

        // Lane-jump chips: one per non-empty section, tap scrolls to that lane's header.
        if (sections.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sections.forEach { (status, label) ->
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
                            "$label ${tasks[status]?.size ?: 0}",
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
                sections.forEach { (status, label) ->
                    val cards = tasks[status].orEmpty()
                    item(key = "hdr-$status") {
                        KeryxSectionHeader(
                            label = label,
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
        MissionCreateDialog(
            profiles = missionAssignees(caps?.roomProfiles.orEmpty()),
            canNotify = viewModel.missionAlertRoom() != null,
            onCreate = { title, assignee, body, triage, notify ->
                viewModel.kanbanCreate(title, assignee, body, triage, notify)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }

    openTaskId?.let { tid ->
        MissionDetailSheet(
            taskId = tid,
            viewModel = viewModel,
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
    "running" -> MaterialTheme.colorScheme.primary
    "ready" -> MaterialTheme.colorScheme.tertiary
    "blocked" -> KeryxStatus.bad
    "done" -> KeryxStatus.good
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun MissionCard(task: KanbanTask, subscribed: Boolean, onClick: () -> Unit) {
    val color = statusColor(task.status)
    val running = task.status == "running"
    KeryxCard(
        onClick = onClick,
        tint = if (running || task.status == "blocked") color else null,
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
                }
                if (task.bodyExcerpt.isNotBlank()) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissionDetailSheet(
    taskId: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var detail by remember { mutableStateOf<KanbanDetail?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(taskId, reload) {
        viewModel.kanbanTaskDetail(taskId)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeryxSectionHeader(
                            label = d.task.status,
                            dotColor = statusColor(d.task.status),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            d.task.id,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(d.task.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val subs by viewModel.kanbanSubs.collectAsState()
                    val subscribed = subs[taskId]?.isNotEmpty() == true
                    val roomName = viewModel.missionAlertRoomName()
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
                                    else -> "Open a room first — alerts land in a Matrix room"
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = subscribed,
                            enabled = subscribed || roomName != null,
                            onCheckedChange = { viewModel.kanbanSetAlert(taskId, it) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (d.task.body.isNotBlank()) item {
                            Text(d.task.body, fontSize = 13.sp)
                        }
                        if (d.task.result.isNotBlank()) item {
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
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(d.task.result, fontSize = 12.sp)
                            }
                        }
                        if (d.task.lastFailureError.isNotBlank()) item {
                            Text(
                                "⚠ ${d.task.lastFailureError}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (d.comments.isNotEmpty()) {
                            item {
                                KeryxSectionHeader(
                                    label = "Comments",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    count = d.comments.size,
                                )
                            }
                            items(d.comments) { c ->
                                // Comments wear the chat voice: author chip over a soft bubble.
                                Column {
                                    Text(
                                        c.author,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(KeryxRadius.chip))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                            .padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        c.body,
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(KeryxRadius.field))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
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
                                    viewModel.kanbanComment(taskId, comment.trim()) { reload++ }
                                    comment = ""
                                },
                            ) { Text("Send") }
                        },
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MissionCreateDialog(
    profiles: List<String>,
    canNotify: Boolean,
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
                            else "Open a room first — alerts land in a Matrix room",
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
