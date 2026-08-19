package chat.keryx.app

import chat.keryx.app.domain.model.Theater
import chat.keryx.app.domain.model.ToolBeat
import chat.keryx.app.domain.model.ToolGrammar
import chat.keryx.app.domain.model.ToolGrammar.DiffKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared tool vocabulary (2.4). Worth pinning because it is the thing that stops the live
 * view and the committed transcript reading as two different features — if these drift, the
 * "fight" Jonny saw on device comes back.
 */
class ToolGrammarTest {

    /** The escape byte the gateway's rendered diff carries. */
    private val ESC = "\u001B"

    // --- verbs and glyphs ----------------------------------------------------------------------

    @Test
    fun `a known tool speaks in tense`() {
        assertEquals("Reading SOUL.md", ToolGrammar.title("read_file", "SOUL.md", running = true))
        assertEquals("Read SOUL.md", ToolGrammar.title("read_file", "SOUL.md", running = false))
    }

    @Test
    fun `nothing worth naming leaves the bare verb, not a dangling one`() {
        assertEquals("Ran", ToolGrammar.title("terminal", "", running = false))
    }

    @Test
    fun `an unknown tool is still readable, not raw snake_case`() {
        assertEquals("Used weather lookup", ToolGrammar.title("weather_lookup", "", running = false))
        assertEquals("⚙", ToolGrammar.glyphOf("weather_lookup"))
    }

    @Test
    fun `the browser family is recognised by prefix`() {
        assertEquals("◍", ToolGrammar.glyphOf("browser_click"))
        assertEquals("Browsing", ToolGrammar.verbOf("browser_click").present)
    }

    // --- targets -------------------------------------------------------------------------------

    @Test
    fun `a path is named by its basename — the rest buries the part that identifies it`() {
        assertEquals("SOUL.md", ToolGrammar.targetOf("read_file", "/home/cocakova/.hermes/SOUL.md"))
    }

    @Test
    fun `a url is named by its host`() {
        assertEquals(
            "docs.example.com",
            ToolGrammar.targetOf("web_extract", "https://docs.example.com/a/b?c=1"),
        )
    }

    @Test
    fun `quotes the gateway printed around an argument are not part of it`() {
        assertEquals("trixnity", ToolGrammar.targetOf("web_search", "“trixnity”"))
        assertEquals("ls -la", ToolGrammar.targetOf("terminal", "`ls -la`"))
    }

    @Test
    fun `a multi-line command collapses to one line`() {
        assertEquals("python3 -c print(1)", ToolGrammar.targetOf("terminal", "python3 -c\n  print(1)"))
    }

    @Test
    fun `tools whose argument is machinery name nothing`() {
        assertEquals("", ToolGrammar.targetOf("memory", "{\"op\":\"store\"}"))
        assertEquals("", ToolGrammar.targetOf("todo", "anything at all"))
    }

    @Test
    fun `a target is capped so one row cannot become three`() {
        assertEquals(80, ToolGrammar.targetOf("terminal", "x".repeat(400)).length)
    }

    // --- run summary ---------------------------------------------------------------------------

    @Test
    fun `one call names what it did`() {
        assertEquals(
            "Read SOUL.md",
            ToolGrammar.summarize(listOf(ToolGrammar.Mention("read_file", "SOUL.md")), live = false),
        )
    }

    @Test
    fun `categories are counted in a fixed order, later clauses lower-cased`() {
        val calls = listOf(
            ToolGrammar.Mention("terminal", "ls"),
            ToolGrammar.Mention("read_file", "a.kt"),
            ToolGrammar.Mention("read_file", "b.kt"),
            ToolGrammar.Mention("write_file", "c.kt"),
        )
        assertEquals("Wrote c.kt, explored 2 files, ran ls", ToolGrammar.summarize(calls, live = false))
    }

    @Test
    fun `the category still working speaks present tense`() {
        val calls = listOf(
            ToolGrammar.Mention("read_file", "a.kt"),
            ToolGrammar.Mention("terminal", "make", running = true),
        )
        assertEquals("Read a.kt, running make", ToolGrammar.summarize(calls, live = true))
    }

    @Test
    fun `an empty run says so honestly in both tenses`() {
        assertEquals("Working…", ToolGrammar.summarize(emptyList(), live = true))
        assertEquals("Worked", ToolGrammar.summarize(emptyList(), live = false))
    }

    // --- diffs ---------------------------------------------------------------------------------

    @Test
    fun `ANSI colouring does not hide a changed line`() {
        // The bug this guards: the gateway's rendered diff is ANSI-coloured, so anything that
        // classifies by leading character sees an escape byte and counts zero, forever.
        val diff = "@@ -1 +1 @@\n$ESC[32m+added line$ESC[0m\n$ESC[31m-old line$ESC[0m\n context"
        assertEquals(1 to 1, ToolGrammar.diffStats(diff))
        val lines = ToolGrammar.diffLines(diff)
        assertEquals(DiffKind.ADD, lines[0].kind)
        assertEquals("added line", lines[0].text)
        assertEquals(DiffKind.REMOVE, lines[1].kind)
        assertEquals(DiffKind.CONTEXT, lines[2].kind)
    }

