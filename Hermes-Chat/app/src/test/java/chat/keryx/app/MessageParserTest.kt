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
}
