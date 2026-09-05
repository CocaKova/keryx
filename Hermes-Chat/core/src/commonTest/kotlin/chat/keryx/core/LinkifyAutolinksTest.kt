package chat.keryx.core

import chat.keryx.core.protocol.MessageParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Bare-URL autolinks become inline links before rendering — the renderer underlines a GFM
 *  autolink but attaches no LinkAnnotation, so only inline links actually fire (2.6.0 walk). */
class LinkifyAutolinksTest {

    @Test
    fun bareUrl_becomesInlineLink() {
        assertEquals(
            "see [https://example.com/a](https://example.com/a) now",
            MessageParser.linkifyAutolinks("see https://example.com/a now"),
        )
    }

    @Test
    fun newsBriefBullet_urlAfterBoldTitle() {
        val line = "- **[1483↑ | 244💬] Minecraft clone** — https://reddit.com/r/LocalLLaMA/comments/1w2cxcw/"
        assertEquals(
            "- **[1483↑ | 244💬] Minecraft clone** — [https://reddit.com/r/LocalLLaMA/comments/1w2cxcw/](https://reddit.com/r/LocalLLaMA/comments/1w2cxcw/)",
            MessageParser.linkifyAutolinks(line),
        )
    }

    @Test
    fun existingInlineLink_untouched() {
        val line = "read this: [Read](https://arxiv.org/abs/2608.28560) today"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }

    @Test
    fun urlInsideLinkText_untouched() {
        val line = "[see https://example.com here](https://other.org)"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }

    @Test
    fun angleAutolink_untouched() {
        val line = "go to <https://example.com> now"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }

    @Test
    fun inlineCodeSpan_untouched() {
        val line = "run `curl https://example.com/api` locally"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }

    @Test
    fun fencedBlock_untouched_butProseAroundIsNot() {
        val text = "see https://a.io\n```\ncurl https://b.io\n```\nand https://c.io"
        assertEquals(
            "see [https://a.io](https://a.io)\n```\ncurl https://b.io\n```\nand [https://c.io](https://c.io)",
            MessageParser.linkifyAutolinks(text),
        )
    }

    @Test
    fun trailingPunctuation_staysOutsideTheLink() {
        assertEquals(
            "at [https://example.com/x](https://example.com/x).",
            MessageParser.linkifyAutolinks("at https://example.com/x."),
        )
        assertEquals(
            "([https://example.com/x](https://example.com/x))",
            MessageParser.linkifyAutolinks("(https://example.com/x)"),
        )
    }

    @Test
    fun balancedParens_stayInTheUrl() {
        assertEquals(
            "[https://en.wikipedia.org/wiki/X_(y)](https://en.wikipedia.org/wiki/X_(y))",
            MessageParser.linkifyAutolinks("https://en.wikipedia.org/wiki/X_(y)"),
        )
    }

    @Test
    fun bareSchemeOnly_untouched() {
        assertEquals("broken https:// end", MessageParser.linkifyAutolinks("broken https:// end"))
    }

    @Test
    fun noUrls_returnsSameInstanceShape() {
        val text = "nothing to do here"
        assertEquals(text, MessageParser.linkifyAutolinks(text))
    }

    // --- Bare image URLs render as images -----------------------------------------------
    // SILAS handed over a GIF on 2026-09-04 by typing its address in prose; 2.9.0 had taught
    // the app to animate `![](…gif)` and MEDIA:, neither of which is what an agent writes, so
    // the bubble showed a link and the GIF was invisible.

    @Test
    fun bareGifUrl_becomesInlineImage() {
        val url = "https://media3.giphy.com/media/RYbKLVwPDMcBvCQuOB/200.gif"
        assertEquals(
            "Here it is: ![$url]($url)",
            MessageParser.linkifyAutolinks("Here it is: $url"),
        )
    }

    @Test
    fun imageUrl_withQueryString_stillReadsAsImage() {
        val url = "https://media.tenor.com/abc/party.gif?itemid=123"
        assertEquals("![$url]($url)", MessageParser.linkifyAutolinks(url))
    }

    @Test
    fun imageUrl_extensionIsCaseInsensitive() {
        val url = "https://example.com/a/PHOTO.PNG"
        assertEquals("![$url]($url)", MessageParser.linkifyAutolinks(url))
    }

    @Test
    fun imageUrl_trailingSentencePunctuationStaysOutside() {
        val url = "https://example.com/a.gif"
        assertEquals("see ![$url]($url).", MessageParser.linkifyAutolinks("see $url."))
    }

    @Test
    fun nonImageUrl_staysALink() {
        val url = "https://example.com/article"
        assertEquals("[$url]($url)", MessageParser.linkifyAutolinks(url))
    }

    @Test
    fun dottedPathSegment_isNotAnExtension() {
        // The version-looking segment is a directory, and the file it names has no extension.
        val url = "https://example.com/v1.2/download"
        assertEquals("[$url]($url)", MessageParser.linkifyAutolinks(url))
    }

    @Test
    fun gifAlreadyInAnImage_isUntouched() {
        val line = "![a](https://example.com/a.gif)"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }

    @Test
    fun gifInCodeSpan_isUntouched() {
        val line = "run `curl https://example.com/a.gif` now"
        assertEquals(line, MessageParser.linkifyAutolinks(line))
    }
}
