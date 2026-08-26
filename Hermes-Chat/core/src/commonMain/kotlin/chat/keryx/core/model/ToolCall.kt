package chat.keryx.core.model

/**
 * One tool invocation, whoever produced it — the absorption's "one `ToolCall`" (§2).
 *
 * Two producers, one shape:
 *  - the **direct transport** fills everything from the gateway wire, live (`tool.start` /
 *    `tool.complete`) and hydrated (assistant `tool_calls[]` + role:"tool" rows) alike;
 *  - the **Matrix parser** fills what committed text carries — name, display argument, a
 *    verdict when the text shows one — and the side-channel's live record enriches the rest
 *    (durations, real verdicts, diffs) while the app is up. History after a restart is the
 *    same row with fewer facts, never a different feature.
 *
 * ⚠️ The two concurrency claims stay distinguishable (plan §6). A shared [batchId] means
 * "dispatched in one turn" — the model asked for these together, and the runtime may still
 * have run them one at a time. [concurrent] is set only on OBSERVED overlap (the direct
 * gateway wire says so); the Matrix side-channel observes announcement, not execution, so it
 * only ever sets [batchId]. Renderers say "in parallel" for [concurrent] and "in one turn"
 * for a bare shared batch — the weaker claim is the one that survives.
 */
data class ToolCall(
    /** Wire id when the producer has one; blank on the parsed/side-channel path. */
    val toolId: String = "",
    val name: String,
    /** ≤80-char primary-argument preview — the display half of the row's title. */
    val context: String = "",
    /** Full arguments as a JSON object string (pretty-printed lazily at render time);
     *  blank when the producer never had them (committed Matrix text doesn't). */
    val argsJson: String = "",
    val status: ToolStatus = ToolStatus.EXECUTING,
    /** Result payload as display text, capped. Carried by both producers: the direct door's
     *  `tool.complete`, and (2.5.7) the side-channel's every `end` frame, middle-clipped on the
     *  gateway. Blank only for a committed Matrix message parsed after the fact — that text
     *  carries tool names, never tool output. Drawn as the row's `▸ output` fold; a failure's
     *  reason ([Theater.reason]) is always on show. */
    val result: String = "",
    /** Gateway's human summary ("Did 3 searches in 2.1s"), when it ships one. */
    val summary: String = "",
    val durationS: Double? = null,
    /** The edit this call made, when it made one — the gateway's own inline diff, ANSI and all. */
    val inlineDiff: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    /** The panel was cut to fit the wire; [added]/[removed] are still counted from the whole. */
    val diffTruncated: Boolean = false,
    /** Calls dispatched in one turn share this — see the class doc before reading it as parallel. */
    val batchId: String = "",
    /** True only with OBSERVED overlap — direct evidence, never an inference from grouping. */
    val concurrent: Boolean = false,
) {
    val running: Boolean get() = status == ToolStatus.EXECUTING
    val failed: Boolean get() = status == ToolStatus.FAILED
    val hasDiff: Boolean get() = inlineDiff.isNotBlank()

    /** The three-verdict read the committed card renders: ✓ only when SEEN to succeed. */
    val verdictOk: Boolean? get() = when (status) {
        ToolStatus.COMPLETED -> true
        ToolStatus.FAILED -> false
        else -> null
    }
}
