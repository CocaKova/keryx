package chat.keryx.core

import chat.keryx.core.protocol.RichBlock
import chat.keryx.core.protocol.RichBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichBlocksTest {

    // --- routing ----------------------------------------------------------------------------

    @Test
    fun `languages route case-insensitively and unknown ones are not claimed`() {
        assertTrue("chart" in RichBlocks.LANGS && "patch" in RichBlocks.LANGS && "palette" in RichBlocks.LANGS)
        assertIs<RichBlock.Progress>(RichBlocks.parse("PROGRESS", "a: 50%"))
        assertNull(RichBlocks.parse("kotlin", "val x = 1"))
    }

    // --- chart ------------------------------------------------------------------------------

    @Test
    fun `full chart spec parses`() {
        val c = assertIs<RichBlock.Chart>(RichBlocks.parse("chart", """
            {"type":"bar","title":"Latency","labels":["a","b","c"],
             "series":[{"name":"p50","values":[1,2,3]},{"name":"p99","values":[4,5,null]}],
             "unit":"ms","stacked":true}
        """.trimIndent()))
        assertEquals(RichBlock.ChartType.BAR, c.type)
        assertEquals("Latency", c.title)
        assertEquals(listOf("a", "b", "c"), c.labels)
        assertEquals(2, c.series.size)
        assertEquals(listOf(4.0, 5.0, null), c.series[1].values)
        assertEquals("ms", c.unit)
        assertTrue(c.stacked)
    }

    @Test
    fun `shorthands and sloppy json are accepted`() {
        // single-series data shorthand, trailing comma, unquoted keys, a comment
        val c = assertIs<RichBlock.Chart>(RichBlocks.parse("chart", """
            { type: "line", // weekly
              labels: ["Mon","Tue",], data: [3, 4,], }
        """.trimIndent()))
        assertEquals(RichBlock.ChartType.LINE, c.type)
        assertEquals(listOf(3.0, 4.0), c.series.single().values)

        val pie = assertIs<RichBlock.Chart>(RichBlocks.parse("chart", """{"type":"pie","labels":["x","y"],"values":[30,70]}"""))
        assertEquals(RichBlock.ChartType.PIE, pie.type)

        val pairs = assertIs<RichBlock.Chart>(RichBlocks.parse("chart", """{"type":"donut","data":[{"label":"a","value":1},{"label":"b","value":"2"}]}"""))
        assertEquals(listOf("a", "b"), pairs.labels)
        assertEquals(listOf(1.0, 2.0), pairs.series.single().values)

        val nested = assertIs<RichBlock.Chart>(RichBlocks.parse("chart", """{"type":"columns","series":[[1,2],[3,4]]}"""))
        assertEquals(listOf("1", "2"), nested.labels) // labels default to 1..n
        assertEquals(2, nested.series.size)

        assertEquals(RichBlock.ChartType.HBAR, (RichBlocks.parse("chart", """{"type":"horizontal_bar","data":[1]}""") as RichBlock.Chart).type)
        assertEquals(RichBlock.ChartType.BAR, (RichBlocks.parse("chart", """{"data":[1,2]}""") as RichBlock.Chart).type)
    }

    @Test
    fun `charts that do not line up are refused`() {
        assertNull(RichBlocks.parse("chart", "not json"))
        assertNull(RichBlocks.parse("chart", "[1,2,3]"))
        assertNull(RichBlocks.parse("chart", """{"type":"radar","data":[1,2]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"bar","labels":["a","b"],"data":[1,2,3]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"bar","series":[{"values":[1,2]},{"values":[1]}]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"bar","data":[]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"bar","data":[null,null]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"pie","data":[-1,2]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"pie","data":[0,0]}"""))
        assertNull(RichBlocks.parse("chart", """{"type":"pie","series":[[1,2],[3,4]]}"""))
        val tooMany = (1..RichBlocks.MAX_POINTS + 1).joinToString(",")
        assertNull(RichBlocks.parse("chart", """{"data":[$tooMany]}"""))
        val tooManySeries = (1..RichBlocks.MAX_SERIES + 1).joinToString(",") { "[1]" }
        assertNull(RichBlocks.parse("chart", """{"series":[$tooManySeries]}"""))
    }

    // --- diff -------------------------------------------------------------------------------

    @Test
    fun `unified diff lines are classified`() {
        val d = assertIs<RichBlock.Diff>(RichBlocks.parse("diff", """
            diff --git a/x b/x
            --- a/x
            +++ b/x
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
        """.trimIndent()))
        assertEquals(
            listOf(
                RichBlock.DiffKind.META, RichBlock.DiffKind.META, RichBlock.DiffKind.META,
                RichBlock.DiffKind.HUNK, RichBlock.DiffKind.CONTEXT, RichBlock.DiffKind.DEL, RichBlock.DiffKind.ADD,
            ),
            d.lines.map { it.kind },
        )
        assertEquals("+new", d.lines.last().text)
    }

    @Test
    fun `a diff with no changes is just text`() {
        assertNull(RichBlocks.parse("diff", "hello\nworld"))
        assertNull(RichBlocks.parse("patch", ""))
    }

    // --- timeline ---------------------------------------------------------------------------

    @Test
    fun `timeline accepts the common shapes`() {
        val t = assertIs<RichBlock.Timeline>(RichBlocks.parse("timeline", """
            2026-09-01 · Shipped 2.16
            2026-09-15: Beta opens
            - [x] 2026-10-01 — Launch — on the store
            * [ ] Q4 | Retro
            Later polish
        """.trimIndent()))
        assertEquals(5, t.items.size)
        assertEquals(RichBlock.TimelineItem("2026-09-01", "Shipped 2.16", null, null), t.items[0])
        assertEquals(RichBlock.TimelineItem("2026-09-15", "Beta opens", null, null), t.items[1])
        assertEquals(RichBlock.TimelineItem("2026-10-01", "Launch", "on the store", true), t.items[2])
        assertEquals(RichBlock.TimelineItem("Q4", "Retro", null, false), t.items[3])
        assertEquals(RichBlock.TimelineItem(null, "Later polish", null, null), t.items[4])
    }

    @Test
    fun `dates with hyphens are not split and one line is not a timeline`() {
        val t = assertIs<RichBlock.Timeline>(RichBlocks.parse("timeline", "2026-01-02 - start\n2026-03-04 - end"))
        assertEquals("2026-01-02", t.items[0].time)
        assertEquals("start", t.items[0].title)
        assertNull(RichBlocks.parse("timeline", "just one thing"))
        assertNull(RichBlocks.parse("timeline", "\n\n"))
    }

    @Test
    fun `clock times read as times`() {
        val t = assertIs<RichBlock.Timeline>(RichBlocks.parse("timeline", "10:30: standup\n14:00: review"))
        assertEquals("10:30", t.items[0].time)
        assertEquals("standup", t.items[0].title)
    }

    // --- progress ---------------------------------------------------------------------------

    @Test
    fun `progress reads percents ratios and fractions`() {
        val p = assertIs<RichBlock.Progress>(RichBlocks.parse("progress", """
            - Build: 60%
            Tests: 3/5
            Docs — 0.25
            Deploy = 2 of 4
            Phase 2: polish: 40
            Overshoot: 140%
        """.trimIndent()))
        assertEquals(listOf("Build", "Tests", "Docs", "Deploy", "Phase 2: polish", "Overshoot"), p.items.map { it.label })
        assertEquals(listOf(0.6f, 0.6f, 0.25f, 0.5f, 0.4f, 1f), p.items.map { it.fraction })
        assertEquals("3/5", p.items[1].display)
    }

    @Test
    fun `progress refuses lines it cannot read`() {
        assertNull(RichBlocks.parse("progress", "Build: soon"))
        assertNull(RichBlocks.parse("progress", "no separator here"))
        assertNull(RichBlocks.parse("progress", "Div: 1/0"))
        assertNull(RichBlocks.parse("progress", ""))
    }

    // --- swatches ---------------------------------------------------------------------------

    @Test
    fun `swatches take names and runs of colours`() {
        val s = assertIs<RichBlock.Swatches>(RichBlocks.parse("palette", """
            Ink: #1a1a2e
            - #fff (paper)
            #80FF0000 — scrim
            #111 #222, #333
        """.trimIndent()))
        assertEquals(6, s.colors.size)
        assertEquals(RichBlock.Swatch("Ink", 0xFF1A1A2E, "#1A1A2E"), s.colors[0])
        assertEquals(RichBlock.Swatch("paper", 0xFFFFFFFF, "#FFFFFF"), s.colors[1])
        assertEquals(RichBlock.Swatch("scrim", 0x80FF0000, "#80FF0000"), s.colors[2])
        assertEquals(listOf("#111111", "#222222", "#333333"), s.colors.drop(3).map { it.hex })
    }

    @Test
    fun `swatches refuse lines without a colour`() {
        assertNull(RichBlocks.parse("swatch", "#fff\nnot a colour"))
        assertNull(RichBlocks.parse("colors", "#12345"))
        assertNull(RichBlocks.parse("colors", ""))
    }

    // --- card -------------------------------------------------------------------------------

    @Test
    fun `card keys fields and body`() {
        val c = assertIs<RichBlock.Card>(RichBlocks.parse("card", """
            title: Spark 1
            subtitle: DGX Spark · brain host
            image: https://example.com/spark.png
            link: https://example.com
            GPU: GB10
            Memory: 128 GB
            body: Runs the brain.
            Second line of body: with a colon.
        """.trimIndent()))
        assertEquals("Spark 1", c.title)
        assertEquals("DGX Spark · brain host", c.subtitle)
        assertEquals("https://example.com/spark.png", c.image)
        assertEquals("https://example.com", c.url)
        assertEquals(listOf("GPU" to "GB10", "Memory" to "128 GB"), c.fields)
        assertEquals("Runs the brain.\nSecond line of body: with a colon.", c.body)
    }

    @Test
    fun `card needs a title and refuses unsafe links`() {
        assertNull(RichBlocks.parse("card", "subtitle: nope"))
        val c = assertIs<RichBlock.Card>(RichBlocks.parse("card", "title: X\nimage: http://insecure/x.png\nurl: javascript:alert(1)"))
        assertNull(c.image)
        assertNull(c.url)
    }

    // --- details ----------------------------------------------------------------------------

    @Test
    fun `details title and markdown body`() {
        val d = assertIs<RichBlock.Details>(RichBlocks.parse("details", "summary: Full log\n\n- one\n- **two**"))
        assertEquals("Full log", d.title)
        assertEquals("- one\n- **two**", d.body)
        assertEquals("Why", (RichBlocks.parse("collapse", "## Why\nbecause") as RichBlock.Details).title)
        assertNull(RichBlocks.parse("details", "title only"))
        assertNull(RichBlocks.parse("details", ""))
    }
}
