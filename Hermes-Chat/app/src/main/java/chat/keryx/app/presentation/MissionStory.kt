package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient.KanbanDetail
import chat.keryx.app.data.remote.HermesStreamClient.KanbanEvent
import chat.keryx.app.data.remote.HermesStreamClient.KanbanRun
import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import chat.keryx.app.KeryxText
import chat.keryx.app.R
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
     * Where the story's words come from (res/values/strings_pass2.xml and friends): the app's
     * language at run time, the English file in the plain-JVM tests. [get] gives a string's
     * template, [plural] a plural's template for a count; the story fills the blanks.
     */
    class Words(val get: (Int) -> String, val plural: (Int, Int) -> String) {
        fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) get(id) else get(id).format(*args)
        fun count(id: Int, n: Int): String = plural(id, n).format(n)

        companion object {
            val App = Words({ KeryxText.get(it) }, { id, n -> KeryxText.pluralTemplate(id, n) })
        }
    }

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
    fun subject(agent: String?, words: Words = Words.App): String = agent ?: words.s(R.string.agent_your_agent)

    /** "just now", "4 min ago", "2h ago", "3 days ago". Null for no time at all. */
    fun ago(epochS: Long?, nowS: Long, words: Words = Words.App): String? {
        if (epochS == null || epochS <= 0L) return null
        val s = (nowS - epochS).coerceAtLeast(0L)
        return when {
            s < 60 -> words.s(R.string.work_age_just_now)
            s < 3600 -> words.count(R.plurals.p2_story_min_ago, (s / 60).toInt())
            s < 86_400 -> words.s(R.string.work_ago_h, (s / 3600).toInt())
            s < 2 * 86_400 -> words.s(R.string.p2_story_yesterday)
            else -> words.count(R.plurals.p2_story_days_ago, (s / 86_400).toInt())
        }
    }

    /** How long ago something started: "started 25 min ago", "started just now". */
    private fun started(epochS: Long?, nowS: Long, words: Words): String? =
        ago(epochS, nowS, words)?.let { words.s(R.string.p2_story_started, it) }

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
    fun headline(task: KanbanTask, detail: KanbanDetail?, agent: String?, nowS: Long, words: Words = Words.App): Headline {
        val who = subject(agent, words)
        fun w(id: Int, vararg args: Any) = words.s(id, *args)
        fun withAgo(text: String, at: Long?): String = ago(at, nowS, words)?.let { "$text · $it" } ?: text
        val newestRun = detail?.runs?.maxByOrNull { it.id }
        return when {
            task.status == "review" -> Headline(
                withAgo(w(R.string.p2_story_review, who),
                    lastEvent(detail, "review_requested")?.createdAt ?: newestRun?.endedAt ?: task.startedAt),
                Mood.NEEDS_YOU,
            )
            task.needsYou -> {
                val at = lastEvent(detail, "blocked")?.createdAt ?: newestRun?.endedAt ?: task.startedAt
                when (task.blockKind) {
                    "capability" -> Headline(withAgo(w(R.string.p2_story_stuck_capability, who), at), Mood.NEEDS_YOU)
                    "needs_input" -> Headline(withAgo(w(R.string.p2_story_question, who), at), Mood.NEEDS_YOU)
                    else -> Headline(withAgo(w(R.string.p2_story_needs_you, who), at), Mood.NEEDS_YOU)
                }
            }
            failed(task) -> {
                val at = lastEvent(detail, *FAILED_OUTCOMES.toTypedArray())?.createdAt
                    ?: newestRun?.endedAt ?: task.startedAt
                val retrying = task.status == "ready" || task.status == "todo"
                Headline(withAgo(w(if (retrying) R.string.p2_story_retrying else R.string.p2_story_stopped, who), at), Mood.STUCK)
            }
            task.status == "running" -> {
                val sign = lastEvent(detail, "heartbeat")?.createdAt
                val parts = listOfNotNull(
                    w(R.string.p2_story_working, who),
                    started(task.startedAt, nowS, words),
                    ago(sign, nowS, words)?.takeIf { sign != null && sign != task.startedAt }?.let { w(R.string.p2_story_last_sign, it) },
                )
                Headline(parts.joinToString(" · "), Mood.WORKING)
            }
            task.status == "blocked" -> when (task.blockKind) {
                "dependency" -> Headline(w(R.string.p2_story_waiting_dependency), Mood.WAITING)
                "transient" -> Headline(w(R.string.p2_story_paused_moment), Mood.WAITING)
                else -> Headline(withAgo(w(R.string.p2_story_paused), lastEvent(detail, "blocked")?.createdAt), Mood.WAITING)
            }
            task.status == "ready" -> Headline(w(R.string.p2_story_ready, who), Mood.WAITING)
            task.status == "todo" -> Headline(
                when {
                    agent != null -> w(R.string.p2_story_waiting_turn_for, agent)
                    task.assignee.isNotBlank() -> w(R.string.p2_story_waiting_turn_for_yours)
                    else -> w(R.string.p2_story_waiting_turn)
                },
                Mood.WAITING,
            )
            task.status == "triage" -> Headline(w(R.string.p2_story_triage), Mood.WAITING)
            task.status == "scheduled" -> Headline(w(R.string.p2_story_scheduled), Mood.WAITING)
            task.status == "done" -> Headline(withAgo(w(R.string.work_lane_done), task.completedAt), Mood.DONE)
            task.status == "archived" -> Headline(w(R.string.app_archived), Mood.DONE)
            else -> Headline(task.status.replace('_', ' ').replaceFirstChar { it.uppercase() }, Mood.WAITING)
        }
    }

    /** The one thing the card puts in front of you, boxed: [label] over [text]. */
    data class Focus(val label: String, val text: String, val error: Boolean = false)

    fun focus(task: KanbanTask, detail: KanbanDetail?, words: Words = Words.App): Focus? {
        fun w(id: Int) = words.s(id)
        val newestRun = detail?.runs?.maxByOrNull { it.id }
        val made = task.result.ifBlank { task.latestSummary }.ifBlank { newestRun?.summary.orEmpty() }
        return when {
            task.status == "review" -> made.takeIf { it.isNotBlank() }?.let { Focus(w(R.string.p2_story_focus_ok), it) }
                ?: task.ask.takeIf { it.isNotBlank() }?.let { Focus(w(R.string.p2_story_focus_asks), it) }
            task.needsYou -> task.ask.takeIf { it.isNotBlank() }?.let {
                Focus(w(if (task.blockKind == "needs_input") R.string.p2_story_focus_question else R.string.p2_story_focus_needs), it)
            }
            failed(task) -> Focus(w(R.string.p2_story_focus_wrong), task.lastFailureError, error = true)
            task.status == "done" -> made.takeIf { it.isNotBlank() }?.let { Focus(w(R.string.work_details_result), it) }
            else -> task.body.ifBlank { task.bodyExcerpt }.takeIf { it.isNotBlank() }?.let { Focus(w(R.string.p2_story_focus_task), it) }
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
    fun timeline(detail: KanbanDetail, agent: String?, words: Words = Words.App): List<Moment> {
        val who = subject(agent, words)
        fun w(id: Int, vararg args: Any) = words.s(id, *args)
        val needsYouNow = detail.task.needsYou
        val lastBlock = detail.events.filter { it.kind == "blocked" }.maxByOrNull { it.createdAt }
        val out = mutableListOf<Moment>()
        detail.events.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { e ->
            val text = when (e.kind) {
                "created" -> w(R.string.p2_story_ev_created)
                "specified" -> w(R.string.p2_story_ev_specified)
                "promoted", "promoted_manual" -> w(R.string.p2_story_ev_queued)
                "claimed" -> w(R.string.p2_story_ev_claimed, who)
                "blocked" -> if (needsYouNow && e == lastBlock && detail.task.blockKind == "needs_input")
                    w(R.string.p2_story_ev_asked) else w(R.string.p2_story_paused)
                "unblocked" -> w(R.string.p2_story_ev_unblocked)
                "review_requested" -> w(R.string.p2_story_ev_review)
                "changes_requested" -> w(R.string.p2_story_ev_changes)
                "completed" -> w(R.string.p2_story_ev_completed)
                "crashed" -> w(R.string.p2_story_ev_crashed)
                "timed_out" -> w(R.string.p2_story_ev_timed_out)
                "gave_up" -> w(R.string.p2_story_ev_gave_up)
                "reclaimed" -> w(R.string.p2_story_ev_reclaimed)
                "dependency_wait" -> w(R.string.p2_story_ev_dependency)
                "assigned" -> w(R.string.p2_story_ev_assigned)
                "scheduled" -> w(R.string.work_lane_scheduled)
                "commented" -> w(R.string.p2_story_ev_commented)
                "edited" -> w(R.string.p2_story_ev_edited)
                "archived" -> w(R.string.app_archived)
                else -> null
            } ?: return@forEach
            if (out.lastOrNull()?.text != text) out += Moment(e.createdAt, text)
        }
        return out
    }
}
