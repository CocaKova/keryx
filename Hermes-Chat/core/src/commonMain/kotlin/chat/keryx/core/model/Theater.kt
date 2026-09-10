package chat.keryx.core.model

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
/**
 * One delegated subagent, assembled from the `subagent.*` frames.
 *
 * A delegated child is not a session you can open — it runs inside the parent's turn and its
 * relay is not persisted — so this live view is the only window onto it. Fields keep their
 * last known value, because each event carries a different subset.
 */
/**
 * One thing a subagent did, kept.
 *
 * The `subagent.*` frames have always carried the child's whole working history — a `tool`
 * frame per call it picked up, a `progress` frame per note it filed. [Delegation.activity]
 * kept only the newest of them and overwrote the rest, so the record streamed onto the phone
 * and straight back off it: by the time a wing landed, everything it had said on the way was
 * gone. The parent's own calls have been ring-buffered since 2.4 ([Theater.MAX_BEATS]); this
 * is the same buffer, for the child.
 *
 * Nothing new on the wire — these frames were already arriving and being dropped.
 */
data class DelegationBeat(
    /** "tool" or "progress". Thinking fragments drive the activity line and are NOT kept:
     *  they are texture, they arrive per delta, and forty of them would evict the actual work. */
    val kind: String,
    /** The tool it picked up, when [kind] is "tool". */
    val name: String = "",
    /** Its object — the tool's preview, or the progress note itself. */
    val text: String = "",
) {
    /** How the wing and the sheet both say it: name then object, whichever exist. */
    val line: String get() =
        if (name.isBlank()) text else if (text.isBlank()) name else "$name $text"
}

data class Delegation(
    /** `subagent_id` when the gateway sends one, else the per-task fallback key. */
    val key: String,
    val goal: String = "",
    /** 0-based position in a fan-out; [taskCount] is how many wings went out together. */
    val taskIndex: Int = 0,
    val taskCount: Int = 1,
    val model: String = "",
    /** The child's own stored session — what "open this subagent" opens. Blank on older
     *  gateways, and the wing simply isn't tappable then. */
    val sessionId: String = "",
    val depth: Int = 0,
    val state: DelegationState = DelegationState.RUNNING,
    /** Newest live line: the tool it just picked up, a thinking fragment, a batch summary.
     *  Cleared when the wing settles — the enduring record is [trail]. */
    val activity: String = "",
    /** Everything it did, oldest first, capped at [Theater.MAX_TRAIL]. Survives completion,
     *  because "what did this subagent actually do" is a question asked mostly in the past
     *  tense. Empty for a wing parsed out of a landed [DelegationReport], which never saw the
     *  live frames — that one opens its stored session instead. */
    val trail: List<DelegationBeat> = emptyList(),
    /** When this client first heard of the wing, by its own clock; 0 when it never did (a
     *  parsed report, a restart). Not the gateway's start time and not claimed to be: it is
     *  what lets a flying wing show a ticking elapsed instead of no time at all. */
    val startedAtMs: Long = 0L,
    val toolCount: Int = 0,
    val summary: String = "",
    val durationSeconds: Double? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val apiCalls: Int = 0,
    /** File names, when the wire carries them (the direct transport's rollup does). */
    val filesRead: List<String> = emptyList(),
    val filesWritten: List<String> = emptyList(),
    /** Count-only wire — the Matrix side-channel sends counts, not names. Read
     *  [filesReadN]/[filesWrittenN], which take whichever form is known. */
    val filesReadCount: Int = 0,
    val filesWrittenCount: Int = 0,
) {
    val filesReadN: Int get() = if (filesRead.isNotEmpty()) filesRead.size else filesReadCount
    val filesWrittenN: Int get() = if (filesWritten.isNotEmpty()) filesWritten.size else filesWrittenCount

    val running: Boolean get() = state == DelegationState.SPAWNING || state == DelegationState.RUNNING

    /** Every token the child burned — the number that makes delegation cost legible. */
    val totalTokens: Int get() = inputTokens + outputTokens + reasoningTokens

    /**
     * Seconds on the clock: the gateway's own number once it has landed, this client's count
     * while it flies.
     *
     * `duration_seconds` only rides `subagent.complete`, so until 2.11 a running subagent
     * showed no time whatsoever — a child five seconds in and one wedged for four minutes read
     * identically. Falls back to whatever is known when the clock isn't (pass `nowMs = 0`),
     * so a caller with no clock is never worse off than before.
     */
    fun elapsedSeconds(nowMs: Long): Double? = when {
        !running -> durationSeconds
        startedAtMs > 0L && nowMs > startedAtMs -> (nowMs - startedAtMs) / 1000.0
        else -> durationSeconds
    }

    /** Whether there is anything to show for it — a stored session, or the live record. */
    val hasRecord: Boolean get() = sessionId.isNotBlank() || trail.isNotEmpty()
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
    // --- edit diffs, present on "diff" frames only ---
    val diff: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val truncated: Boolean = false,
    // --- the delegation identity block + rollup, present on "sub" frames only ---
    val goal: String = "",
    val sessionId: String = "",
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
    /** The turn's tool calls in announcement order — the same [ToolCall] every producer speaks
     *  (the beat vocabulary folded into it; a live row is simply `status = EXECUTING`). */
    val beats: List<ToolCall> = emptyList(),
    /** Insertion-ordered so a fan-out keeps the order it went out in. */
    val delegations: List<Delegation> = emptyList(),
    /** Monotonic id source for announced-together batches — see [Theater.reduce]'s open. */
    val batchSeq: Int = 0,
) {
    val isEmpty: Boolean get() = beats.isEmpty() && delegations.isEmpty()
}

