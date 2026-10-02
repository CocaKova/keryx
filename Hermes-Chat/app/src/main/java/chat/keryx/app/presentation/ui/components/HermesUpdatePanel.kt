package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.HermesUpdateDelegate
import chat.keryx.core.model.PluginUpdateStatus
import chat.keryx.core.model.UpdateOutcome
import chat.keryx.core.model.UpdatePhase
import chat.keryx.core.model.UpdatePlan
import chat.keryx.core.model.UpdatePlanner
import chat.keryx.core.model.UpdateReceipt
import chat.keryx.core.model.UpdateText
import kotlinx.coroutines.delay

/**
 * The Update spoke (2.16): is Hermes current, what would change, and the button that updates it
 * — which restarts the gateway the phone is talking to, so the confirm says exactly that.
 *
 * Revived from the 2.4.1 Controls panel (archived as `archive/hub-hermes-update`), keeping what
 * it got right: a count nobody could take reads "unknown", never "up to date"; the operator's
 * own command (and its preflight) wins over a bare `hermes update`; "not run" is not "passed".
 * New here: the dashboard's commit list, its live log, and its receipts, which survive the
 * restart that would otherwise leave the phone guessing how the update went.
 */
@Composable
internal fun UpdateTab(viewModel: ChatViewModel) {
    val board by viewModel.hermesUpdate.board.collectAsState()
    val runView by viewModel.hermesUpdate.run.collectAsState()
    var confirm by remember { mutableStateOf<UpdatePlan?>(null) }

    // The panel's own loop, on screen only: it reads only while something it started is in
    // flight (an update, a fetch, a preflight), and stops the moment you leave or background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (viewModel.hermesUpdate.needsPoll()) viewModel.hermesUpdate.pollTick()
                delay(POLL_MS)
            }
        }
    }

    val b = board
    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(b?.error)
        if (b == null) {
            PanelLoading()
            return@Column
        }
        val plugin = b.plugin
        val check = b.check
        val plan = UpdatePlanner.plan(plugin, check)
        val behind = UpdatePlanner.behind(plugin, check)
        val now = System.currentTimeMillis()
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "here") {
                KeryxCard {
                    Text(
                        "Hermes " + (check?.currentVersion?.takeIf { it.isNotBlank() } ?: plugin?.version.orEmpty())
                            .ifBlank { "(version unknown)" },
                        fontSize = KeryxType.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                    plugin?.takeIf { it.head.isNotBlank() }?.let { p ->
                        Text(
                            "at ${p.head}" + (p.headBranch.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "") +
                                (if (p.ahead > 0) " · ${p.ahead} local commit${if (p.ahead == 1) "" else "s"} ahead" else ""),
                            fontSize = KeryxType.micro,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    check?.installMethod?.takeIf { it.isNotBlank() }?.let {
                        Text("installed via $it", fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item(key = "upstream") {
                KeryxCard {
                    Text(
                        UpdatePlanner.behindLine(behind),
                        fontSize = KeryxType.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = when (behind?.count) {
                            0 -> KeryxStatus.good
                            null, -1 -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> KeryxStatus.warn
                        },
                    )
                    if (behind?.fromLocalRefs == true) {
                        Text(
                            "Counted against the host's own copy of upstream" +
                                (UpdateText.ago(behind.checkedAt, now)?.let { ", fetched $it" } ?: ""),
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    plugin?.checkError?.takeIf { it.isNotBlank() }?.let {
                        Text("Last fetch failed: $it", fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(enabled = !b.checking, onClick = { viewModel.hermesUpdate.checkNow() }) {
                        Text(if (b.checking) "Checking…" else "Check for updates", fontSize = KeryxType.caption)
                    }
                }
            }

            val commits = check?.commits.orEmpty()
            if (commits.isNotEmpty()) {
                item(key = "commits-label") { SectionLabel("What would change") }
                commits.forEach { c ->
                    item(key = "commit-" + c.sha) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    c.sha.take(7),
                                    fontSize = KeryxType.micro,
                                    fontFamily = FontFamily.Monospace,
                                    color = keryxAccentInk(),
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(c.summary, fontSize = KeryxType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                listOfNotNull(
                                    c.author.takeIf { it.isNotBlank() },
                                    c.atEpochS.takeIf { it > 0 }?.let { UpdateText.agoMs(it * 1000, now) },
                                ).joinToString(" · "),
                                fontSize = KeryxType.micro,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp),
                            )
                        }
                    }
                }
                val more = (check?.behind ?: 0) - commits.size
                if (more > 0) item(key = "commits-more") {
                    Text("and $more more", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (plugin != null && plugin.probeConfigured) {
                item(key = "probe") { PreflightCard(plugin, probing = b.probing, onRun = { viewModel.hermesUpdate.runProbe() }) }
            }

            val rv = runView
            item(key = "action") {
                when {
                    rv != null -> RunCard(rv, onDismiss = { viewModel.hermesUpdate.dismissRun() })
                    plan == null -> Text(
                        UpdatePlanner.noPlanReason(plugin, check),
                        fontSize = KeryxType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UpdatePlanner.worthOffering(behind) -> Column {
                        OutlinedButton(onClick = { confirm = plan }) {
                            Text("Update now…", fontSize = KeryxType.body, color = KeryxStatus.warn)
                        }
                        Text(
                            "Runs `${plan.command}` on the gateway host" +
                                if (plan.operatorWrapper) " — the operator's own update command." else ".",
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Unit
                }
            }

            val receipt = b.receipt
            val showingRunReceipt = (rv?.phase as? UpdatePhase.Finished)?.receipt != null
            if (!b.receiptMissing && !showingRunReceipt) {
                item(key = "last") {
                    if (receipt == null) {
                        Text(
                            "No update has run since this Hermes started keeping receipts.",
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column {
                            SectionLabel("Last update")
                            ReceiptCard(receipt, now)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { p ->
        val n = landingCount(board)
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirm = null },
            title = { Text("Update Hermes now?", fontSize = KeryxType.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Keryx asks the gateway to run `${p.command}`" +
                            if (p.operatorWrapper) ", the operator's own update command from config.yaml." else ".",
                        fontSize = KeryxType.body,
                    )
                    Text(
                        "This restarts the agent's gateway. A turn in progress is cut off, and Keryx loses " +
                            "its connection for a minute or two while Hermes reinstalls and comes back.",
                        fontSize = KeryxType.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                    n?.let { Text(if (it == 1) "1 commit will land." else "$it commits will land.", fontSize = KeryxType.body) }
                    val probe = board?.plugin
                    if (p.operatorWrapper && probe != null && probe.probeConfigured && probe.probeExit != 0) {
                        Text(
                            "The preflight (${probe.probeLabel.ifBlank { "preflight" }}) hasn't passed since the gateway started.",
                            fontSize = KeryxType.caption,
                            color = KeryxStatus.warn,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; viewModel.hermesUpdate.start(p) }) {
                    Text("Update and restart", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Not now") } },
        )
    }
}

private const val POLL_MS = 3_000L

/** A positive known count for the confirm's "N commits will land", else null. */
private fun landingCount(b: HermesUpdateDelegate.Board?): Int? =
    UpdatePlanner.behind(b?.plugin, b?.check)?.count?.takeIf { it > 0 }

@Composable
private fun PreflightCard(plugin: PluginUpdateStatus, probing: Boolean, onRun: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val exit = plugin.probeExit
    val running = probing || plugin.probeRunning
    KeryxCard(tint = when { running || exit == null -> null; exit == 0 -> KeryxStatus.good; else -> KeryxStatus.bad }) {
        Text("Preflight · ${plugin.probeLabel.ifBlank { "preflight" }}", fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold)
        Text(
            when {
                running -> "Running…"
                // "Not run" and "passed" must never look alike: an update gated on a preflight
                // nobody ran is an ungated update.
                exit == null -> "Not run since the gateway started"
                exit == 0 -> "Passed" + (UpdateText.ago(plugin.probeAt, System.currentTimeMillis())?.let { " $it" } ?: "")
                else -> "Failed (exit $exit)" + (UpdateText.ago(plugin.probeAt, System.currentTimeMillis())?.let { " $it" } ?: "")
            },
            fontSize = KeryxType.caption,
            color = when {
                running || exit == null -> MaterialTheme.colorScheme.onSurfaceVariant
                exit == 0 -> KeryxStatus.good
                else -> KeryxStatus.bad
            },
        )
        if (plugin.probeOutput.isNotBlank()) {
            Text(
                plugin.probeOutput,
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Less" else "All output", fontSize = KeryxType.caption)
            }
        }
        OutlinedButton(enabled = !running, onClick = onRun) {
            Text(if (running) "Running…" else "Run preflight", fontSize = KeryxType.caption)
        }
    }
}

@Composable
private fun RunCard(rv: HermesUpdateDelegate.RunView, onDismiss: () -> Unit) {
    val phase = rv.phase
    val tint: Color? = when (phase) {
        is UpdatePhase.Finished -> outcomeColor(phase.outcome)
        UpdatePhase.Silent -> KeryxStatus.warn
        else -> null
    }
    KeryxCard(tint = tint, breathing = phase is UpdatePhase.Working) {
        when (phase) {
            is UpdatePhase.Working -> {
                Text(
                    if (phase.restarting) "The gateway is restarting" else "Updating with `${rv.run.plan.command}`…",
                    fontSize = KeryxType.body,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (phase.restarting) "That is the update at work. Keryx asks again every few seconds."
                    else "Started ${UpdateText.agoMs(rv.run.startedAtMs, System.currentTimeMillis())}.",
                    fontSize = KeryxType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LogTail(rv.lines)
            }
            is UpdatePhase.Finished -> {
                Text(UpdateText.outcomeTitle(phase.outcome), fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold,
                    color = outcomeColor(phase.outcome))
                Text(phase.detail, fontSize = KeryxType.caption)
                phase.receipt?.let { ReceiptLines(it) }
                LogTail(rv.lines)
                TextButton(onClick = onDismiss) { Text("Done", fontSize = KeryxType.caption) }
            }
            UpdatePhase.Silent -> {
                Text("No word after 20 minutes", fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold, color = KeryxStatus.warn)
                Text(
                    "The update may still be running, or it stopped without writing a receipt. " +
                        "The host's own log has the answer (~/.hermes/logs/update.log, or keryx-update.log for " +
                        "the operator's command).",
                    fontSize = KeryxType.caption,
                )
                LogTail(rv.lines)
                TextButton(onClick = onDismiss) { Text("Dismiss", fontSize = KeryxType.caption) }
            }
        }
    }
}

@Composable
private fun ReceiptCard(r: UpdateReceipt, now: Long) {
    val outcome = UpdateText.receiptOutcome(r.outcome)
    KeryxCard(tint = outcome?.let { outcomeColor(it) }) {
        Text(
            (outcome?.let { UpdateText.outcomeTitle(it) } ?: r.outcome.replaceFirstChar { it.uppercase() }) +
                ((r.finishedAt ?: r.startedAt).let { UpdateText.ago(it, now) }?.let { " · $it" } ?: ""),
            fontSize = KeryxType.body,
            fontWeight = FontWeight.SemiBold,
            color = outcome?.let { outcomeColor(it) } ?: MaterialTheme.colorScheme.onSurface,
        )
        if (r.preSha.isNotBlank() || r.postSha.isNotBlank()) {
            Text(
                r.preSha.take(8) + " → " + r.postSha.take(8).ifBlank { "?" } +
                    (r.postVersion.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""),
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ReceiptLines(r)
    }
}

/** What the receipt says went wrong or was left out — the honest small print. */
@Composable
private fun ReceiptLines(r: UpdateReceipt) {
    (r.failedSteps.map { "✕ $it" } + r.restartProblems.map { "✕ $it" } + r.skips.map { "– skipped $it" })
        .forEach { line ->
            Text(
                line,
                fontSize = KeryxType.micro,
                color = if (line.startsWith("✕")) KeryxStatus.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
}

@Composable
private fun LogTail(lines: List<String>) {
    val tail = lines.filter { it.isNotBlank() }.takeLast(12)
    if (tail.isEmpty()) return
    Text(
        tail.joinToString("\n"),
        fontSize = KeryxType.micro,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun outcomeColor(o: UpdateOutcome): Color = when (o) {
    UpdateOutcome.SUCCESS -> KeryxStatus.good
    UpdateOutcome.PARTIAL, UpdateOutcome.ROLLED_BACK, UpdateOutcome.UNVERIFIED -> KeryxStatus.warn
    UpdateOutcome.FAILED, UpdateOutcome.REFUSED -> KeryxStatus.bad
}

/** The spoke row's second line. */
@Composable
internal fun updateSubtitle(viewModel: ChatViewModel): String {
    val board by viewModel.hermesUpdate.board.collectAsState()
    val run by viewModel.hermesUpdate.run.collectAsState()
    val p = run?.phase
    if (p is UpdatePhase.Working) return if (p.restarting) "Restarting…" else "Updating…"
    val b = board ?: return "Is Hermes current?"
    val version = b.check?.currentVersion?.takeIf { it.isNotBlank() } ?: b.plugin?.version.orEmpty()
    val behind = UpdatePlanner.behind(b.plugin, b.check)
    val count = when (val n = behind?.count) {
        null -> null
        -1 -> "behind, count unknown"
        0 -> "up to date"
        else -> "$n behind"
    }
    return listOfNotNull(version.takeIf { it.isNotBlank() }, count).joinToString(" · ").ifBlank { "Is Hermes current?" }
}
