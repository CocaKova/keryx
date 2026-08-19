package chat.keryx.app.domain.model

/**
 * The tool theater (2.4): what the agent is *doing* while a turn is in flight.
 *
 * Until now a Matrix turn showed a spinner and then, all at once, a finished answer with its
 * tool rows parsed back out of the committed text. The gateway has been firing the lifecycle
 * the whole time (`tool_progress_callback` in the agent core) — the Keryx side-channel just
 * never carried it. It does now, as `event: tool` frames, and this is the pure half: a state
 * machine over those frames with no Compose and no Matrix in it.
 *
 * Deliberately the same shape Talaria reads over its WS `turnEvents` (`Delegation`,
 * `GatewayChatRepository`'s subagent reducer), so a delegation looks like the same thing on
 * both clients instead of each inventing its own half-view.
 */
data class ToolBeat(
    val name: String,
    val preview: String = "",
    /** null while the call is in flight; true/false once it lands. */
    val ok: Boolean? = null,
    val ms: Long = 0L,
    /** A glimpse of the reason — sent for failures only (see `keryx_stream._attach_tool_callbacks`). */
    val result: String = "",
    /**
     * This call was still open when another one opened, so the model fired them in one breath.
     *
     * ⚠️ That is an observation about *announcement*, not about execution: the runtime may
     * still have run them one at a time. The renderer says "in one turn" for exactly that
     * reason — Talaria can say "in parallel" because its gateway tells it so; this channel
     * doesn't, and the weaker claim is the one that survives.
     */
    val concurrent: Boolean = false,
) {
    val running: Boolean get() = ok == null
}

/**
 * One delegated subagent, assembled from the `subagent.*` frames.
 *
 * A delegated child is not a session you can open — it runs inside the parent's turn and its
 * relay is not persisted — so this live view is the only window onto it. Fields keep their
 * last known value, because each event carries a different subset.
 */
data class Delegation(
    /** `subagent_id` when the gateway sends one, else the per-task fallback key. */
    val key: String,
    val goal: String = "",
    /** 0-based position in a fan-out; [taskCount] is how many wings went out together. */
    val taskIndex: Int = 0,
    val taskCount: Int = 1,
    val model: String = "",
    val depth: Int = 0,
    val state: DelegationState = DelegationState.RUNNING,
    /** Newest live line: the tool it just picked up, a thinking fragment, a batch summary. */
    val activity: String = "",
    val toolCount: Int = 0,
    val summary: String = "",
    val durationSeconds: Double? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val apiCalls: Int = 0,
    val filesRead: Int = 0,
    val filesWritten: Int = 0,
) {
    val running: Boolean get() = state == DelegationState.SPAWNING || state == DelegationState.RUNNING

    /** Every token the child burned — the number that makes delegation cost legible. */
    val totalTokens: Int get() = inputTokens + outputTokens + reasoningTokens
}

/** Gateway `status` on `subagent.complete`, plus the two states inferred from the lifecycle. */
enum class DelegationState {
    SPAWNING, RUNNING, DONE, FAILED, INTERRUPTED;

    companion object {
        fun fromWire(status: String?): DelegationState = when (status) {
            "completed", "success" -> DONE
            // The completion report speaks a wider vocabulary than `subagent.complete` does;
            // reading error/timeout as DONE would mark a dead subagent successful.
            "failed", "error", "timeout" -> FAILED
            "interrupted" -> INTERRUPTED
            // "unknown", and anything a later gateway invents: it finished, we can't say well.
            else -> DONE
        }
    }
}

/** One `event: tool` frame, already parsed off the wire. */
data class TheaterEvent(
    /** "start" · "end" · "sub". */
    val phase: String,
    /** For [phase] "sub": start · tool · complete · thinking · progress · spawn_requested. */
    val kind: String = "",
    val name: String = "",
    val preview: String = "",
    val ok: Boolean? = null,
    val ms: Long = 0L,
    val result: String = "",
    val child: String = "",
    // --- the delegation identity block + rollup, present on "sub" frames only ---
    val goal: String = "",
    val model: String = "",
    val status: String = "",
    val summary: String = "",
    val taskIndex: Int? = null,
    val taskCount: Int? = null,
    val depth: Int? = null,
    val toolCount: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val apiCalls: Int? = null,
    val filesRead: Int? = null,
    val filesWritten: Int? = null,
    val durationSeconds: Double? = null,
)

/** Everything the theater knows about the turn in flight. */
data class TheaterState(
    val beats: List<ToolBeat> = emptyList(),
    /** Insertion-ordered so a fan-out keeps the order it went out in. */
    val delegations: List<Delegation> = emptyList(),
) {
    val isEmpty: Boolean get() = beats.isEmpty() && delegations.isEmpty()
}

object Theater {
    /**
     * A long turn can run dozens of tools; the overlay only ever shows the tail, and an
     * unbounded list would grow for the life of the turn behind it.
     */
    const val MAX_BEATS = 40

