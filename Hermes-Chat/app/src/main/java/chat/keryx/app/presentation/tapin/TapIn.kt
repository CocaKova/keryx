package chat.keryx.app.presentation.tapin

import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.SessionStatus
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolGrammar

/**
 * Tap-In (2.12): the turn in flight, projected for a full screen.
 *
 * The transcript already draws every fact a live turn produces — one theater row per call, a
 * wing per subagent, the working banner, the ring — and draws them quietly on purpose. This is
 * the loud view of the same facts: no new producer, no second reducer, no re-worded tool names.
 * One [TapInState] is a pure function of what the chat screen already holds, so a frozen state
 * after the turn ends is simply the last live one, and a test can hold the projection to the
 * mock corpus without a Compose runtime.
 */
data class TapInState(
    /** The agent is still working on this turn. */
    val running: Boolean,
    /** The one line, large: a compaction while the gateway reports one,
     *  else the open call in the shared grammar's present tense, else the working label. */
    val headline: String,
    /** Under the headline: the turn's shape in numbers, or the crew's count when there is one. */
    val subline: String,
    /** The thinking, tailed to its last few lines; blank when the model sends none. */
    val mind: String,
    /** The answer so far, tailed; blank until the first answer token. */
    val answer: String,
    /** The tool timeline, oldest first — the same [ToolCall]s the transcript's run draws. */
    val rail: List<ToolCall>,
    /** The helpers, flying first, then landed in the order they went out. */
    val crew: List<CrewMember>,
    val elapsedMs: Long,
    val toolCount: Int,
    val openCount: Int,
    val failedCount: Int,
    val usedTokens: Long?,
    val maxTokens: Long?,
    val model: String,
    /** Live side-channel throughput, chars/s; 0 when unknown. */
    val charsPerSec: Float,
) {
    val crewLanded: Int get() = crew.count { !it.run.running }
    val hasCrew: Boolean get() = crew.isNotEmpty()
    val isEmpty: Boolean get() = rail.isEmpty() && crew.isEmpty() && mind.isBlank() && answer.isBlank()
}

/**
 * One helper on the deck. [role] is the first clause of its goal, short enough for a card;
 * [glyph] is the kind of helper, so a reviewer and a delegate read differently at a glance.
 */
data class CrewMember(
    val run: Delegation,
    val role: String,
    val glyph: String,
) {
    /** 1-based position in its fan-out, or 0 for a lone helper. */
    val ordinal: Int get() = if (run.taskCount > 1) run.taskIndex + 1 else 0
    val failed: Boolean get() = run.state == DelegationState.FAILED
    val interrupted: Boolean get() = run.state == DelegationState.INTERRUPTED
}

object TapIn {
    /** How many lines of thought the mind region keeps on screen. */
    const val MIND_LINES = 6

    /** How much of the answer the screen tails (chars). */
    const val ANSWER_TAIL = 1_200

    /** A role name longer than this is a goal, not a name. */
    const val ROLE_MAX = 44

    fun project(
        running: Boolean,
        status: SessionStatus?,
        workLabel: String,
        calls: List<ToolCall>,
        delegations: List<Delegation>,
        reasoning: String,
        answer: String,
        startedAtMs: Long?,
        nowMs: Long,
        usedTokens: Long? = null,
        maxTokens: Long? = null,
        model: String = "",
        charsPerSec: Float = 0f,
    ): TapInState {
        val open = calls.count { it.running }
        val failed = calls.count { it.failed }
        val crew = crewOf(delegations)
        val headline = headlineOf(running, status, workLabel, calls, crew)
        val subline = sublineOf(running, calls.size, open, failed, crew)
        val elapsed = if (startedAtMs != null && startedAtMs > 0L && nowMs > startedAtMs) nowMs - startedAtMs else 0L
        return TapInState(
            running = running,
            headline = headline,
            subline = subline,
            mind = tailLines(reasoning, MIND_LINES),
            answer = answer.takeLast(ANSWER_TAIL),
            rail = calls,
            crew = crew,
            elapsedMs = elapsed,
            toolCount = calls.size,
            openCount = open,
            failedCount = failed,
            usedTokens = usedTokens,
            maxTokens = maxTokens,
            model = model,
            charsPerSec = charsPerSec,
        )
    }

