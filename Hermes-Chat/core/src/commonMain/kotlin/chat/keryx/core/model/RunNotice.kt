package chat.keryx.core.model

/**
 * What an agent is doing right now, as the shade tells it (2.11.6).
 *
 * Until now a running turn reached the shade as a message alert every few seconds: turn traffic
 * re-stamps the roster, a re-stamped row looked like new activity, and each one buzzed with
 * whatever the latest row happened to be. A turn is ONE thing happening, so it gets one silent
 * notice that changes in place — who is working, on what, for how long — and the alert waits
 * for the turn's end (see [AlertPolicy]). This file is the pure half: which wire events move
 * the state, and what words the state earns. The platform side only draws it.
 */
data class RunActivity(
    val sessionId: String,
    val startedAt: Long,
    val phase: Phase = Phase.THINKING,
    /** Tools in flight, oldest first — calls can run in parallel, and one returning must not
     *  read as the agent having gone back to thinking while another is still out. */
    val running: List<RunningTool> = emptyList(),
    val toolsDone: Int = 0,
) {
    enum class Phase { THINKING, WRITING, TOOL }

    data class RunningTool(val toolId: String, val name: String, val target: String)

    /** The call the notice names: the newest one still out. */
    val tool: RunningTool? get() = running.lastOrNull()
}

object RunActivities {

    /**
     * [prev] moved by one gateway event. Returns [prev] ITSELF when nothing the shade shows
     * changed — this runs per streaming delta, and callers skip the publish on identity.
     */
    fun reduce(
        prev: RunActivity,
        eventType: String,
        toolId: String = "",
        toolName: String = "",
        toolContext: String = "",
    ): RunActivity = when (eventType) {
        "reasoning.delta", "message.start" -> prev.inPhase(RunActivity.Phase.THINKING)
        "message.delta" -> prev.inPhase(RunActivity.Phase.WRITING)
        // The model is still writing the call's arguments: the name is known, the target is not.
        "tool.generating", "tool.start" -> {
            val id = toolId.ifBlank { toolName }
            val call = RunActivity.RunningTool(id, toolName, ToolGrammar.targetOf(toolName, toolContext))
            val others = prev.running.filterNot { it.toolId == id || (it.toolId == it.name && it.name == toolName) }
            prev.copy(phase = RunActivity.Phase.TOOL, running = others + call)
        }
        "tool.complete" -> {
            val id = toolId.ifBlank { toolName }
            val left = prev.running.filterNot { it.toolId == id || (it.toolId == it.name && it.name == toolName) }
            prev.copy(
                phase = if (left.isEmpty()) RunActivity.Phase.THINKING else RunActivity.Phase.TOOL,
                running = left,
                toolsDone = prev.toolsDone + 1,
            )
        }
        else -> prev
    }

    // A tool out in the world outranks the prose around it: tokens streaming beside a running
    // call must not flicker the notice between "Writing" and the call.
    private fun RunActivity.inPhase(next: RunActivity.Phase): RunActivity =
        if (phase == next || running.isNotEmpty()) this else copy(phase = next)

    /** One line: what the agent is doing, in the transcript's own tool vocabulary. */
    fun line(a: RunActivity): String {
        val call = a.tool
        return when {
            call != null -> {
                val verb = ToolGrammar.verbOf(call.name)
                listOf(verb.glyph, verb.present, call.target).filter { it.isNotBlank() }.joinToString(" ")
            }
            a.phase == RunActivity.Phase.WRITING -> "Writing the reply…"
            else -> "Thinking…"
        }
    }
}

/** One running session, as the notice needs it. */
data class RunSubject(
    val sessionId: String,
    /** Who is working: the label of the profile that owns the session, as this install named
     *  it. Blank when the roster has not said yet — the notice then names the session alone. */
    val agent: String,
    /** The session's title; equal to [agent] in a Bot Chat, where it adds nothing. */
    val session: String,
    /** The palette key, so the notice wears the colour the transcript gives this agent. */
    val colorKey: String,
    val activity: RunActivity,
    val plan: TodoPlan? = null,
)

