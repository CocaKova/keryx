package chat.keryx.core

import chat.keryx.core.protocol.MessageParser
import chat.keryx.core.protocol.MessageParser.Segment
import chat.keryx.core.protocol.RichBlock
import chat.keryx.core.protocol.RichBlocks
import chat.keryx.core.protocol.StreamTailStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2.17 render surface against the inputs models actually produce when they get it wrong:
 * nesting, truncation, hostile values, half-written fences. Everything here must either draw
 * something sensible or fall back to text — never lose the rest of the message.
 */
class RenderEdgeCasesTest {

    private fun parse(s: String) = MessageParser.parse(s, agentChrome = true, cacheable = false)
    private fun texts(segs: List<Segment>) = segs.filterIsInstance<Segment.Text>().joinToString("\n") { it.text }

    // --- fences inside fences -------------------------------------------------------------

    @Test
    fun `a code fence inside details stays inside and the message after it survives`() {
        val segs = parse("Intro\n\n```details\nShow code\n```python\nx = 1\n```\nafter code\n```\n\nOutro line.")
        val d = segs.filterIsInstance<Segment.Rich>().single().block
        assertIs<RichBlock.Details>(d)
        assertTrue(d.body.contains("x = 1") && d.body.contains("after code"), d.body)
        assertTrue(texts(segs).contains("Outro line."), texts(segs))
    }

    @Test
    fun `a four-backtick details holds a three-backtick fence`() {
        val segs = parse("````details\nTitle\n```\nplain\n```\n````\nTail.")
        val d = segs.filterIsInstance<Segment.Rich>().single().block as RichBlock.Details
        assertTrue(d.body.contains("plain"))
        assertTrue(texts(segs).contains("Tail."))
    }

    @Test
    fun `an unclosed rich fence mid-stream is just code and swallows nothing before it`() {
        val segs = parse("Before.\n\n```chart\n{\"type\":\"bar\",\"labels\":[\"a\"")
        assertTrue(segs.none { it is Segment.Rich })
        assertTrue(texts(segs).contains("Before."))
        assertTrue(texts(segs).contains("```chart"))
    }

    // --- charts with bad data --------------------------------------------------------------

    @Test
    fun `charts reject what they cannot draw honestly`() {
        assertNull(RichBlocks.parse("chart", "{\"type\":\"bar\",\"labels\":[],\"series\":[]}"))
        assertNull(RichBlocks.parse("chart", "{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"series\":[{\"values\":[1]}]}"))
        assertNull(RichBlocks.parse("chart", "{\"type\":\"pie\",\"labels\":[\"a\",\"b\"],\"values\":[0,0]}"))
        assertNull(RichBlocks.parse("chart", "{\"type\":\"pie\",\"labels\":[\"a\",\"b\"],\"values\":[5,-1]}"))
        assertNull(RichBlocks.parse("chart", "{\"type\":\"radar\",\"values\":[1,2]}"))
        assertNull(RichBlocks.parse("chart", "not json at all"))
        assertNull(RichBlocks.parse("chart", "{\"type\":\"bar\",\"values\":[null,null]}"))
    }

    @Test
    fun `non-finite and huge numbers never reach the canvas as NaN`() {
        val c = RichBlocks.parse("chart", "{\"type\":\"line\",\"values\":[1, \"NaN\", \"Infinity\", 1e308, -1e308]}") as RichBlock.Chart?
        if (c != null) assertTrue(c.series.all { s -> s.values.all { it == null || it.isFinite() } })
    }

    @Test
    fun `a single point and all zeros are still charts`() {
        assertIs<RichBlock.Chart>(RichBlocks.parse("chart", "{\"type\":\"line\",\"labels\":[\"x\"],\"values\":[3]}"))
        assertIs<RichBlock.Chart>(RichBlocks.parse("chart", "{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"values\":[0,0]}"))
    }

    @Test
    fun `too many points or series fall back`() {
        val many = (1..201).joinToString(",")
        assertNull(RichBlocks.parse("chart", "{\"type\":\"line\",\"values\":[$many]}"))
        val series = (1..13).joinToString(",") { "{\"values\":[1,2]}" }
        assertNull(RichBlocks.parse("chart", "{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"series\":[$series]}"))
    }

