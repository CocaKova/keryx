package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.data.remote.HermesStreamClient.HubSession
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.CronHumanize
import chat.keryx.core.model.CronJobCard
import chat.keryx.core.model.CronRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * RUNS — everything the agent does on a schedule, as a place of its own (was the hub's 4th
 * tab; three taps deep it went unread — Jonny: "the crons are hard to get to and digest").
 *
 * The layout is Talaria's CronSpace, the second harvest from that donor: an arrivals rail
 * first — what landed since you last looked, each report wearing its own headline so it can
 * be READ here rather than merely counted — then one card per job. You don't converse with
 * the Daily Brief; on the direct door a run opens as a real room (the full renderer), on the
 * Matrix door in the transcript reader. Jobs (hub) still manages the schedules; this reads
 * their work.
 */
@Composable
fun RunsSpace(
    viewModel: ChatViewModel,
    /** (session id, title) — a cron run lives outside every roster, so the title travels. */
    onOpenSession: (String, String) -> Unit,
    onClose: () -> Unit,
) {
    val panel by viewModel.hub.cron.collectAsState()
    var openSession by remember { mutableStateOf<HubSession?>(null) }

    // The tab's poll cadence, kept: runs land on the gateway's clock, not the user's.
    LaunchedEffect(Unit) {
        while (isActive) {
            viewModel.hub.refreshCron()
            delay(10_000)
        }
    }

    val board = panel.data
    fun openRun(run: CronRun) {
        viewModel.hub.cronMarkSeen(run.id)
        if (viewModel.transportIsDirect) {
            onOpenSession(run.id, run.title)
        } else {
            openSession = board?.runsById?.get(run.id)
        }
    }

    openSession?.let { session ->
        SessionTranscript(
            session = session,
            viewModel = viewModel,
            onBack = { openSession = null },
            onForked = { fork ->
                if (viewModel.transportIsDirect) onOpenSession(fork.id, fork.title ?: fork.model)
                else openSession = fork
            },
        )
        return
    }

    KeryxSpace(
        title = "Runs",
        onClose = onClose,
        standalone = false,
        actions = {
            // Only where there is something to clear: a permanent "mark all read" on a quiet
            // screen is a button that does nothing, which teaches people not to press buttons.
            val unread = board?.unread
            if (unread != null && unread.any) {
                TextButton(onClick = { viewModel.hub.cronMarkAllSeen() }) {
                    Text("Mark ${unread.total} read", fontSize = 12.sp)
                }
            }
        },
    ) {
        PanelErrorLine(panel.error)
        when {
            board == null -> PanelLoading()
            board.cards.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No scheduled work yet.\nThe agent creates jobs with the cronjob tool.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // What came in while you weren't looking, before anything else on the screen.
                // The cards answer "what does this gateway do"; this answers "what do I have
                // to read", which is a different question and the one you arrive with.
                if (board.unread.any) {
                    item(key = "new-rail") {
                        NewArrivals(
                            board = board,
                            viewModel = viewModel,
                            onOpenRun = ::openRun,
                        )
                    }
                }
                items(board.cards, key = { it.name }) { card ->
                    RunCard(
                        card = card,
                        job = board.jobsByName[card.name],
                        unreadCount = board.unread.countFor(card.name),
                        isNew = { board.unread.isNew(it) },
                        viewModel = viewModel,
                        onOpenRun = ::openRun,
                    )
                }
            }
        }
    }
}

/** How many arrivals the rail reads out loud before it stops counting. Past a dozen unread
 *  reports the honest summary is "and N more", not a scroll. */
private const val NEW_RAIL_MAX = 12

/**
 * The arrivals rail — unread runs newest-first across every job, each with its report's own
 * headline. Deliberately not a badge-and-nothing-else: a number says there is homework, a
 * headline says whether it's homework you care about. Rows carry their job's identity tint,
 * so the rail and the cards below name the same things the same way.
 */
@Composable
private fun NewArrivals(
    board: chat.keryx.app.presentation.HubDelegate.CronBoard,
    viewModel: ChatViewModel,
    onOpenRun: (CronRun) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    // The run's job name gives the row its tint and its address.
    val jobOf = remember(board.cards) {
        board.cards.flatMap { c -> c.runs.map { it.id to c.name } }.toMap()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(accent.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(KeryxRadius.card))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        KeryxSectionHeader("New since you looked", count = board.unread.total, color = accent)
        Spacer(Modifier.height(2.dp))
        board.unread.runs.take(NEW_RAIL_MAX).forEach { run ->
            val jobName = jobOf[run.id] ?: run.title.substringBefore(" · ", run.title)
            val tint = RUN_TINTS[CronHumanize.tintIndex(jobName, RUN_TINTS.size)]
            // Headline per row, once per run id — the delegate caches, so a settled rail
            // costs nothing on re-composition or poll.
            val digest by produceState<chat.keryx.core.model.CronDigest?>(null, run.id) {
                value = viewModel.hub.cronDigest(run.id)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(KeryxRadius.field))
                    .clickable { onOpenRun(run) }
                    .height(IntrinsicSize.Min),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.8f)))
                Column(Modifier.padding(start = 9.dp, end = 2.dp, top = 1.dp, bottom = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            jobName,
                            color = onSurface.copy(alpha = 0.9f),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            relativeWhen(run.timestamp),
                            color = quiet.copy(alpha = 0.8f),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    val d = digest
                    Text(
                        d?.title ?: "reading…",
                        color = onSurface.copy(alpha = if (d?.title != null) 0.75f else 0.35f),
                        fontSize = 11.5.sp, lineHeight = 15.5.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        if (board.unread.total > NEW_RAIL_MAX) {
            Text(
                "and ${board.unread.total - NEW_RAIL_MAX} more below",
                color = quiet.copy(alpha = 0.7f),
                fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp, start = 12.dp),
            )
        }
    }
}

