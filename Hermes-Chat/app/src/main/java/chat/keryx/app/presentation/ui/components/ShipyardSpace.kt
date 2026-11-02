package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
        else -> stringResource(R.string.work_shipyard)
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
        // The failure was a bare red sentence whose only dismissal was knowing the sentence was
        // tappable. It is a card now — the app's own recipe, tinted by the status that produced
        // it — and the way out is a glyph you can see and a target you can hit.
        error?.let { msg ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                KeryxCard(tint = KeryxStatus.bad) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            msg,
                            color = KeryxStatus.bad,
                            fontSize = KeryxType.caption,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.IconButton(
                            onClick = { yard.clearError() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                KeryxGlyphs.Close,
                                contentDescription = stringResource(R.string.work_dismiss_error),
                                tint = KeryxStatus.bad,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
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
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    if (row != null) DiffStat(row.added, row.removed)
                }
                Spacer(Modifier.height(8.dp))
                if (row != null && scope == "uncommitted") {
                    ShipyardChip(
                        label = if (row.staged) stringResource(R.string.work_ship_unstage) else stringResource(R.string.work_ship_stage),
                        enabled = !busy,
                        onClick = { yard.stage(row.path, row.staged) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when {
                    d == null -> Text(stringResource(R.string.work_ship_reading_diff), fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.diff.isBlank() -> Text(stringResource(R.string.work_ship_no_diff), fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                stringResource(R.string.work_ship_clipped, d.omittedLines, d.totalLines),
                                fontSize = KeryxType.micro,
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
        // The empty state was the one thing in this list with no shape — a bare paragraph above a
        // column of bordered cards. It reads as the card that would be here if there were one.
        if (repos.isEmpty()) item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(KeryxRadius.card))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.145f),
                        RoundedCornerShape(KeryxRadius.card),
                    )
                    .padding(14.dp),
            ) {
                KeryxSectionHeader(stringResource(R.string.work_ship_nothing))
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.work_ship_nothing_body),
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    Text(r.label, fontWeight = FontWeight.SemiBold, fontSize = KeryxType.title, modifier = Modifier.weight(1f))
                    r.branch?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text(r.path, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
                    status?.branch ?: repo.branch ?: stringResource(R.string.work_ship_detached),
                    fontFamily = FontFamily.Monospace,
                    fontSize = KeryxType.body,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                status?.let { st ->
                    if (st.ahead > 0) Text("↑${st.ahead}", fontSize = KeryxType.caption, color = KeryxStatus.warn)
                    if (st.behind > 0) Text(" ↓${st.behind}", fontSize = KeryxType.caption, color = KeryxStatus.warn)
                    if (st.ahead == 0 && st.behind == 0 && !st.detached) Text(stringResource(R.string.work_ship_in_step), fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            shipInfoLine?.let { Text(it, fontSize = KeryxType.micro, color = KeryxStatus.good) }
            Text(repo.path, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShipyardChip(stringResource(R.string.work_ship_working_tree), filled = scope == "uncommitted", onClick = { onScope("uncommitted") })
                ShipyardChip(stringResource(R.string.work_ship_branch), filled = scope == "branch", onClick = { onScope("branch") })
                Spacer(Modifier.weight(1f))
                ShipyardChip(stringResource(R.string.work_ship_refresh), onClick = onRefresh)
            }
        }
        item {
            val st = status
            val summary = when {
                st == null -> stringResource(R.string.work_ship_reading_tree)
                scope == "branch" -> pluralStringResource(R.plurals.work_ship_branch_files, files?.size ?: 0, files?.size ?: 0)
                st.clean -> stringResource(R.string.work_ship_clean)
                st.conflicted > 0 -> stringResource(R.string.work_ship_summary_conflicted, st.changed, st.staged, st.untracked, st.conflicted)
                else -> stringResource(R.string.work_ship_summary, st.changed, st.staged, st.untracked)
            }
            KeryxSectionHeader(summary, count = null)
        }
        val list = files ?: emptyList()
        // Was a literal one-character "…" in the dot palette: 2.83:1 on parchment, and it said
        // nothing about what was being waited on. The rest of the app's loading voice is a
        // sentence naming the wait (PanelLoading), so this speaks the same way.
        if (hydrating) item {
            Text(
                stringResource(R.string.work_ship_reading_working_tree),
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
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
                    fontSize = KeryxType.micro,
                    fontWeight = FontWeight.Bold,
                    color = statusTint(f.status),
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    f.path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = KeryxType.caption,
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
                ShipyardChip(stringResource(R.string.work_ship_stage_all), enabled = canCommit, onClick = onStageAll)
                ShipyardChip(stringResource(R.string.work_ship_commit_more), enabled = canCommit, filled = canCommit, onClick = onCommit)
                ShipyardChip(
                    if (st != null && st.ahead > 0) stringResource(R.string.work_ship_push_ahead, st.ahead) else stringResource(R.string.work_ship_push),
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
    KeryxSheet(onDismiss = onDismiss, title = stringResource(R.string.work_ship_commit)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            val st = status
            Text(
                if (st != null && st.staged > 0) pluralStringResource(R.plurals.work_ship_staged_files, st.staged, st.staged)
                else stringResource(R.string.work_ship_nothing_staged),
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.work_ship_message)) },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            ctx?.recentSubjects?.takeIf { it.isNotEmpty() }?.let { recent ->
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.work_ship_recent), fontSize = KeryxType.micro, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recent.take(4).forEach { s ->
                    Text(s, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { push = !push }) {
                Checkbox(checked = push, onCheckedChange = { push = it })
                Text(stringResource(R.string.work_ship_push_after), fontSize = KeryxType.body)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                ShipyardChip(
                    label = if (push) stringResource(R.string.work_ship_commit_push) else stringResource(R.string.work_ship_commit),
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
            // 6dp of padding around a 10sp cap left these ~25dp tall. They are not decoration —
            // Stage, Unstage, Commit, Push, Refresh are every action this space has, and a
            // 25dp target for "Push" is a mis-tap with consequences. 44dp, and the press-scale
            // 2.8.1 gave every other chip in the app.
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .let {
                if (filled) it.background(tint.copy(alpha = if (enabled) 1f else 0.3f))
                else it.border(1.dp, tint.copy(alpha = if (enabled) 0.22f else 0.1f), shape)
            }
            .keryxPressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            fontSize = KeryxType.micro,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) MaterialTheme.colorScheme.background else tint.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
        )
    }
}
