package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
 * The layout is Talaria's CronSpace, the second harvest from that donor: a shelf of the runs
 * you KEPT first (pinned on the gateway — the report worth coming back to has an address), then
 * an arrivals rail — what landed since you last looked, each report wearing its own headline
 * so it can be READ here rather than merely counted — then one card per job. You don't
 * converse with the Daily Brief; on the direct door a run opens as a real room (the full
 * renderer), on the Matrix door in the transcript reader. Jobs (hub) still manages the
 * schedules; this reads their work.
 *
 * Every run row here answers a long press with the same small menu: keep it / release it, and
 * read it when it's new. The pin is the gateway's own keep flag (Desktop parity), so a report
 * pinned on the phone is pinned everywhere and exempt from the auto-archive sweep.
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
    val verbs = RunVerbs(
        open = ::openRun,
        setPinned = { run, pinned -> viewModel.hub.cronSetPinned(run.id, pinned) },
        markRead = { run -> viewModel.hub.cronMarkSeen(run.id) },
    )

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
                // What you chose to keep, above what merely arrived: a pin is a decision the
                // user already made, and the screen should honour it before asking for another.
                if (board.pinned.isNotEmpty()) {
                    item(key = "pinned-shelf") {
                        PinnedShelf(board = board, viewModel = viewModel, verbs = verbs)
                    }
                }
                // What came in while you weren't looking, before anything else on the screen.
                // The cards answer "what does this gateway do"; this answers "what do I have
                // to read", which is a different question and the one you arrive with.
                if (board.unread.any) {
                    item(key = "new-rail") {
                        NewArrivals(board = board, viewModel = viewModel, verbs = verbs)
                    }
                }
                items(board.cards, key = { it.name }) { card ->
                    RunCard(
                        card = card,
                        job = board.jobsByName[card.name],
                        unreadCount = board.unread.countFor(card.name),
                        isNew = { board.unread.isNew(it) },
                        viewModel = viewModel,
                        verbs = verbs,
                    )
                }
            }
        }
    }
}

/** What a run row can do — one bundle so every row (shelf, rail, card) offers the same verbs. */
private class RunVerbs(
    val open: (CronRun) -> Unit,
    val setPinned: (CronRun, Boolean) -> Unit,
    val markRead: (CronRun) -> Unit,
)

/** How many arrivals the rail reads out loud before it stops counting. Past a dozen unread
 *  reports the honest summary is "and N more", not a scroll. */
private const val NEW_RAIL_MAX = 12

/** The shelf shows this many kept runs before folding the rest behind "show all" — a shelf
 *  that grows without bound stops being a shelf and becomes the list it sits above. */
private const val PINNED_SHOWN = 5

/**
 * The kept shelf — pinned runs newest-first across every job, each with its report's own
 * headline and lead. Calmer than the arrivals rail on purpose: news is tinted with the accent
 * and asks to be read; the shelf is ink on paper, because what's on it has been read already
 * and is here to be found again. Rows wear their job's identity bar and a filled pin. Tap
 * opens; long-press releases the pin.
 */
