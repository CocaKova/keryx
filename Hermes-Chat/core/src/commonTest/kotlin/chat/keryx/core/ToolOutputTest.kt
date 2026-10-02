package chat.keryx.core

import chat.keryx.core.protocol.ToolOutput
import chat.keryx.core.protocol.ToolText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool results read rather than dumped (2.16). The envelopes are the live gateway's, from
 * `state.db` role:"tool" rows on 2026-10-02: `terminal` → `{"output", "exit_code", "error"}`,
 * `execute_code` → the same plus status and kernel fields.
 */
class ToolOutputTest {

    @Test
    fun `the terminal envelope unwraps to its output and exit code`() {
        val o = ToolOutput.parse("""{"output": "399", "exit_code": 0, "error": null}""")
        assertEquals("399", o.body)
        assertEquals(0, o.exitCode)
        assertNull(o.error)
        assertFalse(o.bad)
    }

    @Test
    fun `a non-zero exit is bad, and multi-line output keeps its lines`() {
        val o = ToolOutput.parse("""{"output": "make: *** [all] Error 2\nstopped\n", "exit_code": 2, "error": null}""")
        assertEquals("make: *** [all] Error 2\nstopped", o.body)
        assertEquals(2, o.exitCode)
        assertTrue(o.bad)
    }

    @Test
    fun `the tool's own error is read`() {
        val o = ToolOutput.parse("""{"output": "", "exit_code": -1, "error": "Command timed out after 180s"}""")
        assertEquals("", o.body)
        assertEquals(-1, o.exitCode)
        assertEquals("Command timed out after 180s", o.error)
    }

    @Test
    fun `execute_code's richer envelope unwraps too`() {
        val raw = """{"status": "success", "output": "fixed 5 fit lines\n", "exit_code": 0, "tool_calls_made": 0,""" +
            """ "duration_seconds": 0.0, "kernel": {"mode": "session", "reused": true}}"""
        val o = ToolOutput.parse(raw)
        assertEquals("fixed 5 fit lines", o.body)
        assertEquals(0, o.exitCode)
    }

    @Test
    fun `plain text passes through untouched`() {
        val o = ToolOutput.parse("a.txt\nb.txt")
        assertEquals("a.txt\nb.txt", o.body)
        assertNull(o.exitCode)
        assertFalse(o.bad)
    }

    @Test
    fun `other JSON shapes are left as they came`() {
        // A process status: "killed" is the news, so the JSON stays whole.
        val proc = """{"status": "killed", "session_id": "proc_1", "output": "[INFO] up"}"""
        assertEquals(proc, ToolOutput.parse(proc).body)
        // A write_file result has no output field at all.
        val write = """{"bytes_written": 4761, "dirs_created": true}"""
        assertEquals(write, ToolOutput.parse(write).body)
        assertNull(ToolOutput.parse(write).exitCode)
    }

    @Test
    fun `missing fields are missing, not zero`() {
        val o = ToolOutput.parse("""{"output": "hi"}""")
        assertEquals("hi", o.body)
        assertNull(o.exitCode)
        assertNull(o.error)
    }

    @Test
    fun `non-JSON that starts with a brace is left alone`() {
        val o = ToolOutput.parse("{not json at all")
        assertEquals("{not json at all", o.body)
        assertNull(o.exitCode)
    }

    @Test
    fun `an envelope the display cap cut still shows its output`() {
        // ToolText caps results at 6 000 chars, which cuts a long log's envelope mid-string and
        // takes the exit code with it. The output still reads as output, not as raw JSON.
        val log = (1..2000).joinToString("\\n") { "line $it" }
        val raw = """{"output": "$log", "exit_code": 0, "error": null}"""
        val capped = ToolText.displayResult(raw)
        assertTrue(capped.endsWith("[truncated]"))
        val o = ToolOutput.parse(capped)
        assertTrue(o.body.startsWith("line 1\nline 2\n"))
        assertTrue(o.body.endsWith("… [truncated]"))
        assertFalse(o.body.contains("\"output\""))
        assertNull(o.exitCode)
    }

    @Test
    fun `escapes decode in a cut envelope`() {
        val o = ToolOutput.parse("{\"output\": \"tab\\there \\\"quoted\\\" \\u00e9")
        assertEquals("tab\there \"quoted\" é\n… [truncated]", o.body)
    }
}