object Theater {
    /**
     * A long turn can run dozens of tools; the overlay only ever shows the tail, and an
     * unbounded list would grow for the life of the turn behind it.
     */
    const val MAX_BEATS = 40

    /** The same ceiling for one child's own record. A subagent that ran 200 tools is a
     *  subagent whose first 160 tools are no longer the question. */
    const val MAX_TRAIL = 40

    /**
     * @param nowMs the client's wall clock, for stamping when a wing was first seen. Pass 0
     *   (the default) to reduce without a clock: every existing caller and every test does,
     *   and the only thing they lose is the live elapsed counter.
     */
    fun reduce(state: TheaterState, ev: TheaterEvent, nowMs: Long = 0L): TheaterState = when (ev.phase) {
        "start" -> state.open(ev)

        // Correlated by ORDER, not by id: `tool.completed` carries no call id. The executor
        // emits completions in the same order it emitted the starts, so this closes the OLDEST
        // open row — FIFO, not a stack.
        //
        // ⚠️ A model batches calls: `read_file` A and `read_file` B can both open before either
        // closes (observed live, 2026-08-19 — two starts, then two ends). Closing newest-first
        // there hands A's success to B and B's failure to A, which is worse than showing
        // nothing. The name is a tiebreak, not a key.
        "end" -> state.copy(beats = state.beats.close(ev))

        // Its own frame because it arrives AFTER the end (the progress callback fires before
        // the complete one), so it lands on the row that just closed.
        "diff" -> state.copy(beats = state.beats.attachDiff(ev))

        "sub" -> state.copy(delegations = state.delegations.fold(ev, nowMs))

        else -> state
    }

    private fun String.orTool(): String = ifBlank { "tool" }

    /**
     * Opening a call while another is still open is the only evidence this channel gives that
     * the model fired them together — so both ends of that overlap join one batch, not just
     * the newcomer. A shared [ToolCall.batchId], never [ToolCall.concurrent]: this channel
     * observes *announcement*, not execution, and "in one turn" is the claim that survives.
     */
    private fun TheaterState.open(ev: TheaterEvent): TheaterState {
        val overlaps = beats.any { it.running }
        if (!overlaps) {
            val next = beats + ToolCall(name = ev.name.orTool(), context = ev.preview)
            return copy(beats = if (next.size > MAX_BEATS) next.takeLast(MAX_BEATS) else next)
        }
        val existing = beats.firstOrNull { it.running && it.batchId.isNotBlank() }?.batchId
        val seq = if (existing == null) batchSeq + 1 else batchSeq
        val bid = existing ?: "turn-$seq"
        val marked = beats.map { if (it.running && it.batchId.isBlank()) it.copy(batchId = bid) else it }
        val next = marked + ToolCall(name = ev.name.orTool(), context = ev.preview, batchId = bid)
        return copy(
            beats = if (next.size > MAX_BEATS) next.takeLast(MAX_BEATS) else next,
            batchSeq = seq,
        )
    }

