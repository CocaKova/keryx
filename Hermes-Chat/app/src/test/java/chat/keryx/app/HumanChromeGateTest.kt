package chat.keryx.app

import chat.keryx.app.presentation.ui.components.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parse(…, agentChrome = false)` — a HUMAN sender's message must never be restyled as agent
 * output, no matter how hard its text pattern-matches tool lines, reasoning tags, telemetry
 * shapes, or action-output JSON. Only universal markdown (text, tables, fences, mermaid) may
 * survive. Each payload here is asserted BOTH ways so the test also proves the agent path still
 * produces its chrome (i.e. the flag gates, not the pattern).
 */
class HumanChromeGateTest {

    private fun humanSegments(content: String) = MessageParser.parse(content, agentChrome = false)

    private fun assertPlain(segments: List<MessageParser.Segment>) {
        assertTrue(
            "human parse must only contain Text/Table/Mermaid, got $segments",
            segments.all {
                it is MessageParser.Segment.Text ||
                    it is MessageParser.Segment.Table ||
                    it is MessageParser.Segment.Mermaid
            },
        )
    }

    @Test
    fun toolShapedLine_staysProseForHumans() {
        val content = "⚙️ deploy_prod: \"run the friday deploy\"\nsounds good?"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.Tools })
        val human = humanSegments(content)
        assertPlain(human)
        // The text survives verbatim inside one Text segment.
        assertEquals(content, (human.single() as MessageParser.Segment.Text).text)
    }

    @Test
    fun thinkBlock_staysProseForHumans() {
        val content = "<think>should I tell him?</think>\nok here's my real answer"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.Thinking })
        assertPlain(humanSegments(content))
    }

    @Test
    fun subtextAside_notDemotedForHumans() {
        val content = "-# brb grabbing coffee"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.Telemetry })
        val human = humanSegments(content)
        assertPlain(human)
        assertTrue(human.isNotEmpty()) // and critically: not hidden
    }

    @Test
    fun footerShapedLastLine_notTelemetryForHumans() {
        val content = "meet at the office\ngemma · 42% · ~/dir"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.Telemetry })
        assertPlain(humanSegments(content))
    }

    @Test
    fun actionOutputJson_staysCodeForHumans() {
        val content = """{"tool": "swaps", "action": "check", "result": "all good"}"""
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.ActionOutput })
        assertPlain(humanSegments(content))
    }

    @Test
    fun jsonFence_staysCodeForHumans() {
        val content = "```json\n{\"tool\": \"x\", \"params\": {}}\n```"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.ActionOutput })
        assertPlain(humanSegments(content))
    }

    @Test
    fun brainGlyphLine_staysProseForHumans() {
        val content = "🧠 galaxy brain take incoming"
        assertTrue(MessageParser.parse(content).any { it is MessageParser.Segment.Thinking })
        assertPlain(humanSegments(content))
    }

    @Test
    fun markdownStillWorksForHumans() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertTrue(humanSegments(table).any { it is MessageParser.Segment.Table })
        val mermaid = "```mermaid\ngraph TD; A-->B\n```"
        assertTrue(humanSegments(mermaid).any { it is MessageParser.Segment.Mermaid })
    }

    @Test
    fun cacheKeepsVariantsApart() {
        val content = "⚙️ terminal: `ls`"
        // Prime both cache entries in each order and re-read — no cross-contamination.
        val agent1 = MessageParser.parse(content)
        val human1 = MessageParser.parse(content, agentChrome = false)
        val agent2 = MessageParser.parse(content)
        val human2 = MessageParser.parse(content, agentChrome = false)
        assertTrue(agent1.any { it is MessageParser.Segment.Tools })
        assertTrue(agent2.any { it is MessageParser.Segment.Tools })
        assertPlain(human1)
        assertPlain(human2)
    }
}
