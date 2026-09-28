package chat.keryx.core.model

import kotlinx.datetime.Instant

/**
 * The Runs page for someone with A LOT of jobs.
 *
 * One card per job, newest activity first, was right at nine jobs and wrong at twenty-four
 * (Jonny's gateway, 09-27: 24 jobs — 7 failing, 4 paused/done, 1 never run). Two things broke
 * as it grew:
 *  - **the failures sank.** The failing jobs on that gateway are the half-hourly watchdogs, and a
 *    `no_agent` script job whose dispatch fails writes NO session — so [CronGrouping] saw "no
 *    runs yet", filed them as idle, and sorted them to the alphabetical tail. The five jobs
 *    with a failure streak were the last five cards on the page.
 *  - **the page had one question.** "Most recent first" answers "what just happened"; with two
 *    dozen jobs the question you arrive with is "is anything broken, and what's new" — and the
 *    quiet ones (the monthly check, the paused digest) only need to be *findable*.
 *
 * So the rows are triaged by STATE, not by cadence: Needs attention → Recent (last 24 h) →
 * Quiet. Cadence was considered and rejected on the real data — the seven failures span three
 * cadences (every 10/30 min, daily, weekly), so grouping by schedule would scatter exactly the
 * rows that need to be together. Cadence stays on every row as text, where it's a fact, not a
 * shelf.
 *
 * :core doesn't know the app's job type, so the job's live fields ride in as [CronJobFacts].
 * Everything here is pure: the same cards, facts, unread ledger and clock always give the same
 * page — no I/O, and `now` is a parameter so the 24 h window is testable.
 */
data class CronJobFacts(
    val enabled: Boolean = true,
    /** The scheduler's own word: "scheduled", "paused", "completed", "running", … */
    val state: String = "",
    val lastStatus: String? = null,
    val lastError: String? = null,
    /** ISO-8601 as the gateway serves it; unparseable = absent. */
    val lastRunAt: String? = null,
    val nextRunAt: String? = null,
    /** The raw schedule (`0 7 * * *`, `every 30m`) — searched through its humanized words too. */
    val schedule: String = "",
    /** A script job (`no_agent`): it runs without an agent turn and leaves no transcript. */
    val scriptOnly: Boolean = false,
    /** Consecutive failed runs, as the scheduler counts them (0 = the last one went fine). */
    val failureStreak: Int = 0,
) {
    // Body properties: parsed once per facts value, and left out of equals/copy on purpose.
    val lastRunAtMs: Long? = CronTriage.parseIso(lastRunAt)
    val nextRunAtMs: Long? = CronTriage.parseIso(nextRunAt)

    /** The job's latest verdict was a failure. Same test the card's ✓/✕ has always used. */
    val lastFailed: Boolean
        get() = lastStatus?.contains("error", ignoreCase = true) == true ||
            lastStatus?.contains("fail", ignoreCase = true) == true ||
            !lastError.isNullOrBlank()

    /** Switched off: disabled, paused, or a one-shot that has done its one thing. */
    val off: Boolean
        get() = !enabled || state.equals("paused", ignoreCase = true) ||
            state.equals("completed", ignoreCase = true)
}

/** What a row's status dot says. Ordered by how loudly it should say it. */
enum class CronHealth { FAILED, RUNNING, OK, PAUSED, IDLE }

/** The page's three shelves, in page order. */
enum class CronSection(val label: String) {
    ATTENTION("Needs attention"),
    RECENT("Recent"),
    /**
     * Script jobs — the watchdogs and bridges that fire every 10–30 minutes, run no agent and
     * leave no transcript (Jonny's gateway: 10 of 24). Among the reports they read as "no runs
     * yet" and buried the jobs that write something; they get their own folded shelf. A FAILING
     * script still goes to [ATTENTION] — the shelf hides the routine, never the broken.
     */
    SCRIPTS("Background scripts"),
    QUIET("Quiet"),
}

/** The control strip's filters. [ALL] is always offered; the rest only when they'd match. */
enum class CronFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    FAILING("Failing"),
    PAUSED("Paused"),
    NEVER_RUN("Never run"),
}

/** One run in a row's outcome strip. [runId] is null for a failure that left no transcript. */
data class CronTick(val failed: Boolean, val unread: Boolean, val runId: String?)