/** Identity hues — a human finds "the gold one" faster than a name in a column of names.
 *  Fixed mid-lightness values that read on both themes; assignment is a stable hash of the
 *  job name ([CronHumanize.tintIndex]), so a job keeps its color for life. */
private val RUN_TINTS = listOf(
    Color(0xFFF0B429), // gold
    Color(0xFF6FA8DC), // sky
    // Two of Talaria's hues are shifted here: its emerald and amber are byte-identical to
    // KeryxStatus's good/warn literals, and identity must never read as verdict (the
    // PaperContrastTest guard bans those exact values outside the palette).
    Color(0xFF58A06E), // emerald
    Color(0xFFA78BFA), // violet
    Color(0xFF4DB6AC), // teal
    Color(0xFFDFA032), // amber
    Color(0xFFC97BA4), // rose
    Color(0xFF8FA3AD), // slate
)

@Composable
private fun RunCard(
    card: CronJobCard,
    job: HubJob?,
    unreadCount: Int,
    isNew: (String) -> Boolean,
    viewModel: ChatViewModel,
    onOpenRun: (CronRun) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val good = KeryxStatus.good
    val bad = KeryxStatus.bad
    var open by rememberSaveable("runs-${card.name}") { mutableStateOf(false) }
    val tint = RUN_TINTS[CronHumanize.tintIndex(card.name, RUN_TINTS.size)]

    // The newest run's own headline — fetched once per run id (the delegate caches), so a
    // settled card costs nothing on re-composition or poll.
    val digest by produceState<chat.keryx.core.model.CronDigest?>(null, card.latest?.id) {
        value = card.latest?.let { viewModel.hub.cronDigest(it.id) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(onSurface.copy(alpha = 0.04f))
            .clickable { open = !open }
            .animateContentSize()
            .height(IntrinsicSize.Min),
    ) {
        // The job's identity, readable from across the room — same bar the rail rows wear.
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.7f)))
        Column(Modifier.padding(start = 11.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.name,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$unreadCount new",
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.6f), fontSize = 11.sp)
            }

            // The meta line: schedule in words, distance to the next run, last verdict.
            val meta = buildList {
                job?.let { j ->
                    CronHumanize.schedule(j.scheduleDisplay).takeIf { it.isNotBlank() }?.let { add(it) }
                    j.nextRunAt?.let { CronHumanize.nextIn(it, System.currentTimeMillis()) }?.let { add(it) }
                    if (!j.enabled) add("paused")
                }
                if (!card.scheduled) add("job no longer scheduled")
                if (card.neverRun) add("no runs yet")
            }
            if (meta.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    val failed = job?.lastStatus?.contains("error", ignoreCase = true) == true ||
                        job?.lastError?.isNotBlank() == true
                    if (job != null && !card.neverRun) {
                        Text(
                            if (failed) "✕" else "✓",
                            fontSize = 9.sp,
                            color = if (failed) bad else good.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        meta.joinToString(" · "),
                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                        color = quiet.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The newest run's own words — title and lead, never the prompt that produced it.
            val d = digest
            if (!card.neverRun && d?.title != null) {
                Text(
                    d.title!!,
                    fontSize = 12.5.sp, color = onSurface.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
                d.lead?.let {
                    Text(
                        it,
                        fontSize = 11.5.sp, color = quiet.copy(alpha = 0.85f), lineHeight = 15.sp,
                        maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }

            if (open && card.runs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                card.runs.take(20).forEach { run ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenRun(run) }
                            .padding(horizontal = 4.dp, vertical = 5.dp),
                    ) {
                        // The unread dot dies the moment the run is opened — the ledger, visible.
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isNew(run.id)) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // The gateway titles runs "<job> · <when>"; the job half is the card.
                            run.title.substringAfter(" · ", run.title),
                            fontSize = 12.sp, color = onSurface.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            relativeWhen(run.timestamp),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = quiet.copy(alpha = 0.6f),
                        )
                    }
                }
                if (card.runs.size > 20) {
                    Text(
                        "${card.runs.size - 20} older runs in Workshop ▸ Sessions",
                        fontSize = 10.5.sp, color = quiet.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** "4h ago" — the rail and run rows answer "how stale" faster than a clock time does. */
private fun relativeWhen(ts: Long): String {
    val mins = ((System.currentTimeMillis() - ts) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}
