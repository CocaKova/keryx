package chat.keryx.app.presentation.ui.components

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Pure (Compose-free, unit-testable) parser that splits a Hermes/Matrix markdown message into
 * renderable segments: plain text, GFM tables, 🧠 thinking lines, ⚙️ tool-call lines, telemetry
 * footers/check-ins, and structured tool JSON ("Action Output" cards).
 *
 * Robustness contract (this parser sees raw, sometimes *mid-stream* AI output):
 *  - an unclosed ``` fence must never eat the rest of the conversation ([closeDanglingFences]);
 *  - a trailing half-written ⟦marker⟧ or fence from a live stream is stripped, not rendered
 *    ([sanitizeStreamingTail]);
 *  - malformed JSON in a tool payload degrades to a plain code block, never a crash.
 */
object MessageParser {

    data class ToolCall(
        val emoji: String,
        val name: String,
        val args: String,
        /** null = announced/running (no verdict in the text); true/false = explicit ✓ / ❌ verdict. */
        val ok: Boolean? = null,
    )

    data class Citation(val n: Int, val kind: String, val label: String, val detail: String)

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Table(val header: List<String>, val rows: List<List<String>>) : Segment
        data class Thinking(val text: String) : Segment
        data class Tools(val calls: List<ToolCall>) : Segment
        data class Mermaid(val code: String) : Segment
        data class Citations(val items: List<Citation>) : Segment
        data class SkillDistilled(val id: String, val name: String, val summary: String) : Segment

        /** Automated agent output that is not dialogue: the runtime footer (`model · 42% · ~/dir`),
         *  cron/heartbeat check-ins, background-completion notices. Rendered low-contrast. */
        data class Telemetry(val text: String, val kind: TelemetryKind) : Segment

        /**
         * A structured tool invocation/result payload that would otherwise render as a raw JSON
         * wall. [tool] is the tool/action name; [params] the flattened top-level arguments;
         * [result] a short human summary of the result field if present; [success] the parsed
         * status; [raw] the original JSON for the expandable detail view.
         */
        data class ActionOutput(
            val tool: String,
            val params: List<Pair<String, String>>,
            val result: String?,
            val success: Boolean?,
            val raw: String,
        ) : Segment
    }

    enum class TelemetryKind { FOOTER, CHECKIN, SUBTEXT }

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

    // Fallback for when Hermes drops the leading glyph (notably repeated `terminal` calls): a bare
    // `name: "args"` line where the WHOLE argument is quote-wrapped. The full-line quoting is the
    // strong tool signal that keeps this from firing on ordinary prose like `note: something`.
    private val TOOL_LINE_NOGLYPH =
        Regex("""^([a-z][a-z0-9]*(?:_[a-z0-9]+)*):\s*(["“`].*["”`])$""")

    // Some tools (notably `terminal`) render as a bare header line `<glyph> name` (NO colon) with the
    // command/args in a following ``` fenced block, instead of inline after a colon. Match the header
    // here; the loop then pulls the fence in as the args so it compacts into the tool card too.
    private val TOOL_HEADER = Regex("""^(\S+)\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*)$""")

    // Glyphs that mark a tool line as failed. (The default announcement carries no verdict.)
    private val FAIL_GLYPHS = setOf("❌", "✖", "✗", "🚫")
    private val OK_GLYPHS = setOf("✅", "✔", "✓")

    // --- Telemetry ------------------------------------------------------------------------------
    // The gateway runtime footer is `model · 42% · ~/dir` appended as the message's final line
    // (fields can drop out individually — see gateway/runtime_footer.py). We require the " · "
    // separator plus at least one part that is unambiguously footer-ish (a percentage or a path),
    // so prose that merely contains a middle dot never matches.
    private val FOOTER_PART_PCT = Regex("""^\d{1,3}%$""")
    private fun isFooterLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 160 || '\n' in t) return false
        val parts = t.split(" · ")
        if (parts.size < 2) return false
        return parts.any { p -> FOOTER_PART_PCT.matches(p) || p.startsWith("~/") || p.startsWith("~") && p.length <= 2 || p.startsWith("/") }
    }

    // Discord-style "subtext" lines Hermes emits for quiet asides (`-# like this`).
    private fun isSubtextLine(line: String) = line.trimStart().startsWith("-# ")

    // Message-level automated check-in signals: cron briefs, heartbeats, background completions.
    // Deliberately conservative — a false DIALOGUE is harmless, a false TELEMETRY hides an answer.
    private val CHECKIN_PREFIXES = listOf(
        "⏰", "🔔", "🛰", "📊", "🫀",
        "[cron]", "[telemetry]", "[sync]", "[heartbeat]", "[status]", "[background]",
    )
    private val CHECKIN_PATTERNS = listOf(
        Regex("""^(cron|heartbeat|status update|scheduled task|background (task|process|job))\b""", RegexOption.IGNORE_CASE),
    )

    /** True when a whole message is automated telemetry rather than dialogue (drives the
     *  low-contrast block render and keeps telemetry from breaking tool-run grouping). */
    fun isTelemetryMessage(content: String): Boolean {
        val body = extractKeryx(content).let { k -> if (k.telemetry) return true else k.text }.trim()
        if (body.isBlank()) return false
        val firstLine = body.lineSequence().first().trim()
        if (CHECKIN_PREFIXES.any { firstLine.startsWith(it, ignoreCase = true) }) return true
        if (CHECKIN_PATTERNS.any { it.containsMatchIn(firstLine) }) return true
        // A message that is nothing but subtext/footer lines is pure telemetry.
        val lines = body.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.all { isSubtextLine(it) || isFooterLine(it) }
    }

    /** True for a standalone runtime footer event (`model · 42% · ~/dir`), not cron/status telemetry. */
    fun isRuntimeFooterMessage(content: String): Boolean {
        val body = extractKeryx(content).text.trim()
        if (body.isBlank()) return false
        val lines = body.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.all { isFooterLine(it) }
    }

    // --- Streaming resilience ---------------------------------------------------------------

    /** Append a closing ``` when a message ends inside an open fence, so a half-streamed (or just
     *  sloppy) code block renders as code instead of swallowing everything after it. */
    fun closeDanglingFences(text: String): String {
        var open = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) open = !open
        }
        return if (open) text + "\n```" else text
    }

    /** Trim artifacts a live token stream leaves at the tail: a half-written ⟦marker⟧, a lone
     *  partial fence opener, or dangling inline-marker garbage. Only touches the very end. */
    fun sanitizeStreamingTail(text: String): String {
        var s = text
        // Unterminated ⟦…  marker at the end (a complete marker pair is left alone).
        val lastOpen = s.lastIndexOf('⟦')
        if (lastOpen >= 0 && s.indexOf('⟧', lastOpen) < 0) s = s.substring(0, lastOpen)
        // A trailing line that is just 1–2 backticks (a fence being typed).
        val lines = s.lines()
        if (lines.isNotEmpty() && lines.last().trim().matches(Regex("^`{1,2}$"))) {
            s = lines.dropLast(1).joinToString("\n")
        }
        return s.trimEnd()
    }

    // --- Reasoning extraction ----------------------------------------------------------------
    // Hermes surfaces model reasoning (when display.show_reasoning is on) by *prepending* it to the
    // answer in one of three styles; we also accept raw <think>/<thinking>/<reasoning> tags that
    // some brains/homeservers leak. We pull the reasoning out into a leading Thinking segment so it
    // renders as the dream canvas and the answer renders clean.
    private val THINK_TAG = Regex("""(?is)<(think|thinking|reasoning)>(.*?)</\1>""")
    // A tag opened but never closed (mid-stream, or a brain that forgot): everything after it is
    // reasoning-so-far — treat it as such rather than leaking raw tag text into the bubble.
    private val THINK_TAG_OPEN = Regex("""(?is)<(think|thinking|reasoning)>""")
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
        // 1b) An unclosed tag (mid-stream): the tail is reasoning-in-progress.
        THINK_TAG_OPEN.find(content)?.let { m ->
            val before = content.substring(0, m.range.first).trim()
            val inside = content.substring(m.range.last + 1).trim()
            return inside.ifBlank { null } to before
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
    private val TELEM_MARK = Regex("""⟦keryx:telemetry⟧""")
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
        /** ⟦keryx:telemetry⟧ marker — the emitter explicitly flags this message as automated. */
        val telemetry: Boolean = false,
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
        val telemetry = TELEM_MARK.containsMatchIn(text)
        var out = CITE_SOURCE.replace(text, "")
        out = SKILL_MARK.replace(out, "")
        out = TELEM_MARK.replace(out, "")
        out = BEACON.replace(out, "")
        out = CITE_INLINE.replace(out) { "⁽${superscript(it.groupValues[1].toIntOrNull() ?: 0)}⁾" }
        return Keryx(out.trim('\n', ' '), citations, skill, true, telemetry)
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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        val telemetryBuf = StringBuilder()
        var telemetryKind = TelemetryKind.SUBTEXT

        fun flushText() {
            val t = textBuf.toString().trim('\n')
            if (t.isNotBlank()) {
                // A text segment that is one whole JSON tool payload → an Action Output card.
                val action = tryParseActionOutput(t)
                if (action != null) segments.add(action) else segments.add(Segment.Text(t))
            }
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
        fun flushTelemetry() {
            val t = telemetryBuf.toString().trim('\n')
            if (t.isNotBlank()) segments.add(Segment.Telemetry(t, telemetryKind))
            telemetryBuf.setLength(0)
        }
        fun flushAll() { flushText(); flushThink(); flushTools(); flushTelemetry() }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            val trimmed = line.trimStart(' ', '\t', '*', '-', '•')
            val tool = parseTool(trimmed)
            val headerTool = parseHeaderTool(lines, i)
            // Only the message's LAST non-blank line can be the runtime footer.
            val isLastContentLine = lines.drop(i + 1).all { it.isBlank() }
            when {
                line.trimStart().lowercase().startsWith("```mermaid") -> {
                    flushAll()
                    val code = StringBuilder()
                    i++ // past the opening ```mermaid fence
                    while (i < lines.size && lines[i].trimStart().trimEnd() != "```") {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // past the closing ``` fence (tolerates EOF: loop simply ends)
                    segments.add(Segment.Mermaid(code.toString().trim('\n')))
                    continue
                }
                // ```json fences get a structured-parse attempt (Action Output card); on any
                // failure the raw fence flows into the text buffer and renders as a code block.
                line.trimStart().lowercase().startsWith("```json") -> {
                    val fence = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && lines[j].trimStart().trimEnd() != "```") {
                        fence.append(lines[j]).append('\n'); j++
                    }
                    val closed = j < lines.size
                    val action = tryParseActionOutput(fence.toString().trim('\n'))
                    if (action != null) {
                        flushAll()
                        segments.add(action)
                        i = if (closed) j + 1 else j
                    } else {
                        // Not a tool payload — keep it as ordinary markdown (incl. the fence).
                        textBuf.append(line).append('\n')
                        i++
                    }
                    continue
                }
                // Any other fence is opaque markdown: copy it through verbatim so tool/think/table
                // detection never fires on code SAMPLES inside the fence (nested-content safety).
                line.trimStart().startsWith("```") -> {
                    textBuf.append(line).append('\n')
                    i++
                    while (i < lines.size) {
                        textBuf.append(lines[i]).append('\n')
                        if (lines[i].trimStart().trimEnd() == "```") { i++; break }
                        i++
                    }
                    continue
                }
                line.contains("|") && next != null && SEPARATOR.matches(next) -> {
                    flushAll()
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
                    flushText(); flushTools(); flushTelemetry()
                    thinkBuf.append(trimmed.removePrefix(BRAIN).trim()).append('\n')
                    i++
                }
                isSubtextLine(line) -> {
                    flushText(); flushThink(); flushTools()
                    telemetryKind = TelemetryKind.SUBTEXT
                    telemetryBuf.append(line.trimStart().removePrefix("-#").trimStart()).append('\n')
                    i++
                }
                isLastContentLine && line.isNotBlank() && isFooterLine(line) -> {
                    flushAll()
                    segments.add(Segment.Telemetry(line.trim(), TelemetryKind.FOOTER))
                    i++
                }
                tool != null -> {
                    flushText(); flushThink(); flushTelemetry()
                    toolBuf.add(tool)
                    i++
                }
                headerTool != null -> {
                    flushText(); flushThink(); flushTelemetry()
                    toolBuf.add(headerTool.first)
                    i = headerTool.second
                }
                else -> {
                    flushThink(); flushTools(); flushTelemetry()
                    textBuf.append(line).append('\n')
                    i++
                }
            }
        }
        flushAll()
        keryx.skill?.let { segments.add(it) }
        if (keryx.citations.isNotEmpty()) segments.add(Segment.Citations(keryx.citations))
        return segments
    }

    /** Parse a single line as a tool call, or null if it isn't one. */
    private fun parseTool(trimmed: String): ToolCall? {
        val line = trimmed.trim()
        TOOL_LINE.matchEntire(line)?.let { m ->
            val (emoji, name, rawArgs) = m.destructured
            // Reject prose: the leading glyph must be a symbol/emoji, not a word.
            if (!emoji.first().isLetterOrDigit()) {
                val glyph = emoji.trimEnd('️')
                val ok = when {
                    glyph in FAIL_GLYPHS -> false
                    glyph in OK_GLYPHS -> true
                    else -> null
                }
                val (args, verdict) = stripTrailingVerdict(cleanArgs(rawArgs))
                return ToolCall(emoji = glyph, name = name, args = args, ok = verdict ?: ok)
            }
        }
        // Glyph-less fallback (e.g. an emoji-less `terminal: "…"` repeat).
        TOOL_LINE_NOGLYPH.matchEntire(line)?.let { m ->
            val (args, verdict) = stripTrailingVerdict(cleanArgs(m.groupValues[2]))
            return ToolCall(emoji = "", name = m.groupValues[1], args = args, ok = verdict)
        }
        return null
    }

    /** Pull a trailing ` ✓` / ` ❌` / ` (failed)` verdict off a tool's arg string. */
    private fun stripTrailingVerdict(args: String): Pair<String, Boolean?> {
        val t = args.trimEnd()
        for (g in OK_GLYPHS) if (t.endsWith(g)) return t.dropLast(g.length).trimEnd() to true
        for (g in FAIL_GLYPHS) if (t.endsWith(g)) return t.dropLast(g.length).trimEnd() to false
        if (t.endsWith("(failed)", ignoreCase = true)) return t.dropLast(8).trimEnd() to false
        return t to null
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
        val glyph = emoji.trimEnd('️')
        val ok = when {
            glyph in FAIL_GLYPHS -> false
            glyph in OK_GLYPHS -> true
            else -> null
        }
        return ToolCall(emoji = glyph, name = m.groupValues[2], args = cmd.toString().trim('\n'), ok = ok) to j
    }

    // --- Action Output (structured tool JSON) --------------------------------------------------

    private val TOOL_NAME_KEYS = listOf("tool", "action", "name", "tool_name", "function")
    private val PARAM_KEYS = listOf("arguments", "args", "params", "parameters", "input")
    private val RESULT_KEYS = listOf("result", "output", "stdout", "response", "observation")

    /**
     * Try to read [raw] as one structured tool payload. Requirements: the whole segment is a single
     * JSON object AND it names a tool — a random JSON example in prose stays a code block. Trailing
     * garbage after the closing brace (a classic messy-model artifact) is tolerated by cutting at
     * the last `}` first. Never throws.
     */
    fun tryParseActionOutput(raw: String): Segment.ActionOutput? {
        val t = raw.trim()
        if (!t.startsWith("{")) return null
        val cut = t.lastIndexOf('}')
        if (cut < 0) return null
        val candidate = t.substring(0, cut + 1)
        val obj = try {
            json.parseToJsonElement(candidate).jsonObject
        } catch (e: Exception) {
            return null
        }
        val tool = TOOL_NAME_KEYS.firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.content }
            ?: return null
        val paramsObj = PARAM_KEYS.firstNotNullOfOrNull { k -> obj[k] as? JsonObject }
        val params = paramsObj?.entries?.map { (k, v) ->
            k to ((v as? JsonPrimitive)?.content ?: v.toString()).let { s -> if (s.length > 200) s.take(200) + "…" else s }
        }.orEmpty()
        val result = RESULT_KEYS.firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.content }
            ?.let { if (it.length > 400) it.take(400) + "…" else it }
        val success = when (((obj["status"] ?: obj["success"] ?: obj["ok"]) as? JsonPrimitive)?.content?.lowercase()) {
            "success", "ok", "true", "completed", "done" -> true
            "failure", "failed", "error", "false" -> false
            else -> if (obj.containsKey("error")) false else null
        }
        return Segment.ActionOutput(tool = tool, params = params, result = result, success = success, raw = candidate)
    }

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }
}
