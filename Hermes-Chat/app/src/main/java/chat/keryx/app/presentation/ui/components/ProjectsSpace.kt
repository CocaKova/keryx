package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
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

    val hidden by viewModel.projects.hiddenProjects.collectAsState()
    var showHidden by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val allNodes = (tree?.projects ?: emptyList()).filter { !it.isNoProject }
    // Only auto projects can be hidden; an id left over from one that has since gone counts for nothing.
    val hiddenCount = allNodes.count { it.isAuto && it.id in hidden }
    val nodes = if (showHidden) allNodes else allNodes.filterNot { it.isAuto && it.id in hidden }
    val openNode = openId?.let { id -> nodes.firstOrNull { it.id == id } }

    KeryxSpace(
        title = openNode?.label?.uppercase() ?: stringResource(R.string.work_projects),
        // 3.0: one meaning per button, as in every place. The arrow steps out of a project to
        // the list; the X leaves Projects from wherever you are.
        onClose = onClose,
        onBack = if (openId != null) ({ openId = null; viewModel.projects.closeProjectDetail() }) else null,
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

        if (tree != null && nodes.isEmpty()) {
            KeryxEmptyState(
                title = stringResource(R.string.work_proj_none),
                body = stringResource(R.string.work_proj_none_body),
                illustration = { ProjectsFolderIllustration() },
                actionLabel = stringResource(R.string.work_proj_new),
                onAction = { showCreate = true },
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
                        stringResource(R.string.work_proj_new), fontSize = KeryxType.body, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        error ?: stringResource(R.string.work_proj_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = KeryxType.body,
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
                    // An auto project can't be removed on stock Hermes, only hidden here (3.0).
                    hidden = node.isAuto && node.id in hidden,
                    onHide = if (!node.isAuto) null else { hide ->
                        viewModel.projects.setProjectHidden(node.id, hide)
                    },
                )
            }
            if (hiddenCount > 0) {
                item(key = "hidden-toggle") {
                    TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            if (showHidden) stringResource(R.string.work_proj_hide_hidden)
                            else stringResource(R.string.work_proj_show_hidden, hiddenCount),
                            fontSize = KeryxType.caption,
                        )
                    }
                }
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
    /** This auto project is hidden in Keryx and only on screen because hidden ones are shown. */
    hidden: Boolean = false,
    /** Hide (true) or show again (false) an auto project, in Keryx only. */
    onHide: ((Boolean) -> Unit)? = null,
) {
    val haptics = LocalKeryxHaptics.current
    val snack = LocalKeryxSnack.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun report(err: String?, ok: String) {
        snack.show(err ?: ok)
    }
    val onLongPress: (() -> Unit)? =
        if (onArchive == null && onDelete == null && onHide == null) null
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
            // A hidden one, shown on request, reads as set aside.
            .then(if (hidden) Modifier.alpha(0.5f) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .drawTintRail(tintFor(node))
            .padding(start = 15.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = node.label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = KeryxType.body,
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
                    stringResource(R.string.work_proj_auto),
                    fontSize = KeryxType.micro,
                    fontFamily = FontFamily.Monospace,
                    color = chat.keryx.app.theme.quietInk(alpha = 0.55f),
                )
            }
            // 3.0: archive and delete in plain sight. They were only behind a long-press,
            // which nobody finds; the long-press still opens the same menu.
            if (onLongPress != null) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        KeryxGlyphs.Kebab,
                        contentDescription = stringResource(R.string.work_proj_actions, node.label),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        val meta = buildList {
            add(pluralStringResource(R.plurals.work_proj_sessions, node.sessionCount.toInt(), node.sessionCount.toInt()))
            if (node.lastActiveMs > 0L) add(
                formatRelativeTime(node.lastActiveMs)
            )
            node.path?.let { add(it.substringAfterLast('/')) }
        }.joinToString(" · ")
        Text(
            text = meta,
            color = chat.keryx.app.theme.quietInk(alpha = 0.65f),
            fontSize = KeryxType.micro,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Up to three preview titles — the card answers "what's in here" without a tap.
        node.previewSessions.take(3).forEach { s ->
            Text(
                text = s.name,
                color = chat.keryx.app.theme.quietInk(alpha = 0.8f),
                fontSize = KeryxType.micro,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
    val archivedMsg = stringResource(R.string.work_proj_archived, node.label)
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
                text = { Text(stringResource(R.string.work_proj_archive)) },
                onClick = { menuOpen = false; archive { report(it, archivedMsg) } },
            )
        }
        onHide?.let { hide ->
            val hiddenMsg = stringResource(R.string.work_proj_hidden_toast, node.label)
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(if (hidden) R.string.work_proj_unhide else R.string.work_proj_hide)) },
                onClick = {
                    menuOpen = false
                    hide(!hidden)
                    if (!hidden) snack.show(hiddenMsg)
                },
            )
        }
        onDelete?.let {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.work_proj_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmDelete = true },
            )
        }
    }
    } // end anchor Box
    if (confirmDelete && onDelete != null) {
        val deletedMsg = stringResource(R.string.work_proj_deleted, node.label)
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.work_proj_delete_title, node.label), fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    // The reassurance that makes this safe to tap: a project is a grouping
                    // row, and deleting it is not deleting anyone's work.
                    stringResource(R.string.work_proj_delete_body),
                    fontSize = KeryxType.body,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete { report(it, deletedMsg) }
                }) { Text(stringResource(R.string.work_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.mk_cancel)) } },
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
                        fontSize = KeryxType.micro,
                        fontFamily = FontFamily.Monospace,
                        color = chat.keryx.app.theme.quietInk(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (node.path != null) {
                    TalariaActionChip(label = stringResource(R.string.work_proj_new_chat_here), onClick = onNewChat)
                }
            }
        }
        if (flat.isEmpty()) {
            item {
                Text(
                    if (hydrating) stringResource(R.string.work_proj_loading_sessions) else stringResource(R.string.work_proj_no_sessions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = KeryxType.body,
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
                            fontSize = KeryxType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val meta = buildList {
                        if (session.timestamp > 0L) add(
                            formatRelativeTime(session.timestamp)
                        )
                        if (session.messageCount > 0L) add(pluralStringResource(R.plurals.work_proj_msgs, session.messageCount.toInt(), session.messageCount.toInt()))
                        session.source.takeIf { it.isNotBlank() && it != "unknown" }?.let { add(it) }
                    }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            color = chat.keryx.app.theme.quietInk(alpha = 0.65f),
                            fontSize = KeryxType.micro,
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
        title = { Text(stringResource(R.string.work_proj_new), fontSize = KeryxType.titleLarge) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.work_proj_name)) },
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
                    stringResource(R.string.work_proj_new_body),
                    fontSize = KeryxType.micro,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                err?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = KeryxType.micro,
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
            ) { Text(if (busy) stringResource(R.string.work_creating) else stringResource(R.string.work_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.mk_cancel)) } },
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
    size: androidx.compose.ui.unit.TextUnit = KeryxType.micro,
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
