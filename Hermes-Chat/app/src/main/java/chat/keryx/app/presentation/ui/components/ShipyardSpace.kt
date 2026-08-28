package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.ShipyardFile
import chat.keryx.core.model.ShipyardRepo
import chat.keryx.core.model.ShipyardStatus

/**
 * The Shipyard — review the agent's work and ship it, from the phone (roadmap §2 "The Forge",
 * renamed: the app already has a Skill Forge). Three levels inside one place: the repo roster,
 * one repo's changed files, one file's diff. Stage/unstage per file or all; a commit sheet
 * pre-armed with the recent subjects; push; the PR line says the true thing.
 *
 * Deliberately absent (the roadmap's own traps): revert — it destroys work no git object
 * holds — and create-PR, which opens a PR as the gateway's user. Both wait for a landing that
 * can name the file and confirm.
 */
@Composable
fun ShipyardSpace(
    viewModel: ChatViewModel,
    onClose: () -> Unit,
) {
    val yard = viewModel.shipyard
    val repos by yard.repos.collectAsState()
    val repo by yard.openRepo.collectAsState()
    val status by yard.status.collectAsState()
    val scope by yard.reviewScope.collectAsState()
    val review by yard.review.collectAsState()
    val openFile by yard.openFile.collectAsState()
    val diff by yard.diff.collectAsState()
    val shipInfo by yard.shipInfo.collectAsState()
    val busy by yard.busy.collectAsState()
    val error by yard.error.collectAsState()
    LaunchedEffect(Unit) { yard.refreshRepos() }

    // Back walks up one level before the place itself closes.
    androidx.activity.compose.BackHandler(enabled = repo != null) {
        if (openFile != null) yard.closeFile() else yard.closeRepo()
    }

    var showCommit by remember { mutableStateOf(false) }
    if (showCommit && repo != null) {
        CommitSheet(
            viewModel = viewModel,
            status = status,
            onDismiss = { showCommit = false },
        )
    }

    val title = when {
        openFile != null -> openFile!!.substringAfterLast('/')
        repo != null -> repo!!.label
        else -> "Shipyard"
    }

    KeryxSpace(
        title = title,
        onClose = {
            when {
                openFile != null -> yard.closeFile()
                repo != null -> yard.closeRepo()
                else -> onClose()
            }
        },
        standalone = false,
    ) {
        error?.let { msg ->
            Text(
                msg,
                color = KeryxStatus.bad,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { yard.clearError() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val openRepo = repo
        if (openRepo == null) {
            RepoRoster(repos, onOpen = yard::openRepo)
            return@KeryxSpace
        }

        val file = openFile
        if (file != null) {
            val d = diff
            val row = review?.files?.firstOrNull { it.path == file }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    if (row != null) DiffStat(row.added, row.removed)
                }
                Spacer(Modifier.height(8.dp))
                if (row != null && scope == "uncommitted") {
                    ShipyardChip(
                        label = if (row.staged) "Unstage" else "Stage",
                        enabled = !busy,
                        onClick = { yard.stage(row.path, row.staged) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    d == null -> Text("reading the diff…", fontSize = 12.sp, color = KeryxStatus.idle)
                    d.diff.isBlank() -> Text("No diff — the file is unchanged in this scope.", fontSize = 12.sp, color = KeryxStatus.idle)
                    else -> {
                        DiffPanel(
                            diff = d.diff,
                            truncated = false,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            maxHeight = 2000.dp,
                        )
                        if (d.clipped) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "clipped by the gateway: ${d.omittedLines} of ${d.totalLines} lines not shown",
                                fontSize = 11.sp,
                                color = KeryxStatus.warn,
                            )
                        }
                    }
                }
            }
            return@KeryxSpace
        }

        RepoReview(
            repo = openRepo,
            status = status,
            scope = scope,
            files = review?.files,
            hydrating = review == null,
            busy = busy,
            shipInfoLine = shipInfo?.let { si ->
                when {
                    si.prUrl != null -> "PR #${si.prNumber ?: "?"} ${si.prState ?: ""}".trim()
                    !si.ghReady -> null
                    else -> null
                }
            },
            onScope = yard::setScope,
            onOpenFile = { yard.openFile(it.path, it.staged) },
            onStage = { f -> yard.stage(f.path, f.staged) },
            onStageAll = { yard.stage(null, false) },
            onCommit = { yard.loadCommitContext(); showCommit = true },
            onPush = yard::push,
            onRefresh = yard::refreshTree,
        )
    }
}

@Composable
private fun RepoRoster(repos: List<ShipyardRepo>, onOpen: (ShipyardRepo) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (repos.isEmpty()) item {
            Text(
                "No repos the gateway will let a phone review. A project's folder or a discovered repo under the gateway user's home appears here.",
                fontSize = 12.sp,
                color = KeryxStatus.idle,
            )
        }
        items(repos, key = { it.path }) { r ->
            val tint = roomLight(r.path)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KeryxRadius.card))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.145f), RoundedCornerShape(KeryxRadius.card))
                    .clickable { onOpen(r) }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(tint))
                    Spacer(Modifier.width(10.dp))
                    Text(r.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    r.branch?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text(r.path, fontSize = 11.sp, color = KeryxStatus.idle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RepoReview(
    repo: ShipyardRepo,
    status: ShipyardStatus?,
    scope: String,
    files: List<ShipyardFile>?,
    hydrating: Boolean,
    busy: Boolean,
    shipInfoLine: String?,
    onScope: (String) -> Unit,
    onOpenFile: (ShipyardFile) -> Unit,
    onStage: (ShipyardFile) -> Unit,
    onStageAll: () -> Unit,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // The branch line: where we are, and whether the remote agrees.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    status?.branch ?: repo.branch ?: "detached",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                status?.let { st ->
                    if (st.ahead > 0) Text("↑${st.ahead}", fontSize = 12.sp, color = KeryxStatus.warn)
                    if (st.behind > 0) Text(" ↓${st.behind}", fontSize = 12.sp, color = KeryxStatus.warn)
                    if (st.ahead == 0 && st.behind == 0 && !st.detached) Text("in step", fontSize = 11.sp, color = KeryxStatus.idle)
                }
            }
            shipInfoLine?.let { Text(it, fontSize = 11.sp, color = KeryxStatus.good) }
            Text(repo.path, fontSize = 10.sp, color = KeryxStatus.idle, maxLines = 1)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShipyardChip("Working tree", filled = scope == "uncommitted", onClick = { onScope("uncommitted") })
                ShipyardChip("Branch", filled = scope == "branch", onClick = { onScope("branch") })
                Spacer(Modifier.weight(1f))
                ShipyardChip("Refresh", onClick = onRefresh)
            }
        }
        item {
            val st = status
            val summary = when {
                st == null -> "reading the tree…"
                scope == "branch" -> "${files?.size ?: 0} files changed on this branch"
                st.clean -> "Clean — nothing to commit."
                else -> "${st.changed} changed · ${st.staged} staged · ${st.untracked} untracked" +
                    if (st.conflicted > 0) " · ${st.conflicted} conflicted" else ""
            }
            KeryxSectionHeader(summary, count = null)
        }
        val list = files ?: emptyList()
        if (hydrating) item { Text("…", color = KeryxStatus.idle) }
        items(list, key = { it.path }) { f ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KeryxRadius.field))
                    .clickable { onOpenFile(f) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                if (scope == "uncommitted") {
                    Checkbox(checked = f.staged, onCheckedChange = { onStage(f) }, enabled = !busy)
                } else Spacer(Modifier.width(8.dp))
                Text(
                    f.status,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusTint(f.status),
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    f.path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                DiffStat(f.added, f.removed)
            }
        }
        if (scope == "uncommitted") item {
            Spacer(Modifier.height(8.dp))
            val st = status
            val canCommit = st != null && !st.clean && !busy
            val canPush = st != null && st.ahead > 0 && !busy && !st.detached
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ShipyardChip("Stage all", enabled = canCommit, onClick = onStageAll)
                ShipyardChip("Commit…", enabled = canCommit, filled = canCommit, onClick = onCommit)
                ShipyardChip(
                    if (st != null && st.ahead > 0) "Push ↑${st.ahead}" else "Push",
                    enabled = canPush,
                    filled = canPush,
                    onClick = onPush,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommitSheet(
    viewModel: ChatViewModel,
    status: ShipyardStatus?,
    onDismiss: () -> Unit,
) {
    val yard = viewModel.shipyard
    val ctx by yard.commitContext.collectAsState()
    val busy by yard.busy.collectAsState()
    var message by remember { mutableStateOf("") }
    var push by remember { mutableStateOf(false) }
    val haptics = LocalKeryxHaptics.current
    KeryxSheet(onDismiss = onDismiss, title = "Commit") {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val st = status
            Text(
                if (st != null && st.staged > 0) "${st.staged} staged file${if (st.staged == 1) "" else "s"} will be committed."
                else "Nothing is staged — everything in the working tree will be committed.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            ctx?.recentSubjects?.takeIf { it.isNotEmpty() }?.let { recent ->
                Spacer(Modifier.height(10.dp))
                Text("RECENT", fontSize = 10.sp, letterSpacing = 1.4.sp, color = KeryxStatus.idle)
                recent.take(4).forEach { s ->
                    Text(s, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { push = !push }) {
                Checkbox(checked = push, onCheckedChange = { push = it })
                Text("Push after committing", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                ShipyardChip(
                    label = if (push) "Commit & push" else "Commit",
                    filled = true,
                    enabled = message.isNotBlank() && !busy,
                    onClick = {
                        haptics.commit()
                        yard.commit(message, push) { ok -> if (ok) onDismiss() }
                    },
                )
            }
        }
    }
}

@Composable
private fun statusTint(letter: String): Color = when (letter) {
    "A", "?" -> KeryxStatus.good
    "D" -> KeryxStatus.bad
    "U" -> KeryxStatus.warn
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ShipyardChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(KeryxRadius.chip)
    Box(
        modifier
            .clip(shape)
            .let {
                if (filled) it.background(tint.copy(alpha = if (enabled) 1f else 0.3f))
                else it.border(1.dp, tint.copy(alpha = if (enabled) 0.22f else 0.1f), shape)
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) MaterialTheme.colorScheme.background else tint.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
        )
    }
}
