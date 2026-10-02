package chat.keryx.core

import chat.keryx.core.model.ExportFormat
import chat.keryx.core.model.SessionExport
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export body is shaped as `export_session_endpoint` streams it: the session row's columns,
 * then `"messages"` — every row of `get_messages(include_inactive=True)`, flags and all.
 */
class SessionExportTest {
    private val utc = TimeZone.UTC

    // 2026-10-02 14:00:00 UTC = 1790949600
    private val body = """
        {"id":"20261002_140000_abc123ef","source":"tui","model":"qwen3.8-flash-next","title":"Fix the Spark's memory","started_at":1790949600.25,
         "system_prompt":"You are Sy…","message_count":9,
         "messages":[
          {"id":1,"session_id":"s","role":"user","content":"why is the brain slow?","timestamp":1790949600.5,"active":1,"compacted":0},
          {"id":2,"session_id":"s","role":"assistant","content":"","reasoning":"check memory first","timestamp":1790949610,"active":1,"compacted":0,
           "tool_calls":[{"id":"call_1","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"free -g\"}"}}]},
          {"id":3,"session_id":"s","role":"tool","content":"{\"output\": \"Mem: 119 112 7\", \"exit_code\": 0, \"error\": null}","tool_call_id":"call_1","tool_name":"terminal","timestamp":1790949611,"active":1,"compacted":0},
          {"id":4,"session_id":"s","role":"assistant","content":"Only **7 GB** free — ComfyUI is up.","timestamp":1790949620,"active":1,"compacted":0},
          {"id":5,"session_id":"s","role":"user","content":"undo that","timestamp":1790949700,"active":0,"compacted":0},
          {"id":6,"session_id":"s","role":"user","content":"stop ComfyUI","timestamp":1790949800,"active":1,"compacted":0},
          {"id":7,"session_id":"s","role":"user","content":"[internal] scratch","timestamp":1790949801,"active":1,"compacted":0,"display_metadata":{"model_only":true}},
          {"id":8,"session_id":"s","role":"user","content":[{"type":"text","text":"and this"},{"type":"image_url","image_url":{"url":"data:x"}}],"timestamp":1790949900,"active":1,"compacted":0}
         ]}
    """.trimIndent()

    @Test
    fun parse_readsMetaAndEveryRowWithItsFlags() {
        val doc = SessionExport.parse(body)!!
        assertEquals("20261002_140000_abc123ef", doc.meta.id)
        assertEquals("Fix the Spark's memory", doc.meta.title)
        assertEquals(1790949600250L, doc.meta.startedAtMs)
        assertEquals(8, doc.rows.size)
        assertFalse(doc.rows[4].active)
        assertTrue(doc.rows[6].modelOnly)
        assertEquals("and this\n[image]", doc.rows[7].row.content)
        assertEquals("terminal", doc.rows[1].row.toolCalls.single().name)
        assertNull(SessionExport.parse("<html>502</html>"))
        assertNull(SessionExport.parse("""{"detail":"Session not found"}"""))
    }

