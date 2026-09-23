package chat.keryx.app

import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Retry re-sends the undone exchange's own prompt, or offers nothing. */
class RetryPromptTest {

    private fun msg(id: String, content: String, sender: SenderType = SenderType.ME, mediaKind: MediaKind? = null) =
        Message(id = id, roomId = "s", sender = sender, content = content, timestamp = 0L, mediaKind = mediaKind)

    @Test
    fun `the newest text prompt is what retry sends`() {
        val msgs = listOf(msg("1", "old question"), msg("2", "answer", SenderType.HERMES), msg("3", "new question"), msg("4", "reply", SenderType.HERMES))
        assertEquals("new question", ChatViewModel.retryPromptOf(msgs))
    }

    @Test
    fun `an uncaptioned image never lets an older prompt stand in for it`() {
        val msgs = listOf(msg("1", "old question"), msg("2", "answer", SenderType.HERMES), msg("3", "", mediaKind = MediaKind.IMAGE), msg("4", "reply", SenderType.HERMES))
        assertNull(ChatViewModel.retryPromptOf(msgs))
    }

    @Test
    fun `a captioned attachment is not re-sent as bare text`() {
        val msgs = listOf(msg("1", "what is this?", mediaKind = MediaKind.FILE), msg("2", "reply", SenderType.HERMES))
        assertNull(ChatViewModel.retryPromptOf(msgs))
    }
}
