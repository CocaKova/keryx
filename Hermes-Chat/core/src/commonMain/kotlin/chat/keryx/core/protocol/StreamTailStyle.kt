package chat.keryx.core.protocol

/**
 * Live formatting for the paragraph still streaming in.
 *
 * Settled paragraphs go through the markdown renderer; the one being written is drawn as plain
 * text so each new run of characters can fade in. Plain text meant raw `**`, backticks and `#`
 * marks on screen until the paragraph finished, then a jump to the formatted look. This keeps the
 * fade and still formats as it types: it never changes the text's length (the fade tracks runs
 * by index), it only says which ranges to style and which marker characters to hide, plus a few
 * same-length swaps (`- ` → `• `).
 *
 * An opened `**` or backtick with no closer yet styles to the end of the text, so a bold phrase
 * reads bold while it is being typed rather than popping bold when its closer lands.
 */
object StreamTailStyle {

    enum class Kind { HIDE, BOLD, ITALIC, CODE, STRIKE, LINK, HEADING }

    data class Span(val start: Int, val end: Int, val kind: Kind, val level: Int = 0)

    data class Styled(val text: String, val spans: List<Span>)

    fun style(raw: String): Styled {
        val out = StringBuilder(raw)
        val spans = mutableListOf<Span>()
        var lineStart = 0
        var inFence = false
        while (lineStart <= raw.length) {
            val nl = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            val line = raw.substring(lineStart, nl)
            val t = line.trimStart()
            val lead = line.length - t.length
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence
                spans += Span(lineStart, nl, Kind.CODE)
            } else if (inFence) {
                spans += Span(lineStart, nl, Kind.CODE)
            } else {
                var contentStart = lineStart + lead
                val hashes = t.takeWhile { it == '#' }.length
                when {
                    hashes in 1..6 && t.length > hashes && t[hashes] == ' ' -> {
                        spans += Span(contentStart, contentStart + hashes + 1, Kind.HIDE)
                        spans += Span(contentStart + hashes + 1, nl, Kind.HEADING, level = hashes)
                        contentStart += hashes + 1
                    }
                    (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) -> {
                        out.setCharAt(contentStart, '•')
                        contentStart += 2
                    }
                    t.startsWith("> ") -> {
                        spans += Span(contentStart, contentStart + 2, Kind.HIDE)
                        spans += Span(contentStart + 2, nl, Kind.ITALIC)
                        contentStart += 2
                    }
                }
                inline(raw, contentStart, nl, spans)
            }
            lineStart = nl + 1
        }
        return Styled(out.toString(), spans)
    }

    /** Inline marks within `[from, to)` of [s]. */
    private fun inline(s: String, from: Int, to: Int, spans: MutableList<Span>) {
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '`' -> {
                    val end = s.indexOf('`', i + 1).takeIf { it in (i + 1) until to }
                    spans += Span(i, i + 1, Kind.HIDE)
                    if (end == null) { spans += Span(i + 1, to, Kind.CODE); return }
                    spans += Span(i + 1, end, Kind.CODE)
                    spans += Span(end, end + 1, Kind.HIDE)
                    i = end + 1
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    // `__` inside a word (snake__case) is not emphasis.
                    if (mark == "__" && i > from && s[i - 1].isLetterOrDigit()) { i += 2; continue }
                    val end = s.indexOf(mark, i + 2).takeIf { it in (i + 2) until to }
                    spans += Span(i, i + 2, Kind.HIDE)
                    if (end == null) { spans += Span(i + 2, to, Kind.BOLD); inline(s, i + 2, to, spans); return }
                    spans += Span(i + 2, end, Kind.BOLD)
                    inline(s, i + 2, end, spans)
                    spans += Span(end, end + 2, Kind.HIDE)
                    i = end + 2
                }
                s.startsWith("~~", i) -> {
                    val end = s.indexOf("~~", i + 2).takeIf { it in (i + 2) until to }
                    if (end == null) { i += 2; continue }
                    spans += Span(i, i + 2, Kind.HIDE)
                    spans += Span(i + 2, end, Kind.STRIKE)
                    spans += Span(end, end + 2, Kind.HIDE)
                    i = end + 2
                }
                (c == '*' || c == '_') && i + 1 < to && !s[i + 1].isWhitespace() &&
                    !(c == '_' && i > from && s[i - 1].isLetterOrDigit()) -> {
                    // Italic only once it closes: a lone `*` is as likely a footnote or a product.
                    val end = s.indexOf(c, i + 1).takeIf { it in (i + 1) until to && !s[it - 1].isWhitespace() }
                    if (end == null) { i++; continue }
                    spans += Span(i, i + 1, Kind.HIDE)
                    spans += Span(i + 1, end, Kind.ITALIC)
                    spans += Span(end, end + 1, Kind.HIDE)
                    i = end + 1
                }
                c == '[' -> {
                    val mid = s.indexOf("](", i + 1).takeIf { it in (i + 1) until to }
                    if (mid == null) { i++; continue }
                    val close = s.indexOf(')', mid + 2).takeIf { it in (mid + 2) until to }
                    spans += Span(i, i + 1, Kind.HIDE)
                    spans += Span(i + 1, mid, Kind.LINK)
                    // The target is hidden while it is still being typed, too.
                    spans += Span(mid, (close ?: (to - 1)) + 1, Kind.HIDE)
                    if (close == null) return
                    i = close + 1
                }
                else -> i++
            }
        }
    }
}
