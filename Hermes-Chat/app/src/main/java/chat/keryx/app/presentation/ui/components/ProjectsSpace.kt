package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.ProjectTreeNode
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.formatRelativeTime

/**
 * PROJECTS — the gateway's native workspace grouping, rendered for a phone.
 *
 * A project is a workspace (folders on the host); a session belongs to whichever project
 * claims its cwd. This space lists every project the gateway knows — explicit ones from
 * projects.db and auto-discovered repos — and drills into one as a flat, recency-ordered
 * session list (lanes become quiet captions, not a tree: nobody walks a repo tree on a
 * phone). Sessions open on the chat floor like any other; the Home bucket stays out of
 * the list because the drawer already IS the Home bucket.
 */
@Composable
fun ProjectsSpace(
    viewModel: ChatViewModel,
    /** (session id, session title) — the title travels because project sessions can be
     *  excluded from the conversation list (cron runs, other-machine work), so nothing on
     *  the chat floor could name them (0.6.7 invariant). */
    onOpenSession: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val tree by viewModel.projects.projectsTree.collectAsState()
    val detail by viewModel.projects.projectDetail.collectAsState()
    val error by viewModel.projects.projectsError.collectAsState()
    LaunchedEffect(Unit) { viewModel.projects.refreshProjects() }

    // Two-level place: the overview list, or one project's sessions. Back inside the space
    // returns to the overview before the space itself closes.
    var openId by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = openId != null) {
        openId = null
        viewModel.projects.closeProjectDetail()
    }

    var showCreate by remember { mutableStateOf(false) }
    if (showCreate) {
        CreateProjectDialog(
            viewModel = viewModel,
            onDismiss = { showCreate = false },
            onCreate = { name, path, done -> viewModel.projects.createProject(name, path, done) },
        )
    }

    val nodes = (tree?.projects ?: emptyList()).filter { !it.isNoProject }
    val openNode = openId?.let { id -> nodes.firstOrNull { it.id == id } }

    KeryxSpace(
        title = openNode?.label?.uppercase() ?: "Projects",
        onClose = {
            if (openId != null) { openId = null; viewModel.projects.closeProjectDetail() } else onClose()
        },
        standalone = false,
    ) {
        if (openNode != null) {
            ProjectDetail(
                node = detail ?: openNode,
                hydrating = detail == null,
                onOpenSession = onOpenSession,
                onNewChat = {
                    viewModel.projects.newChatInProject(detail ?: openNode) { err ->
                        if (err == null) onClose() // straight to the fresh chat
                    }
                },
            )
            return@KeryxSpace
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TalariaRadius.field))
                        .clickable { showCreate = true }
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = TalariaStroke.line),
                            RoundedCornerShape(TalariaRadius.field),
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Icon(
                        Icons.Filled.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(TalariaIconSize.md),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "New project", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        if (tree == null) (error ?: "Loading projects…")
                        else "No projects yet.\nA project is a workspace folder — sessions " +
                            "whose work lives there group under it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(nodes, key = { it.id }) { node ->
                ProjectCard(
                    node = node,
                    onOpen = {
                        openId = node.id
                        viewModel.projects.openProjectDetail(node.id)
                    },
                    // Only an EXPLICIT project is a row someone made and can unmake; an
                    // auto-discovered repo has nothing to delete but the repo itself.
                    onArchive = if (node.isAuto) null else { done ->
                        viewModel.projects.archiveProject(node.id, done)
                    },
                    onDelete = if (node.isAuto) null else { done ->
                        viewModel.projects.deleteProject(node.id, done)
                    },
                )
            }
        }
    }
}

/** A project wears a room's light: the same stable identity hue the drawer gives a room
 *  (hash of the id, darkened on parchment by [roomLight]); an explicit server `color` wins. */
@Composable
private fun tintFor(node: ProjectTreeNode): Color {
    node.color?.let { hex ->
        runCatching { return Color(android.graphics.Color.parseColor(hex)) }
    }
    return roomLight(node.id)
}

