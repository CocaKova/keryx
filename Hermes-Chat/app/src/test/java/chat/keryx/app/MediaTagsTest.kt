package chat.keryx.app

import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.MediaTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes from desktop's MEDIA_LINE_RE / MEDIA_TAG_RE — kept compatible. */
class MediaTagsTest {

    @Test
    fun `no tag passes through untouched`() {
        val s = MediaTags.split("just words\nand more")
        assertEquals("just words\nand more", s.text)
        assertTrue(s.refs.isEmpty())
    }

    @Test
    fun `whole-line tag becomes a ref and leaves the prose joined`() {
        val s = MediaTags.split("Here is the PDF.\nMEDIA:/home/u/notes/Week2.pdf\nLet me know.")
        assertEquals("Here is the PDF.\nLet me know.", s.text)
        assertEquals(1, s.refs.size)
        assertEquals("/home/u/notes/Week2.pdf", s.refs[0].path)
        assertEquals("Week2.pdf", s.refs[0].name)
        assertEquals(MediaKind.FILE, s.refs[0].kind)
    }

    @Test
    fun `several tags, quoted and backticked, in order`() {
        val s = MediaTags.split("Slides:\nMEDIA: `/a/one.png`\nMEDIA:\"/a/two.png\"\n MEDIA: '/a/three.png' \n")
        assertEquals("Slides:", s.text)
        assertEquals(listOf("/a/one.png", "/a/two.png", "/a/three.png"), s.refs.map { it.path })
        assertTrue(s.refs.all { it.kind == MediaKind.IMAGE })
    }

    @Test
    fun `inline tag is replaced by the file name`() {
        val s = MediaTags.split("Saved it as MEDIA:/tmp/out/report.pdf for you.")
        assertEquals("Saved it as `report.pdf` for you.", s.text)
        assertEquals("/tmp/out/report.pdf", s.refs.single().path)
    }

    @Test
    fun `tag only message leaves blank prose`() {
        val s = MediaTags.split("MEDIA:/x/y.mp3")
        assertEquals("", s.text)
        assertEquals(MediaKind.AUDIO, s.refs.single().kind)
    }

    @Test
    fun `kind by extension, query and fragment ignored`() {
        assertEquals(MediaKind.VIDEO, MediaTags.kindOf("/v/clip.MP4?x=1"))
        assertEquals(MediaKind.IMAGE, MediaTags.kindOf("https://h/i.webp#f"))
        assertEquals(MediaKind.FILE, MediaTags.kindOf("/d/deck.pptx"))
        assertEquals("deck.pptx", MediaTags.nameOf("/d/deck.pptx"))
    }
}
