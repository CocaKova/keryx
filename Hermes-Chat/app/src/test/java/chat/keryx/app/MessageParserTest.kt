package chat.keryx.app

import chat.keryx.app.presentation.ui.components.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    @Test
    fun plainText_singleTextSegment() {
        val segments = MessageParser.parse("hello **world**")
        assertEquals(1, segments.size)
        assertEquals("hello **world**", (segments[0] as MessageParser.Segment.Text).text)
    }

    @Test
    fun emptyContent_noSegments() {
        assertEquals(0, MessageParser.parse("").size)
        assertEquals(0, MessageParser.parse("   \n  \n").size)
    }

    @Test
    fun toolLines_parsedToToolCalls() {
        val content = "* ⚙️ graphiti_forget: \"User should still have keys\"\n" +
            "⚙️ graphiti_list: \"memory context stale\""
        val tools = MessageParser.parse(content)
            .filterIsInstance<MessageParser.Segment.Tools>()
            .single().calls
        assertEquals(2, tools.size)
        assertEquals("graphiti_forget", tools[0].name)
        assertEquals("User should still have keys", tools[0].args)
        assertEquals("graphiti_list", tools[1].name)
        assertEquals("memory context stale", tools[1].args)
    }

    @Test
    fun toolArgs_stripWrappingBackticks() {
        // Hermes often wraps the command in markdown code formatting; the card should show it clean.
        val single = MessageParser.parse("⚙️ terminal: `ls -la /tmp`")
            .filterIsInstance<MessageParser.Segment.Tools>().single().calls.single()
        assertEquals("ls -la /tmp", single.args)

        // But backticks that are part of the command (shell substitution) survive.
        val inner = MessageParser.parse("⚙️ terminal: \"echo `date`\"")
            .filterIsInstance<MessageParser.Segment.Tools>().single().calls.single()
        assertEquals("echo `date`", inner.args)
    }

    @Test
    fun glyphlessTerminal_stillParsedAsTool() {
        // Hermes sometimes drops the leading emoji on repeated terminal calls; the fully-quoted arg
        // is the signal that lets us still treat it as a tool.
        val call = MessageParser.parse("terminal: \"git status\"")
            .filterIsInstance<MessageParser.Segment.Tools>().single().calls.single()
        assertEquals("terminal", call.name)
        assertEquals("git status", call.args)
        // Ordinary prose with a colon must NOT become a tool.
        assertTrue(MessageParser.parse("Note: this is just a note").none { it is MessageParser.Segment.Tools })
    }

    @Test
    fun thinkingLines_groupedIntoOnePane() {
        val content = "🧠 memory: \"User: Jonny\"\n🧠 reasoning about the plan"
        val thinking = MessageParser.parse(content)
            .filterIsInstance<MessageParser.Segment.Thinking>()
            .single()
        assertTrue(thinking.text.contains("memory: \"User: Jonny\""))
        assertTrue(thinking.text.contains("reasoning about the plan"))
    }

    @Test
    fun gfmTable_parsedHeaderAndRows() {
        val content = "| Col A | Col B |\n|-------|-------|\n| 1 | 2 |\n| 3 | 4 |"
        val table = MessageParser.parse(content)
            .filterIsInstance<MessageParser.Segment.Table>()
            .single()
        assertEquals(listOf("Col A", "Col B"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
        assertEquals(listOf("3", "4"), table.rows[1])
    }

    @Test
    fun mixedContent_preservesOrder() {
        val content = "Here's what I did:\n⚙️ tool_a: \"x\"\nAll done."
        val segments = MessageParser.parse(content)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is MessageParser.Segment.Text)
        assertTrue(segments[1] is MessageParser.Segment.Tools)
        assertTrue(segments[2] is MessageParser.Segment.Text)
    }

    @Test
    fun tableThenText_split() {
        val content = "| A | B |\n|---|---|\n| 1 | 2 |\nthanks!"
        val segments = MessageParser.parse(content)
        assertTrue(segments.any { it is MessageParser.Segment.Table })
        assertEquals("thanks!", segments.filterIsInstance<MessageParser.Segment.Text>().last().text)
    }

    // --- Streaming / messy-output resilience -------------------------------------------------

    @Test
    fun uncloseFence_getsClosed() {
        val messy = "Here you go:\n```kotlin\nval x = 1"
        val fixed = MessageParser.closeDanglingFences(messy)
        assertTrue(fixed.endsWith("```"))
        // A properly closed fence is left alone.
        val clean = "```sh\nls\n```"
        assertEquals(clean, MessageParser.closeDanglingFences(clean))
    }

    @Test
    fun unclosedFence_doesNotSwallowParse() {
        // The parser must survive a message that ends inside an open fence (mid-stream state).
        val segments = MessageParser.parse("intro\n```python\nprint('hi')\nstill inside")
        assertTrue(segments.isNotEmpty())
    }

    @Test
    fun nestedCodeSamples_insideFence_notParsedAsTools() {
        // A fenced block CONTAINING tool-looking lines and a table must stay opaque markdown.
        val content = "Example transcript:\n```\n⚙️ terminal: \"rm -rf /\"\n| a | b |\n|---|---|\n| 1 | 2 |\n```\ndone"
        val segments = MessageParser.parse(content)
        assertTrue(segments.none { it is MessageParser.Segment.Tools })
        assertTrue(segments.none { it is MessageParser.Segment.Table })
    }

    @Test
    fun streamingTail_partialMarkerStripped() {
        assertEquals("answer text", MessageParser.sanitizeStreamingTail("answer text ⟦cite 1 | mem"))
        // A complete marker pair is untouched.
        val complete = "answer ⟦keryx:v1⟧"
        assertEquals(complete, MessageParser.sanitizeStreamingTail(complete))
    }

    @Test
    fun trailingJsonGarbage_toleratedInActionOutput() {
        val raw = """{"tool": "terminal", "arguments": {"cmd": "ls"}, "status": "success"}, extra garbage"""
        val action = MessageParser.tryParseActionOutput(raw)
        assertTrue(action != null)
        assertEquals("terminal", action!!.tool)
        assertEquals(true, action.success)
        assertEquals("cmd" to "ls", action.params.single())
    }

    @Test
    fun jsonFence_withToolKeys_becomesActionCard() {
        val content = "```json\n{\"tool\": \"graphiti_search\", \"args\": {\"q\": \"jonny\"}, \"result\": \"3 hits\"}\n```"
        val segments = MessageParser.parse(content)
        val action = segments.filterIsInstance<MessageParser.Segment.ActionOutput>().single()
        assertEquals("graphiti_search", action.tool)
        assertEquals("3 hits", action.result)
    }

    @Test
    fun jsonFence_withoutToolKeys_staysCodeBlock() {
        val content = "```json\n{\"just\": \"data\"}\n```"
        val segments = MessageParser.parse(content)
        assertTrue(segments.none { it is MessageParser.Segment.ActionOutput })
        assertTrue(segments.filterIsInstance<MessageParser.Segment.Text>().single().text.contains("\"just\""))
    }

    @Test
    fun malformedJson_neverThrows() {
        assertEquals(null, MessageParser.tryParseActionOutput("{\"tool\": \"x\", broken"))
        assertEquals(null, MessageParser.tryParseActionOutput("not json at all"))
    }

    // --- Telemetry ---------------------------------------------------------------------------

    @Test
    fun runtimeFooter_lastLine_isTelemetry() {
        val content = "Here's your answer.\n\nOrnith-1.0-35B · 42% · ~/workspace/keryx"
        val segments = MessageParser.parse(content)
        val telem = segments.filterIsInstance<MessageParser.Segment.Telemetry>().single()
        assertEquals(MessageParser.TelemetryKind.FOOTER, telem.kind)
        assertTrue(telem.text.contains("42%"))
        // The prose survives as dialogue.
        assertEquals("Here's your answer.", segments.filterIsInstance<MessageParser.Segment.Text>().single().text)
    }

    @Test
    fun standaloneRuntimeFooter_classifiedSeparatelyFromCheckins() {
        assertTrue(MessageParser.isRuntimeFooterMessage("qwen3.5-122b · 42% · ~/workspace/keryx"))
        assertTrue(!MessageParser.isRuntimeFooterMessage("[status] gateway healthy, 3 sessions active"))
    }

    @Test
    fun middleDotProse_notFooter() {
        // A middle dot in prose must not trigger footer detection (not the last line + no %/path).
        val segments = MessageParser.parse("I like tea · coffee · juice\nmore text")
        assertTrue(segments.none { it is MessageParser.Segment.Telemetry })
    }

    @Test
    fun checkinMessage_classifiedAsTelemetry() {
        assertTrue(MessageParser.isTelemetryMessage("⏰ Cron: nightly consolidation finished (12 nodes merged)"))
        assertTrue(MessageParser.isTelemetryMessage("[status] gateway healthy, 3 sessions active"))
        assertTrue(MessageParser.isTelemetryMessage("-# background sync complete"))
        // Real answers are never telemetry.
        assertTrue(!MessageParser.isTelemetryMessage("Here's the plan we discussed."))
        assertTrue(!MessageParser.isTelemetryMessage("The cron job you asked about is defined in crontab."))
    }

    @Test
    fun subtextLines_becomeTelemetry() {
        val segments = MessageParser.parse("-# quiet aside\n-# second line")
        val telem = segments.filterIsInstance<MessageParser.Segment.Telemetry>().single()
        assertEquals(MessageParser.TelemetryKind.SUBTEXT, telem.kind)
        assertTrue(telem.text.contains("quiet aside"))
    }

    // --- Tool verdicts -----------------------------------------------------------------------

    @Test
    fun failedToolGlyph_setsFailure() {
        val call = MessageParser.parse("❌ terminal: \"cat /missing\"")
            .filterIsInstance<MessageParser.Segment.Tools>().single().calls.single()
        assertEquals(false, call.ok)
    }

    @Test
    fun unclosedThinkTag_treatedAsReasoningInProgress() {
        val (reasoning, body) = MessageParser.extractReasoning("prefix\n<think>half a thought")
        assertEquals("half a thought", reasoning)
        assertEquals("prefix", body)
    }
}
