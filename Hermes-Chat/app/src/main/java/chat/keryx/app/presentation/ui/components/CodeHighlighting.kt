package chat.keryx.app.presentation.ui.components

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Syntax colour for a code fence, as plain data: the tokenizer's spans clamped to the code's
 * bounds, with no Compose in sight so it is testable on the JVM and cannot be tangled in a
 * layout.
 *
 * Why not the renderer's own `MarkdownHighlightedCode`: it wraps its text in a
 * `horizontalScroll` of its own, and Keryx's code block already scrolls (with the
 * only-when-it-overflows rule that keeps drawer swipes alive over short blocks). Two nested
 * horizontal scrollers hand the inner one an infinite width, and Compose refuses that at
 * measure time — every fence tagged with a language the tokenizer knew (`bash`, `python`…)
 * killed the app the moment it scrolled into view. The colours were the only thing wanted
 * from that composable, so only the colours are taken.
 */
object CodeHighlighting {

    /** One coloured or bold run in `[start, end)`; [rgb] is `0xRRGGBB`, null for bold-only. */
    data class Span(val start: Int, val end: Int, val rgb: Int?, val bold: Boolean)

    /** True when [language] names a grammar the tokenizer has. */
    fun knows(language: String?): Boolean = languageOf(language) != null

    /**
     * Spans for [code] in [language]; empty when the language is unknown or blank, and empty
     * (never a throw) when the tokenizer chokes — a fence is text first, colour second.
     */
    fun spans(code: String, language: String?, darkMode: Boolean): List<Span> {
        val lang = languageOf(language) ?: return emptyList()
        if (code.isEmpty()) return emptyList()
        val highlights = runCatching {
            Highlights.Builder()
                .code(code)
                .language(lang)
                .theme(SyntaxThemes.pastel(darkMode = darkMode))
                .build()
                .getHighlights()
        }.getOrElse { return emptyList() }
        return highlights.mapNotNull { h ->
            val start = h.location.start.coerceIn(0, code.length)
            val end = h.location.end.coerceIn(start, code.length)
            if (end <= start) return@mapNotNull null
            when (h) {
                is ColorHighlight -> Span(start, end, rgb = h.rgb and 0xFFFFFF, bold = false)
                is BoldHighlight -> Span(start, end, rgb = null, bold = true)
                else -> null
            }
        }
    }

    /** What fences are actually tagged with, mapped onto the grammar names the tokenizer has
     *  (it matches its own enum names only — `bash` is not `shell` to it, `ts` is not
     *  `typescript`). */
    private val ALIASES = mapOf(
        "bash" to "shell", "sh" to "shell", "zsh" to "shell", "console" to "shell",
        "js" to "javascript", "mjs" to "javascript", "jsx" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "py" to "python", "kt" to "kotlin", "kts" to "kotlin", "rs" to "rust",
        "cs" to "csharp", "c++" to "cpp", "cc" to "cpp", "h" to "c", "golang" to "go",
        "rb" to "ruby", "coffee" to "coffeescript",
    )

    private fun languageOf(language: String?): SyntaxLanguage? {
        val name = language?.trim()?.lowercase().orEmpty()
        if (name.isBlank()) return null
        return runCatching { SyntaxLanguage.getByName(ALIASES[name] ?: name) }.getOrNull()
    }
}
