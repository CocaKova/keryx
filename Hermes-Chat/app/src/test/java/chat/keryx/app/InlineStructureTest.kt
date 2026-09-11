package chat.keryx.app

import chat.keryx.app.presentation.ui.components.hasInlineStructure
import chat.keryx.core.protocol.MessageParser
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The custom bold/strike annotators may flatten a node only when nothing tappable lives inside it. */
class InlineStructureTest {
    private fun first(text: String, type: IElementType): ASTNode {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        fun walk(n: ASTNode): ASTNode? = if (n.type == type) n else n.children.firstNotNullOfOrNull(::walk)
        return walk(root) ?: error("no $type in: $text")
    }

    @Test fun `plain bold is flat`() {
        val n = first("**Alfa flag** is white/blue now, with: punctuation (and) 'quotes'", MarkdownElementTypes.STRONG)
        assertFalse(n.hasInlineStructure())
    }

    @Test fun `a bold url, as agents write it, has structure once linkified`() {
        val text = MessageParser.linkifyAutolinks("Link again, unchanged: **https://spark-ef6b.tail5ff3bd.ts.net:8461**")
        assertTrue(text, text.contains("**[https://spark-ef6b.tail5ff3bd.ts.net:8461](https://spark-ef6b.tail5ff3bd.ts.net:8461)**"))
        assertTrue(first(text, MarkdownElementTypes.STRONG).hasInlineStructure())
    }

    @Test fun `a bold inline link has structure`() {
        assertTrue(first("see **[the preview](https://x.example/p)** now", MarkdownElementTypes.STRONG).hasInlineStructure())
    }

    @Test fun `bold with a code span or nested emphasis has structure`() {
        assertTrue(first("**run `ship.sh` first**", MarkdownElementTypes.STRONG).hasInlineStructure())
        assertTrue(first("**really *quite* bold**", MarkdownElementTypes.STRONG).hasInlineStructure())
    }

    @Test fun `a bare gfm autolink inside bold counts as structure`() {
        assertTrue(first("**https://x.example/p**", MarkdownElementTypes.STRONG).hasInlineStructure())
    }

    @Test fun `strikethrough follows the same rule`() {
        assertFalse(first("~~old words~~", GFMElementTypes.STRIKETHROUGH).hasInlineStructure())
        assertTrue(first("~~[old link](https://x.example)~~", GFMElementTypes.STRIKETHROUGH).hasInlineStructure())
    }
}