/** One job, triaged: its card, its live facts (null = runs survived a deleted job), and the verdicts. */
data class CronRow(
    val card: CronJobCard,
    val facts: CronJobFacts?,
    val health: CronHealth,
    val section: CronSection,
    /** Newest sign of life: the newest run's activity or the scheduler's last fire, whichever is later. 0 = none. */
    val lastActivity: Long,
    val unread: Int,
) {
    val name: String get() = card.name
}

/** A section as the page draws it: header + the rows under it (never empty). */
data class CronSectionBlock(val section: CronSection, val rows: List<CronRow>)

object CronTriage {

    /** "Recent" means the last day: daily jobs land here every morning, weekly ones on their day. */
    const val RECENT_WINDOW_MS: Long = 24L * 60 * 60 * 1000

    /** The strip's length: enough to see a streak, short enough to sit beside a name. */
    const val STRIP_MAX: Int = 8

    /** Past this many unread, the arrivals rail folds into one summary row — a dozen headlines
     *  above the jobs pushes every job off the first screen, and the Unread filter reads them
     *  better anyway. */
    const val RAIL_FOLD_AT: Int = 5

    /**
     * The one line of a job's error worth reading. The scheduler wraps a worker crash as
     * "Restart-safe cron worker dispatch failed: … Traceback (most recent call last): …" — the
     * card's one-line clip showed that preamble on every broken job alike, and the actual cause
     * ("ModuleNotFoundError: No module named 'ruamel'") sat on the LAST line, never shown. A
     * traceback answers with its final exception line; anything else with its first line.
     */
    fun errorGist(error: String): String {
        val lines = error.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val pick = if (error.contains("Traceback")) {
            lines.lastOrNull { EXCEPTION_LINE.containsMatchIn(it) } ?: lines.last()
        } else {
            lines.first()
        }
        return pick.take(GIST_MAX)
    }

    private val EXCEPTION_LINE = Regex("""^[A-Za-z_][\w.]*(Error|Exception|Exit|Interrupt|Warning)\b""")
    private const val GIST_MAX = 300

