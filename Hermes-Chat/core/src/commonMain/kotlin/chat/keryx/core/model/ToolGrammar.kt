package chat.keryx.core.model

/**
 * One vocabulary for tool calls, wherever they are drawn.
 *
 * Keryx renders the same call twice: live, from the side-channel's structured frames
 * ([Theater] / `TheaterStage`), and afterwards, parsed back out of the committed message text
 * (`ToolGroupCard`). Those two grew up apart and read as two different features in one
 * transcript — a boxed gradient card and a monospace hairline row describing the same
 * `read_file`. This is the shared grammar that ends that: same glyph, same verb, same way of
 * naming what was acted on, so the live view and the record are the same thing at two ages.
 *
 * Ported from Talaria's `ToolTheater` (itself a port of the desktop's TOOL_META), with one
 * difference: Talaria has structured JSON args, Keryx often has only a display string, so the
 * target is read out of text rather than dug out of a payload.
 */
object ToolGrammar {

    data class Verb(val past: String, val present: String, val glyph: String)

    private val VERBS: Map<String, Verb> = mapOf(
        "terminal" to Verb("Ran", "Running", "❯"),
        "execute_code" to Verb("Ran", "Running", "❯"),
        "read_file" to Verb("Read", "Reading", "▤"),
        "list_files" to Verb("Listed", "Listing", "▤"),
        "search_files" to Verb("Searched files", "Searching files", "⌕"),
        "web_search" to Verb("Searched web", "Searching web", "⌕"),
        "web_extract" to Verb("Extracted", "Extracting", "◍"),
        "browser_navigate" to Verb("Opened", "Opening", "◍"),
        "edit_file" to Verb("Edited", "Editing", "✎"),
        "patch" to Verb("Edited", "Editing", "✎"),
        "write_file" to Verb("Wrote", "Writing", "✎"),
        "memory" to Verb("Saved to memory", "Saving to memory", "✦"),
        "skill_manage" to Verb("Saved skill", "Saving skill", "✦"),
        "vision_analyze" to Verb("Analyzed image", "Analyzing image", "◉"),
        "image_generate" to Verb("Generated image", "Generating image", "▣"),
        "text_to_speech" to Verb("Spoke", "Speaking", "♪"),
        "delegate_task" to Verb("Delegated", "Delegating", "⑂"),
        "video_generate" to Verb("Generated video", "Generating video", "▣"),
        "session_search" to Verb("Searched sessions", "Searching sessions", "⌕"),
        "skill_view" to Verb("Read skill", "Reading skill", "✦"),
        "skills_list" to Verb("Listed skills", "Listing skills", "✦"),
        "browser_click" to Verb("Clicked", "Clicking", "◍"),
        "browser_type" to Verb("Typed", "Typing", "◍"),
        "clarify" to Verb("Asked", "Asking", "?"),
        "cronjob" to Verb("Scheduled", "Scheduling", "◷"),
        "session_search_recall" to Verb("Recalled", "Recalling", "⌕"),
        "todo" to Verb("Updated todos", "Updating todos", "⚙"),
    )

    fun verbOf(name: String): Verb =
        VERBS[name]
            ?: if (name.startsWith("browser_")) Verb("Browsed", "Browsing", "◍")
            else Verb("Used ${friendly(name)}", "Using ${friendly(name)}", "⚙")

    /** Success is silent, so the glyph is the tool's identity, not its verdict. */
    fun glyphOf(name: String): String = verbOf(name).glyph

    fun friendly(name: String): String = name.replace('_', ' ')

    /** File-ish tools name a path; showing all of it buries the part that identifies it. */
    private val PATH_TOOLS = setOf("read_file", "write_file", "edit_file", "patch", "list_files")

    /** Tools whose argument is machinery, not a thing the reader wants named. */
    private val TARGETLESS =
        setOf("memory", "skill_manage", "todo", "text_to_speech", "image_generate")

    /**
     * The thing the verb acted on, from whatever text this surface has — a gateway preview, or
     * the argument the parser lifted out of the committed message.
     */
    fun targetOf(name: String, raw: String): String {
        if (name in TARGETLESS) return ""
        var t = raw.trim().trim('"', '“', '”', '`', '\'')
        if (t.isBlank()) return ""
        if (name in PATH_TOOLS) t = basename(t)
        if (name == "web_extract" || name.startsWith("browser_")) t = hostname(t)
        return t.replace(WHITESPACE, " ").trim().take(80)
    }

