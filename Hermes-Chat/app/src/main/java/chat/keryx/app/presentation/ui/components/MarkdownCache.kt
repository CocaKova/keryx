package chat.keryx.app.presentation.ui.components

import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Parsed markdown, kept (2.8 — "a long message lags when I swipe up").
 *
 * The renderer parses a message's markdown when its bubble composes. A LazyColumn disposes
 * the composition of every row that scrolls off, so a long answer was re-parsed on the UI
 * thread every time it scrolled back into view — a full intellij-markdown pass over a few
 * thousand characters is a dropped frame or three, and the tree it builds is identical each
 * time. This keeps the tree by content: the first composition pays (or the warmer pays off
 * the main thread, ahead of the scroll), every later one is a map lookup.
 *
 * What is cached is the library's own [State.Success] — the AST, the source it indexes into,
 * and a link handler — wrapped in a [MarkdownState] whose flow never moves. Streaming bodies
 * never come here: they change every tick and are parsed once, uncached, on the sync path.
 */
object MarkdownCache {
    private const val MAX_ENTRIES = 160
    /** Bodies under this are cheap to parse in place; caching them only churns the map. */
    const val MIN_CHARS = 600

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    private val cache = object : LinkedHashMap<String, State.Success>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State.Success>) = size > MAX_ENTRIES
    }

    fun cached(content: String): State.Success? = synchronized(cache) { cache[content] }

    /** Parse (or fetch) [content]'s tree. Safe off the main thread; the parser is pure. */
    fun parse(content: String): State.Success {
        cached(content)?.let { return it }
        val tree = parser.buildMarkdownTreeFromString(content)
        val handler = ReferenceLinkHandlerImpl()
        val success = State.Success(node = tree, content = content, linksLookedUp = false, referenceLinkHandler = handler)
        synchronized(cache) { cache[content] = success }
        return success
    }

    /** Pre-parse ahead of the scroll: a settled body that is long enough to matter. */
    fun warm(content: String) {
        if (content.length < MIN_CHARS) return
        if (cached(content) != null) return
        runCatching { parse(content) }
    }

    fun clear() = synchronized(cache) { cache.clear() }

    /** Test hook: how many trees are held. */
    val size: Int get() = synchronized(cache) { cache.size }
}

/** A [MarkdownState] that already holds its tree — nothing to parse, nothing to await. */
class ParsedMarkdownState(success: State.Success) : MarkdownState {
    private val _state = MutableStateFlow<State>(success)
    override val state: StateFlow<State> = _state
    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())
    override val links: StateFlow<Map<String, String>> = _links
    override suspend fun parse(): State = _state.value
    val referenceLinkHandler: ReferenceLinkHandler = success.referenceLinkHandler
}
