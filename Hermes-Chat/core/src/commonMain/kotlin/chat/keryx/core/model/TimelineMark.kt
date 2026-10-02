package chat.keryx.core.model

/**
 * A system row drawn as a divider (2.16), not as a bubble.
 *
 * The gateway leaves machinery in the transcript: a `/model` switch, a resumed turn, a
 * background process finishing, a skill's instructions, a cron job's brief. 2.11 stopped them
 * speaking in the user's voice, but they still drew as full agent bubbles, and a model switch
 * read "model changed" twice, each in its own timestamped bubble, without saying WHICH model.
 * A mark is the same row as a quiet centred rule with one line on it — the way the compaction
 * divider already draws its handoff — and what the row actually said stays one tap away.
 *
 * [kind] is the gateway's `display_kind` when it set one, otherwise a sniffed stand-in; it is
 * what [TimelineMarks.collapse] compares. [detail] is null when the label already says it all.
 *
 * Pure Kotlin (KMP rule): no android.* here.
 */
data class TimelineMark(
    val kind: String,
    /** One line for the rule: "model → qwen3.8-flash-next". */
    val label: String,
    /** The row's own text, opened on tap; null when there is nothing more than the label. */
    val detail: String? = null,
)

object TimelineMarks {

    /**
     * The model-switch marker's fixed opening — `tui_gateway/server.py`
     * `_MODEL_SWITCH_MARKER_PREFIX`, read off live `state.db` rows on 2026-10-02:
     * "[System: The active model for this chat has changed to qwen3.8-flash-next via provider
     * custom. From this point forward, …]". The model name was always there; the label dropped it.
     */
    const val MODEL_PREFIX = "[System: The active model for this chat has changed to "

    /** `tui_gateway/agent_callbacks.py`'s two personality markers. */
    private const val PERSONALITY_SET = "[System: The user has changed the assistant's personality."
    private const val PERSONALITY_CLEARED = "[System: The user has cleared the personality overlay."
    private const val PERSONALITY_BODY = "respond accordingly:"

    /** A machine row with no kind of its own: a gateway note, a role:"system" row. */
    const val NOTE = "note"
    const val TODO_INJECTION = "todo_injection"
    const val SKILL = "skill"

    /** The longest label the rule carries before it ellipsizes. */
    const val LABEL_MAX = 80

    /** Kinds that SET a state: only the newest of a consecutive run is still true. */
    private val STATE_KINDS = setOf(DisplayKind.MODEL_SWITCH, DisplayKind.PERSONALITY_SWITCH)

    /** `[IMPORTANT: The user has invoked the "intel-brief" skill, …` — the skill's whole body
     *  follows, which is exactly why it must not be a bubble. */
    private val SKILL_INVOKED = Regex("""^\[IMPORTANT: The user has invoked the "([^"]+)" skill""")

    /** Openers stripped from a note before its first sentence becomes the label. */
    private val NOTE_OPENERS = listOf("[System note:", "[System:", "[IMPORTANT:")

    /**
     * The mark for one machine row. [kind] is the row's `display_kind` (null when the gateway
     * set none), [content] its text, [displayText] the gateway's own one-line summary
     * (`display_metadata.display_text`, set on background-process and delegation rows).
     */
    fun of(kind: String?, content: String, displayText: String? = null): TimelineMark {
        val text = content.trim()
        val model = modelSwitch(text)
        if (model != null) return TimelineMark(DisplayKind.MODEL_SWITCH, "model → ${model.first}")
        if (kind == DisplayKind.MODEL_SWITCH) {
            // Classed as a switch but not in the marker's words: the canned phrase, and the text
            // kept behind it — a row this deep in a gateway quirk should never vanish unread.
            return TimelineMark(DisplayKind.MODEL_SWITCH, "model changed", text.ifEmpty { null })
        }
        if (text.startsWith(PERSONALITY_CLEARED)) {
            return TimelineMark(DisplayKind.PERSONALITY_SWITCH, "personality cleared")
        }
        if (text.startsWith(PERSONALITY_SET) || kind == DisplayKind.PERSONALITY_SWITCH) {
            val persona = text.substringAfter(PERSONALITY_BODY, "").trim().removeSuffix("]").trim()
            return TimelineMark(
                DisplayKind.PERSONALITY_SWITCH,
                "personality changed",
                persona.ifEmpty { text }.ifEmpty { null },
            )
        }
        val k = kind ?: NOTE
        displayText?.let(::oneLine)?.takeIf { it.isNotEmpty() }
            ?.let { return TimelineMark(k, cap(it), text.ifEmpty { null }) }
        DisplayKind.timelineLabel(kind)?.let { return TimelineMark(k, it, text.ifEmpty { null }) }
        if (TodoPlanParser.isTodoInjection(text)) {
            return TimelineMark(TODO_INJECTION, "task list carried over", text)
        }
        SKILL_INVOKED.find(text)?.let { return TimelineMark(SKILL, "skill · ${it.groupValues[1]}", text) }
        val label = headline(text)
        return TimelineMark(k, label, text.takeIf { it.isNotEmpty() && it != label })
    }

