package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail
import chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent
import chat.keryx.app.data.remote.HermesStreamClient.KanbanRun
import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import chat.keryx.core.model.BotProfile

/**
 * A mission card told as a story (3.0): what is happening in one plain sentence, the one thing
 * to act on, the move that fits, and what has happened, in the words a person uses. The sheet
 * before this opened on a status code, a handle and a card id, asked for an approval a screen and
 * a half above the draft it approved, and spoke in runs, handoffs and heartbeats. The technical
 * record is still there, one tap down under Details. Pure, so every state is unit-tested.
 */
internal object MissionStory {

    /**
     * The name the install gives the profile that works the card: its friendly title or display
     * name, else the profile name tidied ("research-buddy" → "Research buddy"). Null for the
     * gateway's own unnamed agent ("default" is not a name) and for a card nobody holds.
     */
    fun agentName(assignee: String, roster: List<BotProfile>): String? {
        val name = assignee.trim()
        if (name.isEmpty()) return null
        roster.firstOrNull { it.name == name }?.let { p ->
            friendlyName(p)?.let { return it }
            if (p.isDefault) return null
        }
        if (name == "default") return null
        return name.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
    }

    /** A profile's own name for itself, when the install gave it one. */
    fun friendlyName(p: BotProfile): String? = p.title.ifBlank { p.displayName }.trim().takeIf { it.isNotBlank() }

    /** The agent as a sentence's subject: its name, or "Your agent". */
    fun subject(agent: String?): String = agent ?: "Your agent"

