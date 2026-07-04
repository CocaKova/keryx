package chat.keryx.app

import chat.keryx.app.presentation.ui.components.fencedCodeText
import chat.keryx.app.presentation.ui.components.indentedCodeText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the fix for the "identical message inside a copy-paste code block" bug: the markdown
 * component was handed MarkdownComponentModel.content (the WHOLE message source) instead of the
 * fence node's own text, so every mixed prose+fence message rendered itself twice — once as
 * markdown, once raw inside the code block. These cover the node-text extraction helpers.
 */
class CodeBlockTextTest {

    @Test
    fun `fence markers and language are stripped`() {
        assertEquals("rm /tmp/x.txt", fencedCodeText("```bash\nrm /tmp/x.txt\n```"))
        assertEquals("line1\nline2", fencedCodeText("```\nline1\nline2\n```"))
    }

    @Test
    fun `unterminated fence keeps all code lines`() {
        assertEquals("still streaming", fencedCodeText("```\nstill streaming"))
    }

    @Test
    fun `box drawing diagram survives intact`() {
        val art = "┌────┐\n│ OK │\n└────┘"
        assertEquals(art, fencedCodeText("```\n$art\n```"))
    }

    @Test
    fun `indented block loses its four-space prefix`() {
        assertEquals("a\n  b", indentedCodeText("    a\n      b"))
    }

    @Test
    fun `table cells shed bold and code markers`() {
        val cell = chat.keryx.app.presentation.ui.components.tableCellAnnotated("**Free** uses `xurl`")
        assertEquals("Free uses xurl", cell.text)
    }
}
