package chat.keryx.core

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import chat.keryx.core.model.WorkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The direct door's overlay ends in a streaming placeholder that wears the whole turn's
 * thought; the label must still name the tool that is running (09-05: "just says reasoning").
 */
class WorkStateTest {
    private fun msg(id: String, sender: SenderType = SenderType.HERMES, content: String = "",
                    tools: List<ToolCall> = emptyList(), streaming: Boolean = false, room: String = "r") =
        Message(id = id, roomId = room, sender = sender, content = content, timestamp = 1L,
            toolCalls = tools, isStreaming = streaming,
            reasoning = if (streaming) "thinking hard" else null)

    private val running = ToolCall(name = "terminal", context = "docker ps", status = ToolStatus.EXECUTING)
    private val done = ToolCall(name = "read_file", context = "a.txt", status = ToolStatus.COMPLETED)

    @Test
    fun `a running tool wins over the placeholder's old thought`() {
        val live = msg("live", streaming = true)
        val list = listOf(msg("u1", SenderType.ME, "restart them"), msg("s0", content = "On it."),
            msg("s1", tools = listOf(done)), msg("s2", tools = listOf(running)), live)
        assertEquals("s2", WorkState.runningTool(list, live)?.id)
    }

    @Test
    fun `once the newest tool returned the thought is current again`() {
        val live = msg("live", streaming = true)
        val list = listOf(msg("u1", SenderType.ME, "restart them"),
            msg("s1", tools = listOf(running)), msg("s2", tools = listOf(done)), live)
        assertNull(WorkState.runningTool(list, live))
    }

    @Test
    fun `an earlier turn's tool is not this turn's work`() {
        val live = msg("live", streaming = true)
        val list = listOf(msg("old", tools = listOf(running)), msg("u1", SenderType.ME, "and now?"), live)
        assertNull(WorkState.runningTool(list, live))
    }

    @Test
    fun `concurrent calls - the newest running one names the work`() {
        val live = msg("live", streaming = true)
        val list = listOf(msg("u1", SenderType.ME, "go"), msg("s1", tools = listOf(running, done)), live)
        assertEquals("s1", WorkState.runningTool(list, live)?.id)
    }
}