    fun reduce(state: TheaterState, ev: TheaterEvent): TheaterState = when (ev.phase) {
        "start" -> state.copy(beats = state.beats.open(ToolBeat(name = ev.name.orTool(), preview = ev.preview)))

        // Correlated by ORDER, not by id: `tool.completed` carries no call id. The executor
        // emits completions in the same order it emitted the starts, so this closes the OLDEST
        // open row — FIFO, not a stack.
        //
        // ⚠️ A model batches calls: `read_file` A and `read_file` B can both open before either
        // closes (observed live, 2026-08-19 — two starts, then two ends). Closing newest-first
        // there hands A's success to B and B's failure to A, which is worse than showing
        // nothing. The name is a tiebreak, not a key.
        "end" -> state.copy(beats = state.beats.close(ev))

        "sub" -> state.copy(delegations = state.delegations.fold(ev))

        else -> state
    }

    private fun String.orTool(): String = ifBlank { "tool" }

    /**
     * Opening a call while another is still open is the only evidence this channel gives that
     * the model fired them together — so both ends of that overlap are marked, not just the
     * newcomer.
     */
    private fun List<ToolBeat>.open(beat: ToolBeat): List<ToolBeat> {
        val overlaps = any { it.running }
        val marked = if (overlaps) map { if (it.running) it.copy(concurrent = true) else it } else this
        return (marked + beat.copy(concurrent = overlaps))
            .let { if (it.size > MAX_BEATS) it.takeLast(MAX_BEATS) else it }
    }

    private fun List<ToolBeat>.close(ev: TheaterEvent): List<ToolBeat> {
        val byName = indexOfFirst { it.running && it.name == ev.name }
        val i = if (byName >= 0) byName else indexOfFirst { it.running }
        if (i < 0) return this
        return toMutableList().also {
            it[i] = it[i].copy(ok = ev.ok ?: true, ms = ev.ms, result = ev.result)
        }
    }

    /**
     * Every `subagent.*` frame carries the same identity block and adds what only it knows, so
     * identity folds in once and the kind decides state and activity line — the same reducer
     * Talaria runs over its own wire.
     */
    private fun List<Delegation>.fold(ev: TheaterEvent): List<Delegation> {
        val key = ev.child.ifBlank { "task-${ev.taskIndex ?: 0}" }
        val i = indexOfFirst { it.key == key }
        val prev = if (i >= 0) this[i] else Delegation(key = key)
        val withIdentity = prev.copy(
            goal = ev.goal.ifBlank { prev.goal },
            taskIndex = ev.taskIndex ?: prev.taskIndex,
            taskCount = ev.taskCount ?: prev.taskCount,
            model = ev.model.ifBlank { prev.model },
            depth = ev.depth ?: prev.depth,
            toolCount = ev.toolCount ?: prev.toolCount,
        )
        val next = when (ev.kind) {
            "spawn_requested" -> withIdentity.copy(state = DelegationState.SPAWNING)
            "start" -> withIdentity.copy(state = DelegationState.RUNNING, activity = "")
            // The child's own tool: name it, with its preview as the object.
            "tool" -> withIdentity.copy(
                state = DelegationState.RUNNING,
                activity = listOf(ev.name, ev.preview).filter { it.isNotBlank() }.joinToString(" "),
            )
            "thinking", "progress" -> withIdentity.copy(
                state = DelegationState.RUNNING,
                activity = ev.preview.ifBlank { prev.activity },
            )
            "complete" -> withIdentity.copy(
                state = DelegationState.fromWire(ev.status.ifBlank { null }),
                activity = "",
                summary = ev.summary.ifBlank { ev.preview },
                durationSeconds = ev.durationSeconds ?: prev.durationSeconds,
                inputTokens = ev.inputTokens ?: prev.inputTokens,
                outputTokens = ev.outputTokens ?: prev.outputTokens,
                reasoningTokens = ev.reasoningTokens ?: prev.reasoningTokens,
                apiCalls = ev.apiCalls ?: prev.apiCalls,
                filesRead = ev.filesRead ?: prev.filesRead,
                filesWritten = ev.filesWritten ?: prev.filesWritten,
            )
            // A kind this client doesn't know still folds its identity in, so a later gateway
            // adding one can't blank a wing.
            else -> withIdentity
        }
        return if (i >= 0) toMutableList().also { it[i] = next } else this + next
    }

    /**
     * The beats grouped for display: a run of calls that overlapped is one batch, everything
     * else stands alone. Structural, not decorative — the fact being shown is "these ran
     * together".
     */
    fun batches(beats: List<ToolBeat>): List<List<ToolBeat>> {
        val out = mutableListOf<MutableList<ToolBeat>>()
        for (beat in beats) {
            val last = out.lastOrNull()
            if (beat.concurrent && last != null && last.last().concurrent) last.add(beat)
            else out.add(mutableListOf(beat))
        }
        return out
    }
}