@Composable
private fun PinnedShelf(
    board: chat.keryx.app.presentation.HubDelegate.CronBoard,
    viewModel: ChatViewModel,
    verbs: RunVerbs,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val jobOf = remember(board.cards) { jobIndex(board.cards) }
    var showAll by rememberSaveable("runs-pinned-all") { mutableStateOf(false) }
    var folded by rememberSaveable("runs-pinned-folded") { mutableStateOf(false) }
    val shown = if (showAll || folded) board.pinned else board.pinned.take(PINNED_SHOWN)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(onSurface.copy(alpha = 0.035f))
            .border(1.dp, onSurface.copy(alpha = 0.14f), RoundedCornerShape(KeryxRadius.card))
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .clickable { folded = !folded }
                .padding(vertical = 2.dp),
        ) {
            Icon(
                KeryxGlyphs.PinFilled,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(7.dp))
            KeryxSectionHeader("Pinned", count = board.pinned.size, color = onSurface.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            Text(if (folded) "▸" else "▾", color = quiet.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        if (!folded) {
            Spacer(Modifier.height(2.dp))
            shown.forEach { run ->
                val jobName = jobOf[run.id] ?: run.title.substringBefore(" · ", run.title)
                RunHeadlineRow(
                    run = run,
                    jobName = jobName,
                    // The shelf keeps things for days: a date reads better than "9d ago".
                    whenText = keptWhen(run.timestamp),
                    titleAlpha = 0.85f,
                    leadLines = 2,
                    unread = board.unread.isNew(run.id),
                    trailing = {
                        Icon(
                            KeryxGlyphs.PinFilled,
                            contentDescription = "Pinned",
                            tint = onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(11.dp),
                        )
                    },
                    viewModel = viewModel,
                    verbs = verbs,
                )
            }
            if (board.pinned.size > PINNED_SHOWN) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        if (showAll) "Show fewer" else "Show all ${board.pinned.size}",
                        fontSize = 11.sp, color = quiet,
                    )
                }
            }
        }
    }
}

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
    verbs: RunVerbs,
) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    // The run's job name gives the row its tint and its address.
    val jobOf = remember(board.cards) { jobIndex(board.cards) }
    val prevOf = remember(board.cards) { previousIndex(board.cards) }
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
            RunHeadlineRow(
                run = run,
                jobName = jobName,
                whenText = relativeWhen(run.timestamp),
                titleAlpha = 0.75f,
                leadLines = 0,
                unread = true,
                previous = prevOf[run.id],
                trailing = if (run.pinned) ({
                    Icon(
                        KeryxGlyphs.PinFilled,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.size(11.dp),
                    )
                }) else null,
                viewModel = viewModel,
                verbs = verbs,
            )
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

/** run id → job name, so a rail or shelf row can wear its job's tint and address. */
private fun jobIndex(cards: List<CronJobCard>): Map<String, String> =
    cards.flatMap { c -> c.runs.map { it.id to c.name } }.toMap()

/** run id → the run before it in the same job (newest-first lists), for a row's delta badge. */
private fun previousIndex(cards: List<CronJobCard>): Map<String, CronRun> =
    cards.flatMap { c -> c.runs.zipWithNext().map { (run, prev) -> run.id to prev } }.toMap()

/**
 * One headline row — the shelf's and the rail's shared shape: identity bar, job name, when,
 * then the report's own title (and lead, where the row has room for it). Tap opens; a long
 * press opens the run menu. The pin state the menu shows is the run's own flag, so the shelf
 * and the rail can never disagree about a run they both list.
 */
