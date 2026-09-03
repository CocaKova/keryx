package chat.keryx.core.model

/**
 * The pinned deck's cron tiles — a scheduled job's output at the top of the session list, the
 * way a Quick Room sits at the top of the Matrix drawer.
 *
 * Two kinds, one deck:
 *  - a JOB tile follows its job: it always opens the newest run, wears the unread dot when
 *    that run is news, and is the thing you pin once and then read every morning. The gateway
 *    has no job-level pin (its pin is per session), so which jobs are pinned is the phone's
 *    ledger, keyed by job name — the one stable name a job has.
 *  - a RUN tile is one specific output kept on the gateway ([CronRun.pinned]); it opens that
 *    run and only that run. Pinned means "at the top of the list" for sessions and runs alike.
 *
 * Job tiles first, in the order they were pinned; then kept runs, newest first. A pinned job
 * whose runs aren't visible (new, or aged out) still gets a tile with no run behind it — the
 * pin is a fact about the job, not about any run.
 */
data class CronTile(
    /** Stable id for the deck row: `cronjob:<name>` for a job, the session id for a run. */
    val id: String,
    /** What the tile is called — the job name for both kinds; a run's label adds its when. */
    val name: String,
    val label: String,
    /** The run the tile opens (null = a pinned job with nothing to open yet). */
    val runId: String?,
    val runTitle: String?,
    val timestamp: Long,
    val unread: Boolean,
    val job: Boolean,
) {
    companion object {
        const val JOB_PREFIX = "cronjob:"
        fun jobId(name: String) = JOB_PREFIX + name
    }
}

object CronTiles {

    fun build(cards: List<CronJobCard>, pinnedJobs: Collection<String>, unread: CronUnread): List<CronTile> {
        val byName = cards.associateBy { it.name }
        val jobs = pinnedJobs.distinct().map { name ->
            val latest = byName[name]?.latest
            CronTile(
                id = CronTile.jobId(name),
                name = name,
                label = name,
                runId = latest?.id,
                runTitle = latest?.title,
                timestamp = latest?.timestamp ?: 0L,
                unread = latest != null && unread.isNew(latest.id),
                job = true,
            )
        }
        val runs = CronPins.of(cards).map { run ->
            val name = cards.firstOrNull { c -> c.runs.any { it.id == run.id } }?.name
                ?: run.title.substringBefore(" · ", run.title)
            CronTile(
                id = run.id,
                name = name,
                label = run.title,
                runId = run.id,
                runTitle = run.title,
                timestamp = run.timestamp,
                unread = unread.isNew(run.id),
                job = false,
            )
        }
        return jobs + runs
    }
}
