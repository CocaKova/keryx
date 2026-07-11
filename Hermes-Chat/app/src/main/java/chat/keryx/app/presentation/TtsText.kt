package chat.keryx.app.presentation

import chat.keryx.app.presentation.ui.components.MessageParser

/**
 * Reduces a message body to speakable prose for text-to-speech.
 *
 * Segmentation is [MessageParser]'s — the same pass that renders the chat — so anything the
 * timeline hides from prose (reasoning canvases, tool run cards, telemetry footers, action-output
 * JSON, tables, mermaid, citation lists) is never spoken either. What remains is text segments,
 * which still carry raw markdown; that syntax is flattened here because a voice reading
 * "asterisk asterisk" is worse than no voice at all.
 *
 * Pure Kotlin (no Android/Compose deps) so the rules are unit-testable, like [StreamHandoff].
 */
object TtsText {

    // Fences survive verbatim inside Segment.Text (the parser's opaque-fence branch); a voice
    // spelling out code is noise, so the whole block collapses to a spoken placeholder.
    private val FENCED_BLOCK = Regex("""(?m)^[ \t]*```[^\n]*\n[\s\S]*?(?:^[ \t]*```[ \t]*$|\z)""")
    private val INLINE_CODE = Regex("""`([^`\n]*)`""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val EMPHASIS = Regex("""(\*\*\*|\*\*|\*|___|__|_(?![\p{L}\p{N}])|~~)""")
    private val HEADING = Regex("""(?m)^[ \t]*#{1,6}[ \t]+""")
    private val QUOTE = Regex("""(?m)^[ \t]*>[ \t]?""")
    private val BULLET = Regex("""(?m)^[ \t]*(?:[-*+]|\d{1,3}[.)])[ \t]+""")
    private val RULE = Regex("""(?m)^[ \t]*(?:-{3,}|\*{3,}|_{3,})[ \t]*$""")
    // The parser rewrites cite markers to superscript refs like ⁽¹²⁾ — silent in speech.
    // ¹²³ are Latin-1 codepoints, not part of the U+2070 superscript block.
    private val CITE_REF = Regex("""[⁽⁾¹²³⁰-₟]+""")

    /** The spoken form of [content], or "" when nothing in it is worth reading aloud. */
    fun speakable(content: String): String {
        val prose = MessageParser.parse(content)
            .filterIsInstance<MessageParser.Segment.Text>()
            .joinToString("\n") { it.text }
        if (prose.isBlank()) return ""
        var out = prose
        out = FENCED_BLOCK.replace(out, " Code block. ")
        out = IMAGE.replace(out, "")
        out = LINK.replace(out) { it.groupValues[1] }
        out = INLINE_CODE.replace(out) { it.groupValues[1] }
        out = RULE.replace(out, "")
        out = HEADING.replace(out, "")
        out = QUOTE.replace(out, "")
        out = BULLET.replace(out, "")
        out = EMPHASIS.replace(out, "")
        out = CITE_REF.replace(out, "")
        return out.replace(Regex("""\s+"""), " ").trim()
    }
}
