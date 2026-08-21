package chat.keryx.core.model

/**
 * One scheduled job and every session it has produced.
 *
 * [job] is null when runs exist but no matching job does — the job was deleted or renamed
 * after those runs happened. Their transcripts are still real work and still readable, so
 * they keep a card rather than disappearing.
 */
/** One scheduled run as the session list serves it — all the cron surface needs to know. */
data class CronRun(
    val id: String,
    val title: String,
    val timestamp: Long,
)

data class CronJobCard(
    val name: String,
    /** True when a job with this exact name is currently configured ([CronGrouping.group]'s
     *  jobNames); false = runs survived a deleted or renamed job. The UI pairs the live job
     *  row by name — :core doesn't duplicate the job type. */
    val scheduled: Boolean,
    /** Runs newest-first. */
    val runs: List<CronRun>,
) {
    val latest: CronRun? get() = runs.firstOrNull()
    val runCount: Int get() = runs.size

    /** A job that is scheduled but has produced nothing we can see (new, or its runs aged out). */
    val neverRun: Boolean get() = runs.isEmpty()
}

/**
 * Turns a flat list of cron sessions into one card per job.
 *
 * The gateway names every cron session `"<exact job name> · <when>"` and marks the title
 * `title_source: "user"` — it is written by the runner, not invented by a model — so matching
 * a run to its job is exact rather than fuzzy. Verified against a live gateway: all 13
 * configured job names appear verbatim as title prefixes.
 *
 * The matching is still defensive, because this ships to everyone's gateway, not one:
 *  - the LONGEST matching job name wins, so "Weekly Review" can't swallow a hypothetical
 *    "Weekly Review — Finance" run;
 *  - a run matching no known job falls back to the text before the separator, which keeps
 *    deleted and renamed jobs grouped instead of scattering them into singletons;
 *  - a run with no separator at all keeps its whole title as the group name.
 * Nothing is ever dropped: every session handed in comes back out in exactly one card.
 */
object CronGrouping {

    /** What the runner puts between the job name and the timestamp. */
    private const val SEP = " · "

    fun group(sessions: List<CronRun>, jobNames: List<String>): List<CronJobCard> {
        // Longest first so the most specific job name claims a title before a shorter prefix.
        val byLength = jobNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
        val runsByName = LinkedHashMap<String, MutableList<CronRun>>()
        val named = jobNames.toSet()

        for (s in sessions) {
            val title = s.title.trim()
            val match = byLength.firstOrNull { n -> title == n || title.startsWith(n + SEP) }
            val key = match ?: fallbackName(title)
            runsByName.getOrPut(key) { mutableListOf() } += s
        }

        val cards = runsByName.map { (name, runs) ->
            CronJobCard(
                name = name,
                scheduled = name in named,
                runs = runs.sortedByDescending { it.timestamp },
            )
        }
        // Jobs that exist but have produced no visible run still deserve a card: "scheduled,
        // nothing yet" is information, and without it a fresh job looks like it isn't there.
        val seen = cards.mapTo(HashSet()) { it.name }
        val idle = jobNames.filterNot { it in seen }
            .map { CronJobCard(name = it, scheduled = true, runs = emptyList()) }

        // Most recently active first; never-run jobs settle at the bottom, alphabetically.
        return cards.sortedByDescending { it.latest?.timestamp ?: 0L } +
            idle.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    /** Group name for a run whose job we can't see: the text before the timestamp. */
    private fun fallbackName(title: String): String {
        val head = title.substringBefore(SEP).trim()
        return head.ifBlank { title.ifBlank { "Untitled job" } }
    }
}

/**
 * What has come in since you last looked.
 *
 * The Cron place answers "what does this gateway do on a schedule" well and "what do I have to
 * READ right now" not at all: nine cards, each showing its newest run, and no way to tell the
 * brief that landed twenty minutes ago from the one you read yesterday. On a gateway where the
 * machinery outnumbers the conversations several to one, that turns a page of reports into a
 * page of homework with no due dates.
 *
 * Unread is decided by two facts and nothing else:
 *  - a **baseline**, stamped the first time this install ever sees the cron surface. Everything
 *    that already existed then is history, not a backlog — a fresh install must never open on
 *    forty-four unread reports, and a run that predates the app was never "delivered" to it.
 *  - a set of **seen run ids**. A run is read when the user opens it, or when they say so with
 *    mark-all-read. Fetching a run's headline is NOT reading it — the client seeing something
 *    and the person seeing it are different events, and only the second one clears a notice.
 *
 * Everything here is a pure function of those two plus the current cards, so the same inputs
 * always give the same badge — no clock, no I/O, no ordering surprises.
 */
data class CronUnread(
    /** Unread runs, newest first, across every job. */
    val runs: List<CronRun> = emptyList(),
    /** Job name → how many of its runs are unread. Jobs with none are absent. */
    val byJob: Map<String, Int> = emptyMap(),
    /** Fast membership for row-level marks. */
    val ids: Set<String> = emptySet(),
) {
    val total: Int get() = runs.size
    val any: Boolean get() = runs.isNotEmpty()

    fun countFor(jobName: String): Int = byJob[jobName] ?: 0
    fun isNew(runId: String): Boolean = runId in ids
}

object CronUnreadCalc {

    /**
     * [baseline] is the install's first-sight timestamp (0 = never baselined, which reads as
     * "nothing is new yet" — the honest answer before we know what normal looks like).
     *
     * A run counts as new when it landed strictly after the baseline and hasn't been opened.
     * Strictly, so the newest run at first sight — the one the baseline is taken FROM — is
     * history like everything beside it.
     */
    fun compute(
        cards: List<CronJobCard>,
        seenIds: Set<String>,
        baseline: Long,
    ): CronUnread {
        if (baseline <= 0L) return CronUnread()
        val byJob = LinkedHashMap<String, Int>()
        val runs = ArrayList<CronRun>()
        for (card in cards) {
            var count = 0
            for (run in card.runs) {
                if (run.timestamp > baseline && run.id !in seenIds) {
                    runs += run
                    count++
                }
            }
            if (count > 0) byJob[card.name] = count
        }
        runs.sortByDescending { it.timestamp }
        return CronUnread(
            runs = runs,
            byJob = byJob,
            ids = runs.mapTo(LinkedHashSet()) { it.id },
        )
    }

    /**
     * The baseline to stamp when a gateway's cron runs are seen for the first time: the newest
     * run there is. Zero when there is nothing yet — a gateway whose jobs have never run must
     * stay un-baselined so its FIRST report still arrives as news.
     */
    fun baselineOf(cards: List<CronJobCard>): Long =
        cards.mapNotNull { it.latest?.timestamp }.maxOrNull()?.coerceAtLeast(0L) ?: 0L

    /** Every run id currently on the surface — the vocabulary a seen-set is allowed to hold. */
    fun knownIds(cards: List<CronJobCard>): Set<String> =
        cards.flatMapTo(LinkedHashSet()) { card -> card.runs.map { it.id } }

    /**
     * Keep the seen-set from growing forever: past [cap] entries, drop the ids the gateway no
     * longer lists, since a run that has aged off the surface can never be shown as unread
     * again. Held until the cap because the cron list is a PAGE — an id can sit outside the
     * window this fetch happened to return and still come back into it, and dropping it there
     * would resurrect a report the user already read.
     */
    fun prune(seenIds: Set<String>, known: Set<String>, cap: Int = 400): Set<String> =
        if (seenIds.size <= cap || known.isEmpty()) seenIds
        else seenIds.filterTo(LinkedHashSet()) { it in known }
}
