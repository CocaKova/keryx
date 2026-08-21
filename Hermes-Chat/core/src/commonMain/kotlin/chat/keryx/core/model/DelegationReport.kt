package chat.keryx.core.model

/**
 * The report a background delegation files when its children land.
 *
 * A top-level `delegate_task` runs in the BACKGROUND, so the parent turn ends at dispatch and
 * the results arrive minutes later as an injected row. The gateway writes that row with
 * **role: "user"** (`tools/process_registry.py`) — not because you said it, but because that
 * is the only channel it has for handing the model something between turns. Rendered
 * literally it became a giant bubble in your own colour, saying things you never said.
 *
 * It is also written FOR A MODEL, not for a reader: a paragraph explaining that a fan-out has
 * finished and that it may re-dispatch if the world has moved on, then `--- ✓ TASK 1/3 ---`
 * banners with `status=`/`api_calls=` inline. All of that is prompt scaffolding around the one
 * thing a person wants: what each subagent was asked to do, and what it came back with.
 *
 * So we parse it back into the [Delegation]s it describes and let it render as the wings card
 * the live dispatch already uses — the same card, now landed. Nothing about the shape is
 * guessed: it is read off the two builders in `_format_delegation_completion`.
 *
 * ⚠️ Parsing is best-effort by design. Every failure path still has to get the ATTRIBUTION
 * right, because that is the actual bug — an unparseable report must fall back to a system
 * row, never to the user's own voice. [isReport] alone decides that; [parse] only decides how
 * pretty it gets.
 */
object DelegationReport {

    private const val BATCH_MARK = "[ASYNC DELEGATION BATCH COMPLETE"
    private const val SINGLE_MARK = "[ASYNC DELEGATION COMPLETE"

    /** Message-id prefix for a parsed report row, so landings it supersedes can be found. */
    const val ROW_ID_PREFIX = "deleg-report-"

    /** Message-id prefix the live path uses when a wing lands (`wing-<subagent id>`). */
    const val LANDED_ID_PREFIX = "wing-"

    /**
     * Drop the live landing rows a consolidated report already covers.
     *
     * The same subagent can be witnessed twice: once live, when `subagent.complete` lands it
     * as its own transcript row, and again when the background batch files its report minutes
     * later. Both are real, but they are the SAME work, and two identical wings cards read as
     * a bug rather than as history.
     *
     * The report wins because it is the persisted one — it survives process death, where the
     * live row does not, so keeping it is what makes the transcript look the same tomorrow as
     * it does now. Matching is by goal within the session, which is what a reader compares
     * too; the live key (`subagent_id`) and the report's (`<delegation>#<n>`) have no shared
     * vocabulary to join on.
     */
    fun withoutSupersededLandings(messages: List<Message>): List<Message> {
        val reported = messages
            .filter { it.id.startsWith(ROW_ID_PREFIX) }
            .flatMap { m -> m.delegations.map { it.goal.trim() } }
            .filter { it.isNotBlank() }
            .toHashSet()
        if (reported.isEmpty()) return messages
        return messages.filterNot { m ->
            m.id.startsWith(LANDED_ID_PREFIX) &&
                m.delegations.isNotEmpty() &&
                // Only a settled landing is superseded; anything still flying is live news.
                m.delegations.all { !it.running && it.goal.trim() in reported }
        }
    }

    /** True for either completion block. Cheap, and deliberately the only attribution test. */
    fun isReport(content: String): Boolean {
        val head = content.trimStart()
        return head.startsWith(BATCH_MARK) || head.startsWith(SINGLE_MARK)
    }

    /** `--- ✓ TASK 2/3: refactor the parser  (status=completed, api_calls=4, 12.3s) ---` */
    private val TASK_HEADER = Regex(
        """^---\s*(?<icon>[✓✗])?\s*TASK\s+(?<idx>\d+)\s*/\s*(?<total>\d+)\s*(?::\s*(?<goal>.*?))?\s*\((?<meta>[^)]*)\)\s*---\s*$"""
    )
    private val STATUS_IN_META = Regex("""status=([A-Za-z_]+)""")
    private val CALLS_IN_META = Regex("""api_calls=(\d+)""")
    private val SECONDS_IN_META = Regex("""(?<!\w)(\d+(?:\.\d+)?)s(?![A-Za-z])""")