    /** The marker's model and provider, or null when [content] is not a model-switch marker. */
    fun modelSwitch(content: String): Pair<String, String?>? {
        val t = content.trimStart()
        if (!t.startsWith(MODEL_PREFIX)) return null
        // "<model> via provider <provider>. From this point forward…" — the model is the first
        // word; a provider is optional (the gateway omits the clause when it has none).
        val rest = t.removePrefix(MODEL_PREFIX)
        val head = rest.substringBefore(". From this point").substringBefore("]").trim()
        val model = head.substringBefore(" via provider ").trim()
        if (model.isEmpty()) return null
        val provider = head.substringAfter(" via provider ", "").trim().ifEmpty { null }
        return model to provider
    }

    /**
     * A note's first sentence, with its bracket opener and closer gone: "[IMPORTANT: Background
     * process proc_6b5f completed normally (exit code 0).\nCommand: …" reads "Background process
     * proc_6b5f completed normally (exit code 0)".
     */
    fun headline(content: String): String {
        var line = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "system note"
        val opener = NOTE_OPENERS.firstOrNull { line.startsWith(it, ignoreCase = true) }
        val close = line.indexOf(']')
        line = when {
            opener != null -> line.substring(opener.length).trim()
            // "[ASYNC DELEGATION COMPLETE — deleg_3]": the whole line is the bracket.
            line.startsWith("[") && close == line.lastIndex -> line.substring(1, close).trim()
            // "[background watch] the build finished": a tag, then the news.
            line.startsWith("[") && close > 1 ->
                line.substring(1, close).trim() +
                    line.substring(close + 1).trim().let { if (it.isEmpty()) "" else " · $it" }
            else -> line
        }
        val stop = line.indexOf(". ")
        if (stop > 0) line = line.substring(0, stop)
        line = line.trimEnd(']', '.', ' ', ':')
        return if (line.isEmpty()) "system note" else cap(line)
    }

    /**
     * Drop every mark the next row supersedes. Two marks only collapse when nothing stood
     * between them: two identical marks are one event said twice (the live state.db carries
     * consecutive identical model-switch rows), and a run of switches nobody spoke under ends
     * on the one that is still true — the gateway keeps only the newest marker for the same
     * reason. The NEWEST row always survives, so the timeline's last id never moves.
     */
    fun collapse(messages: List<Message>): List<Message> {
        if (messages.size < 2) return messages
        val out = ArrayList<Message>(messages.size)
        for (m in messages) {
            val prev = out.lastOrNull()?.mark
            val cur = m.mark
            if (prev != null && cur != null && supersedes(prev, cur)) out[out.size - 1] = m
            else out += m
        }
        return out
    }

    /** Does [newer], directly after [older], make [older] redundant? */
    fun supersedes(older: TimelineMark, newer: TimelineMark): Boolean =
        older.kind == newer.kind && (older.kind in STATE_KINDS || older.label == newer.label)

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun cap(s: String): String =
        if (s.length <= LABEL_MAX) s else s.take(LABEL_MAX - 1).trimEnd() + "…"
}
