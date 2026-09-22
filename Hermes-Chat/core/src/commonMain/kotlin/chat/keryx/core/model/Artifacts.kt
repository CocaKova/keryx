package chat.keryx.core.model

/**
 * An artifact is a page the agent wrote — a mockup, a report, the Salt Creek kind — and it
 * arrives in the transcript one of two ways: as a `MEDIA:<path>.html` line (already lifted into
 * a media message by [MediaTags]) or as a bare absolute path in the prose ("saved it to
 * `/home/sy/out/mock-v1.html`"). Both should open in the viewer, not in a system file chooser.
 *
 * Pure. [findArtifactPaths] is deliberately narrow: it wants a path that starts at the root, has
 * no spaces or URL scheme in front of it, and ends in `.html`/`.htm` with nothing path-like after.
 * A URL's path (`https://host/x.html`) is not a file on the gateway host and is left alone; so is
 * `/x.html.bak`. Missing a real path costs one tap on the media card; a false hit puts a dead
 * "Open" chip on a sentence, which is worse.
 */
object Artifacts {

    private val EXT = setOf("html", "htm")

    /**
     * One absolute path ending in `.html`/`.htm`. Preceded by the start, whitespace, a quote,
     * a backtick or an open bracket — never by a slash, colon or word character, which is how a
     * URL's path or the tail of a longer token would otherwise sneak in. Followed by nothing
     * that continues a path or an extension: `.html)` and `.html,` close, `.html/x` and
     * `.html.bak` do not.
     */
    private val BARE = Regex(
        """(?<![\w/:.])(/(?:[\w.@+~\-]+/)*[\w.@+~\-]+\.(?:html|htm))(?![\w/]|\.\w)""",
        RegexOption.IGNORE_CASE,
    )

    fun isArtifactPath(path: String): Boolean {
        val ext = path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return ext in EXT
    }

    /** Every bare artifact path in [text], first occurrence first, each once. */
    fun findArtifactPaths(text: String): List<String> {
        if (!text.contains(".htm", ignoreCase = true)) return emptyList()
        return BARE.findAll(text).map { it.groupValues[1] }.distinct().toList()
    }

    /** The file's own name, for a chip or a title. */
    fun nameOf(path: String): String = MediaTags.nameOf(path)
}
