package chat.keryx.core

import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.MediaTags
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A photo turn the model saw as pixels, as the transcript keeps it: the caption, an
 * `[Image attached at: <path>]` line per photo, a `[screenshot]` per image part, then the
 * context the turn carried for the model (recalled memory, plugin context). Reloaded, all of
 * it showed in the user's bubble, the memory block reading like a system prompt (device,
 * 2026-10-25).
 */
class MediaTagsUserRowTest {

    private val memory = "<memory-context>\n" +
        "[System note: The following is recalled memory context, NOT new user input.]\n\n" +
        "## Memory\n- the user lives somewhere\n" +
        "</memory-context>"
    private val pluginContext = "<context-hints>\nThings that look relevant:\n- one\n- two\n</context-hints>"

    private fun stored(caption: String, vararg paths: String, tail: String = "$memory\n\n$pluginContext") =
        caption + "\n\n" + paths.joinToString("\n") { "[Image attached at: $it]" } + "\n" +
            paths.joinToString("\n") { "[screenshot]" } + "\n" + tail

    @Test
    fun nativeVisionRow_isTheCaptionAndThePhotos() {
        val s = MediaTags.splitUserRow(stored("what do you make of these", "/home/me/.hermes/images/upload_a.jpg", "/home/me/.hermes/images/upload_b.jpg"))
        assertEquals("what do you make of these", s.text)
        assertEquals(listOf("/home/me/.hermes/images/upload_a.jpg", "/home/me/.hermes/images/upload_b.jpg"), s.refs.map { it.path })
        assertTrue(s.refs.all { it.kind == MediaKind.IMAGE })
    }

    @Test
    fun memoryOnly_noPluginContext() {
        val s = MediaTags.splitUserRow(stored("this one", "/a/1.png", tail = memory))
        assertEquals("this one", s.text)
        assertEquals(listOf("/a/1.png"), s.refs.map { it.path })
    }

    @Test
    fun contextGluedOntoTheLastStandIn_isStillLeftOut() {
        // A part list read with inline_images=false joins the parts bare: "[image]<memory-context>…".
        val s = MediaTags.splitUserRow("look\n\n[Image attached at: /a/1.jpg]\n[image]$memory")
        assertEquals("look", s.text)
        assertEquals(listOf("/a/1.jpg"), s.refs.map { it.path })
    }

    @Test
    fun transportStandIns_andDetail_areDropped() {
        assertEquals("two", MediaTags.splitUserRow("two\n\n[Image attached at: /a/1.jpg]\n[Image attached at: /a/2.jpg]\n🖼 image\n🖼 image").text)
        val s = MediaTags.splitUserRow("Caption\n@image:`/a/shot 1.png`\n[screenshot detail=high]")
        assertEquals("Caption", s.text)
        assertEquals("/a/shot 1.png", s.refs.single().path)
    }

    @Test
    fun linkedImage_isARefToo() {
        val s = MediaTags.splitUserRow("see\n\n[Image attached: https://example.com/cat.png]\n[screenshot]")
        assertEquals("see", s.text)
        assertEquals("https://example.com/cat.png", s.refs.single().path)
    }

    @Test
    fun unfencedTextAfterTheStandIns_staysVisible() {
        val s = MediaTags.splitUserRow(stored("hi", "/a/1.jpg", tail = "$memory\n\n[System: the model was switched]"))
        assertEquals("hi\n\n[System: the model was switched]", s.text)
    }

    @Test
    fun noPhoto_keepsItsStandIns() {
        val text = "Caption\n[screenshot]"
        assertEquals(text, MediaTags.splitUserRow(text).text)
    }

    @Test
    fun memoryBlockWithoutAPhoto_isRemoved() {
        val s = MediaTags.splitUserRow("hello\n\n$memory")
        assertEquals("hello", s.text)
        assertTrue(s.refs.isEmpty())
    }

    @Test
    fun theConventionInProse_staysProse() {
        for (text in listOf("[Image attached at: <path>]", "what is [Image attached at: /a/1.jpg] doing in my chat")) {
            val s = MediaTags.splitUserRow(text)
            assertEquals(text, s.text)
            assertTrue(s.refs.isEmpty())
        }
    }

    @Test
    fun expand_liftsThePhotos_andKeepsTheUserSide() {
        val row = Message(id = "42", roomId = "r", sender = SenderType.ME, content = stored("these", "/a/1.jpg", "/a/2.jpg"), timestamp = 1L)
        val seen = mutableMapOf<String, String>()
        val out = MediaTags.expand(listOf(row)) { id, path -> seen[id] = path }
        assertEquals(listOf("42", "42#media:0", "42#media:1"), out.map { it.id })
        assertEquals("these", out[0].content)
        assertTrue(out.all { it.sender == SenderType.ME })
        assertEquals(mapOf("42#media:0" to "/a/1.jpg", "42#media:1" to "/a/2.jpg"), seen)
    }

    @Test
    fun expand_cleansAMemoryBlock_andLeavesOtherRowsAlone() {
        val mine = Message(id = "1", roomId = "r", sender = SenderType.ME, content = "hello\n\n$memory", timestamp = 1L)
        val plain = Message(id = "2", roomId = "r", sender = SenderType.ME, content = "just text", timestamp = 2L)
        val out = MediaTags.expand(listOf(mine, plain))
        assertEquals("hello", out[0].content)
        assertSame(plain, out[1])
    }
}
