package chat.keryx.app

import chat.keryx.app.presentation.ui.components.MarkdownCache
import chat.keryx.app.presentation.ui.components.MarkdownWarmer
import chat.keryx.app.presentation.ui.components.ParsedMarkdownState
import com.mikepenz.markdown.model.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The parse-once contract behind "a long message lags when I swipe up" (2.8). */
class MarkdownCacheTest {
    private val long = buildString {
        repeat(40) { append("Paragraph $it with **bold** and `code` and a [link](https://example.com).\n\n") }
    }

    @Before fun reset() = MarkdownCache.clear()

    @Test fun parseIsCachedByContent() {
        assertNull(MarkdownCache.cached(long))
        val first = MarkdownCache.parse(long)
        assertSame(first, MarkdownCache.parse(long))
        assertSame(first, MarkdownCache.cached(long))
        assertEquals(1, MarkdownCache.size)
        assertEquals(long, first.content)
        assertTrue(first.node.children.isNotEmpty())
    }

    @Test fun warmSkipsShortBodiesAndParsesLongOnes() {
        MarkdownCache.warm("short")
        assertEquals(0, MarkdownCache.size)
        MarkdownCache.warm(long)
        assertEquals(1, MarkdownCache.size)
        assertNotNull(MarkdownCache.cached(long))
    }

    @Test fun warmerKeysExactlyWhatTheBubbleAsksFor() {
        val body = "\n" + long + "\n"
        MarkdownWarmer.warm(body)
        // The bubble trims newlines, parses to segments, then runs the pre-render chain on
        // each text segment — the warmer must land on the same key or it warms nothing.
        val segments = chat.keryx.core.protocol.MessageParser.parse(body.trim('\n'), agentChrome = true)
        val text = segments.filterIsInstance<chat.keryx.core.protocol.MessageParser.Segment.Text>().single().text
        assertNotNull(MarkdownCache.cached(MarkdownWarmer.source(text)))
    }

    @Test fun parsedStateHoldsItsTreeWithoutAwaiting() {
        val success = MarkdownCache.parse(long)
        val state = ParsedMarkdownState(success)
        assertTrue(state.state.value is State.Success)
        assertSame(success, state.state.value)
    }

    @Test fun cacheIsBounded() {
        repeat(200) { i -> MarkdownCache.parse("x".repeat(MarkdownCache.MIN_CHARS) + i) }
        assertTrue(MarkdownCache.size <= 160)
    }
}