    /** The newest closed row of that name still without a diff — an edit tool called twice in
     *  one turn gets one diff each, in the order they landed. */
    private fun List<ToolCall>.attachDiff(ev: TheaterEvent): List<ToolCall> {
        if (ev.diff.isBlank()) return this
        val byName = indexOfLast { !it.running && !it.hasDiff && it.name == ev.name }
        val i = if (byName >= 0) byName else indexOfLast { !it.running && !it.hasDiff }
        if (i < 0) return this
        return toMutableList().also {
            it[i] = it[i].copy(
                inlineDiff = ev.diff, added = ev.added, removed = ev.removed,
                diffTruncated = ev.truncated,
            )
        }
    }

    private fun List<ToolCall>.close(ev: TheaterEvent): List<ToolCall> {
        val byName = indexOfFirst { it.running && it.name == ev.name }
        val i = if (byName >= 0) byName else indexOfFirst { it.running }
        if (i < 0) return this
        return toMutableList().also {
            it[i] = it[i].copy(
                status = if (ev.ok == false) ToolStatus.FAILED else ToolStatus.COMPLETED,
                durationS = if (ev.ms > 0L) ev.ms / 1000.0 else null,
                result = ev.result,
            )
        }
    }

    /**
     * Every `subagent.*` frame carries the same identity block and adds what only it knows, so
     * identity folds in once and the kind decides state and activity line — the same reducer
     * Talaria runs over its own wire.
     */
    private fun List<Delegation>.fold(ev: TheaterEvent, nowMs: Long): List<Delegation> {
        val key = ev.child.ifBlank { "task-${ev.taskIndex ?: 0}" }
        val i = indexOfFirst { it.key == key }
        val prev = if (i >= 0) this[i] else Delegation(key = key)
        val withIdentity = prev.copy(
            goal = ev.goal.ifBlank { prev.goal },
            taskIndex = ev.taskIndex ?: prev.taskIndex,
            taskCount = ev.taskCount ?: prev.taskCount,
            model = ev.model.ifBlank { prev.model },
            sessionId = ev.sessionId.ifBlank { prev.sessionId },
            depth = ev.depth ?: prev.depth,
            toolCount = ev.toolCount ?: prev.toolCount,
            // The first frame about a child is when this client learned it exists — stamp it
            // once and never move it, or the elapsed would reset on every tool it picks up.
            startedAtMs = if (prev.startedAtMs == 0L && nowMs > 0L) nowMs else prev.startedAtMs,
        )
        val next = when (ev.kind) {
            "spawn_requested" -> withIdentity.copy(state = DelegationState.SPAWNING)
            "start" -> withIdentity.copy(state = DelegationState.RUNNING, activity = "")
            // The child's own tool: name it, with its preview as the object. Kept twice —
            // once as the live line, once in the record that outlives the flight.
            "tool" -> {
                val beat = DelegationBeat("tool", ev.name, ev.preview)
                withIdentity.copy(
                    state = DelegationState.RUNNING,
                    activity = beat.line,
                    trail = withIdentity.trail.append(beat),
                )
            }
            // A progress note is a thing the child chose to say about its work, so it is
            // kept. A thinking fragment arrives per delta and is texture: it drives the live
            // line and is deliberately not written down.
            "progress" -> withIdentity.copy(
                state = DelegationState.RUNNING,
                activity = ev.preview.ifBlank { prev.activity },
                trail = withIdentity.trail.append(DelegationBeat("progress", text = ev.preview)),
            )
            "thinking" -> withIdentity.copy(
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
                filesReadCount = ev.filesRead ?: prev.filesReadCount,
                filesWrittenCount = ev.filesWritten ?: prev.filesWrittenCount,
            )
            // A kind this client doesn't know still folds its identity in, so a later gateway
            // adding one can't blank a wing.
            else -> withIdentity
        }
        return if (i >= 0) toMutableList().also { it[i] = next } else this + next
    }

