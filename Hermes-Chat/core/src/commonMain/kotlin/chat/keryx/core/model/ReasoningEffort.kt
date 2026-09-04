package chat.keryx.core.model

/**
 * How hard the model is asked to think — Hermes' reasoning effort, as a selectable scale.
 *
 * Ported from the two upstream surfaces that own this: `apps/desktop/src/lib/reasoning-effort.ts`
 * (the scale, the short labels, the resolution rules) and `web/src/lib/reasoning-effort.ts`
 * (the option list including `none`). The backend's own list is `VALID_REASONING_EFFORTS` in
 * `hermes_constants.py`; `none` is not a level on that scale but a separate state — thinking
 * off — which `parse_reasoning_effort` returns as `{enabled: false}`.
 *
 * The wire is `config.get` / `config.set` on key `reasoning`, which is session-scoped when a
 * `session_id` rides along and global when one doesn't (verified live against a running
 * gateway: a session-scoped write left `agent.reasoning_effort` in config.yaml untouched, and
 * an unknown level came back as error 4002 rather than silently landing).
 */
object ReasoningEffort {

    /** The scale, ascending. `none` is deliberately absent — it is off, not a level. */
    val LEVELS: List<String> = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** Everything a config value may legally hold: the scale plus thinking-off. */
    val VALUES: List<String> = listOf("none") + LEVELS

    /** Hermes' own fallback when neither the session nor the profile names one. */
    const val DEFAULT: String = "medium"

    /** Compact labels for chrome where space is tight — the composer pill, picker rows. */
    private val SHORT = mapOf(
        "none" to "Off",
        "minimal" to "Min",
        "low" to "Low",
        "medium" to "Med",
        "high" to "High",
        "xhigh" to "XHigh",
        "max" to "Max",
        "ultra" to "Ultra",
    )

    /** Spelled-out names for the picker itself, where the reader has room to read. */
    private val LONG = mapOf(
        "none" to "Off · no thinking",
        "minimal" to "Minimal",
        "low" to "Low",
        "medium" to "Medium",
        "high" to "High",
        "xhigh" to "Extra high",
        "max" to "Max",
        "ultra" to "Ultra",
    )

    /** An empty or unrecognized value means the Hermes default — never a blank pill. */
    fun normalize(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return DEFAULT
        return if (v in VALUES) v else DEFAULT
    }

    /** Unknown values pass through as themselves: a gateway that grows a level we don't know
     *  yet must still be able to SAY so, rather than be relabelled as something it isn't. */
    fun shortLabel(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return ""
        return SHORT[v] ?: v
    }

    fun longLabel(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return ""
        return LONG[v] ?: v
    }

    fun isValid(raw: String?): Boolean = raw?.trim()?.lowercase() in VALUES

    /** Thinking is on unless a level explicitly says otherwise; empty inherits [fallback]. */
    fun thinkingEnabled(effort: String?, fallback: String = DEFAULT): Boolean {
        val v = effort?.trim()?.lowercase().orEmpty().ifEmpty { fallback.trim().lowercase() }
        return v != "none"
    }

    /**
     * The composer's status line: model, then the level actually in force. Desktop shows the
     * effort ALWAYS, not just when it differs from the default — "which level am I on" is the
     * question the pill exists to answer (`formatModelStatusLabel`, model-status-label.ts).
     * [effort] empty falls back to the profile default so the label never advertises a level
     * the agent won't use.
     */
    /**
     * Did this turn die because the MODEL refused the level?
     *
     * The scale is Hermes', but the levels a given model accepts are the model's — a local
     * vLLM brain renders effort through its chat template, and the template decides. Measured
     * on a Qwen 3.8 NVFP4 build: `low`/`medium`/`high` render, while `minimal` and `max` come
     * back HTTP 400 ("Unexpected reasoning effort minimal. Supported types are xhigh
     * (default), medium, and low.") and `ultra` is rejected by vLLM's own schema before the
     * template ever sees it. Nothing in the gateway knows that list in advance — `config.set`
     * accepts every level, and the refusal only arrives when a real turn runs.
     *
     * So the app learns it the only way it can: from the corpse. Deliberately narrow — a
     * mention of reasoning effort AND a word that means refusal — because the cost of a false
     * positive is silently overriding a level the user chose.
     */
    fun isLevelRejection(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        if (!m.contains("reasoning effort") && !m.contains("reasoning_effort")) return false
        return REJECTION_WORDS.any { it in m }
    }

    private val REJECTION_WORDS = listOf(
        "unexpected", "unsupported", "not supported", "supported types",
        "invalid", "literal_error", "must be one of",
    )

    /**
     * The quietest level a call can ask [model] for: the first of `none → minimal → low` this
     * model has not already refused ([rejected] holds [rejectionKey]s learned from dead turns).
     * A brain whose template only renders low/medium/high (the SGLang Qwen 3.8 build: "Supported
     * types are xhigh, medium, and low") would kill EVERY call turn on `none`; it gets `low`.
     * Null when even `low` is refused — then the call leaves the level alone.
     */
    fun quietestFor(model: String, rejected: Set<String>): String? =
        CALL_QUIET_LADDER.firstOrNull { rejectionKey(model, it) !in rejected }

    private val CALL_QUIET_LADDER = listOf("none", "minimal", "low")

    /**
     * Where to go when [current] was refused on [model]: the nearest level BELOW it on the scale
     * that this model has not already refused, or null when there is nothing left to try.
     *
     * The ladder is [LEVELS], deliberately — so a walk-back steps down the scale and can never
     * silently arrive at `none`. Turning thinking off is a different decision from thinking less,
     * and it is the user's to make; a brain that refuses even `minimal` gets a plain "it won't go
     * lower" rather than a quiet lobotomy.
     *
     * [rejected] holds [rejectionKey]s learned from dead turns, so a level already known bad on
     * this model is skipped rather than being walked into a second time — one refusal costs one
     * turn, not one per rung.
     */
    fun fallbackBelow(model: String, current: String, rejected: Set<String>): String? {
        val idx = LEVELS.indexOf(current.trim().lowercase())
        if (idx <= 0) return null
        return LEVELS.take(idx).asReversed().firstOrNull { rejectionKey(model, it) !in rejected }
    }

    /** Key for remembering a refusal: the level is only rejected on THAT model. */
    fun rejectionKey(model: String, level: String): String =
        "${model.trim()}|${level.trim().lowercase()}"

    fun statusLabel(model: String, effort: String?, defaultEffort: String? = null): String {
        val name = model.trim()
        if (name.isEmpty()) return name
        val level = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: defaultEffort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT
        val tag = shortLabel(level)
        return if (tag.isEmpty()) name else "$name · $tag"
    }
}