    /**
     * A compaction wins (it is the one long operation worth a headline), then the newest open
     * call in the present tense, then the label the working banner already shows. A settled turn says so, and says how it went.
     */
    fun headlineOf(
        running: Boolean,
        status: SessionStatus?,
        workLabel: String,
        calls: List<ToolCall>,
        crew: List<CrewMember>,
    ): String {
        if (!running) {
            val failed = calls.count { it.failed } + crew.count { it.failed }
            return if (failed > 0) "Landed · $failed failed" else "Landed"
        }
        // Only a compaction: every other status line (a heartbeat, a goal verdict, a warning)
        // is a one-off with no end edge, and it held the headline over the work that followed.
        status?.takeIf { it.isCompacting }?.headline?.takeIf { it.isNotBlank() }?.let { return it }
        calls.lastOrNull { it.running }?.let {
            return ToolGrammar.title(it.name, ToolGrammar.targetOf(it.name, it.context), running = true)
        }
        val flying = crew.count { it.run.running }
        if (flying > 0 && calls.none { it.running }) {
            return if (flying == 1) "Waiting on a helper" else "Waiting on $flying helpers"
        }
        return workLabel.ifBlank { "Working" }
    }

    fun sublineOf(running: Boolean, tools: Int, open: Int, failed: Int, crew: List<CrewMember>): String {
        val parts = mutableListOf<String>()
        if (tools > 0) {
            parts += buildString {
                append(tools).append(if (tools == 1) " tool" else " tools")
                if (running && open > 0) append(" · ").append(open).append(" open")
                if (failed > 0) append(" · ").append(failed).append(" failed")
            }
        }
        if (crew.isNotEmpty()) {
            val landed = crew.count { !it.run.running }
            parts += when {
                crew.size == 1 && landed == 0 -> "1 helper flying"
                crew.size == 1 -> "1 helper landed"
                else -> "$landed of ${crew.size} helpers landed"
            }
        }
        return parts.joinToString(" · ")
    }

    /** Flying first — they are the news — then landed, each half in dispatch order. */
    fun crewOf(delegations: List<Delegation>): List<CrewMember> {
        val members = delegations.map { CrewMember(run = it, role = roleOf(it.goal), glyph = glyphOf(it)) }
        return members.filter { it.run.running } + members.filter { !it.run.running }
    }

    /**
     * A name for the card: the goal's first clause, capitalised, ended before it becomes a
     * paragraph. "Review the diff for correctness bugs; report each with a file and line" is
     * "Review the diff for correctness bugs". Blank goals get the kind.
     */
    fun roleOf(goal: String): String {
        val one = goal.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (one.isEmpty()) return "Delegated task"
        val cut = one.indexOfFirst { it == ';' || it == ':' || it == '—' || it == '–' || it == '(' }
        var clause = if (cut > 0) one.substring(0, cut) else one
        // A sentence end inside the clause ends the name too; a trailing one is dropped.
        val period = clause.indexOf(". ")
        if (period > 0) clause = clause.substring(0, period)
        clause = clause.trimEnd('.', ' ', ',')
        if (clause.length > ROLE_MAX) {
            val space = clause.lastIndexOf(' ', ROLE_MAX)
            clause = (if (space > ROLE_MAX / 2) clause.substring(0, space) else clause.substring(0, ROLE_MAX)) + "…"
        }
        return clause.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * The kind of helper, from what its goal says it is for. The wire carries no role, so this
     * is the goal read as a person would: a reviewer reviews, a researcher searches, a tester
     * tests, and the rest are hands. Only the glyph rides on it — never a re-worded goal.
     */
    fun glyphOf(run: Delegation): String {
        val g = run.goal.lowercase()
        return when {
            g.contains("review") || g.contains("audit") || g.contains("verify") -> "⚖"
            g.contains("research") || g.contains("search") || g.contains("find") || g.contains("look up") -> "⌕"
            g.contains("test") -> "⚗"
            g.contains("write") || g.contains("draft") || g.contains("document") -> "✎"
            g.contains("fix") || g.contains("implement") || g.contains("build") || g.contains("patch") -> "⚒"
            else -> "⑂"
        }
    }

    /** The last [n] non-blank lines, joined — the mind region's window. */
    fun tailLines(text: String, n: Int): String {
        if (text.isBlank()) return ""
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        return lines.takeLast(n).joinToString("\n")
    }

    /** m:ss, or h:mm:ss past the hour. */
    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun compact(n: Long): String = when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0).trimEnd('0').trimEnd('.')}M"
        n >= 10_000 -> "${(n + 500) / 1000}k"
        n >= 1_000 -> "${"%.1f".format(n / 1000.0).trimEnd('0').trimEnd('.')}k"
        else -> n.toString()
    }
}