    @Test
    fun `git chrome and file headers are not diff content`() {
        val diff = """
            diff --git a/x.kt b/x.kt
            index abc..def 100644
            --- a/x.kt
            +++ b/x.kt
            @@ -1,2 +1,2 @@
            +new
            -gone
        """.trimIndent()
        assertEquals(1 to 1, ToolGrammar.diffStats(diff))
        assertEquals(2, ToolGrammar.diffLines(diff).size)
    }

    @Test
    fun `a second hunk becomes a blank row, not an at-at line`() {
        val diff = "@@ -1 +1 @@\n+a\n@@ -9 +9 @@\n+b"
        val kinds = ToolGrammar.diffLines(diff).map { it.kind }
        assertEquals(listOf(DiffKind.ADD, DiffKind.GAP, DiffKind.ADD), kinds)
        assertEquals(2 to 0, ToolGrammar.diffStats(diff))
    }

    @Test
    fun `the stat and the panel classify the same lines`() {
        val diff = "@@ -1 +1 @@\n+one\n+two\n-three\n unchanged"
        val (add, rem) = ToolGrammar.diffStats(diff)
        val lines = ToolGrammar.diffLines(diff)
        assertEquals(lines.count { it.kind == DiffKind.ADD }, add)
        assertEquals(lines.count { it.kind == DiffKind.REMOVE }, rem)
    }

    @Test
    fun `the real thing — a patch diff captured off the live side-channel`() {
        // Verbatim shape from the gateway on 2026-08-19 (an edit to /tmp/keryx-diff-test.txt),
        // which is the only way to be sure the chrome rules match what is actually sent: a
        // "review diff" banner, an ANSI-coloured a/… → b/… header, and 24-bit colour on every
        // content line. The gateway reported +2 -1; the panel must agree.
        val diff = listOf(
            "┊ review diff",
            "$ESC[38;2;218;165;32ma//tmp/keryx-diff-test.txt → b//tmp/keryx-diff-test.txt$ESC[0m",
            "$ESC[38;2;139;134;130m@@ -1,4 +1,5 @@$ESC[0m",
            "$ESC[38;2;184;134;11m alpha$ESC[0m",
            "$ESC[38;2;255;255;255;48;2;119;20;20m-beta$ESC[0m",
            "$ESC[38;2;255;255;255;48;2;19;87;20m+BETA CHANGED$ESC[0m",
            "$ESC[38;2;184;134;11m gamma$ESC[0m",
            "$ESC[38;2;184;134;11m delta$ESC[0m",
            "$ESC[38;2;255;255;255;48;2;19;87;20m+epsilon$ESC[0m",
        ).joinToString("\n")

        assertEquals(2 to 1, ToolGrammar.diffStats(diff))
        val lines = ToolGrammar.diffLines(diff)
        assertEquals(
            listOf("alpha", "beta", "BETA CHANGED", "gamma", "delta", "epsilon"),
            lines.map { it.text },
        )
        assertEquals(
            listOf(
                DiffKind.CONTEXT, DiffKind.REMOVE, DiffKind.ADD,
                DiffKind.CONTEXT, DiffKind.CONTEXT, DiffKind.ADD,
            ),
            lines.map { it.kind },
        )
    }

    @Test
    fun `something that is not a diff yields no lines rather than a wall of context`() {
        assertTrue(ToolGrammar.diffLines("").isEmpty())
        assertEquals(0 to 0, ToolGrammar.diffStats(""))
    }

    // --- aligning the record to the transcript --------------------------------------------------

    @Test
    fun `beats attach to the parsed calls they describe`() {
        val beats = listOf(
            ToolBeat("read_file", ok = true, ms = 10),
            ToolBeat("write_file", ok = true, ms = 20, added = 4, removed = 1),
        )
        val map = Theater.align(listOf("read_file", "write_file"), beats)
        assertEquals(2, map.size)
        assertEquals(4, map[1]!!.added)
    }

    @Test
    fun `a drifted position is left un-enriched rather than given another call's diff`() {
        val beats = listOf(
            ToolBeat("read_file", ok = true),
            ToolBeat("write_file", ok = true, added = 40),
        )
        // The parser missed the read, so position 0 is the write. Attaching beat 0 by position
        // would put a read's (absent) diff on a write; attaching beat 1 would be a coincidence.
        val map = Theater.align(listOf("write_file"), beats)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `a run longer than the record enriches only as far as the record goes`() {
        val beats = listOf(ToolBeat("read_file", ok = true, ms = 7))
        val map = Theater.align(listOf("read_file", "terminal"), beats)
        assertEquals(setOf(0), map.keys)
    }

    @Test
    fun `no record at all means no enrichment, which is the pre-2_4 card`() {
        assertTrue(Theater.align(listOf("read_file"), emptyList()).isEmpty())
        assertTrue(Theater.align(emptyList(), listOf(ToolBeat("read_file"))).isEmpty())
    }
}
