package chat.keryx.app

import chat.keryx.app.domain.model.MediaKind
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drawer's last-message previews: dialogue prose only, chrome stripped, honest stand-ins. */
class RoomPreviewTest {

    private fun msg(
        content: String,
        sender: SenderType = SenderType.HERMES,
        mediaKind: MediaKind? = null,
        fileName: String = "",
    ) = Message(
        id = "\$e", sessionId = "!r", sender = sender, content = content,
        timestamp = 1L, mediaKind = mediaKind, fileName = fileName,
    )

    @Test
    fun plainAgentProse_isUsedVerbatim() {
        assertEquals("Deploy finished cleanly.", ChatViewModel.previewOf(msg("Deploy finished cleanly.")))
    }

    @Test
    fun ownMessage_getsYouPrefix() {
        assertEquals("You: check the logs", ChatViewModel.previewOf(msg("check the logs", sender = SenderType.ME)))
    }

    @Test
    fun reasoningBlock_isStrippedToTheAnswer() {
        val body = "💭 **Reasoning:**\n```\nthinking about it\n```\n\nAll three backups verified."
        assertEquals("All three backups verified.", ChatViewModel.previewOf(msg(body)))
    }

    @Test
    fun multilineProse_collapsesToOneLine() {
        val p = ChatViewModel.previewOf(msg("First line.\n\nSecond line."))
        assertEquals("First line. Second line.", p)
    }

    @Test
    fun imageMessage_showsPhotoStandIn() {
        assertEquals("🖼 Photo", ChatViewModel.previewOf(msg("", mediaKind = MediaKind.IMAGE)))
    }

    @Test
    fun fileMessage_showsNamedAttachment() {
        assertEquals("📎 notes.pdf", ChatViewModel.previewOf(msg("", mediaKind = MediaKind.FILE, fileName = "notes.pdf")))
    }

    @Test
    fun longProse_isCapped() {
        val p = ChatViewModel.previewOf(msg("x".repeat(500)))
        assertTrue(p.length <= 140)
    }
}