    fun parseIso(iso: String?): Long? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull()
    }

    fun health(card: CronJobCard, facts: CronJobFacts?): CronHealth = when {
        // Off beats failed: a job you switched off is not asking for anything, even if its last
        // fire went wrong — that's history, and it'll show again the day you resume it.
        facts != null && facts.off -> CronHealth.PAUSED
        facts != null && facts.state.equals("running", ignoreCase = true) -> CronHealth.RUNNING
        facts != null && facts.lastFailed -> CronHealth.FAILED
        card.neverRun && facts?.lastRunAtMs == null -> CronHealth.IDLE
        else -> CronHealth.OK
    }

    fun lastActivity(card: CronJobCard, facts: CronJobFacts?): Long =
        maxOf(card.latest?.timestamp ?: 0L, facts?.lastRunAtMs ?: 0L)

    fun sectionOf(health: CronHealth, lastActivity: Long, nowMs: Long, scriptOnly: Boolean = false): CronSection = when {
        health == CronHealth.FAILED -> CronSection.ATTENTION
        scriptOnly -> CronSection.SCRIPTS
        health == CronHealth.RUNNING -> CronSection.RECENT
        health == CronHealth.PAUSED || health == CronHealth.IDLE -> CronSection.QUIET
        lastActivity > 0L && nowMs - lastActivity <= RECENT_WINDOW_MS -> CronSection.RECENT
        else -> CronSection.QUIET
    }

    /** Every card, triaged. Order is the page's: section, then newest activity, then name. */
    fun rows(
        cards: List<CronJobCard>,
        facts: Map<String, CronJobFacts>,
        unread: CronUnread,
        nowMs: Long,
    ): List<CronRow> = cards.map { card ->
        val f = facts[card.name]
        val h = health(card, f)
        val last = lastActivity(card, f)
        CronRow(
            card = card,
            facts = f,
            health = h,
            section = sectionOf(h, last, nowMs, scriptOnly = f?.scriptOnly == true),
            lastActivity = last,
            unread = unread.countFor(card.name),
        )
    }.sortedWith(
        compareBy<CronRow> { it.section.ordinal }
            // Quiet keeps live-but-slow jobs above switched-off and never-run ones.
            .thenBy { if (it.section == CronSection.QUIET) quietRank(it.health) else 0 }
            .thenByDescending { it.lastActivity }
            .thenBy { it.name.lowercase() },
    )

    private fun quietRank(h: CronHealth): Int = when (h) {
        CronHealth.PAUSED -> 1
        CronHealth.IDLE -> 2
        else -> 0
    }

    fun matchesFilter(row: CronRow, filter: CronFilter): Boolean = when (filter) {
        CronFilter.ALL -> true
        CronFilter.UNREAD -> row.unread > 0
        CronFilter.FAILING -> row.health == CronHealth.FAILED
        CronFilter.PAUSED -> row.health == CronHealth.PAUSED
        CronFilter.NEVER_RUN -> row.health == CronHealth.IDLE
    }

    /**
     * Every whitespace-separated token must appear (case-insensitive) somewhere in the job's
     * name, its newest headline when one is known, or its schedule — raw or in words, so
     * "weekly", "sun" and "0 7" all find what you'd expect. AND, not OR: typing more narrows.
     */
    fun matchesQuery(row: CronRow, query: String, headline: String?): Boolean {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val schedule = row.facts?.schedule.orEmpty()
        val hay = buildString {
            append(row.name.lowercase()); append('\n')
            if (!headline.isNullOrBlank()) { append(headline.lowercase()); append('\n') }
            if (schedule.isNotBlank()) {
                append(schedule.lowercase()); append('\n')
                append(CronHumanize.schedule(schedule).lowercase())
            }
        }
        return tokens.all { it in hay }
    }

    /** Filter + search, order kept. [headlines] maps a job name to its newest headline, where known. */
    fun visible(
        rows: List<CronRow>,
        filter: CronFilter,
        query: String,
        headlines: Map<String, String>,
    ): List<CronRow> = rows.filter { matchesFilter(it, filter) && matchesQuery(it, query, headlines[it.name]) }

    /** How many rows each filter would show. [CronFilter.ALL] is the total. */
    fun counts(rows: List<CronRow>): Map<CronFilter, Int> =
        CronFilter.entries.associateWith { f -> rows.count { matchesFilter(it, f) } }

    /** The chips worth drawing: All, plus every filter that would match something. The active
     *  one stays even at zero, so the chip you're standing on never vanishes under you. */
    fun offeredFilters(counts: Map<CronFilter, Int>, active: CronFilter): List<CronFilter> =
        CronFilter.entries.filter { it == CronFilter.ALL || it == active || (counts[it] ?: 0) > 0 }

    /** Rows cut into shelves, in [CronSection] order, empty shelves dropped. */
    fun sections(rows: List<CronRow>): List<CronSectionBlock> =
        CronSection.entries.mapNotNull { s ->
            rows.filter { it.section == s }.takeIf { it.isNotEmpty() }?.let { CronSectionBlock(s, it) }
        }

    /**
     * The row's last [max] outcomes, OLDEST first (read left to right, newest at the right edge).
     *
     * Per-run verdicts don't exist on the session list — a transcript is proof a run happened,
     * not that it went well — so this is honest about what it knows: every visible run is a
     * tick, and the job's latest verdict colours the newest. When that verdict is a failure
     * that left no transcript (the scheduler's last fire is later than the newest run's last
     * activity — a failed dispatch writes no session), the failure gets its own tick, with no
     * run to open behind it.
     */
    fun strip(
        card: CronJobCard,
        facts: CronJobFacts?,
        unreadIds: Set<String>,
        max: Int = STRIP_MAX,
    ): List<CronTick> {
        if (max <= 0) return emptyList()
        val failed = facts != null && facts.lastFailed
        val lastFire = facts?.lastRunAtMs
        val newest = card.latest
        val orphanFailure = failed && lastFire != null && (newest == null || lastFire > newest.timestamp)
        val ticks = ArrayList<CronTick>(max)
        if (orphanFailure) ticks += CronTick(failed = true, unread = false, runId = null)
        for ((i, run) in card.runs.withIndex()) {
            if (ticks.size >= max) break
            ticks += CronTick(
                failed = failed && !orphanFailure && i == 0,
                unread = run.id in unreadIds,
                runId = run.id,
            )
        }
        return ticks.asReversed().toList()
    }

    /** "3h ago" from [nowMs]; 0 or future-skewed stamps read "now"/"—" rather than lying. */
    fun ago(ts: Long, nowMs: Long): String {
        if (ts <= 0L) return "—"
        val mins = ((nowMs - ts) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }

    /** Whether the arrivals rail should fold to its summary row. */
    fun foldRail(unreadTotal: Int): Boolean = unreadTotal > RAIL_FOLD_AT
}