    private val WHITESPACE = Regex("\\s+")

    private fun basename(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    private fun hostname(url: String): String =
        HOST.find(url)?.groupValues?.get(1) ?: url.take(40)

    private val HOST = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)")

    /** "Read SOUL.md" / "Reading SOUL.md" — or the bare verb when there is nothing to name. */
    fun title(name: String, target: String, running: Boolean): String {
        val verb = verbOf(name).let { if (running) it.present else it.past }
        return if (target.isBlank()) verb else "$verb $target"
    }

    // ---- friendly progress lines ---------------------------------------------------------

    /**
     * The gateway prints *two* kinds of tool line. One names the tool (`read_file: "a.txt"`); the
     * other is human-phrased progress — `📖 Reading a.txt`, `🌐 Searching the web for keryx` — built
     * from `agent/display.py`'s `_TOOL_VERBS`. The second kind names a VERB, and the parser handed
     * that word on as the tool name, so this grammar had no entry for it and fell through to the
     * generic gear: the committed card read "Used Reading a.txt" while the live theater, which
     * gets real tool ids off the side-channel, read "▤ Read a.txt" — the two surfaces fighting
     * again, in the one place the shared grammar exists to stop it.
     *
     * Worse, and invisibly: [Theater.align] pairs the live turn to the committed text BY NAME, so
     * the mismatch also cost every enriched fact — duration, real verdict, diff stats — on any
     * turn the agent narrated this way. Mapping the phrase back to its tool is what makes one call
     * read as one call.
     *
     * Longest phrase first: "Reading skill" is not "Reading", and "Running code" is not "Running".
     */
    private val GERUND_TOOLS: List<Pair<String, String>> = listOf(
        "Searching past sessions" to "session_search",
        "Looking at the image" to "vision_analyze",
        "Searching the web" to "web_search",
        "Generating image" to "image_generate",
        "Generating video" to "video_generate",
        "Generating speech" to "text_to_speech",
        "Searching files" to "search_files",
        "Updating memory" to "memory",
        "Updating tasks" to "todo",
        "Updating skill" to "skill_manage",
        "Listing skills" to "skills_list",
        "Reading skill" to "skill_view",
        "Running code" to "execute_code",
        "Delegating" to "delegate_task",
        "Scheduling" to "cronjob",
        "Browsing" to "browser_navigate",
        "Clicking" to "browser_click",
        "Typing" to "browser_type",
        "Writing" to "write_file",
        "Editing" to "patch",
        "Running" to "terminal",
        "Asking" to "clarify",
        "Reading" to "read_file",
    ).sortedByDescending { it.first.length }

    /** A friendly progress line resolved back to the tool that printed it. */
    data class Friendly(val name: String, val target: String)

    private val URLISH = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://|^www\.""")

    /**
     * Resolve `🌐 Searching the web for keryx` to `web_search` + `keryx`. Returns null when the
     * verb is not one the gateway prints, so an unknown gerund keeps reading exactly as it did.
     */
    fun fromFriendly(verb: String, rest: String): Friendly? {
        val whole = if (rest.isBlank()) verb else "$verb $rest"
        for ((phrase, tool) in GERUND_TOOLS) {
            if (!whole.startsWith(phrase)) continue
            // The phrase has to end on a word boundary, or "Read" would swallow "Readiness".
            val after = whole.drop(phrase.length)
            if (after.isNotEmpty() && !after.first().isWhitespace()) continue
            // "Searching the web FOR <query>" — the connector belongs to the phrase, not the target.
            val target = after.trim().removePrefix("for ").trim()
            // `read_file` and `web_extract` share the word "Reading"; only one of them reads URLs.
            val name =
                if (tool == "read_file" && URLISH.containsMatchIn(target)) "web_extract" else tool
            return Friendly(name, target)
        }
        return null
    }

    // ---- run summary ---------------------------------------------------------------------

    enum class Category(val order: Int) { EDIT(0), EXPLORE(1), RUN(2), DELEGATE(3), OTHER(4) }

    fun categoryOf(name: String): Category = when (name) {
        "edit_file", "patch", "write_file" -> Category.EDIT
        "read_file", "list_files", "search_files" -> Category.EXPLORE
        "terminal", "execute_code" -> Category.RUN
        "delegate_task" -> Category.DELEGATE
        else -> Category.OTHER
    }

