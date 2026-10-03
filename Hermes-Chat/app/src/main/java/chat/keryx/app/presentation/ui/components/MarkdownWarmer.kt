package chat.keryx.app.presentation.ui.components

/**
 * The off-main-thread half of [MarkdownCache]: what the timeline calls for every settled long
 * body it knows about, so the parse has happened by the time the row scrolls in. Kept apart
 * from the cache so the cache stays a pure map (tested) and this stays a policy.
 */
object MarkdownWarmer {
    /** The pre-render chain the bubble applies before parsing — the cache key must match it. */
    fun source(content: String): String {
        val body = content.trim('\n')
        return chat.keryx.core.protocol.MessageParser.linkifyAutolinks(
            chat.keryx.core.protocol.MessageParser.closeDanglingFences(
                runCatching { chat.keryx.core.protocol.MathUnicode.render(body) }.getOrDefault(body),
            ),
        )
    }

    /** Bodies already warmed. The timeline re-runs the warmer on every list change (a new
     *  message, a reaction); without this each pass re-segmented and re-ran the regex chain over
     *  every long body in the window just to find its trees already cached. */
    private val done = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 400
    }

    fun warm(content: String) {
        if (synchronized(done) { done.put(content, Unit) } != null) return
        // Only the prose segments reach the markdown renderer; tables, fences-as-segments and
        // markers take their own paths. Warm exactly what the bubble will ask for.
        val segments = runCatching {
            chat.keryx.core.protocol.MessageParser.parse(content.trim('\n'), agentChrome = true, cacheable = true)
        }.getOrNull() ?: return
        for (seg in segments) {
            val text = (seg as? chat.keryx.core.protocol.MessageParser.Segment.Text)?.text ?: continue
            MarkdownCache.warm(source(text))
        }
    }
}
