package chat.keryx.app.transport.demo

import chat.keryx.app.data.remote.HermesStreamClient.KanbanBoard
import chat.keryx.app.data.remote.HermesStreamClient.KanbanComment
import chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail
import chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent
import chat.keryx.app.data.remote.HermesStreamClient.KanbanRun
import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask

/**
 * The demo's Missions board (3.0 herald): a few jobs handed to the agents as cards, in the states
 * a working board has. One is running, two wait on you (a review and a question), one is queued
 * and one is done. It's drawn by the real board, so what a stranger sees is what Hermes's kanban
 * looks like on the phone.
 *
 * The assignees are the demo's bots ([DemoTransport]'s roster). Like [chat.keryx.core.demo.DemoScript],
 * nothing here names a real person, product or price, and times are relative to the phone's clock.
 */
internal object DemoBoard {

    // The board's times are epoch SECONDS, as Hermes's kanban stores them.
    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE

    /** [nowMs] is the phone's clock; every stamp on the board is seconds before it. */
    fun board(nowMs: Long): KanbanBoard {
        val cards = cards(nowMs / 1000)
        return KanbanBoard("default", cards.groupBy { it.status }, needsYou = cards.count { it.needsYou })
    }

    fun detail(taskId: String, nowMs: Long): Result<KanbanDetail> {
        val now = nowMs / 1000
        val task = cards(now).firstOrNull { it.id == taskId }
            ?: return Result.failure(IllegalStateException("That mission isn't on the demo board."))
        val full = task.copy(body = BODIES[taskId] ?: task.bodyExcerpt, result = task.latestSummary)
        val started = task.startedAt
        // A run's status is its outcome once it ends, as Hermes's `_end_run` records it.
        val outcome = when (task.status) {
            "done" -> "completed"
            "review" -> "review_requested"
            "blocked" -> "blocked"
            else -> ""
        }
        val runs = if (started == null) emptyList() else listOf(
            KanbanRun(
                id = 1, profile = task.assignee,
                status = outcome.ifEmpty { "running" },
                outcome = outcome,
                summary = task.latestSummary, error = "",
                startedAt = started, endedAt = task.completedAt,
                durationSeconds = task.completedAt?.let { it - started },
            ),
        )
        val events = buildList {
            add(KanbanEvent(1, taskId, "created", task.createdAt))
            started?.let { add(KanbanEvent(2, taskId, "claimed", it, detail = "by ${task.assignee}")) }
            if (task.status == "blocked") add(KanbanEvent(3, taskId, "blocked", now - 20 * MINUTE, detail = task.ask))
            if (task.status == "review") add(KanbanEvent(3, taskId, "review_requested", now - 35 * MINUTE, detail = task.ask))
            task.completedAt?.let { add(KanbanEvent(4, taskId, "completed", it, detail = task.latestSummary)) }
        }
        val comments = COMMENTS[taskId].orEmpty().map { (author, body) -> KanbanComment(author, body, now - 40 * MINUTE) }
        return Result.success(KanbanDetail(full, comments, events, runs))
    }

    private fun cards(now: Long): List<KanbanTask> = listOf(
        task(
            "t_demo_prune", "Add the backup prune to the nightly job", "default", "running", now,
            created = 30 * MINUTE, started = 25 * MINUTE,
            excerpt = "Keep 7 daily, 8 weekly and 12 monthly snapshots, then check tonight's run.",
        ),
        task(
            "t_demo_notes", "Release notes for the photo importer", "quill", "review", now,
            created = 3 * HOUR, started = 2 * HOUR,
            excerpt = "Short notes for the speed-up, written for people who use it, not for developers.",
            ask = "Read it over before I post it to the repo.",
            summary = "Drafted the notes: one paragraph on the speed-up and a line on the thumbnail cache.",
            needsYou = true,
        ),
        task(
            "t_demo_router", "Update the router's firmware", "default", "blocked", now,
            created = 5 * HOUR, started = 4 * HOUR,
            excerpt = "There's a firmware update with security fixes. Install it when nobody is online.",
            ask = "The update cuts the internet for about five minutes. Which evening works?",
            blockKind = "needs_input", needsYou = true,
        ),
        task(
            "t_demo_fans", "Find quieter fans for the server closet", "scout", "todo", now,
            created = 26 * HOUR,
            excerpt = "Same size as the ones in there now. Compare noise, airflow and reviews, with sources.",
        ),
        task(
            "t_demo_downloads", "Tidy the downloads folder", "default", "done", now,
            created = 30 * HOUR, started = 29 * HOUR, completed = 28 * HOUR,
            excerpt = "Sort it into folders by type and find the duplicates.",
            summary = "Sorted 1,340 files into folders by type and moved 9 GB of duplicates to the trash.",
        ),
    )

    private fun task(
        id: String, title: String, assignee: String, status: String, now: Long,
        created: Long, started: Long? = null, completed: Long? = null,
        excerpt: String, ask: String = "", summary: String = "", blockKind: String = "", needsYou: Boolean = false,
    ) = KanbanTask(
        id = id, title = title, assignee = assignee, status = status, priority = 0,
        createdBy = "you", createdAt = now - created,
        startedAt = started?.let { now - it }, completedAt = completed?.let { now - it },
        consecutiveFailures = 0, bodyExcerpt = excerpt,
        blockKind = blockKind, ask = ask, latestSummary = summary, needsYou = needsYou,
    )

    private val BODIES = mapOf(
        "t_demo_prune" to "Keep 7 daily, 8 weekly and 12 monthly snapshots, then check tonight's run.\n\n" +
            "The backup disk filled up once already. Add the prune after the nightly backup and make sure it finishes cleanly.",
        "t_demo_notes" to "Short notes for the speed-up, written for people who use it, not for developers.\n\n" +
            "Mention the new thumbnail cache behaviour. Keep it under 150 words.",
    )

    private val COMMENTS = mapOf(
        "t_demo_notes" to listOf(
            "quill" to "Draft is in the card's result. I kept it to four sentences and left out the profiling details.",
        ),
        "t_demo_router" to listOf(
            "default" to "Downloaded and checked the firmware's signature. Ready to install as soon as you pick a time.",
        ),
    )
}