@Composable
private fun RunHeadlineRow(
    run: CronRun,
    jobName: String,
    whenText: String,
    titleAlpha: Float,
    leadLines: Int,
    unread: Boolean,
    trailing: (@Composable () -> Unit)?,
    viewModel: ChatViewModel,
    verbs: RunVerbs,
    /** The run before this one in its job, when the row should say what changed since it. */
    previous: CronRun? = null,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalKeryxHaptics.current
    val tint = RUN_TINTS[CronHumanize.tintIndex(jobName, RUN_TINTS.size)]
    var menuOpen by remember { mutableStateOf(false) }
    // Headline per row, once per run id — the delegate caches, so a settled row costs
    // nothing on re-composition or poll.
    val digest by produceState<chat.keryx.core.model.CronDigest?>(null, run.id) {
        value = viewModel.hub.cronDigest(run.id)
    }
    // "+3" beside the clock: the arrival's news in one glyph, before its headline is read.
    val delta by produceState<chat.keryx.core.model.CronDelta?>(null, run.id, previous?.id) {
        value = previous?.let { viewModel.hub.cronDelta(run.id, it.id) }
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(KeryxRadius.field))
                .combinedClickable(
                    onClick = { verbs.open(run) },
                    onLongClick = { haptics.press(); menuOpen = true },
                )
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
                    delta?.badge?.let { badge ->
                        Text(
                            badge,
                            color = accent,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accent.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        whenText,
                        color = quiet.copy(alpha = 0.8f),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    )
                    if (trailing != null) {
                        Spacer(Modifier.width(6.dp))
                        trailing()
                    }
                }
                val d = digest
                Text(
                    d?.title ?: "reading…",
                    color = onSurface.copy(alpha = if (d?.title != null) titleAlpha else 0.35f),
                    fontSize = 11.5.sp, lineHeight = 15.5.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
                if (leadLines > 0) {
                    d?.lead?.let {
                        Text(
                            it,
                            color = quiet.copy(alpha = 0.85f),
                            fontSize = 11.sp, lineHeight = 14.5.sp,
                            maxLines = leadLines, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
        RunMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            run = run,
            unread = unread,
            verbs = verbs,
        )
    }
}

/**
 * The run menu — the same three verbs wherever a run is a row. "Pin" is worded as what it
 * does on the gateway (keep), because that is the promise: a kept run survives the sweep.
 */
@Composable
private fun RunMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    run: CronRun,
    unread: Boolean,
    verbs: RunVerbs,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (run.pinned) "Unpin" else "Pin — keep this run") },
            leadingIcon = {
                Icon(
                    if (run.pinned) KeryxGlyphs.PinFilled else KeryxGlyphs.Pin,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            onClick = { onDismiss(); verbs.setPinned(run, !run.pinned) },
        )
        if (unread) {
            DropdownMenuItem(
                text = { Text("Mark read") },
                leadingIcon = {
                    Icon(KeryxGlyphs.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = { onDismiss(); verbs.markRead(run) },
            )
        }
        DropdownMenuItem(
            text = { Text("Open") },
            leadingIcon = {
                Icon(KeryxGlyphs.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            onClick = { onDismiss(); verbs.open(run) },
        )
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
    verbs: RunVerbs,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val good = KeryxStatus.good
    val bad = KeryxStatus.bad
    val haptics = LocalKeryxHaptics.current
    var open by rememberSaveable("runs-${card.name}") { mutableStateOf(false) }
    var jobMenu by remember { mutableStateOf(false) }
    val pinnedJobs by viewModel.hub.pinnedJobs.collectAsState()
    val jobPinned = card.name in pinnedJobs
    val tint = RUN_TINTS[CronHumanize.tintIndex(card.name, RUN_TINTS.size)]

    // The newest run's own headline — fetched once per run id (the delegate caches), so a
    // settled card costs nothing on re-composition or poll.
    val digest by produceState<chat.keryx.core.model.CronDigest?>(null, card.latest?.id) {
        value = card.latest?.let { viewModel.hub.cronDigest(it.id) }
    }
    // The diff feed: what the newest report says that the one before it didn't. Only when
    // there IS a run before it — a first report has nothing to be measured against.
    val previous = card.runs.getOrNull(1)
    val delta by produceState<chat.keryx.core.model.CronDelta?>(null, card.latest?.id, previous?.id) {
        val latest = card.latest
        value = if (latest != null && previous != null) viewModel.hub.cronDelta(latest.id, previous.id) else null
    }

    Box {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(onSurface.copy(alpha = 0.04f))
            // Tap opens the card; a long press is about the JOB — pin it to the top of the
            // session list, where its newest output then waits every morning.
            .combinedClickable(
                onClick = { open = !open },
                onLongClick = { haptics.press(); jobMenu = true },
            )
            .animateContentSize()
            .height(IntrinsicSize.Min),
    ) {
        // The job's identity, readable from across the room — same bar the rail rows wear.
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.7f)))
        Column(Modifier.padding(start = 11.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (jobPinned) {
                    // The job sits at the top of the sessions: say so where its name is.
                    Icon(
                        KeryxGlyphs.PinFilled,
                        contentDescription = "Pinned to sessions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
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
                // How many of this job's runs are kept — a quiet count, not a badge: a pin
                // is a decision already made, and it has nothing to shout about.
                if (card.pinnedCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        KeryxGlyphs.PinFilled,
                        contentDescription = "${card.pinnedCount} pinned",
                        tint = quiet.copy(alpha = 0.7f),
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${card.pinnedCount}",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = quiet.copy(alpha = 0.7f),
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

            // Since last run — the line the forty-first report is actually read for. Quiet
            // when nothing moved; accent-marked when something did. Open the card and the new
            // and changed lines themselves are listed, in the report's own words.
            delta?.let { d ->
                val accent = MaterialTheme.colorScheme.primary
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (d.same) quiet.copy(alpha = 0.35f) else accent),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "since last run · ${d.summary}",
                        fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                        color = if (d.same) quiet.copy(alpha = 0.7f) else onSurface.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (open && !d.same) {
                    Spacer(Modifier.height(4.dp))
                    DeltaLines(prefix = "+", lines = d.added, color = accent, max = 6)
                    DeltaLines(prefix = "~", lines = d.updated, color = onSurface.copy(alpha = 0.7f), max = 4)
                    DeltaLines(prefix = "−", lines = d.removed, color = quiet.copy(alpha = 0.7f), max = 3)
                }
            }

            if (open && card.runs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                card.runs.take(20).forEach { run ->
                    var menuOpen by remember(run.id) { mutableStateOf(false) }
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { verbs.open(run) },
                                    onLongClick = { haptics.press(); menuOpen = true },
                                )
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
                            if (run.pinned) {
                                Icon(
                                    KeryxGlyphs.PinFilled,
                                    contentDescription = "Pinned",
                                    tint = onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.size(10.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                relativeWhen(run.timestamp),
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                color = quiet.copy(alpha = 0.6f),
                            )
                        }
                        RunMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            run = run,
                            unread = isNew(run.id),
                            verbs = verbs,
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
    DropdownMenu(expanded = jobMenu, onDismissRequest = { jobMenu = false }) {
        DropdownMenuItem(
            text = { Text(if (jobPinned) "Unpin from sessions" else "Pin to top of sessions") },
            leadingIcon = {
                Icon(
                    if (jobPinned) KeryxGlyphs.PinFilled else KeryxGlyphs.Pin,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            onClick = { jobMenu = false; viewModel.hub.cronSetJobPinned(card.name, !jobPinned) },
        )
        card.latest?.let { latest ->
            DropdownMenuItem(
                text = { Text("Open newest run") },
                leadingIcon = {
                    Icon(KeryxGlyphs.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = { jobMenu = false; verbs.open(latest) },
            )
        }
    }
    } // job-menu anchor
}

/**
 * One kind of change, listed: a prefix bar in the kind's colour, then the lines in the
 * report's own words. Past [max] the rest is a count — the card is a card, not the diff.
 */
@Composable
private fun DeltaLines(prefix: String, lines: List<String>, color: Color, max: Int) {
    if (lines.isEmpty()) return
    val onSurface = MaterialTheme.colorScheme.onSurface
    lines.take(max).forEach { line ->
        Row(modifier = Modifier.padding(start = 2.dp, top = 2.dp)) {
            Text(
                prefix,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.width(12.dp),
            )
            Text(
                line,
                fontSize = 11.5.sp, lineHeight = 15.sp,
                color = onSurface.copy(alpha = 0.8f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (lines.size > max) {
        Text(
            "$prefix ${lines.size - max} more",
            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
            color = color.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
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

/** The shelf's clock: today's kept run still reads as "4h ago", but a run kept for weeks is
 *  "Aug 12" — a date you can cite, not a countdown you have to convert. Past a year, the year. */
private fun keptWhen(ts: Long): String {
    val ageDays = (System.currentTimeMillis() - ts) / 86_400_000L
    if (ageDays < 1) return relativeWhen(ts)
    val pattern = if (ageDays < 365) "MMM d" else "MMM d, yyyy"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ts))
}