@Composable
private fun ProjectCard(
    node: ProjectTreeNode,
    onOpen: () -> Unit,
    /** Shelve this project (null where the row isn't someone's to unmake). */
    onArchive: ((done: (String?) -> Unit) -> Unit)? = null,
    /** Forget the row entirely — folder and sessions untouched. */
    onDelete: ((done: (String?) -> Unit) -> Unit)? = null,
) {
    val haptics = LocalKeryxHaptics.current
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun report(err: String?, ok: String) {
        android.widget.Toast.makeText(toastCtx, err ?: ok, android.widget.Toast.LENGTH_SHORT).show()
    }
    val onLongPress: (() -> Unit)? =
        if (onArchive == null && onDelete == null) null
        else ({
            haptics.press()
            menuOpen = true
        })
    Box {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TalariaRadius.card))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = TalariaStroke.line),
                RoundedCornerShape(TalariaRadius.card),
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .drawTintRail(tintFor(node))
            .padding(start = 15.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (node.isAuto) {
                Spacer(Modifier.width(8.dp))
                Text(
                    // A discovered repo, not a project someone declared — labeled so the
                    // difference stays visible instead of silently blended.
                    "auto",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        val meta = buildList {
            add(if (node.sessionCount == 1L) "1 session" else "${node.sessionCount} sessions")
            if (node.lastActiveMs > 0L) add(
                formatRelativeTime(node.lastActiveMs)
            )
            node.path?.let { add(it.substringAfterLast('/')) }
        }.joinToString(" · ")
        Text(
            text = meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Up to three preview titles — the card answers "what's in here" without a tap.
        node.previewSessions.take(3).forEach { s ->
            Text(
                text = s.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
    androidx.compose.material3.DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        containerColor = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = TalariaStroke.line),
        ),
    ) {
        onArchive?.let { archive ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Archive project") },
                onClick = { menuOpen = false; archive { report(it, "Archived ${node.label}") } },
            )
        }
        onDelete?.let {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Delete project", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmDelete = true },
            )
        }
    }
    } // end anchor Box
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${node.label}?", fontSize = 16.sp) },
            text = {
                Text(
                    // The reassurance that makes this safe to tap: a project is a grouping
                    // row, and deleting it is not deleting anyone's work.
                    "Only the project row goes. The folder on the gateway and every session " +
                        "in it stay exactly where they are.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete { report(it, "Deleted ${node.label}") }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectDetail(
    node: ProjectTreeNode,
    hydrating: Boolean,
    onOpenSession: (String, String) -> Unit,
    onNewChat: () -> Unit,
) {
    val flat = node.flatSessions()
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                node.path?.let {
                    Text(
                        it,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (node.path != null) {
                    TalariaActionChip(label = "New chat here", onClick = onNewChat)
                }
            }
        }
        if (flat.isEmpty()) {
            item {
                Text(
                    if (hydrating) "Loading sessions…" else "No sessions in this workspace yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        // Lanes flatten to quiet captions: emit one when the lane changes between rows.
        var lastLane: String? = null
        flat.forEach { (session, lane) ->
            val caption = if (lane != lastLane && lane.isNotBlank()) lane else null
            lastLane = lane
            if (caption != null && flat.size > 1) {
                item(key = "lane-${session.id}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
                    ) {
                        DitherSquare(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        TalariaLabel(caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = session.id) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TalariaRadius.control))
                        .clickable { onOpenSession(session.id, session.name) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.isActive) {
                            Box(
                                Modifier.size(4.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = session.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val meta = buildList {
                        if (session.timestamp > 0L) add(
                            formatRelativeTime(session.timestamp)
                        )
                        if (session.messageCount > 0L) add("${session.messageCount} msgs")
                        session.source.takeIf { it.isNotBlank() && it != "unknown" }?.let { add(it) }
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onCreate: (name: String, path: String, done: (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val roots by viewModel.projects.workspaceRoots.collectAsState()
    AlertDialog(
        shape = RoundedCornerShape(KeryxRadius.sheet),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New project", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                GatewayFolderField(
                    viewModel = viewModel,
                    value = path,
                    onValueChange = { path = it; err = null },
                    suggestions = roots,
                )
                Text(
                    // The one fact that makes this form make sense: grouping is by workspace.
                    "Sessions whose work lives in this folder belong to the project; " +
                        "“Move to project” re-homes a session into it. The folder has to " +
                        "exist on the gateway already — make it there first.",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                err?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    onCreate(name.trim(), path.trim()) { e ->
                        busy = false
                        if (e == null) onDismiss() else err = e
                    }
                },
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** The Cron place's identity rail, shared shape: a 3dp full-height tint at the left edge. */
private fun Modifier.drawTintRail(color: Color): Modifier =
    drawBehind {
        drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
    }

// ---- chrome the port needs, kept file-private (Talaria's design kit, trimmed) ----
private object TalariaStroke { const val strong = 0.30f; const val mid = 0.22f; const val line = 0.145f; const val faint = 0.09f }
private object TalariaIconSize { val sm = 14.dp; val md = 16.dp; val lg = 18.dp; val xl = 20.dp }
private object TalariaRadius { val control = KeryxRadius.chip; val chip = KeryxRadius.chip; val field = KeryxRadius.field; val card = KeryxRadius.card }
@Composable
private fun DitherSquare(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    cell: Dp = 2.dp,
    alpha: Float = 1f,
) {
    androidx.compose.foundation.Canvas(modifier.size(size).clip(RoundedCornerShape(1.dp))) {
        val cellPx = cell.toPx()
        val cols = kotlin.math.ceil(this.size.width / cellPx).toInt()
        val rows = kotlin.math.ceil(this.size.height / cellPx).toInt()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) % 2 == 0) {
                    drawRect(
                        color = color,
                        alpha = alpha,
                        topLeft = androidx.compose.ui.geometry.Offset(c * cellPx, r * cellPx),
                        size = androidx.compose.ui.geometry.Size(cellPx, cellPx),
                    )
                }
            }
        }
    }
}
@Composable
private fun TalariaLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    size: androidx.compose.ui.unit.TextUnit = 10.sp,
    tracking: androidx.compose.ui.unit.TextUnit = 1.4.sp,
    weight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = size,
        letterSpacing = tracking,
        fontWeight = weight,
        color = color,
        maxLines = maxLines,
    )
}
@Composable
private fun TalariaActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(TalariaRadius.control)
    Box(
        modifier
            .clip(shape)
            .let {
                if (filled) it.background(tint.copy(alpha = if (enabled) 1f else 0.3f))
                else it.border(1.dp, tint.copy(alpha = if (enabled) TalariaStroke.mid else 0.1f), shape)
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TalariaLabel(
            label,
            color = if (filled) MaterialTheme.colorScheme.background
            else tint.copy(alpha = if (enabled) 1f else 0.4f),
            tracking = 1.2.sp,
        )
    }
}