    private val ROLE_MODEL = Regex("""^Role:\s*(?<role>\S+)\s+Model:\s*(?<model>.+?)(?:\s{2,}.*)?$""")
    private val SINGLE_STATUS = Regex(
        """^Status:\s*(?<status>\S+)\s+API calls:\s*(?<calls>\d+)\s+Duration:\s*(?<dur>[\d.]+|\?)s\s*$"""
    )

    /**
     * The subagents this report describes, in task order. Empty when the block cannot be read
     * — the caller then keeps the raw text as a system row rather than inventing structure.
     */
    fun parse(content: String): List<Delegation> {
        val text = content.trimStart()
        val id = delegationId(text)
        return when {
            text.startsWith(BATCH_MARK) -> parseBatch(text, id)
            text.startsWith(SINGLE_MARK) -> parseSingle(text, id)
            else -> emptyList()
        }
    }

    /** `[ASYNC DELEGATION BATCH COMPLETE — deleg_7]` → `deleg_7`. */
    private fun delegationId(text: String): String =
        text.lineSequence().firstOrNull()
            ?.substringAfter('—', "")
            ?.trim()?.removeSuffix("]")?.trim()
            .orEmpty()
            .ifBlank { "delegation" }

    private fun parseBatch(text: String, id: String): List<Delegation> {
        val lines = text.lines()
        val model = lines.firstNotNullOfOrNull { ROLE_MODEL.find(it.trim())?.groups?.get("model")?.value }
            ?.trim().orEmpty()

        val out = ArrayList<Delegation>()
        var current: Delegation? = null
        val body = StringBuilder()

        fun flush() {
            val c = current ?: return
            out += c.copy(summary = tidySummary(body.toString()))
            body.setLength(0)
        }

        for (line in lines) {
            val m = TASK_HEADER.find(line.trim())
            if (m != null) {
                flush()
                val idx = (m.groups["idx"]?.value?.toIntOrNull() ?: 1) - 1
                val meta = m.groups["meta"]?.value.orEmpty()
                // The ✓/✗ is the gateway's own verdict on the task. Trust `status=` first,
                // but let a ✗ override a status word we don't recognise — otherwise an
                // unknown failure word falls through to DONE and a dead subagent reads green.
                val fromStatus = DelegationState.fromWire(STATUS_IN_META.find(meta)?.groupValues?.get(1))
                val state =
                    if (m.groups["icon"]?.value == "✗" && fromStatus == DelegationState.DONE)
                        DelegationState.FAILED
                    else fromStatus
                current = Delegation(
                    // Each task gets its own key so the card can list them side by side; the
                    // gateway's own 1-based numbering is what the wing label shows.
                    key = "$id#${idx + 1}",
                    goal = m.groups["goal"]?.value?.trim().orEmpty(),
                    taskIndex = idx.coerceAtLeast(0),
                    taskCount = m.groups["total"]?.value?.toIntOrNull() ?: 1,
                    model = model,
                    state = state,
                    apiCalls = CALLS_IN_META.find(meta)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    durationSeconds = SECONDS_IN_META.find(meta)?.groupValues?.get(1)?.toDoubleOrNull(),
                )
                continue
            }
            if (current == null) continue
            // The live-transcript pointer is a path for the agent, not a result for a reader.
            if (line.trimStart().startsWith("Full live transcript")) continue
            body.appendLine(line)
        }
        flush()
        return out
    }

    private fun parseSingle(text: String, id: String): List<Delegation> {
        val lines = text.lines()
        fun field(prefix: String): String =
            lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()

        val roleModel = lines.firstNotNullOfOrNull { ROLE_MODEL.find(it.trim()) }
        val status = lines.firstNotNullOfOrNull { SINGLE_STATUS.find(it.trim()) }

        val resultAt = lines.indexOfFirst { it.trim() == "--- RESULT ---" }
        val summary = if (resultAt >= 0) tidySummary(lines.drop(resultAt + 1).joinToString("\n")) else ""

        return listOf(
            Delegation(
                key = id,
                goal = field("Original goal:"),
                taskIndex = 0,
                taskCount = 1,
                model = roleModel?.groups?.get("model")?.value?.trim().orEmpty(),
                state = DelegationState.fromWire(status?.groups?.get("status")?.value),
                apiCalls = status?.groups?.get("calls")?.value?.toIntOrNull() ?: 0,
                durationSeconds = status?.groups?.get("dur")?.value?.toDoubleOrNull(),
                summary = summary,
            )
        )
    }

    /** Collapse the blank lines the block uses for the model's benefit; keep the prose. */
    private fun tidySummary(raw: String): String =
        raw.trim().lines()
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
