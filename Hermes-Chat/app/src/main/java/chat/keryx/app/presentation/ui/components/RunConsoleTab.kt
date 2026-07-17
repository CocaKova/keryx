package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.ConsoleRun
import chat.keryx.app.presentation.ChatViewModel

/**
 * The Run Console (1.20): launch an agent run on the STOCK gateway API and watch it live —
 * tokens, reasoning, tool activity, approval gates, stop. Also the landing surface for the
 * Sessions tab's Resume. State lives in the ViewModel, so a run keeps streaming while the sheet
 * is closed; reopening the tab reattaches to the same [ChatViewModel.console] flow.
 *
 * Rendering rides the existing chat machinery: [MessageContent] with `isStreaming` keeps the
 * live transcript parse O(window) (the 1.19 contract), and approval gates reuse the ask-tile
 * look via [QuickActionTiles] — the console's approval IS a decision request, same chrome.
 */
@Composable
fun RunConsoleTab(viewModel: ChatViewModel) {
    val console by viewModel.console.collectAsState()
    val target by viewModel.consoleSessionTarget.collectAsState()
    val runs by viewModel.consoleRuns.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var openRun by remember { mutableStateOf<ConsoleRun?>(null) }

    openRun?.let { run ->
        ConsoleRunDetail(run = run, onBack = { openRun = null })
        return
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Resume banner: the next launch goes INTO this session instead of a fresh run.
        target?.let { s ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        "↪ resuming: " + (s.title?.takeIf { it.isNotBlank() } ?: s.id),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { viewModel.consoleSetSessionTarget(null) },
                        modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear resume target",
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // Composer.
        item {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (target != null) "Message into this session…"
                            else "Give the agent a task…",
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    enabled = !console.live,
                    maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = prompt.isNotBlank() && !console.live,
                    onClick = {
                        viewModel.consoleReset()
                        viewModel.consoleLaunch(prompt.trim())
                        prompt = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Launch run",
                        tint = if (prompt.isNotBlank() && !console.live) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            }
        }

        // Live/last run block.
        if (console.status != "idle") {
            item {
                Spacer(Modifier.height(10.dp))
                ConsoleStatusLine(
                    status = console.status,
                    error = console.error,
                    onStop = { viewModel.consoleStop() },
                    onClear = { viewModel.consoleReset() },
                )
            }
            if (console.reasoningTail.isNotBlank()) {
                item {
                    Text(
                        console.reasoningTail,
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (console.tools.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        // Only the recent tail renders — the full activity isn't a log viewer.
                        console.tools.takeLast(6).forEach { line ->
                            Text(
                                line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (console.transcript.isNotBlank()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    MessageContent(
                        content = console.transcript,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        isStreaming = console.live,
                    )
                }
            }
            console.approval?.let { gate ->
                item {
                    Spacer(Modifier.height(8.dp))
                    ConsoleApprovalGate(
                        gate = gate,
                        onChoice = { viewModel.consoleApprove(it) },
                    )
                }
            }
        }

        // Past runs the app launched (local registry — the gateway can't enumerate runs).
        if (runs.isNotEmpty()) {
            item {
                Text(
                    "RECENT RUNS",
                    fontSize = 10.sp,
                    letterSpacing = 2.0.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(runs, key = { it.id + it.startedAt }) { run ->
                ConsoleRunRow(run = run, onClick = { openRun = run })
            }
        }
    }
}

@Composable
private fun ConsoleStatusLine(
    status: String,
    error: String?,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val live = status == "starting" || status == "streaming" || status == "waiting"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(consoleStatusColor(status)))
        Spacer(Modifier.width(8.dp))
        Text(
            when (status) {
                "starting" -> "starting…"
                "streaming" -> "running"
                "waiting" -> "waiting for approval"
                "lost" -> "connection lost" + (error?.let { " — $it" } ?: "")
                "failed" -> "failed" + (error?.let { " — $it" } ?: "")
                else -> status
            },
            fontSize = 12.sp,
            color = if (status == "failed") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (live) {
            TextButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("Stop", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
        }
    }
}

/** The blocked-tool gate: what the agent wants to do, plus the gateway's own choice verbs as
 *  ask-style tiles. Labels are humanized; the RAW verb is what goes back to the gateway. */
@Composable
private fun ConsoleApprovalGate(
    gate: ChatViewModel.ConsoleApproval,
    onChoice: (String) -> Unit,
) {
    val labels = mapOf(
        "once" to "Allow once",
        "session" to "Allow this session",
        "always" to "Always allow",
        "deny" to "Deny",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), shape)
            .padding(10.dp),
    ) {
        Text(
            "⚠ approval needed" + (gate.tool.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        if (gate.command.isNotBlank()) {
            Text(
                gate.command,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                maxLines = 6,
            )
        }
        // Reuse the ask-tile chrome; map the tapped LABEL back to the gateway's raw verb.
        val byLabel = gate.choices.associateBy { labels[it] ?: it }
        CompositionLocalProvider(LocalQuickActionSender provides { label ->
            onChoice(byLabel[label] ?: label)
        }) {
            QuickActionTiles(
                options = gate.choices.map { labels[it] ?: it },
                baseColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConsoleRunRow(run: ConsoleRun, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(consoleStatusColor(run.status)))
            Spacer(Modifier.width(8.dp))
            Text(
                run.prompt.ifBlank { run.id },
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (run.kind == "session") {
                Text(
                    "resume",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(run.status, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (run.error.isNotBlank()) {
            Text(
                run.error,
                fontSize = 10.sp, color = MaterialTheme.colorScheme.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 15.dp, top = 2.dp),
            )
        }
    }
}

/** Read-only detail for a finished registry run: prompt + full final output. */
@Composable
private fun ConsoleRunDetail(run: ConsoleRun, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 20.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to console")
            }
            Column {
                Text(run.prompt.ifBlank { run.id }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    run.status + " · " + run.id,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            item {
                if (run.error.isNotBlank()) {
                    Text("⚠ ${run.error}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                if (run.output.isNotBlank()) {
                    MessageContent(
                        content = run.output,
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text("No output captured.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun consoleStatusColor(status: String): Color = when (status) {
    "completed" -> Color(0xFF4CAF50)
    "starting", "streaming", "running" -> Color(0xFFE8A33D)
    "waiting" -> Color(0xFFE8A33D)
    "cancelled", "lost" -> Color(0x66FFFFFF)
    else -> Color(0xFFE0524D)
}
