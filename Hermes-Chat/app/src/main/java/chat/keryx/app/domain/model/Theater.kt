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
 * Pure Kotlin so the ordering rules — which are the whole difficulty — can be tested.
 */
data class ToolBeat(
    val name: String,
    val preview: String = "",
    /** null while the call is in flight; true/false once it lands. */
    val ok: Boolean? = null,
    val ms: Long = 0L,
    /** A glimpse of the result (the gateway clips it); the full text arrives with the commit. */
    val result: String = "",
    /** 0 = this agent's own call · 1 = a delegated subagent · 2 = that subagent's own calls. */
    val depth: Int = 0,
    /** The delegation key, for rows that belong to a subagent. */
    val child: String = "",
    /** True when the row IS a subagent rather than a tool call. */
    val subagent: Boolean = false,
) {
    val running: Boolean get() = ok == null
}

/** One `event: tool` frame, already parsed off the wire. */
data class TheaterEvent(
    /** "start" · "end" · "sub". */
    val phase: String,
    /** For [phase] "sub": start · tool · text · complete · progress · thinking · spawn_requested. */
    val kind: String = "",
    val name: String = "",
    val preview: String = "",
    val ok: Boolean? = null,
    val ms: Long = 0L,
    val result: String = "",
    val child: String = "",
)

object Theater {
    /**
     * A long turn can run dozens of tools; the overlay only ever shows the tail, and an unbounded
     * list would grow for the life of the turn behind it.
     */
    const val MAX_BEATS = 40

    fun reduce(beats: List<ToolBeat>, ev: TheaterEvent): List<ToolBeat> = when (ev.phase) {
        "start" -> beats.add(ToolBeat(name = ev.name.orTool(), preview = ev.preview))

        // Correlated by ORDER, not by id: `tool.completed` carries no call id. The executor
        // emits completions in the same order it emitted the starts, so this closes the OLDEST
        // open row — FIFO, not a stack.
        //
        // ⚠️ A model batches calls: `read_file` A and `read_file` B can both open before either
        // closes (observed on device, 2026-08-19 — two starts, then two ends). Closing
        // newest-first there hands A's success to B and B's failure to A, which is worse than
        // showing nothing. The name is a tiebreak, not a key.
        "end" -> beats.close(ev, depth = 0)

        "sub" -> when (ev.kind) {
            // `delegate` is itself a tool, so its own start/end already bracket these rows.
            "start" -> beats.add(
                ToolBeat(
                    name = ev.name.ifBlank { ev.preview }.ifBlank { "subagent" },
                    preview = if (ev.name.isBlank()) "" else ev.preview,
                    depth = 1,
                    child = ev.child,
                    subagent = true,
                )
            )
            // A subagent reports its calls one at a time and never says when one ENDED — the
            // next one starting is the only signal, exactly as the desktop bridge treats it.
            "tool" -> beats.closeChildTools(ev.child)
                .add(ToolBeat(name = ev.name.orTool(), preview = ev.preview, depth = 2, child = ev.child))
            "complete" -> beats.closeChildTools(ev.child).closeChild(ev.child)
            // text / thinking / progress / spawn_requested: a running commentary that would
            // outpace the phone and drown the rows that say what actually happened.
            else -> beats
        }

        else -> beats
    }

    private fun String.orTool(): String = ifBlank { "tool" }

    private fun List<ToolBeat>.add(beat: ToolBeat): List<ToolBeat> =
        (this + beat).let { if (it.size > MAX_BEATS) it.takeLast(MAX_BEATS) else it }

    private fun List<ToolBeat>.close(ev: TheaterEvent, depth: Int): List<ToolBeat> {
        val byName = indexOfFirst { it.running && it.depth == depth && it.name == ev.name }
        val i = if (byName >= 0) byName else indexOfFirst { it.running && it.depth == depth }
        if (i < 0) return this
        return toMutableList().also {
            it[i] = it[i].copy(ok = ev.ok ?: true, ms = ev.ms, result = ev.result)
        }
    }

    private fun List<ToolBeat>.closeChildTools(child: String): List<ToolBeat> =
        map { if (it.running && it.depth == 2 && it.child == child) it.copy(ok = true) else it }

    private fun List<ToolBeat>.closeChild(child: String): List<ToolBeat> {
        val i = indexOfLast { it.running && it.subagent && it.child == child }
        if (i < 0) return this
        return toMutableList().also { it[i] = it[i].copy(ok = true) }
    }
}