/**
 * The run notice, decided. ONE notice however many agents are working — a second running
 * session adds a line to it, never a second entry in the shade.
 */
data class RunNotice(
    val title: String,
    val text: String,
    /** Which session, when the title names the agent and not the room. */
    val subText: String = "",
    /** Expanded detail under [text]; for several runs, one line per session. */
    val lines: List<String>,
    /** The session a tap opens and Stop interrupts — null when more than one is running. */
    val sessionId: String?,
    val colorKey: String?,
    /** The clock's zero: the oldest run's start. */
    val startedAt: Long,
    /** The agent's own plan as progress; 0 total = no plan, draw an indeterminate bar. */
    val planDone: Int = 0,
    val planTotal: Int = 0,
)

object RunNotices {
    private const val LINE_MAX = 90

    fun compose(subjects: List<RunSubject>): RunNotice? {
        if (subjects.isEmpty()) return null
        val ordered = subjects.sortedBy { it.activity.startedAt }
        if (ordered.size == 1) {
            val s = ordered.first()
            val plan = s.plan?.takeIf { it.total > 0 && !it.allDone }
            val step = plan?.active?.let { "Step ${plan.done + 1} of ${plan.total} · ${it.content}".take(LINE_MAX) }
            val tally = s.activity.toolsDone.takeIf { it > 0 }?.let { if (it == 1) "1 tool call so far" else "$it tool calls so far" }
            return RunNotice(
                title = if (s.agent.isNotBlank()) "${Heralds.SIGIL} ${s.agent} is working" else "${Heralds.SIGIL} Working · ${s.session}",
                subText = s.session.takeIf { s.agent.isNotBlank() && it != s.agent }.orEmpty(),
                text = RunActivities.line(s.activity).take(LINE_MAX),
                lines = listOfNotNull(step, tally),
                sessionId = s.sessionId,
                colorKey = s.colorKey,
                startedAt = s.activity.startedAt,
                planDone = plan?.done ?: 0,
                planTotal = plan?.total ?: 0,
            )
        }
        val lines = ordered.map { "${it.agent.ifBlank { it.session }} — ${RunActivities.line(it.activity)}".take(LINE_MAX) }
        return RunNotice(
            title = "${Heralds.SIGIL} ${ordered.size} agents working",
            text = lines.last(),
            lines = lines,
            sessionId = null,
            colorKey = null,
            startedAt = ordered.first().activity.startedAt,
        )
    }
}

/**
 * When a message earns an ALERT (sound, heads-up) as opposed to silence.
 *
 * Two rules, both learned from the shade filling up: nothing alerts while its session's turn is
 * still running (the run notice is already saying so, and the words are not final), and the
 * same message never alerts twice (the roster re-stamps a row for many reasons — a read mark,
 * a list refresh, the server catching up on what the phone already knew — and each one used to
 * re-announce the row's last message).
 */
object AlertPolicy {

    /** What makes two sightings the same message. Content, not id: a live row and the same row
     *  re-read from the gateway carry different ids and, often, different stamps. */
    fun keyOf(m: Message): String =
        "${m.sender}|${m.mediaKind}|${m.fileName}|${m.content.trim().hashCode()}|${m.content.trim().length}"

    enum class Verdict { ALERT, SILENT_BUSY, SILENT_SEEN, SILENT_MINE, SILENT_EMPTY }

    fun decide(last: Message, busy: Boolean, lastAlertedKey: String?): Verdict = when {
        last.sender == SenderType.ME -> Verdict.SILENT_MINE
        busy || last.isStreaming -> Verdict.SILENT_BUSY
        last.content.isBlank() && last.mediaKind == null && last.failure == null -> Verdict.SILENT_EMPTY
        keyOf(last) == lastAlertedKey -> Verdict.SILENT_SEEN
        else -> Verdict.ALERT
    }
}
