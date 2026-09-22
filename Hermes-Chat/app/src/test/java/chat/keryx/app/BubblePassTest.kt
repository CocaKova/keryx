package chat.keryx.app

import chat.keryx.core.protocol.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the long-press bar's Copy puts on the clipboard (2.11.9): `extractKeryx(...).text`, the
 * same normalized prose the bubble drew. Markers never survive a copy; an inline cite ref arrives
 * as its superscript; a marker-free body is byte-identical.
 */
class BubblePassTest {

    @Test
    fun `copy drops the cite block and keeps the prose, superscripting the inline ref`() {
        val body =
            "The gateway hot-reloads config ⟦c1⟧\n" +
                "⟦cite 1 | file | run_agent.py:1388 | strip_think_blocks⟧"
        val copied = MessageParser.extractKeryx(body).text
        assertEquals("The gateway hot-reloads config ⁽¹⁾", copied.trim())
    }

    @Test
    fun `a marker-free body copies byte-identical`() {
        val body = "Plain prose, two\nlines, no markers."
        assertEquals(body, MessageParser.extractKeryx(body).text)
    }

    @Test
    fun `hands and telemetry markers leave the copy too`() {
        val body = "Done ⟦keryx:telemetry⟧ ⟦keryx:do|copy|hi there⟧"
        val copied = MessageParser.extractKeryx(body).text
        assertTrue('⟦' !in copied)
        assertTrue(copied.startsWith("Done"))
    }
}
