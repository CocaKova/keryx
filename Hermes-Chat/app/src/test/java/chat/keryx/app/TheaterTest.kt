package chat.keryx.app

import chat.keryx.app.domain.model.Theater
import chat.keryx.app.domain.model.TheaterEvent
import chat.keryx.app.domain.model.ToolBeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool theater's state machine (2.4). The hard part is ORDER: the gateway sends no call ids
 * (`tool.completed` doesn't carry one) and a subagent never says when one of its own calls
 * ended, so every closing rule here is inferred and worth pinning down.
 */
class TheaterTest {

    private fun start(name: String, preview: String = "") =
        TheaterEvent(phase = "start", name = name, preview = preview)

    private fun end(name: String, ok: Boolean = true, ms: Long = 0L, result: String = "") =
        TheaterEvent(phase = "end", name = name, ok = ok, ms = ms, result = result)

    private fun sub(kind: String, child: String = "c1", name: String = "", preview: String = "") =
        TheaterEvent(phase = "sub", kind = kind, child = child, name = name, preview = preview)

    private fun run(vararg events: TheaterEvent): List<ToolBeat> =
        events.fold(emptyList()) { acc, e -> Theater.reduce(acc, e) }

    // --- the ordinary shape --------------------------------------------------------------------

    @Test
    fun `a start opens a running row`() {
        val beats = run(start("terminal", "ls -la"))
        assertEquals(1, beats.size)
        assertEquals("terminal", beats[0].name)
        assertEquals("ls -la", beats[0].preview)
        assertTrue(beats[0].running)
        assertNull(beats[0].ok)
    }

    @Test
    fun `an end closes it and carries the outcome`() {
        val beats = run(start("terminal"), end("terminal", ok = true, ms = 412, result = "hi"))
        assertEquals(1, beats.size)
        assertFalse(beats[0].running)
        assertEquals(true, beats[0].ok)
        assertEquals(412L, beats[0].ms)
        assertEquals("hi", beats[0].result)
    }

    @Test
    fun `a failure is kept as a failure`() {
        val beats = run(start("web_search"), end("web_search", ok = false))
        assertEquals(false, beats[0].ok)
    }

    @Test
    fun `sequential calls stay in order and close independently`() {
        val beats = run(start("read"), end("read"), start("write"), end("write"))
        assertEquals(listOf("read", "write"), beats.map { it.name })
        assertTrue(beats.none { it.running })
    }

    // --- the inference rules -------------------------------------------------------------------

    @Test
    fun `an end prefers the open row with the same name`() {
        val beats = run(start("read"), start("terminal"), end("read"))
        assertFalse(beats[0].running)
        assertTrue(beats[1].running)
    }

    @Test
    fun `an end with an unknown name still closes the oldest open row`() {
        // Better to close something than to leave a row spinning forever, and completions
        // arrive in start order, so the oldest open row is the one that just finished.
        val beats = run(start("read"), start("terminal"), end("something_else"))
        assertFalse(beats[0].running)
        assertTrue(beats[1].running)
    }

    @Test
    fun `batched calls of the SAME tool keep their own outcomes`() {
        // The trace that motivated FIFO closing, captured off the live side-channel: a model
        // opened two read_file calls before either finished, then they landed in start order.
        // Closing newest-first gave the successful read the failure and vice versa.
        val beats = run(
            start("read_file", "SOUL.md"),
            start("read_file", "nope.md"),
            end("read_file", ok = true, ms = 113),
            end("read_file", ok = false, ms = 85, result = "File not found"),
        )
        assertEquals("SOUL.md", beats[0].preview)
        assertEquals(true, beats[0].ok)
        assertEquals(113L, beats[0].ms)
        assertEquals("nope.md", beats[1].preview)
        assertEquals(false, beats[1].ok)
        assertEquals("File not found", beats[1].result)
    }

    @Test
    fun `an end with nothing open is ignored rather than inventing a row`() {
        assertEquals(emptyList<ToolBeat>(), run(end("terminal")))
    }

    @Test
    fun `an unknown phase changes nothing`() {
        val beats = run(start("read"), TheaterEvent(phase = "wat"))
        assertEquals(1, beats.size)
    }

    // --- subagents -----------------------------------------------------------------------------

    @Test
    fun `a subagent is its own row, indented under the delegate call`() {
        val beats = run(start("delegate"), sub("start", name = "researcher"))
        assertEquals(2, beats.size)
        assertEquals(0, beats[0].depth)
        assertEquals(1, beats[1].depth)
        assertTrue(beats[1].subagent)
        assertEquals("researcher", beats[1].name)
    }

    @Test
    fun `a nameless subagent falls back to its goal, then to a generic name`() {
        assertEquals("dig through the logs", run(sub("start", preview = "dig through the logs"))[0].name)
        assertEquals("subagent", run(sub("start"))[0].name)
    }

    @Test
    fun `a subagent's tools sit one level deeper again`() {
        val beats = run(sub("start", name = "researcher"), sub("tool", name = "web_search"))
        assertEquals(1, beats[0].depth)
        assertEquals(2, beats[1].depth)
        assertFalse(beats[1].subagent)
    }

    @Test
    fun `a subagent's next tool closes its previous one — the only end signal there is`() {
        val beats = run(
            sub("start", name = "researcher"),
            sub("tool", name = "web_search"),
            sub("tool", name = "read_file"),
        )
        assertFalse(beats[1].running)
        assertTrue(beats[2].running)
    }

    @Test
    fun `completing a subagent closes it and everything it left open`() {
        val beats = run(
            sub("start", name = "researcher"),
            sub("tool", name = "web_search"),
            sub("complete"),
        )
        assertTrue(beats.none { it.running })
    }

    @Test
    fun `two subagents do not close each other's rows`() {
        val beats = run(
            sub("start", child = "a", name = "alpha"),
            sub("tool", child = "a", name = "read"),
            sub("start", child = "b", name = "beta"),
            sub("tool", child = "b", name = "write"),
            sub("complete", child = "a"),
        )
        assertEquals(listOf("alpha", "read", "beta", "write"), beats.map { it.name })
        assertFalse(beats[0].running)  // alpha closed
        assertFalse(beats[1].running)  // alpha's read closed with it
        assertTrue(beats[2].running)   // beta untouched
        assertTrue(beats[3].running)
    }

    @Test
    fun `the parent's own end is not stolen by an open subagent row`() {
        val beats = run(start("delegate"), sub("start", name = "researcher"), end("delegate"))
        assertFalse(beats[0].running)
        assertTrue(beats[1].running)
    }

    @Test
    fun `a subagent's chatter is dropped — it would outpace the phone`() {
        val beats = run(
            sub("start", name = "researcher"),
            sub("text", preview = "thinking out loud"),
            sub("thinking", preview = "more"),
            sub("progress", preview = "🔀 step 2"),
            sub("spawn_requested", preview = "goal"),
        )
        assertEquals(1, beats.size)
    }

    // --- bounds --------------------------------------------------------------------------------

    @Test
    fun `the list is capped at the tail, so a marathon turn cannot grow it forever`() {
        var beats = emptyList<ToolBeat>()
        repeat(Theater.MAX_BEATS + 12) { i ->
            beats = Theater.reduce(beats, start("tool$i"))
            beats = Theater.reduce(beats, end("tool$i"))
        }
        assertEquals(Theater.MAX_BEATS, beats.size)
        assertEquals("tool${Theater.MAX_BEATS + 11}", beats.last().name)
    }

    @Test
    fun `a blank tool name never renders as an empty row`() {
        assertEquals("tool", run(start(""))[0].name)
    }
}