    // --- cards, swatches, progress ---------------------------------------------------------

    @Test
    fun `a card never carries a non-web link or image`() {
        val c = RichBlocks.parse("card", "title: X\nurl: javascript:alert(1)\nimage: http://insecure.example/a.png") as RichBlock.Card
        assertNull(c.url)
        assertNull(c.image)
        assertNull(RichBlocks.parse("card", "subtitle: no title here"))
    }

    @Test
    fun `swatches skip bad hex and refuse an empty palette`() {
        val s = RichBlocks.parse("swatch", "good: #C4572A\nbad: #GGGGGG\nalpha: #80FFFFFF") as RichBlock.Swatches
        assertEquals(2, s.colors.size)
        assertNull(RichBlocks.parse("swatch", "nothing: #ZZZ"))
    }

    @Test
    fun `progress stays within its bar`() {
        val p = RichBlocks.parse("progress", "Over: 150%\nZero: 0/0\nOdd: 5/3\nFine: 2/4") as RichBlock.Progress?
        if (p != null) assertTrue(p.items.all { it.fraction in 0f..1f && !it.fraction.isNaN() }, p.items.toString())
    }

    // --- display math boundaries -----------------------------------------------------------

    @Test
    fun `display math only claims whole lines`() {
        assertTrue(parse("Costs $$5 and $$10 here").none { it is Segment.Math })
        assertTrue(parse("$$\nx^2\n\nnot closed").none { it is Segment.Math })
        assertTrue(parse("\$\$" + "x" + "\$\$ and more words").none { it is Segment.Math })
        assertIs<Segment.Math>(parse("$$\\int_0^1 x\\,dx$$").single())
        assertIs<Segment.Math>(parse("\\[\n a^2 + b^2 \n\\]").single())
    }

    @Test
    fun `math inside a code fence is code`() {
        val segs = parse("```\n$$ not math $$\n```")
        assertTrue(segs.none { it is Segment.Math })
    }

    // --- tables ----------------------------------------------------------------------------

    @Test
    fun `ragged tables and csv edge cases`() {
        val t = parse("| a | b |\n|:-|-:|-:|\n| 1 |\n| 1 | 2 | 3 |").filterIsInstance<Segment.Table>().single()
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(3, t.align.size)
        val csv = parse("```csv\n\uFEFFname,score\nAda,3\n```").filterIsInstance<Segment.Table>().single()
        assertEquals("name", csv.header.first())
        assertTrue(parse("```csv\nonly,a,header\n```").none { it is Segment.Table })
    }

    // --- svg and the preview directive -----------------------------------------------------

    @Test
    fun `svg needs an svg element and a sane size`() {
        assertTrue(parse("```svg\n<div>no</div>\n```").none { it is Segment.Svg })
        val huge = "<svg>" + "x".repeat(200_001) + "</svg>"
        assertTrue(parse("```svg\n$huge\n```").none { it is Segment.Svg })
        assertIs<Segment.Svg>(parse("```svg\n<svg xmlns='http://www.w3.org/2000/svg'/>\n```").single())
    }

    @Test
    fun `the preview directive reads as its file name`() {
        assertTrue(texts(parse("::preview{file=\"/tmp/a b/w.html\"}")).isNotBlank())
        assertTrue(texts(parse("::preview{file=\"/tmp/widget.html\"}")).contains("widget.html"))
        assertTrue(texts(parse("::preview{ file = rel/page.html }")).contains("page.html"))
    }

    // --- the live paragraph ----------------------------------------------------------------

    @Test
    fun `live styling survives awkward marks`() {
        for (raw in listOf("***both***", "\\*literal\\*", "`code **not bold**`", "**bold `code` bold**", "[", "](", "**", "`", "# ", "-")) {
            val s = StreamTailStyle.style(raw)
            assertEquals(raw.length, s.text.length, raw)
            assertTrue(s.spans.all { it.start in 0..raw.length && it.end in it.start..raw.length }, raw)
        }
        val code = StreamTailStyle.style("`code **not bold**`")
        assertTrue(code.spans.none { it.kind == StreamTailStyle.Kind.BOLD })
    }
}
