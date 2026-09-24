package chat.keryx.core

import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.MediaTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@image:<path>` — how the gateway persists a photo the USER sent (session_history.py,
 * `_build_persist_message_with_image_refs`): caption first, one directive line per file, the
 * path quoted when it holds a space. Shown as text until 2.13.7 (device, 2026-09-24).
 */
class MediaTagsImageRefTest {

    @Test
    fun captionThenDirective_splitsIntoCaptionAndImage() {
        val s = MediaTags.splitImageRefs("She sent this picture after\n@image:/home/sy/.hermes/images/upload_20260924_114242_1.jpg")
        assertEquals("She sent this picture after", s.text)
        assertEquals(1, s.refs.size)
        assertEquals("/home/sy/.hermes/images/upload_20260924_114242_1.jpg", s.refs[0].path)
        assertEquals("upload_20260924_114242_1.jpg", s.refs[0].name)
        assertEquals(MediaKind.IMAGE, s.refs[0].kind)
    }

    @Test
    fun directiveOnly_isAnImageWithNoCaption() {
        val s = MediaTags.splitImageRefs("@image:/home/sy/.hermes/images/upload_1.png")
        assertEquals("", s.text)
        assertEquals(listOf("/home/sy/.hermes/images/upload_1.png"), s.refs.map { it.path })
    }

    @Test
    fun severalDirectives_keepOrder_andTheirNewlinesGoWithThem() {
        val s = MediaTags.splitImageRefs("two of them\n@image:/a/1.jpg\n@image:/a/2.jpg\nthanks")
        assertEquals("two of them\nthanks", s.text)
        assertEquals(listOf("/a/1.jpg", "/a/2.jpg"), s.refs.map { it.path })
    }

    @Test
    fun quotedPathWithSpaces_isUnquoted() {
        val s = MediaTags.splitImageRefs("@image:\"/home/sy/Pictures/my mom.jpg\"")
        assertEquals("/home/sy/Pictures/my mom.jpg", s.refs.single().path)
        assertEquals("my mom.jpg", s.refs.single().name)
    }

    @Test
    fun talkingAboutTheConvention_staysProse() {
        val text = "hermes writes @image:<path> lines, one per file"
        val s = MediaTags.splitImageRefs(text)
        assertTrue(s.refs.isEmpty())
        assertEquals(text, s.text)
    }

    @Test
    fun mediaTagsAndImageRefs_areSeparateGrammars() {
        // A MEDIA: line is the agent's; splitImageRefs must not eat it, and vice versa.
        val text = "MEDIA:/out/chart.png"
        assertTrue(MediaTags.splitImageRefs(text).refs.isEmpty())
        assertTrue(MediaTags.split("@image:/a/1.jpg").refs.isEmpty())
    }
}