    /**
     * Add one beat to a child's record, bounded at the tail like the parent's own.
     *
     * Blank frames are dropped rather than stored as empty rows, and an exact repeat of the
     * last beat is dropped too: a producer that re-sends the same progress note (or a `tool`
     * frame relayed twice by two transports watching one turn) should read as one thing done,
     * not two.
     *
     * Public because the direct door reduces its own wire vocabulary straight into
     * [Delegation] rather than through [reduce] — the two producers speak different JSON, but
     * the record they build has to be bounded by one rule, in one place, or the cap silently
     * means something different depending on which door you came in by.
     */
    fun trailWith(trail: List<DelegationBeat>, beat: DelegationBeat): List<DelegationBeat> {
        if (beat.name.isBlank() && beat.text.isBlank()) return trail
        if (trail.lastOrNull() == beat) return trail
        val next = trail + beat
        return if (next.size > MAX_TRAIL) next.takeLast(MAX_TRAIL) else next
    }

    private fun List<DelegationBeat>.append(beat: DelegationBeat): List<DelegationBeat> =
        trailWith(this, beat)

    /**
     * Pair a committed message's parsed tool names with the structured beats from the same turn,
     * so the transcript can show what the text never carried — durations, verdicts, real diffs.
     *
     * Positional, by name: the two lists describe the same sequence, and there is no id in the
     * committed text to join on. A name mismatch at position i means the two views have drifted
     * (a tool the parser missed, a run stitched from more than one turn), and rather than guess,
     * that position simply goes un-enriched — a row with fewer facts is right, a row with
     * ANOTHER call's diff on it is not.
     *
     * @return parsed-call index -> its beat, for the positions that agreed.
     */
    /**
     * The failure reason, out of whatever the tool handed back.
     *
     * A tool's result is its own envelope, and a phone showed the envelope: a `read_file` on a
     * missing path rendered as `{"content": "", "total_lines": 0, "file_size": 0, "truncated":
     * false, "is_bin…` — every field except the one that says what went wrong, clipped off before
     * the reason it exists. The one line a failure gets should be the reason.
     */
    fun reason(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            REASON_KEYS.firstNotNullOfOrNull { key ->
                Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .find(t)?.groupValues?.get(1)
                    ?.replace("\\n", " ")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }?.let { return it.take(REASON_MAX) }
        }
        // Not JSON, or JSON with nothing that reads as a reason: the first line still beats the
        // middle of one.
        return t.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(REASON_MAX).orEmpty()
    }

    /** In the order a tool is likely to have meant it. */
    private val REASON_KEYS = listOf("error", "message", "detail", "reason", "stderr")

    private const val REASON_MAX = 200

    fun align(parsedNames: List<String>, beats: List<ToolCall>): Map<Int, ToolCall> {
        if (parsedNames.isEmpty() || beats.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, ToolCall>()
        for (i in parsedNames.indices) {
            val beat = beats.getOrNull(i) ?: break
            if (beat.name == parsedNames[i]) out[i] = beat
        }
        return out
    }

    /**
     * The beats grouped for display: calls sharing one dispatch ([ToolCall.batchId]) are one
     * batch, everything else stands alone. Structural, not decorative — the fact being shown
     * is "these went out together".
     */
    fun batches(beats: List<ToolCall>): List<List<ToolCall>> {
        val out = mutableListOf<MutableList<ToolCall>>()
        for (beat in beats) {
            val last = out.lastOrNull()
            if (beat.batchId.isNotBlank() && last != null && last.last().batchId == beat.batchId) last.add(beat)
            else out.add(mutableListOf(beat))
        }
        return out
    }
}