    /** "just now", "4 min ago", "2h ago", "3 days ago". Null for no time at all. */
    fun ago(epochS: Long?, nowS: Long): String? {
        if (epochS == null || epochS <= 0L) return null
        val s = (nowS - epochS).coerceAtLeast(0L)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600}h ago"
            s < 2 * 86_400 -> "yesterday"
            else -> "${s / 86_400} days ago"
        }
    }

    /** How long ago something started: "started 25 min ago", "started just now". */
    private fun started(epochS: Long?, nowS: Long): String? = ago(epochS, nowS)?.let { "started $it" }

    /** What the sheet's headline says about the card, and how it should feel. */
    enum class Mood { WORKING, NEEDS_YOU, STUCK, WAITING, DONE }

    data class Headline(val text: String, val mood: Mood)

    private val FAILED_OUTCOMES = setOf("crashed", "timed_out", "spawn_failed", "gave_up")

    /** A card that hit a problem and isn't simply running again: its last failure is the news. */
    fun failed(task: KanbanTask): Boolean =
        task.lastFailureError.isNotBlank() && task.status != "done" && task.status != "running" && task.status != "review"

    /** The newest event of [kind], or null. */
    private fun lastEvent(detail: KanbanDetail?, vararg kind: String): KanbanEvent? =
        detail?.events?.filter { it.kind in kind }?.maxByOrNull { it.createdAt }

    /** The sentence under the title. One line of plain words, the time it refers to at the end. */
    fun headline(task: KanbanTask, detail: KanbanDetail?, agent: String?, nowS: Long): Headline {
        val who = subject(agent)
        fun withAgo(text: String, at: Long?): String = ago(at, nowS)?.let { "$text · $it" } ?: text
        val newestRun = detail?.runs?.maxByOrNull { it.id }
        return when {
            task.status == "review" -> Headline(
                withAgo("$who finished and wants your OK",
                    lastEvent(detail, "review_requested")?.createdAt ?: newestRun?.endedAt ?: task.startedAt),
                Mood.NEEDS_YOU,
            )
            task.needsYou -> {
                val at = lastEvent(detail, "blocked")?.createdAt ?: newestRun?.endedAt ?: task.startedAt
                when (task.blockKind) {
                    "capability" -> Headline(withAgo("$who is stuck: it needs something it can't do", at), Mood.NEEDS_YOU)
                    "needs_input" -> Headline(withAgo("$who has a question for you", at), Mood.NEEDS_YOU)
                    else -> Headline(withAgo("$who needs you before it can go on", at), Mood.NEEDS_YOU)
                }
            }
            failed(task) -> {
                val at = lastEvent(detail, *FAILED_OUTCOMES.toTypedArray())?.createdAt
                    ?: newestRun?.endedAt ?: task.startedAt
                val retrying = task.status == "ready" || task.status == "todo"
                Headline(withAgo(if (retrying) "$who hit a problem and will try again" else "$who hit a problem and stopped", at), Mood.STUCK)
            }
            task.status == "running" -> {
                val sign = lastEvent(detail, "heartbeat")?.createdAt
                val parts = listOfNotNull(
                    "$who is working on this",
                    started(task.startedAt, nowS),
                    ago(sign, nowS)?.takeIf { sign != null && sign != task.startedAt }?.let { "last sign of life $it" },
                )
                Headline(parts.joinToString(" · "), Mood.WORKING)
            }
            task.status == "blocked" -> when (task.blockKind) {
                "dependency" -> Headline("Waiting for another mission to finish first", Mood.WAITING)
                "transient" -> Headline("Paused for a moment. It carries on by itself", Mood.WAITING)
                else -> Headline(withAgo("Paused", lastEvent(detail, "blocked")?.createdAt), Mood.WAITING)
            }
            task.status == "ready" -> Headline("Ready. ${who} picks it up next", Mood.WAITING)
            task.status == "todo" -> Headline(
                if (agent != null || task.assignee.isNotBlank()) "Waiting its turn · for ${agent ?: "your agent"}" else "Waiting its turn",
                Mood.WAITING,
            )
            task.status == "triage" -> Headline("Waiting for a plan before anyone starts", Mood.WAITING)
            task.status == "scheduled" -> Headline("Scheduled to start later", Mood.WAITING)
            task.status == "done" -> Headline(withAgo("Done", task.completedAt), Mood.DONE)
            task.status == "archived" -> Headline("Archived", Mood.DONE)
            else -> Headline(task.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, Mood.WAITING)
        }
    }

    /** The one thing the card puts in front of you, boxed: [label] over [text]. */
    data class Focus(val label: String, val text: String, val error: Boolean = false)

    fun focus(task: KanbanTask, detail: KanbanDetail?): Focus? {
        val newestRun = detail?.runs?.maxByOrNull { it.id }
        val made = task.result.ifBlank { task.latestSummary }.ifBlank { newestRun?.summary.orEmpty() }
        return when {
            task.status == "review" -> made.takeIf { it.isNotBlank() }?.let { Focus("For your OK", it) }
                ?: task.ask.takeIf { it.isNotBlank() }?.let { Focus("What it asks", it) }
            task.needsYou -> task.ask.takeIf { it.isNotBlank() }?.let {
                Focus(if (task.blockKind == "needs_input") "The question" else "What it needs", it)
            }
            failed(task) -> Focus("What went wrong", task.lastFailureError, error = true)
            task.status == "done" -> made.takeIf { it.isNotBlank() }?.let { Focus("Result", it) }
            else -> task.body.ifBlank { task.bodyExcerpt }.takeIf { it.isNotBlank() }?.let { Focus("The task", it) }
        }
    }

    /**
     * The agent's own words on a review, shown as a quote under the headline — unless they only
     * repeat what the box already shows (the block reason is often the run's summary verbatim).
     */
    fun askQuote(task: KanbanTask, focus: Focus?): String? =
        task.ask.trim().takeIf {
            task.status == "review" && it.isNotBlank() && (focus == null || !sameWords(it, focus.text))
        }

    private fun sameWords(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        return x == y || x.startsWith(y.removeSuffix("…")) || y.startsWith(x.removeSuffix("…"))
    }

    /** The sheet's one filled button. */
    enum class Primary { APPROVE, REPLY_UNBLOCK, REPLY, WATCH, START, UNBLOCK, NONE }

    fun primary(task: KanbanTask, liveRun: KanbanRun?): Primary = when {
        task.status == "review" -> Primary.APPROVE
        task.needsYou && (task.status == "blocked" || task.status == "scheduled") -> Primary.REPLY_UNBLOCK
        task.needsYou -> Primary.REPLY
        task.status == "running" && liveRun?.sessionId != null -> Primary.WATCH
        task.status == "triage" || task.status == "todo" -> Primary.START
        task.status == "blocked" || task.status == "scheduled" -> Primary.UNBLOCK
        else -> Primary.NONE
    }

    /**
     * A run is live only while its card runs. A run whose end the gateway never stamped stays
     * "working…" forever otherwise, on a card already waiting on review or done.
     */
    fun isLive(run: KanbanRun, task: KanbanTask): Boolean = run.endedAt == null && task.status == "running"

    /** The run a running card is on now, if any. */
    fun liveRun(detail: KanbanDetail?, task: KanbanTask): KanbanRun? =
        detail?.runs?.filter { isLive(it, task) }?.maxByOrNull { it.id }

    /** One line of "What happened". */
    data class Moment(val atS: Long, val text: String)

    /**
     * The card's history in words, oldest first. Plumbing (spawns, heartbeats, worker
     * bookkeeping, links) is left to the Events list under Details; a run of the same words
     * in a row reads once.
     */
    fun timeline(detail: KanbanDetail, agent: String?): List<Moment> {
        val who = subject(agent)
        val needsYouNow = detail.task.needsYou
        val lastBlock = detail.events.filter { it.kind == "blocked" }.maxByOrNull { it.createdAt }
        val out = mutableListOf<Moment>()
        detail.events.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { e ->
            val text = when (e.kind) {
                "created" -> "Created"
                "specified" -> "Got its plan"
                "promoted", "promoted_manual" -> "Queued to start"
                "claimed" -> "$who started on it"
                "blocked" -> if (needsYouNow && e == lastBlock && detail.task.blockKind == "needs_input")
                    "Stopped to ask you something" else "Paused"
                "unblocked" -> "Back to work"
                "review_requested" -> "Finished and asked for your OK"
                "changes_requested" -> "Sent back with changes to make"
                "completed" -> "Finished"
                "crashed" -> "Stopped unexpectedly"
                "timed_out" -> "Ran out of time"
                "gave_up" -> "Gave up after several tries"
                "reclaimed" -> "Stopped and put back in line"
                "dependency_wait" -> "Waiting on another mission"
                "assigned" -> "Handed over"
                "scheduled" -> "Scheduled"
                "commented" -> "Note added"
                "edited" -> "Edited"
                "archived" -> "Archived"
                else -> null
            } ?: return@forEach
            if (out.lastOrNull()?.text != text) out += Moment(e.createdAt, text)
        }
        return out
    }
}
