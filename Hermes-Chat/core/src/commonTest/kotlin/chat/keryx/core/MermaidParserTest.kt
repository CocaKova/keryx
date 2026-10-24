package chat.keryx.core

import chat.keryx.core.protocol.MermaidParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidParserTest {

    private fun labelOf(graph: MermaidParser.Graph, id: String): String =
        graph.nodes.first { it.id == id }.label

    @Test
    fun nodeLabel_brTag_becomesNewline() {
        val g = MermaidParser.parse("flowchart TB\n  RED[\"range-red · .20<br/>attacker\"]")
        assertNotNull(g)
        assertEquals("range-red · .20\nattacker", labelOf(g, "RED"))
        assertFalse("<br" in labelOf(g, "RED"), "the literal tag must not survive")
    }

    @Test
    fun nodeLabel_allBrSpellings() {
        // <br>, <br/>, <br />, and upper/odd spacing all collapse to one newline.
        val g = MermaidParser.parse(
            "flowchart LR\n" +
                "  A[\"a<br>b\"]\n" +
                "  B[\"c<br />d\"]\n" +
                "  C[\"e<BR/>f\"]\n" +
                "  D[\"g< br / >h\"]\n"
        )
        assertNotNull(g)
        assertEquals("a\nb", labelOf(g, "A"))
        assertEquals("c\nd", labelOf(g, "B"))
        assertEquals("e\nf", labelOf(g, "C"))
        assertEquals("g\nh", labelOf(g, "D"))
    }

    @Test
    fun edgeLabel_brTag_becomesNewline() {
        val g = MermaidParser.parse("flowchart TB\n  A --> |step one<br/>then two| B")
        assertNotNull(g)
        assertEquals("step one\nthen two", g.edges.single().label)
    }

    @Test
    fun strayLeadingOrTrailingBr_isTrimmed() {
        val g = MermaidParser.parse("flowchart TB\n  A[\"<br/>only<br/>\"]")
        assertNotNull(g)
        assertEquals("only", labelOf(g, "A"))
    }

    @Test
    fun realWorldSubgraphFlowchart_noLiteralBr() {
        // The shape that surfaced the bug: subgraphs (ignored) wrapping <br/> node labels.
        val code = """
            flowchart TB
              subgraph HOST["lab host · one box"]
                subgraph NET["bridge · 172.28.0.0/24"]
                  RED["range-red · .20<br/>attacker"]
                  VIC["range-victim · .10<br/>22 ssh, 31337 http"]
                end
                BLUE["range-blue · host netns<br/>tcpdump on the bridge"]
              end
        """.trimIndent()
        val g = MermaidParser.parse(code)
        assertNotNull(g)
        assertTrue(g.nodes.none { "<br" in it.label }, "no node label may keep a literal <br>")
        assertEquals("range-victim · .10\n22 ssh, 31337 http", labelOf(g, "VIC"))
        assertEquals("range-blue · host netns\ntcpdump on the bridge", labelOf(g, "BLUE"))
    }

    @Test
    fun plainLabel_unchanged() {
        val g = MermaidParser.parse("flowchart TB\n  A[\"just text\"] --> B[\"more\"]")
        assertNotNull(g)
        assertEquals("just text", labelOf(g, "A"))
        assertEquals("more", labelOf(g, "B"))
    }
}
