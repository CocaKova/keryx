package chat.keryx.core

import chat.keryx.core.protocol.StreamTailStyle
import chat.keryx.core.protocol.StreamTailStyle.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamTailStyleTest {

    private fun visible(raw: String): String {
        val s = StreamTailStyle.style(raw)
        val hidden = BooleanArray(s.text.length)
        s.spans.filter { it.kind == Kind.HIDE }.forEach { for (k in it.start until it.end.coerceAtMost(hidden.size)) hidden[k] = true }
        return s.text.filterIndexed { i, _ -> !hidden[i] }
    }

    private fun kinds(raw: String, kind: Kind): List<String> {
        val s = StreamTailStyle.style(raw)
        return s.spans.filter { it.kind == kind }.map { raw.substring(it.start, it.end) }
    }

    @Test
    fun `length never changes`() {
        for (raw in listOf("**bold", "- item **x** and `y`", "## Head", "[a](https://b", "plain")) {
            assertEquals(raw.length, StreamTailStyle.style(raw).text.length)
        }
    }

    @Test
    fun `closed marks hide their markers`() {
        assertEquals("a bold b code c it d", visible("a **bold** b `code` c *it* d"))
        assertEquals(listOf("bold"), kinds("a **bold** b", Kind.BOLD))
        assertEquals(listOf("code"), kinds("x `code` y", Kind.CODE))
        assertEquals(listOf("it"), kinds("x *it* y", Kind.ITALIC))
    }

    @Test
    fun `an open bold or code span styles to the end while it types`() {
        assertEquals(listOf("still typ"), kinds("so **still typ", Kind.BOLD))
        assertEquals(listOf("val x ="), kinds("run `val x =", Kind.CODE))
        assertEquals("so still typ", visible("so **still typ"))
    }

    @Test
    fun `a lone star or snake_case stays literal`() {
        assertEquals("5 * 3 = 15", visible("5 * 3 = 15"))
        assertEquals("max_tokens_total", visible("max_tokens_total"))
        assertEquals("footnote*", visible("footnote*"))
    }

    @Test
    fun `headings bullets and quotes`() {
        assertEquals("Title", visible("## Title"))
        assertEquals(listOf("Title"), kinds("## Title", Kind.HEADING))
        assertTrue(StreamTailStyle.style("- one\n* two").text.startsWith("• one\n• two"))
        assertEquals("quoted", visible("> quoted"))
    }

    @Test
    fun `links show their label and hide the target even half typed`() {
        assertEquals("see docs now", visible("see [docs](https://x.dev/a) now"))
        assertEquals("see docs", visible("see [docs](https://x.de"))
        assertEquals(listOf("docs"), kinds("see [docs](https://x.dev) now", Kind.LINK))
    }

    @Test
    fun `fenced lines are code and not parsed for marks`() {
        val raw = "```\na **b**\n```"
        assertTrue(kinds(raw, Kind.BOLD).isEmpty())
    }
}
