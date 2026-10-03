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
        val cell = inline("**Free** uses `xurl`")
        assertEquals("Free uses xurl", cell.text)
    }

    @Test
    fun `table cells carry italic strike and tappable links`() {
        val cell = inline("*new* ~~old~~ [docs](https://example.com/a) and https://x.dev/b.")
        assertEquals("new old docs and https://x.dev/b.", cell.text)
        val links = cell.getLinkAnnotations(0, cell.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }
        assertEquals(listOf("https://example.com/a", "https://x.dev/b"), links)
    }

    @Test
    fun `unmatched markers and snake_case stay literal`() {
        assertEquals("a ** b", inline("a ** b").text)
        assertEquals("max_tokens_total", inline("max_tokens_total").text)
        assertEquals("[x](not-a-url)", inline("[x](not-a-url)").text)
    }

    @Test
    fun `numbers sort as numbers`() {
        val n = { s: String -> chat.keryx.app.presentation.ui.components.tableNumber(s) }
        assertEquals(1204.0, n("1,204"))
        assertEquals(3.5, n("$3.50"))
        assertEquals(42.0, n("42%"))
        assertEquals(-7.0, n("-7 ms"))
        assertEquals(null, n("n/a"))
    }

    private fun inline(s: String) = chat.keryx.app.presentation.ui.components.inlineMarkdownAnnotated(
        s, androidx.compose.ui.graphics.Color.Blue,
    )
}