    /** The least a summary needs to know about a call, so both surfaces can feed it. */
    data class Mention(val name: String, val target: String = "", val running: Boolean = false)

    private fun clause(cat: Category, calls: List<Mention>, present: Boolean): String {
        val n = calls.size
        val (pastV, presV, noun) = when (cat) {
            Category.EDIT -> Triple("Edited", "Editing", "file")
            Category.EXPLORE -> Triple("Explored", "Exploring", "file")
            Category.RUN -> Triple("Ran", "Running", "command")
            Category.DELEGATE -> Triple("Delegated", "Delegating", "task")
            Category.OTHER -> Triple("Used", "Using", "tool")
        }
        if (n == 1) {
            // One call is already its own sentence, so it gets the TOOL's verb rather than the
            // category's — "Read SOUL.md", not "Explored SOUL.md". (A divergence from Talaria,
            // which uses the category verb throughout; the category grammar exists to count a
            // crowd, and there is no crowd here.)
            val only = calls[0]
            val t = only.target.ifBlank { friendly(only.name) }.take(40)
            if (t.isNotBlank()) return title(only.name, t, running = present)
        }
        val verb = if (present) presV else pastV
        return "$verb $n $noun${if (n == 1) "" else "s"}"
    }

    /**
     * "Explored 3 files, ran 5 commands" — fixed clause order, later clauses lower-cased, and
     * the category holding the still-open call speaking present tense.
     */
    fun summarize(calls: List<Mention>, live: Boolean): String {
        if (calls.isEmpty()) return if (live) "Working…" else "Worked"
        val byCat = calls.groupBy { categoryOf(it.name) }.toSortedMap(compareBy { it.order })
        val pendingCat = calls.lastOrNull { it.running }?.let { categoryOf(it.name) }
            ?: if (live) categoryOf(calls.last().name) else null
        return byCat.map { (cat, cs) -> clause(cat, cs, present = live && cat == pendingCat) }
            .mapIndexed { i, c -> if (i == 0) c else c.replaceFirstChar { ch -> ch.lowercaseChar() } }
            .joinToString(", ")
    }

    // ---- diffs ---------------------------------------------------------------------------

    enum class DiffKind { ADD, REMOVE, CONTEXT, GAP }

    data class DiffLine(val kind: DiffKind, val text: String)

    /**
     * The gateway's inline diff, stripped to what a phone can read: git chrome and file headers
     * gone, the +/- gutter marker gone (the colour says it), hunks separated by a blank row
     * instead of an `@@` line.
     *
     * WARNING: the rendered lines are ANSI-coloured (`ESC[38;2;...m+line ESC[0m`), so anything
     * classifying by leading character has to strip that first or it sees nothing at all.
     */
    fun diffLines(diff: String): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        var seenHunk = false
        for (raw in diff.lineSequence()) {
            val l = ANSI.replace(raw, "")
            val isChrome = !seenHunk && (
                l.startsWith("diff --git") || l.startsWith("index ") || l.startsWith("--- ") ||
                    l.startsWith("+++ ") || l.startsWith("similarity ") ||
                    l.startsWith("rename ") || l.startsWith("new file") ||
                    l.startsWith("deleted file") || l.contains(" → ") ||
                    l.trimStart().startsWith("┊")
                )
            if (isChrome) continue
            if (l.startsWith("@@")) {
                if (seenHunk) out += DiffLine(DiffKind.GAP, "")
                seenHunk = true
                continue
            }
            if (!seenHunk && l.isBlank()) continue
            seenHunk = true
            out += when {
                l.startsWith("+") -> DiffLine(DiffKind.ADD, l.drop(1))
                l.startsWith("-") -> DiffLine(DiffKind.REMOVE, l.drop(1))
                else -> DiffLine(DiffKind.CONTEXT, l.removePrefix(" "))
            }
        }
        return out
    }

    /** (+added, -removed) by the SAME classification the panel draws, so the two cannot disagree. */
    fun diffStats(diff: String): Pair<Int, Int> {
        var add = 0
        var rem = 0
        for (l in diffLines(diff)) {
            when (l.kind) {
                DiffKind.ADD -> add++
                DiffKind.REMOVE -> rem++
                else -> Unit
            }
        }
        return add to rem
    }

    // An escape literal, not a pasted ESC byte: an invisible control character in source is
    // exactly the kind of thing that survives one refactor and not the next.
    private val ANSI = Regex("\\u001B\\[[0-9;]*m")
}
