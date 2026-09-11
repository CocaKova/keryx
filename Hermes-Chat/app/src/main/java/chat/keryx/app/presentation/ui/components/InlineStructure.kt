package chat.keryx.app.presentation.ui.components

import org.intellij.markdown.MarkdownElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * True when an inline node carries anything beyond plain words: a link, an image, a code span,
 * nested emphasis, an autolink. The custom `**strong**` and `~~strike~~` annotators in
 * [MessageContent] flatten a node to its raw inner text and claim it handled, which the
 * renderer honours by never visiting the children. That is fine for `**bold words**` and
 * fatal for `**[a link](url)**`: the link annotation a tap fires lives on the child, so a URL
 * an agent put in bold was never tappable (2.11.4). A node with structure is handed back to
 * the library, which renders the emphasis at its own weight and keeps the children alive.
 *
 * Tokens (text, whitespace, punctuation, the `**` delimiters themselves) are not structure.
 * A GFM autolink is a token in the parser's eyes but a link in the reader's, so it counts.
 */
internal fun ASTNode.hasInlineStructure(): Boolean = children.any { child ->
    val type = child.type
    (type is MarkdownElementType && !type.isToken) || type == GFMTokenTypes.GFM_AUTOLINK
}