    @Test
    fun readableRows_dropRewindsAndModelOnlyAndArchivedTwins() {
        val doc = SessionExport.parse(body)!!
        val ids = SessionExport.readableRows(doc.rows).map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L, 8L), ids)

        // A compaction archived rows 1-2 and re-inserted row 2's twin live as row 12: the
        // archived 1 stays (it is the story), the archived 2 goes (its twin is live).
        val compacted = """
            {"id":"s","messages":[
              {"id":1,"role":"user","content":"old question","timestamp":1,"active":0,"compacted":1},
              {"id":2,"role":"assistant","content":"kept answer","timestamp":2,"active":0,"compacted":1},
              {"id":11,"role":"user","content":"[CONTEXT COMPACTION] summary of before","timestamp":3,"active":1,"compacted":0},
              {"id":12,"role":"assistant","content":"kept answer","timestamp":4,"active":1,"compacted":0}
            ]}
        """.trimIndent()
        val rows = SessionExport.readableRows(SessionExport.parse(compacted)!!.rows)
        assertEquals(listOf(1L, 11L, 12L), rows.map { it.id })
    }

    @Test
    fun markdown_readsLikeTheTranscript() {
        val md = SessionExport.markdown(SessionExport.parse(body)!!, agentName = "Sy", tz = utc)
        assertTrue(md.startsWith("# Fix the Spark's memory\n"), md)
        assertTrue("session `20261002_140000_abc123ef` · tui · model qwen3.8-flash-next · started 2026-10-02 14:00" in md, md)
        assertTrue("**2026-10-02**" in md, md)
        assertTrue("### You · 14:00\n\nwhy is the brain slow?" in md, md)
        // The tool call: one line, its argument, its verdict, its output in a fence.
        assertTrue("- `terminal` `free -g` — ok" in md, md)
        assertTrue("Mem: 119 112 7" in md, md)
        assertTrue("> check memory first" in md, md)
        assertTrue("### Sy · 14:00\n\nOnly **7 GB** free — ComfyUI is up." in md, md)
        // Rewound and model-only rows never reach the reader.
        assertFalse("undo that" in md, md)
        assertFalse("[internal] scratch" in md, md)
        assertTrue("stop ComfyUI" in md)
    }

    @Test
    fun markdown_trimsLongOutputAndSaysSo() {
        val long = "y".repeat(SessionExport.TOOL_RESULT_CAP + 50)
        val b = """
            {"id":"s","title":"t","messages":[
              {"id":1,"role":"assistant","content":"","timestamp":1790949600,"active":1,"compacted":0,
               "tool_calls":[{"id":"c","function":{"name":"read_file","arguments":"{\"path\":\"/tmp/x\"}"}}]},
              {"id":2,"role":"tool","content":"$long","tool_call_id":"c","timestamp":1790949601,"active":1,"compacted":0}
            ]}
        """.trimIndent()
        val md = SessionExport.markdown(SessionExport.parse(b)!!, "Hermes", utc)
        assertTrue("… (50 more characters in the JSON export)" in md, md)
    }

    @Test
    fun fences_outlastTheBackticksTheyHold() {
        assertEquals("```", SessionExport.fenceFor("plain"))
        assertEquals("````", SessionExport.fenceFor("a ``` fence inside"))
        assertEquals("`ls`", SessionExport.inlineCode("ls"))
        assertEquals("`` echo `date` ``", SessionExport.inlineCode("echo `date`"))
    }

    @Test
    fun fileName_isDatedSluggedAndTyped() {
        val at = 1790949600000L // 2026-10-02 14:00 UTC
        assertEquals(
            "keryx-fix-the-spark-s-memory-20261002-1400-abc123ef.md",
            SessionExport.fileName("Fix the Spark's memory", "20261002_140000_abc123ef", ExportFormat.MARKDOWN, at, utc),
        )
        assertEquals(
            "keryx-session-20261002-1400-abc123ef.json",
            SessionExport.fileName("🚀 ✨", "20261002_140000_abc123ef", ExportFormat.JSON, at, utc),
        )
    }

    @Test
    fun slug_staysShortAndNeverEmpty() {
        assertEquals("session", SessionExport.slug(""))
        assertEquals("a-b-c", SessionExport.slug("  A / b -- C!! "))
        val s = SessionExport.slug("one two three four five six seven eight nine ten eleven")
        assertTrue(s.length <= 40, s)
        assertFalse(s.endsWith("-"))
        assertNotNull(s)
    }

    @Test
    fun formats_shareMarkdownAsPlainTextSoEveryNotesAppTakesIt() {
        assertEquals("text/plain", ExportFormat.MARKDOWN.mime)
        assertEquals("application/json", ExportFormat.JSON.mime)
    }
}
