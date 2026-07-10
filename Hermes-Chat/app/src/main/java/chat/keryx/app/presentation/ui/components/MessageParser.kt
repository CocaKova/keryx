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
     *
     * The colon must be followed by whitespace (or end the line — an argless call). Hermes always
     * puts a space there (`name: "args"`), while a URI never does (`https://…`, `mailto:me@x.y`) —
     * that one grammar fact is what keeps a glyph-prefixed link (`🔗 https://github.com/…`) from
     * parsing as a tool named "https" and dragging its whole message into the tool-run log
     * (live-caught on the v1.15 release post, 2026-07-08). Tools taking URL *args* are unaffected:
     * `🌐 web_extract: https://…` has the space.
     */
    private val TOOL_LINE = Regex("""^(\S+)\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)*):(?:\s+(.*))?$""")

    // Fallback for when Hermes drops the leading glyph (notably repeated `terminal` calls): a bare
    // `name: "args"` line where the WHOLE argument is quote-wrapped. The full-line quoting is the
    // strong tool signal that keeps this from firing on ordinary prose like `note: something`.
    private val TOOL_LINE_NOGLYPH =
        Regex("""^([a-z][a-z0-9]*(?:_[a-z0-9]+)*):\s*(["“`].*["”`])$""")

    // Hermes also emits human-readable progress lines for some tools: `📖 Reading consolidate.py
    // L80-89`, `🔧 Editing /path/to/file (×2)` — a leading glyph, a capitalized gerund, then the
    // target, no colon. Conservative guards keep prose out: the glyph must be a symbol, the verb
    // must end in "ing", and the line must not read as a sentence (no terminal punctuation).
    private val TOOL_LINE_GERUND = Regex("""^(\S+)\s+([A-Z][a-z]+ing)\s+(\S.*)$""")

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
        // Gateway control acks (a /steer confirmation) and "⏳ Working — iteration 5/90" progress
        // heartbeats are plumbing, not dialogue — rendering them as telemetry keeps them from
        // ending a live tool run (or masquerading as tool calls).
        "⏩", "⏳",
        // Context compaction ("🗜️ Compacting context — summarizing earlier conversation…") and
        // the post-turn self-improvement review ("💾 Self-improvement review: …") are gateway
        // plumbing too: without these prefixes the compactor line parses as a phantom
        // "Compacting" tool call and the review message reads as a fresh answer (re-lighting
        // the working banner for the full QUIET_LONG window).
        "🗜", "💾",
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

    // --- Self-improvement review ------------------------------------------------------------
    // The gateway's background memory/skill review posts ONE post-turn summary message, its
    // actions joined with " · " (agent/background_review.py). Verbose-mode item shapes:
    //   📝 Skill 'name' patched: "old…" → "new…"  /  created: description  /  rewritten: …
    //   Memory ➕ preview  ·  Memory ✏️ preview  ·  User profile ➖ preview
    // Generic ("on") mode items are raw tool messages ("Skill 'x' updated", "Memory entry
    // created"). Skill items surface as SkillDistilled pills (tap → Skill Forge); the rest
    // renders as a quiet telemetry block, one action per line instead of one " · " mega-line.
    private val REVIEW_PREFIX = Regex("""^💾\s*Self-improvement review:\s*""")
    private val REVIEW_SKILL_ITEM =
        Regex("""^(?:📝\s*)?Skill\s+['"“]?(.+?)['"”]?\s+(patched|created|rewritten|updated)\b[:.]?\s*(.*)$""")

    /** True when a message is the background review's post-turn summary. */
    fun isSelfImprovementReview(content: String): Boolean =
        REVIEW_PREFIX.containsMatchIn(extractKeryx(content).text.trim())

    /** Segments for a review summary, or null when [body] isn't one. */
    private fun parseSelfImprovementReview(body: String): List<Segment>? {
        val t = body.trim()
        val m = REVIEW_PREFIX.find(t) ?: return null
        val items = t.substring(m.range.last + 1).split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val skills = mutableListOf<Segment.SkillDistilled>()
        val quiet = mutableListOf<String>()
        for (item in items) {
            val sm = REVIEW_SKILL_ITEM.matchEntire(item)
            if (sm != null) {
                val (name, verb, detail) = sm.destructured
                val summary = if (detail.isBlank()) verb else "$verb: $detail"
                skills += Segment.SkillDistilled("", name.trim(), summary.trim())
            } else {
                quiet += item
            }
        }
        val segments = mutableListOf<Segment>()
        segments += Segment.Telemetry(
            (listOf("💾 Self-improvement review") + quiet).joinToString("\n"),
            TelemetryKind.SUBTEXT,
        )
        segments += skills
        return segments
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

    // Every reasoning-tag spelling we accept; a half-typed prefix of one of these at the very end
    // of a live stream must be held back, not rendered as literal text.
    private val TAIL_TAG_CANDIDATES = listOf(
        "<think>", "<thinking>", "<reasoning>", "<thought>",
        "</think>", "</thinking>", "</reasoning>", "</thought>",
        "◁think▷", "◁/think▷",
    )

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
        s = s.trimEnd()
        // A half-typed reasoning tag at the very end ("<thin", "</think", "◁thi"): hold it back so
        // it never flashes as literal text between two token dispatches. Complete tags are left
        // alone — extractReasoning owns those.
        val tagStart = maxOf(s.lastIndexOf('<'), s.lastIndexOf('◁'))
        if (tagStart >= 0) {
            val tail = s.substring(tagStart).lowercase()
            val partial = TAIL_TAG_CANDIDATES.any { it.startsWith(tail) && it.length > tail.length }
            if (partial) s = s.substring(0, tagStart)
        }
        return s.trimEnd()
    }

    // --- Reasoning extraction ----------------------------------------------------------------
    // Hermes surfaces model reasoning (when display.show_reasoning is on) by *prepending* it to the
    // answer in one of three styles; we also accept raw <think>/<thinking>/<reasoning> tags that
    // some brains/homeservers leak. We pull the reasoning out into a leading Thinking segment so it
    // renders as the dream canvas and the answer renders clean.
    // Covers the tag zoo across model families: <think>/<thinking> (Qwen/GLM/DeepSeek-style),
    // <reasoning>, <thought> (some Gemma tunes), and Kimi's ◁think▷ delimiters.
    private val THINK_TAG = Regex("""(?is)<(think|thinking|reasoning|thought)>(.*?)</\1>|◁think▷(.*?)◁/think▷""")
    // A tag opened but never closed (mid-stream, or a brain that forgot): everything after it is
    // reasoning-so-far — treat it as such rather than leaking raw tag text into the bubble.
    private val THINK_TAG_OPEN = Regex("""(?is)<(think|thinking|reasoning|thought)>|◁think▷""")
    // "code" style:  💭 **Reasoning:**\n```\n…\n```\n\n<answer>   (bold optional — some brains
    // emit the header without the ** wrapper). The opening fence is captured (group 1) and the
    // close is a backreference to it, so a fence LONGER than any ``` run inside the reasoning
    // isn't closed early — reasoning that quotes fenced code (a thinking brain reasoning about
    // code) no longer truncates, leaking the answer into a copy-paste block. The gateway also
    // weaves a zero-width space through inner backtick runs (see keryx_stream._neutralize_fences)
    // for the same-length case; we strip that ZWSP back out of the extracted reasoning below.
    private val REASON_CODE = Regex("""(?s)^\s*💭\s*\*{0,2}Reasoning:?\*{0,2}\s*\n(`{3,})[a-zA-Z0-9]*\n(.*?)\n\1\s*(.*)$""")
    // Header lines for the "blockquote" (`> 💭 **Reasoning:**`) and "subtext" (`-# 💭 Reasoning`)
    // styles. The quoted/prefixed lines that follow are walked imperatively (not with one big
    // regex) so a blank line INSIDE the reasoning block — which the old `(?:>[^\n]*\n)+` group
    // stopped at, leaking the rest of the reasoning into the answer — stays part of the block as
    // long as another prefixed line follows.
    private val REASON_QUOTE_HEADER = Regex("""^\s*>\s*💭\s*\*{0,2}Reasoning:?\*{0,2}\s*$""")
    private val REASON_SUBTEXT_HEADER = Regex("""^\s*-#\s*💭\s*\*{0,2}Reasoning:?\*{0,2}\s*$""")

    /** A reasoning tag only OPENS a block at a block boundary: the start of the message, or
     *  preceded by nothing but whitespace since the last newline. A tag quoted mid-prose
     *  ("the adapter wraps <thought> and <reasoning> blocks…") is literal text — without this
     *  rule one quoted tag hijacked the whole message tail into the reasoning canvas
     *  (live-caught 2026-07-09 on a 💾 review quoting a stored memory entry). Same invariant
     *  the gateway's stream think-filter applies, so both ends of the pipe agree on what
     *  counts as reasoning. */
    private fun atBlockBoundary(content: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0) {
            val ch = content[i]
            if (ch == '\n') return true
            if (!ch.isWhitespace()) return false
            i--
        }
        return true
    }

    private fun findOpenTagAtBoundary(content: String) =
        THINK_TAG_OPEN.findAll(content).firstOrNull { atBlockBoundary(content, it.range.first) }

    /** Pull any leading/embedded reasoning out of [content]; returns (reasoning?, remainingBody). */
    fun extractReasoning(content: String): Pair<String?, String> {
        // 1) Raw <think> tags at a block boundary → concatenate, strip from body. A SECOND tag
        //    that is still open (mid-stream: one thought finished, the next being written) is also
        //    reasoning — without this the unclosed tail leaked into the answer as raw tag text.
        val paired = THINK_TAG.findAll(content).filter { atBlockBoundary(content, it.range.first) }.toList()
        if (paired.isNotEmpty()) {
            val parts = paired.map {
                // Group 2 = <tag> body, group 3 = ◁think▷ body (whichever alternative matched).
                it.groupValues[2].ifEmpty { it.groupValues[3] }.trim()
            }.toMutableList()
            val sb = StringBuilder(content)
            for (m in paired.asReversed()) sb.delete(m.range.first, m.range.last + 1)
            var body = sb.toString().trim()
            findOpenTagAtBoundary(body)?.let { m ->
                parts.add(body.substring(m.range.last + 1).trim())
                body = body.substring(0, m.range.first).trim()
            }
            val reasoning = parts.filter { it.isNotBlank() }.joinToString("\n\n")
            return reasoning.ifBlank { null } to body
        }
        // 1b) An unclosed tag at a block boundary (mid-stream): the tail is reasoning-in-progress.
        findOpenTagAtBoundary(content)?.let { m ->
            val before = content.substring(0, m.range.first).trim()
            val inside = content.substring(m.range.last + 1).trim()
            return inside.ifBlank { null } to before
        }
        // 2) Hermes "💭 Reasoning" prelude (code / blockquote / subtext).
        REASON_CODE.matchEntire(content)?.let { m ->
            // group 1 = opening fence, 2 = reasoning, 3 = answer body. Strip the gateway's
            // fence-neutralizing zero-width spaces so the canvas shows clean backticks.
            val reasoning = m.groupValues[2].replace("\u200B", "").trim()
            return reasoning.ifBlank { null } to m.groupValues[3].trim()
        }
        extractLinePrefixedReasoning(content, REASON_QUOTE_HEADER, ">")?.let { return it }
        extractLinePrefixedReasoning(content, REASON_SUBTEXT_HEADER, "-#")?.let { return it }
        return null to content
    }

    /** Walk a `> …`/`-# …` reasoning block that starts with [header]: consume prefixed lines, and
     *  blank lines too when more prefixed lines follow. Returns (reasoning?, body) or null when
     *  [content] doesn't open with this style. */
    private fun extractLinePrefixedReasoning(
        content: String,
        header: Regex,
        prefix: String,
    ): Pair<String?, String>? {
        val lines = content.lines()
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || !header.matches(lines[i])) return null
        i++
        val reason = StringBuilder()
        var consumedUntil = i
        var blanksPending = 0
        var j = i
        while (j < lines.size) {
            val l = lines[j]
            when {
                l.trimStart().startsWith(prefix) -> {
                    if (blanksPending > 0 && reason.isNotEmpty()) reason.append('\n')
                    blanksPending = 0
                    reason.append(l.trimStart().removePrefix(prefix).trimStart()).append('\n')
                    consumedUntil = j + 1
                    j++
                }
                l.isBlank() -> { blanksPending++; j++ }
                else -> break
            }
        }
        val body = lines.drop(consumedUntil).joinToString("\n").trim()
        return (reason.toString().trim().ifBlank { null }) to body
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
        // Markers first: the keryx plugin may prepend its ⟦keryx:v1⟧ beacon (or scatter citation
        // markers into the reasoning), and the 💭 prelude patterns are start-anchored — extracting
        // reasoning from the raw body made the whole block leak into the answer as prose.
        val keryx = extractKeryx(content)
        // A self-improvement review summary is fully structured — and its " · "-joined item
        // previews quote arbitrary stored text (fences, glyphs, even literal <think> tags), so it
        // must be recognized BEFORE any free-text heuristic runs: reasoning extraction on a review
        // that quoted a "<thought>" mention folded half the item list into the reasoning canvas
        // (live-caught 2026-07-09), and the line walker would mangle the action list into
        // tool/telemetry segments. Review messages never carry a reasoning prelude.
        parseSelfImprovementReview(keryx.text)?.let { review ->
            val segs = mutableListOf<Segment>()
            segs.addAll(review)
            keryx.skill?.let { segs.add(it) }
            if (keryx.citations.isNotEmpty()) segs.add(Segment.Citations(keryx.citations))
            return segs
        }
        val (reasoning, body) = extractReasoning(keryx.text)
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
        // Progress-style line (`📖 Reading consolidate.py L80-89`). Sentence-shaped lines are
        // rejected: the glyph must be a symbol and the line must not end like prose.
        TOOL_LINE_GERUND.matchEntire(line)?.let { m ->
            val (emoji, verb, rawArgs) = m.destructured
            val tail = rawArgs.trimEnd()
            // "..."/"…" is a truncation marker (`🔧 Editing /very/long/pa...`), not a sentence end.
            val sentenceEnd = (tail.endsWith(".") && !tail.endsWith("...")) ||
                tail.endsWith("!") || tail.endsWith("?")
            // Telemetry glyphs (⏳ Working…, ⏩ Steering…) are heartbeats, never tool calls.
            val telemetryGlyph = CHECKIN_PREFIXES.any { emoji.startsWith(it) }
            // The glyph must be a real emoji/symbol — ASCII markdown markers must not match, or
            // a heading like `## Enduring Legacy` becomes a phantom tool (live-caught 2026-07-03).
            val emojiGlyph = emoji.any { it.code >= 0x2000 }
            // A spaced em/en dash is prose typography ("🚀 Introducing Keryx — a native client…"):
            // tool progress targets are paths/ranges and never contain one. Same family of signal
            // as the sentence-end check above.
            val proseDash = tail.contains(" — ") || tail.contains(" – ")
            if (emojiGlyph && !emoji.first().isLetterOrDigit() && line.length <= 120 &&
                !sentenceEnd && !telemetryGlyph && !proseDash
            ) {
                val glyph = emoji.trimEnd('️')
                val ok = when {
                    glyph in FAIL_GLYPHS -> false
                    glyph in OK_GLYPHS -> true
                    else -> null
                }
                val (args, verdict) = stripTrailingVerdict(cleanArgs(rawArgs))
                return ToolCall(emoji = glyph, name = verb, args = args, ok = verdict ?: ok)
            }
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
