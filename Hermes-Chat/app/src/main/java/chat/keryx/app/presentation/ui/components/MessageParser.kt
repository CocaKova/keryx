package chat.keryx.app.presentation.ui.components

/**
 * Pure (Compose-free, unit-testable) parser that splits a Hermes/Matrix markdown message into
 * renderable segments: plain text, GFM tables, 🧠 thinking lines, and ⚙️ tool-call lines.
 */
object MessageParser {

    data class ToolCall(val emoji: String, val name: String, val args: String)

    data class Citation(val n: Int, val kind: String, val label: String, val detail: String)

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Table(val header: List<String>, val rows: List<List<String>>) : Segment
        data class Thinking(val text: String) : Segment
        data class Tools(val calls: List<ToolCall>) : Segment
        data class Mermaid(val code: String) : Segment
        data class Citations(val items: List<Citation>) : Segment
        data class SkillDistilled(val id: String, val name: String, val summary: String) : Segment
    }

    private val SEPARATOR = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)+\|?\s*$""")
    private const val BRAIN = "🧠"

    /**
     * Hermes renders every tool invocation as a line `<emoji> tool_name: "args"` — but the emoji
     * varies by tool (⚙️ graphiti_*, 👁️ vision_analyze, 🔍 session_search, 🔧 patch, …). Rather than
     * enumerate emoji, we match the stable signature: a non-alphanumeric leading glyph, then a
     * lowercase identifier (snake_case or a single word), then a colon. This catches every tool —
     * `vision_analyze`, `graphiti_forget`, but also single-word ones like `terminal`, `patch`,
     * `bash` — without misfiring on ordinary prose like "Note: …" (no leading glyph) or
     * "✨ Session reset!" (the leading glyph isn't followed by `lowercase_word:`).
     */
    private val TOOL_LINE = Regex("""^(\S+)\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*):\s*(.*)$""")

    // Some tools (notably `terminal`) render as a bare header line `<glyph> name` (NO colon) with the
    // command/args in a following ``` fenced block, instead of inline after a colon. Match the header
    // here; the loop then pulls the fence in as the args so it compacts into the tool card too.
    private val TOOL_HEADER = Regex("""^(\S+)\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*)$""")

    // --- Reasoning extraction ----------------------------------------------------------------
    // Hermes surfaces model reasoning (when display.show_reasoning is on) by *prepending* it to the
    // answer in one of three styles; we also accept raw <think>/<thinking>/<reasoning> tags that
    // some brains/homeservers leak. We pull the reasoning out into a leading Thinking segment so it
    // renders as the dream canvas and the answer renders clean.
    private val THINK_TAG = Regex("""(?is)<(think|thinking|reasoning)>(.*?)</\1>""")
    // "code" style:  💭 **Reasoning:**\n```\n…\n```\n\n<answer>
    private val REASON_CODE = Regex("""(?s)^\s*💭\s*\*\*Reasoning:?\*\*\s*\n```[a-zA-Z0-9]*\n(.*?)\n```\s*(.*)$""")
    // "blockquote" style:  > 💭 **Reasoning:**\n> …\n\n<answer>
    private val REASON_QUOTE = Regex("""(?s)^\s*>\s*💭\s*\*\*Reasoning:?\*\*\s*\n((?:>.*(?:\n|$))+)(.*)$""")
    // "subtext" style:  -# 💭 Reasoning\n-# …\n\n<answer>
    private val REASON_SUBTEXT = Regex("""(?s)^\s*-#\s*💭\s*Reasoning\s*\n((?:-#.*(?:\n|$))+)(.*)$""")

    /** Pull any leading/embedded reasoning out of [content]; returns (reasoning?, remainingBody). */
    fun extractReasoning(content: String): Pair<String?, String> {
        // 1) Raw <think> tags anywhere → concatenate, strip from body.
        if (THINK_TAG.containsMatchIn(content)) {
            val reasoning = THINK_TAG.findAll(content).joinToString("\n\n") { it.groupValues[2].trim() }
            val body = THINK_TAG.replace(content, "").trim()
            return reasoning.trim().ifBlank { null } to body
        }
        // 2) Hermes "💭 Reasoning" prelude (code / blockquote / subtext).
        REASON_CODE.matchEntire(content)?.let { m ->
            return m.groupValues[1].trim().ifBlank { null } to m.groupValues[2].trim()
        }
        REASON_QUOTE.matchEntire(content)?.let { m ->
            val reasoning = m.groupValues[1].lines().joinToString("\n") { it.removePrefix(">").trimStart() }
            return reasoning.trim().ifBlank { null } to m.groupValues[2].trim()
        }
        REASON_SUBTEXT.matchEntire(content)?.let { m ->
            val reasoning = m.groupValues[1].lines().joinToString("\n") { it.removePrefix("-#").trimStart() }
            return reasoning.trim().ifBlank { null } to m.groupValues[2].trim()
        }
        return null to content
    }

    // --- Keryx marker protocol v1 -----------------------------------------------------------------
    // Emitted by the optional bundled `keryx` Hermes plugin inside the plaintext body, using ⟦…⟧
    // (U+27E6/27E7, which never appear in normal prose). All markers are stripped from the displayed
    // text; absent markers mean zero behaviour change, so the app degrades gracefully without the
    // plugin. See hermes-plugin/keryx/README for the emitter side.
    private val CITE_SOURCE = Regex("""⟦cite\s*(\d+)\s*\|([^|]*)\|([^|]*)\|([^⟧]*)⟧""")
    private val CITE_INLINE = Regex("""⟦c(\d+)⟧""")
    private val SKILL_MARK = Regex("""⟦keryx:skill\s*\|([^|]*)\|([^|]*)\|([^⟧]*)⟧""")
    private val BEACON = Regex("""⟦keryx:v\d+⟧""")
    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    )

    private fun superscript(n: Int) = n.toString().map { SUPERSCRIPT[it] ?: it }.joinToString("")

    data class Keryx(
        val text: String,
        val citations: List<Citation>,
        val skill: Segment.SkillDistilled?,
        val present: Boolean,
    )

    /** Strip Keryx markers from [text]; collect citations + a skill signal; rewrite inline cite refs
     *  to superscripts. No markers → returns the text unchanged with empty extras. */
    fun extractKeryx(text: String): Keryx {
        if (!text.contains('⟦')) return Keryx(text, emptyList(), null, false)
        val citations = CITE_SOURCE.findAll(text).map {
            Citation(
                n = it.groupValues[1].toIntOrNull() ?: 0,
                kind = it.groupValues[2].trim(),
                label = it.groupValues[3].trim(),
                detail = it.groupValues[4].trim(),
            )
        }.toList()
        val skillMatch = SKILL_MARK.find(text)
        val skill = skillMatch?.let {
            Segment.SkillDistilled(it.groupValues[1].trim(), it.groupValues[2].trim(), it.groupValues[3].trim())
        }
        var out = CITE_SOURCE.replace(text, "")
        out = SKILL_MARK.replace(out, "")
        out = BEACON.replace(out, "")
        out = CITE_INLINE.replace(out) { "⁽${superscript(it.groupValues[1].toIntOrNull() ?: 0)}⁾" }
        return Keryx(out.trim('\n', ' '), citations, skill, true)
    }

    // Parsing is pure on [content] and runs on the UI thread for both grouping (ChatScreen) and
    // rendering (MessageContent) — i.e. each message was parsed at least twice and re-parsed on every
    // list change. An access-ordered LRU memoizes results so unchanged messages cost ~nothing,
    // keeping scrolling smooth. Plain LinkedHashMap (no Android dep) so the parser stays JVM-testable.
    private const val PARSE_CACHE_MAX = 256
    private val parseCache = object : LinkedHashMap<String, List<Segment>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<Segment>>) = size > PARSE_CACHE_MAX
    }

    @Synchronized
    fun parse(content: String): List<Segment> {
        parseCache[content]?.let { return it }
        val result = parseUncached(content)
        parseCache[content] = result
        return result
    }

    private fun parseUncached(content: String): List<Segment> {
        val (reasoning, body0) = extractReasoning(content)
        val keryx = extractKeryx(body0)
        val body = keryx.text
        val segments = mutableListOf<Segment>()
        if (reasoning != null) segments.add(Segment.Thinking(reasoning))
        val lines = body.lines()
        val textBuf = StringBuilder()
        val thinkBuf = StringBuilder()
        val toolBuf = mutableListOf<ToolCall>()

        fun flushText() {
            val t = textBuf.toString().trim('\n')
            if (t.isNotBlank()) segments.add(Segment.Text(t))
            textBuf.setLength(0)
        }
        fun flushThink() {
            val t = thinkBuf.toString().trim('\n')
            if (t.isNotBlank()) segments.add(Segment.Thinking(t))
            thinkBuf.setLength(0)
        }
        fun flushTools() {
            if (toolBuf.isNotEmpty()) {
                segments.add(Segment.Tools(toolBuf.toList()))
                toolBuf.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            val trimmed = line.trimStart(' ', '\t', '*', '-', '•')
            val tool = parseTool(trimmed)
            val headerTool = parseHeaderTool(lines, i)
            when {
                line.trimStart().lowercase().startsWith("```mermaid") -> {
                    flushText(); flushThink(); flushTools()
                    val body = StringBuilder()
                    i++ // past the opening ```mermaid fence
                    while (i < lines.size && lines[i].trimStart().trimEnd() != "```") {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // past the closing ``` fence
                    segments.add(Segment.Mermaid(body.toString().trim('\n')))
                    continue
                }
                line.contains("|") && next != null && SEPARATOR.matches(next) -> {
                    flushText(); flushThink(); flushTools()
                    val header = splitRow(line)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                        rows.add(splitRow(lines[i]))
                        i++
                    }
                    segments.add(Segment.Table(header, rows))
                    continue
                }
                trimmed.startsWith(BRAIN) -> {
                    flushText(); flushTools()
                    thinkBuf.append(trimmed.removePrefix(BRAIN).trim()).append('\n')
                    i++
                }
                tool != null -> {
                    flushText(); flushThink()
                    toolBuf.add(tool)
                    i++
                }
                headerTool != null -> {
                    flushText(); flushThink()
                    toolBuf.add(headerTool.first)
                    i = headerTool.second
                }
                else -> {
                    flushThink(); flushTools()
                    textBuf.append(line).append('\n')
                    i++
                }
            }
        }
        flushText(); flushThink(); flushTools()
        keryx.skill?.let { segments.add(it) }
        if (keryx.citations.isNotEmpty()) segments.add(Segment.Citations(keryx.citations))
        return segments
    }

    /** Parse a single line as a tool call, or null if it isn't one. */
    private fun parseTool(trimmed: String): ToolCall? {
        val m = TOOL_LINE.matchEntire(trimmed.trim()) ?: return null
        val (emoji, name, rawArgs) = m.destructured
        // Reject prose: the leading glyph must be a symbol/emoji, not a word.
        if (emoji.first().isLetterOrDigit()) return null
        return ToolCall(emoji = emoji.trimEnd('️'), name = name, args = cleanArgs(rawArgs))
    }

    /**
     * Tidy a tool's argument string for display: drop the markdown code wrappers Hermes often puts
     * around a command (a surrounding ``` ``` ``` fence or a single `…` backtick pair) and the quote
     * marks around a quoted arg. We only peel a *wrapping* layer, so backticks/quotes that are part
     * of the command itself (e.g. shell `$(…)` substitution, an inner quoted word) are preserved.
     */
    private fun cleanArgs(raw: String): String {
        var s = raw.trim()
        // Strip a wrapping triple-backtick fence (optionally with a language tag), then collapse a
        // single wrapping backtick pair; finally peel surrounding straight/smart quotes.
        Regex("""(?s)^```[a-zA-Z0-9]*\n?(.*?)\n?```$""").matchEntire(s)?.let { s = it.groupValues[1].trim() }
        if (s.length >= 2 && s.startsWith("`") && s.endsWith("`")) s = s.trim('`').trim()
        s = s.trim('"', '“', '”').trim()
        return s
    }

    /**
     * Detect a bare tool header at [start] (`<glyph> name`, no colon) immediately followed by a ```
     * fenced block, and fold the fence in as the call's args. Returns the call + the index just past
     * the closing fence, or null if this isn't that shape (so normal parsing continues).
     */
    private fun parseHeaderTool(lines: List<String>, start: Int): Pair<ToolCall, Int>? {
        val header = lines[start].trimStart(' ', '\t', '*', '-', '•').trim()
        val m = TOOL_HEADER.matchEntire(header) ?: return null
        val emoji = m.groupValues[1]
        if (emoji.first().isLetterOrDigit()) return null // prose, not a tool glyph
        // Next non-blank line must open a code fence.
        var j = start + 1
        while (j < lines.size && lines[j].isBlank()) j++
        if (j >= lines.size || !lines[j].trimStart().startsWith("```")) return null
        j++ // past opening fence
        val cmd = StringBuilder()
        while (j < lines.size && lines[j].trimStart().trimEnd() != "```") {
            cmd.append(lines[j]).append('\n'); j++
        }
        if (j < lines.size) j++ // past closing fence
        return ToolCall(emoji = emoji.trimEnd('️'), name = m.groupValues[2], args = cmd.toString().trim('\n')) to j
    }

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }
}
