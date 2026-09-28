package chat.keryx.app.presentation.ui.components

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.data.remote.HermesStreamClient.HubSession
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.CronHumanize
import chat.keryx.core.model.CronJobCard
import chat.keryx.core.model.CronRun
import chat.keryx.core.model.CronFilter
import chat.keryx.core.model.CronHealth
import chat.keryx.core.model.CronJobFacts
import chat.keryx.core.model.CronRow
import chat.keryx.core.model.CronSection
import chat.keryx.core.model.CronTick
import chat.keryx.core.model.CronTriage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * RUNS — everything the agent does on a schedule, as a place of its own (was the hub's 4th
 * tab; three taps deep it went unread — Jonny: "the crons are hard to get to and digest").
 *
 * The layout is Talaria's CronSpace, the second harvest from that donor: a shelf of the runs
 * you KEPT first (pinned on the gateway — the report worth coming back to has an address), then
 * an arrivals rail — what landed since you last looked, each report wearing its own headline
 * so it can be READ here rather than merely counted — then the jobs. You don't converse with
 * the Daily Brief; on the direct door a run opens as a real room (the full renderer), on the
 * Matrix door in the transcript reader. Jobs (hub) still manages the schedules; this reads
 * their work.
 *
 * 2.14 — the page for A LOT of jobs (Jonny's gateway: 24, seven of them failing). One card per
 * job, newest first, sank the failures (a failed script dispatch writes no session, so those
 * jobs read as "no runs yet" and sorted last) and made every job cost a card's height. Now:
 *  - a control strip that stays put above the list — search (name, newest headline, schedule
 *    in words), filter chips with counts (only the ones that would match), Cards ↔ List;
 *  - the jobs triaged into Needs attention → Recent (24 h) → Quiet (folded, a count), by
 *    STATE rather than cadence — the failures span three cadences, and they belong together
 *    ([CronTriage]'s header has the numbers);
 *  - List mode: one ~52dp row per job — status dot, name, last/next run, the last eight
 *    outcomes as ticks — that opens in place to the same run list the card has;
 *  - the arrivals rail folds to one summary line past [CronTriage.RAIL_FOLD_AT] unread.
 * The shelf, the rail, the cards and every menu are unchanged underneath.
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
    // Only while the app is on screen: a Runs tab left open in the background polled
    // 150 rows every 10 s for as long as the process lived (2.13.10 battery audit).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                viewModel.hub.refreshCron()
                delay(10_000)
            }
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
        askFix = { card, job ->
            // A fresh chat rather than the open one: a repair is its own thread, and the brief
            // lands in the composer, not sent — you read what the agent is about to be told.
            viewModel.createSession("Fix cron: ${card.name}") { err ->
                if (err == null) {
                    viewModel.prefillComposer(fixBrief(job))
                    onClose()
                }
            }
        },
    )

    // --- The viewer's controls ---------------------------------------------------------------
    // Density is a standing preference (how THIS person likes to scan), so it outlives the
    // screen: its own tiny prefs file, like Senses keeps — a view knob, nothing the account or
    // the gateway ledger should carry. Unset = decided by the data: past a dozen jobs the list
    // is the better first impression; below it the cards' headlines earn their height.
    val context = LocalContext.current
    val viewPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(RUNS_VIEW_PREFS, Context.MODE_PRIVATE)
    }
    var densityPref by remember(viewPrefs) { mutableStateOf(viewPrefs.getString(KEY_DENSITY, null)) }
    // Search and filter are the visit's, not the viewer's: coming back to a filtered page you
    // don't remember filtering is how a failing job hides for a week.
    var query by rememberSaveable("runs-query") { mutableStateOf("") }
    var filterName by rememberSaveable("runs-filter") { mutableStateOf(CronFilter.ALL.name) }
    val filter = CronFilter.entries.firstOrNull { it.name == filterName } ?: CronFilter.ALL
    var railOpen by rememberSaveable("runs-rail-open") { mutableStateOf(false) }
    var attentionOpen by rememberSaveable("runs-sec-attention") { mutableStateOf(true) }
    var recentOpen by rememberSaveable("runs-sec-recent") { mutableStateOf(true) }
    var quietOpen by rememberSaveable("runs-sec-quiet") { mutableStateOf(false) }
    var scriptsOpen by rememberSaveable("runs-sec-scripts") { mutableStateOf(false) }

    // Triage once per board (a poll or a read-mark makes a new board); the clock is taken with
    // it, so "Recent" moves when the data does and never mid-scroll.
    val rows = remember(board) {
        board?.let { b ->
            CronTriage.rows(b.cards, factsOf(b.jobsByName), b.unread, System.currentTimeMillis())
        }.orEmpty()
    }
    val nowMs = remember(board) { System.currentTimeMillis() }
    val compact = densityPref?.let { it == DENSITY_LIST } ?: (rows.size > COMPACT_AUTO_AT)

    // Headlines for search, job name → newest report title. Filled by ONE sequential walk,
    // only while a query is typed — never a fetch per row. The delegate caches digests, so in
    // Cards mode (which already fetched every newest headline) the walk is free.
    val headlines = remember { mutableStateMapOf<String, String>() }
    val searching = query.isNotBlank()
    val latestIds = remember(board) { board?.cards?.map { it.latest?.id }.orEmpty() }
    LaunchedEffect(searching, latestIds) {
        if (!searching) return@LaunchedEffect
        board?.cards?.forEach { card ->
            val latest = card.latest ?: return@forEach
            viewModel.hub.cronDigest(latest.id)?.title?.let { headlines[card.name] = it }
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

    val counts = remember(rows) { CronTriage.counts(rows) }
    val railFolded = board != null && CronTriage.foldRail(board.unread.total)
    // Narrowing = looking for a job. The shelf and the rail answer other questions, so they
    // step aside; and Quiet opens, since a hit you can't see is not a hit.
    val narrowing = filter != CronFilter.ALL || searching

    KeryxSpace(
        title = "Runs",
        onClose = onClose,
        standalone = false,
        liveSlot = {
            // The page's answer before its first row: how many, how many broken.
            if (rows.isNotEmpty()) {
                val failing = counts[CronFilter.FAILING] ?: 0
                Text(
                    buildString {
                        // Short enough to survive beside "Mark N read": the chips carry the rest.
                        append("${rows.size} jobs")
                        if (failing > 0) append(" · $failing failing")
                    },
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    color = if (failing > 0) KeryxStatus.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            // Only where there is something to clear: a permanent "mark all read" on a quiet
            // screen is a button that does nothing, which teaches people not to press buttons.
            // Folded, the rail's own summary line carries it — one button, not two (unless
            // a filter has hidden that line).
            val unread = board?.unread
            if (unread != null && unread.any && (!railFolded || narrowing)) {
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
            else -> {
                // Outside the list, so it never scrolls away: with two hundred rows, the way
                // to narrow them has to be where your thumb already is.
                RunsControls(
                    query = query,
                    onQuery = { query = it },
                    filter = filter,
                    counts = counts,
                    offered = CronTriage.offeredFilters(counts, filter),
                    onFilter = { filterName = it.name },
                    compact = compact,
                    onCompact = { list ->
                        val v = if (list) DENSITY_LIST else DENSITY_CARDS
                        densityPref = v
                        viewPrefs.edit().putString(KEY_DENSITY, v).apply()
                    },
                )
                val visible = CronTriage.visible(rows, filter, query, headlines)
                val unreadIds = board.unread.ids
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                ) {
                    // What you chose to keep, above what merely arrived: a pin is a decision the
                    // user already made, and the screen should honour it before asking for another.
                    if (!narrowing && board.pinned.isNotEmpty()) {
                        item(key = "pinned-shelf") {
                            PinnedShelf(board = board, viewModel = viewModel, verbs = verbs)
                        }
                    }
                    // What came in while you weren't looking, before anything else on the screen.
                    // The jobs answer "what does this gateway do"; this answers "what do I have
                    // to read", which is a different question and the one you arrive with.
                    if (!narrowing && board.unread.any) {
                        if (railFolded) {
                            item(key = "new-summary") {
                                RailSummary(
                                    board = board,
                                    open = railOpen,
                                    onToggle = { railOpen = !railOpen },
                                    onMarkAll = { viewModel.hub.cronMarkAllSeen() },
                                )
                            }
                        }
                        if (!railFolded || railOpen) {
                            item(key = "new-rail") {
                                NewArrivals(board = board, viewModel = viewModel, verbs = verbs)
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "runs-none") {
                            Text(
                                if (searching) "No job matches “${query.trim()}”."
                                else "No ${filter.label.lowercase()} jobs.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.5.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                            )
                        }
                    }
                    for (block in CronTriage.sections(visible)) {
                        val section = block.section
                        val open = narrowing || when (section) {
                            CronSection.ATTENTION -> attentionOpen
                            CronSection.RECENT -> recentOpen
                            CronSection.SCRIPTS -> scriptsOpen
                            CronSection.QUIET -> quietOpen
                        }
                        item(key = "sec-${section.name}") {
                            SectionHead(
                                section = section,
                                count = block.rows.size,
                                open = open,
                                onToggle = if (narrowing) null else ({
                                    when (section) {
                                        CronSection.ATTENTION -> attentionOpen = !attentionOpen
                                        CronSection.RECENT -> recentOpen = !recentOpen
                                        CronSection.SCRIPTS -> scriptsOpen = !scriptsOpen
                                        CronSection.QUIET -> quietOpen = !quietOpen
                                    }
                                    Unit
                                }),
                            )
                        }
                        if (open) {
                            items(block.rows, key = { "job-${it.name}" }) { row ->
                                // A script has no reports to headline, so a card would be a
                                // tall box saying "no runs yet": scripts are always rows.
                                if (compact || section == CronSection.SCRIPTS) {
                                    CompactRunRow(
                                        row = row,
                                        unreadIds = unreadIds,
                                        nowMs = nowMs,
                                        viewModel = viewModel,
                                        verbs = verbs,
                                    )
                                } else {
                                    RunCard(
                                        card = row.card,
                                        job = board.jobsByName[row.name],
                                        unreadCount = row.unread,
                                        isNew = { board.unread.isNew(it) },
                                        viewModel = viewModel,
                                        verbs = verbs,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The view prefs file and its one knob. Values are words, so a future third density reads. */
private const val RUNS_VIEW_PREFS = "keryx_runs_view"
private const val KEY_DENSITY = "density"
private const val DENSITY_CARDS = "cards"
private const val DENSITY_LIST = "list"

/** With no saved choice, more jobs than this opens in List mode. */
private const val COMPACT_AUTO_AT = 12

/** The app's job rows → :core's facts. By name, like every other pairing on this page. */
private fun factsOf(jobs: Map<String, HubJob>): Map<String, CronJobFacts> =
    jobs.mapValues { (_, j) ->
        CronJobFacts(
            enabled = j.enabled,
            state = j.state,
            lastStatus = j.lastStatus,
            lastError = j.lastError,
            lastRunAt = j.lastRunAt,
            nextRunAt = j.nextRunAt,
            schedule = j.scheduleDisplay,
            scriptOnly = j.scriptOnly,
            failureStreak = j.failureStreak,
        )
    }

/** What a run row can do — one bundle so every row (shelf, rail, card) offers the same verbs. */
private class RunVerbs(
    val open: (CronRun) -> Unit,
    val setPinned: (CronRun, Boolean) -> Unit,
    val markRead: (CronRun) -> Unit,
    /** Hand a failing job to the agent: a fresh chat, the brief in the composer. */
    val askFix: (CronJobCard, HubJob) -> Unit,
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
                            // Accent text on a 12% wash of the same accent is 3.03:1 on
                            // parchment; the ground stays the light, the label takes the ink.
                            color = keryxAccentInk(accent),
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
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
                        color = keryxAccentInk(),
                        modifier = Modifier
                            .clip(RoundedCornerShape(KeryxRadius.chip))
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

            // Why it failed, in the scheduler's words — the card sits under "Needs attention"
            // now, and a heading that says so without saying what is half an answer.
            job?.takeIf { it.enabled }?.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                FailureLine(err, maxLines = if (open) 4 else 1)
                FailureActions(card, job, viewModel, verbs, onDetails = { jobMenu = true })
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
                RunList(card = card, isNew = isNew, verbs = verbs)
            }
        }
    }
    JobMenu(
        expanded = jobMenu,
        onDismiss = { jobMenu = false },
        card = card,
        jobPinned = jobPinned,
        viewModel = viewModel,
        verbs = verbs,
    )
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

// --- 2.14: the page for A LOT of jobs ------------------------------------------------------------

/**
 * A job's runs, newest first — the list a card opens to, and the list a List-mode row opens to.
 * One composable so the two densities can't drift: same unread dot, same pin glyph, same menu.
 */
@Composable
private fun RunList(
    card: CronJobCard,
    isNew: (String) -> Boolean,
    verbs: RunVerbs,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalKeryxHaptics.current
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
            "${card.runs.size - 20} older runs in Gateway ▸ Sessions",
            fontSize = 10.5.sp, color = quiet.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The job's sheet — about the JOB, not a run (long-press any job, or "Details" on a failing one).
 * It was a two-item menu (pin, open newest) and a failing job offered nothing to DO: the red
 * line said what broke and the page stopped there (Jonny 09-27: "the need attention crons I
 * can't really do anything with"). Now the whole error, and the verbs the gateway already had —
 * run it now, pause/resume, delete — plus the one that fixes what the phone can't: hand the
 * failure to the agent. Shared by both densities.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun JobMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    card: CronJobCard,
    jobPinned: Boolean,
    viewModel: ChatViewModel,
    verbs: RunVerbs,
) {
    if (!expanded) return
    val board by viewModel.hub.cron.collectAsState()
    val job = board.data?.jobsByName?.get(card.name)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Full height from the start: half-open, the verbs sat under the fold and the first back
    // only collapsed the sheet (device walk 09-27).
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    KeryxSheet(onDismiss = onDismiss, title = card.name, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            if (job != null) {
                val paused = !job.enabled || job.state.equals("paused", ignoreCase = true)
                Text(
                    buildString {
                        append(CronHumanize.schedule(job.scheduleDisplay))
                        append(" · ")
                        append(
                            when {
                                paused -> "paused"
                                job.state.equals("completed", ignoreCase = true) -> "finished"
                                else -> job.state.ifBlank { "scheduled" }
                            },
                        )
                        if (job.scriptOnly) append(" · script, no transcript")
                    },
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = quiet,
                )
                val err = job.lastError?.takeIf { it.isNotBlank() }
                if (err != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (job.failureStreak > 1) "FAILED ${job.failureStreak} RUNS IN A ROW" else "LAST RUN FAILED",
                        fontSize = 10.sp, letterSpacing = 1.5.sp, color = KeryxStatus.bad,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        CronTriage.errorGist(err),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    // The whole error, selectable: the one-line clip on the card is a pointer,
                    // this is the thing itself — what gets pasted into an issue or a chat.
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            err.take(ERROR_SHOWN),
                            fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(KeryxRadius.field))
                                .verticalScroll(rememberScrollState())
                                .background(KeryxStatus.bad.copy(alpha = 0.08f))
                                .padding(10.dp),
                        )
                    }
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(err)) }) {
                        Icon(KeryxGlyphs.Copy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy error", fontSize = 12.sp)
                    }
                    SheetAction(KeryxGlyphs.Wrench, "Ask the agent to fix it", "A new chat with the job and its error, ready to send") {
                        onDismiss(); verbs.askFix(card, job)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SheetAction(KeryxGlyphs.Play, "Run now", if (err != null) "Try it again — the error clears if it passes" else "Fire it once, off schedule") {
                    onDismiss(); viewModel.hub.jobAction(job.id, "run")
                }
                SheetAction(
                    if (paused) KeryxGlyphs.Play else KeryxGlyphs.StopSquare,
                    if (paused) "Resume" else "Pause",
                    if (paused) "Back on its schedule" else "Stops firing until you resume it",
                ) {
                    onDismiss(); viewModel.hub.jobAction(job.id, if (paused) "resume" else "pause")
                }
            }
            SheetAction(
                if (jobPinned) KeryxGlyphs.PinFilled else KeryxGlyphs.Pin,
                if (jobPinned) "Unpin from sessions" else "Pin to top of sessions",
                null,
            ) { onDismiss(); viewModel.hub.cronSetJobPinned(card.name, !jobPinned) }
            card.latest?.let { latest ->
                SheetAction(KeryxGlyphs.ChevronRight, "Open newest run", null) { onDismiss(); verbs.open(latest) }
            }
            if (job != null) {
                SheetAction(KeryxGlyphs.Trash, "Delete job", "Removes the schedule; past runs stay", tint = KeryxStatus.bad) {
                    confirmDelete = true
                }
            }
        }
    }
    if (confirmDelete && job != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${card.name}”?") },
            text = { Text("The job stops for good. Its past runs stay readable.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDismiss(); viewModel.hub.jobDelete(job.id) }) {
                    Text("Delete", color = KeryxStatus.bad)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

/** One verb in the job sheet: glyph, label, what it will do. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.field))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, fontSize = 14.sp, color = tint)
            if (detail != null) Text(detail, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What a failing job offers without a long-press: the two verbs that usually settle it, and the
 * door to the rest. Sits under the red line on both densities — a heading that says "needs
 * attention" and then offers nothing to do with the attention was the complaint.
 */
@Composable
private fun FailureActions(card: CronJobCard, job: HubJob?, viewModel: ChatViewModel, verbs: RunVerbs, onDetails: () -> Unit) {
    if (job == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
    ) {
        FailureChip(KeryxGlyphs.Play, "Run now") { viewModel.hub.jobAction(job.id, "run") }
        FailureChip(KeryxGlyphs.Wrench, "Fix with agent") { verbs.askFix(card, job) }
        FailureChip(null, "Details") { onDetails() }
    }
}

@Composable
private fun FailureChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(KeryxRadius.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The job sheet shows this much of an error; a traceback past it is a copy, not a read. */
private const val ERROR_SHOWN = 4000

/** The scheduler's own failure text, in the verdict colour. Mono, because it IS machine text. */
@Composable
private fun FailureLine(error: String, maxLines: Int) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
        Icon(
            KeryxGlyphs.Warning,
            contentDescription = null,
            tint = KeryxStatus.bad,
            modifier = Modifier.padding(top = 1.dp).size(11.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            // The cause, not the wrapper: the full text lives in the job sheet.
            CronTriage.errorGist(error).ifBlank { error.trim() },
            fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
            color = KeryxStatus.bad,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The control strip: search, then the filter chips and the density switch on one line. Sits
 * above the list, not in it — narrowing is the thing you do FROM the bottom of a long page.
 */
@Composable
private fun RunsControls(
    query: String,
    onQuery: (String) -> Unit,
    filter: CronFilter,
    counts: Map<CronFilter, Int>,
    offered: List<CronFilter>,
    onFilter: (CronFilter) -> Unit,
    compact: Boolean,
    onCompact: (Boolean) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        // A 40dp field rather than the 56dp OutlinedTextField: on this page the strip is
        // chrome above the list, and every dp it takes is a row it hides. The density switch
        // rides beside it: sharing the chip row, it squeezed the chips until the last one was
        // cut in half on a phone (device walk 09-27).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(KeryxRadius.field))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        RoundedCornerShape(KeryxRadius.field),
                    )
                    .padding(start = 12.dp, end = 4.dp),
            ) {
                Icon(
                    KeryxGlyphs.Search,
                    contentDescription = null,
                    tint = quiet,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Find a job, a headline, a schedule…",
                            fontSize = 13.sp, color = quiet.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                        Icon(KeryxGlyphs.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            DensitySwitch(compact = compact, onCompact = onCompact)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            offered.forEach { f ->
                FilterPill(
                    filter = f,
                    count = counts[f] ?: 0,
                    selected = f == filter,
                    onClick = { onFilter(if (f == filter) CronFilter.ALL else f) },
                )
            }
        }
    }
}

/** A filter chip: verdict dot, label, count. Tapping the lit chip goes back to All. */
@Composable
private fun FilterPill(filter: CronFilter, count: Int, selected: Boolean, onClick: () -> Unit) {
    val dot: Color? = when (filter) {
        CronFilter.ALL -> null
        CronFilter.UNREAD -> MaterialTheme.colorScheme.primary
        CronFilter.FAILING -> KeryxStatus.bad
        CronFilter.PAUSED, CronFilter.NEVER_RUN -> KeryxStatus.idle
    }
    val lit = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(
                if (selected) lit.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            )
            .border(
                1.dp,
                if (selected) lit.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                RoundedCornerShape(KeryxRadius.chip),
            )
            .keryxPressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            filter.label, fontSize = 11.sp, letterSpacing = 0.4.sp, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$count", fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/** Cards ↔ List — two words, not two glyphs: nobody should have to guess which icon is dense. */
@Composable
private fun DensitySwitch(compact: Boolean, onCompact: (Boolean) -> Unit) {
    val lit = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(KeryxRadius.chip)),
    ) {
        listOf(false to "Cards", true to "List").forEach { (isList, label) ->
            val on = isList == compact
            Text(
                label,
                fontSize = 11.sp, maxLines = 1,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .background(if (on) lit.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { if (!on) onCompact(isList) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** A shelf's heading: verdict dot, the section voice, a count, and a fold chevron when it folds. */
@Composable
private fun SectionHead(
    section: CronSection,
    count: Int,
    open: Boolean,
    onToggle: (() -> Unit)?,
) {
    val dot = when (section) {
        CronSection.ATTENTION -> KeryxStatus.bad
        CronSection.RECENT -> KeryxStatus.good
        CronSection.SCRIPTS, CronSection.QUIET -> KeryxStatus.idle
    }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 4.dp),
    ) {
        KeryxSectionHeader(
            section.label,
            dotColor = dot,
            count = count,
            // Attention speaks in its verdict; the other two in the page's ordinary ink.
            color = if (section == CronSection.ATTENTION) KeryxStatus.bad else quiet,
        )
        Spacer(Modifier.weight(1f))
        if (onToggle != null) {
            Text(
                if (open) "▾" else "▸ show",
                color = quiet.copy(alpha = 0.6f), fontSize = 11.sp,
            )
        }
    }
}

/**
 * The arrivals rail, folded to one line — past [CronTriage.RAIL_FOLD_AT] unread, a column of
 * headlines above the jobs pushes every job off the first screen (and launches a headline
 * fetch per row). The line says how much and from how many jobs; tap unfolds the rail.
 */
@Composable
private fun RailSummary(
    board: chat.keryx.app.presentation.HubDelegate.CronBoard,
    open: Boolean,
    onToggle: () -> Unit,
    onMarkAll: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(accent.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(KeryxRadius.card))
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(
            "${board.unread.total} new · ${board.unread.byJob.size} jobs",
            color = keryxAccentInk(accent),
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onMarkAll) {
            Text("Mark read", fontSize = 12.sp)
        }
    }
}

/**
 * One job as one line (~52dp): status dot, name, when it last ran and when it runs next, the
 * last eight outcomes as ticks, the unread count. Tap opens it in place — the failure text,
 * the newest headline, then the same run list a card opens to; long-press is the job menu.
 *
 * No headline fetch while closed: at two hundred rows a `produceState` per row is two hundred
 * transcript fetches for a page that only needed names. It starts when the row is opened.
 */
@Composable
private fun CompactRunRow(
    row: CronRow,
    unreadIds: Set<String>,
    nowMs: Long,
    viewModel: ChatViewModel,
    verbs: RunVerbs,
) {
    val card = row.card
    val facts = row.facts
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalKeryxHaptics.current
    // Same key as the card's, so a job opened in one density is still open in the other.
    var open by rememberSaveable("runs-${card.name}") { mutableStateOf(false) }
    var jobMenu by remember { mutableStateOf(false) }
    val pinnedJobs by viewModel.hub.pinnedJobs.collectAsState()
    val jobPinned = card.name in pinnedJobs
    val tint = RUN_TINTS[CronHumanize.tintIndex(card.name, RUN_TINTS.size)]
    val failed = row.health == CronHealth.FAILED
    val ticks = remember(card, facts, unreadIds) { CronTriage.strip(card, facts, unreadIds) }

    val dotColor = when (row.health) {
        CronHealth.FAILED -> KeryxStatus.bad
        CronHealth.RUNNING -> MaterialTheme.colorScheme.primary
        CronHealth.OK -> KeryxStatus.good
        CronHealth.PAUSED, CronHealth.IDLE -> KeryxStatus.idle
    }
    // The meta line, most urgent word first so an ellipsis eats the schedule, never the verdict.
    val meta = buildList {
        when (row.health) {
            CronHealth.FAILED -> add("failed ${CronTriage.ago(row.lastActivity, nowMs)}")
            CronHealth.RUNNING -> add("running now")
            CronHealth.PAUSED -> {
                add(if (facts?.state.equals("completed", ignoreCase = true)) "done" else "paused")
                if (row.lastActivity > 0L) add("last ${CronTriage.ago(row.lastActivity, nowMs)}")
            }
            CronHealth.IDLE -> add("never run")
            CronHealth.OK -> add(CronTriage.ago(row.lastActivity, nowMs))
        }
        if (row.health != CronHealth.PAUSED) {
            facts?.nextRunAt?.let { CronHumanize.nextIn(it, nowMs) }?.let { add(it) }
        }
        facts?.schedule?.let { CronHumanize.schedule(it) }?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (!card.scheduled) add("job no longer scheduled")
    }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KeryxRadius.field))
                // A failing row carries a breath of its verdict, so the shelf reads as red
                // from across the room, not only its dots.
                .background(if (failed) KeryxStatus.bad.copy(alpha = 0.06f) else onSurface.copy(alpha = 0.04f))
                .combinedClickable(
                    onClick = { open = !open },
                    onLongClick = { haptics.press(); jobMenu = true },
                )
                .animateContentSize()
                .height(IntrinsicSize.Min),
        ) {
            // The job's identity bar, thinner than the card's: here the status dot is the loud one.
            Box(Modifier.width(2.dp).fillMaxHeight().background(tint.copy(alpha = 0.6f)))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    KeryxBreathingDot(color = dotColor, alive = row.health == CronHealth.RUNNING, size = 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (jobPinned) {
                                Icon(
                                    KeryxGlyphs.PinFilled,
                                    contentDescription = "Pinned to sessions",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                card.name,
                                fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                                color = onSurface.copy(alpha = if (row.health == CronHealth.PAUSED) 0.6f else 0.95f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            meta.joinToString(" · "),
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = if (failed) KeryxStatus.bad else quiet.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutcomeStrip(ticks)
                    if (row.unread > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${row.unread}",
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                            color = keryxAccentInk(),
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                if (open) {
                    Column(Modifier.padding(start = 12.dp, end = 10.dp, bottom = 8.dp)) {
                        if (failed) {
                            facts?.lastError?.takeIf { it.isNotBlank() }?.let { FailureLine(it, maxLines = 4) }
                            FailureActions(card, viewModel.hub.cron.collectAsState().value.data?.jobsByName?.get(card.name), viewModel, verbs, onDetails = { jobMenu = true })
                        }
                        OpenedHeadline(card = card, viewModel = viewModel)
                        if (card.runs.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            RunList(card = card, isNew = { it in unreadIds }, verbs = verbs)
                        } else if (!failed) {
                            Text(
                                "No transcripts on the gateway for this job.",
                                fontSize = 11.sp, color = quiet.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        JobMenu(
            expanded = jobMenu,
            onDismiss = { jobMenu = false },
            card = card,
            jobPinned = jobPinned,
            viewModel = viewModel,
            verbs = verbs,
        )
    }
}

/** The newest report's headline and lead — composed only inside an OPENED list row, so its
 *  fetch is paid for by the one row you asked about. The delegate caches it after that. */
@Composable
private fun OpenedHeadline(card: CronJobCard, viewModel: ChatViewModel) {
    val latest = card.latest ?: return
    val digest by produceState<chat.keryx.core.model.CronDigest?>(null, latest.id) {
        value = viewModel.hub.cronDigest(latest.id)
    }
    val d = digest ?: return
    val title = d.title ?: return
    Text(
        title,
        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
    d.lead?.let {
        Text(
            it,
            fontSize = 11.5.sp, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * The last [CronTriage.STRIP_MAX] outcomes as ticks, newest at the right edge: accent = unread,
 * verdict red = failed, faded good = delivered. Fixed width, right-aligned, so every row's
 * newest tick sits in the same column and a streak reads DOWN the page as well as across.
 */
@Composable
private fun OutcomeStrip(ticks: List<CronTick>) {
    val good = KeryxStatus.good
    val bad = KeryxStatus.bad
    val accent = MaterialTheme.colorScheme.primary
    val failedCount = ticks.count { it.failed }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width((CronTriage.STRIP_MAX * 5).dp)
            .semantics {
                contentDescription = if (ticks.isEmpty()) "No runs yet"
                else "Last ${ticks.size} runs" + (if (failedCount > 0) ", $failedCount failed" else "")
            },
    ) {
        ticks.forEach { t ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (t.failed) 14.dp else 11.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        when {
                            t.failed -> bad
                            t.unread -> accent
                            else -> good.copy(alpha = 0.5f)
                        },
                    ),
            )
        }
    }
}

/** The brief a failing job hands the agent: what it is, how it fails, and what "fixed" means. */
private fun fixBrief(job: HubJob): String = buildString {
    append("The scheduled job “${job.name}” (id ${job.id}, ${job.scheduleDisplay}")
    if (job.scriptOnly) append(", script job")
    append(") is failing")
    if (job.failureStreak > 1) append(" — ${job.failureStreak} runs in a row")
    append(". Last error:\n\n```\n")
    append(job.lastError.orEmpty().take(1500).trim())
    append("\n```\n\nFind out why and fix it. Check the job with the cronjob tool and whatever it runs; ")
    append("when it's fixed, trigger one run and confirm it passes. Tell me what was wrong and what you changed.")
}
