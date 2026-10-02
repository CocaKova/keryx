package chat.keryx.app

import chat.keryx.app.presentation.LiveStream
import chat.keryx.app.presentation.LiveStreamStatus
import chat.keryx.app.presentation.ui.components.LiveTurnProse
import chat.keryx.app.transport.direct.LiveTurnIds
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What TalkBack is handed to read while a turn streams (2.16): one key per turn and one text
 * that only grows, from either door.
 */
class LiveTurnProseTest {

    private fun msg(id: String, content: String, streaming: Boolean = false, tools: List<ToolCall> = emptyList()) =
        Message(
            id = id, roomId = "r", sender = SenderType.HERMES, content = content, timestamp = 0L,
            isStreaming = streaming, toolCalls = tools,
        )

    @Test
    fun `the direct door's sealed steps and streaming answer read as one text`() {
        val turn = 1700L
        val messages = listOf(
            Message(id = "1", roomId = "r", sender = SenderType.ME, content = "check the build", timestamp = 0L),
            msg(LiveTurnIds.item(turn, 0), "Looking at the log first."),
            msg(LiveTurnIds.item(turn, 1), "", tools = listOf(ToolCall(name = "terminal"))),
            msg(LiveTurnIds.answer(turn), "It passed.", streaming = true),
        )
        val t = LiveTurnProse.of(null, messages)!!
        assertEquals("live-$turn", t.key)
        assertTrue(t.streaming)
        assertEquals("Looking at the log first.\n\nIt passed.", t.text)
    }

    @Test
    fun `an earlier turn's folded rows stay out of it`() {
        val messages = listOf(
            msg(LiveTurnIds.answer(1L), "Old answer."),
            msg(LiveTurnIds.answer(2L), "New", streaming = true),
        )
        assertEquals("New", LiveTurnProse.of(null, messages)!!.text)
    }

    @Test
    fun `nothing streaming is no turn`() {
        assertNull(LiveTurnProse.of(null, listOf(msg("1", "settled"))))
    }

    @Test
    fun `the side-channel overlay is its own turn and says when it stopped`() {
        val live = LiveStream("r", "Partial answer.", LiveStreamStatus.STREAMING, startedAt = 42L)
        val t = LiveTurnProse.of(live, emptyList())!!
        assertEquals("side-42", t.key)
        assertTrue(t.streaming)
        assertFalse(LiveTurnProse.of(live.copy(status = LiveStreamStatus.AWAITING_SYNC), emptyList())!!.streaming)
    }

    @Test
    fun `a live row names its turn, any other row names none`() {
        assertEquals("live-9", LiveTurnIds.turnOf(LiveTurnIds.item(9L, 3)))
        assertEquals("live-9", LiveTurnIds.turnOf(LiveTurnIds.answer(9L)))
        assertEquals("live-9", LiveTurnIds.turnOf(LiveTurnIds.thought(9L)))
        assertNull(LiveTurnIds.turnOf("12345"))
        assertNull(LiveTurnIds.turnOf("local-sys-1"))
    }
}
