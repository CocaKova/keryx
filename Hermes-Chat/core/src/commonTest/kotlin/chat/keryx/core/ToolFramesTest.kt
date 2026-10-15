package chat.keryx.core

import chat.keryx.core.model.ToolStatus
import chat.keryx.core.protocol.ToolFrames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Live tool frames as the transcript draws them. */
class ToolFramesTest {

    @Test
    fun a_start_keeps_the_gateway_context_and_falls_back_to_the_given_id() {
        val call = ToolFrames.started(buildJsonObject {
            put("name", "terminal")
            put("context", "uptime")
            put("args", buildJsonObject { put("command", "uptime") })
        }, "tool-1")
        assertEquals("tool-1", call.toolId)
        assertEquals("terminal", call.name)
        assertEquals("uptime", call.context)
        assertEquals(ToolStatus.EXECUTING, call.status)
    }

    @Test
    fun a_completion_reads_failure_from_the_result_object() {
        val failed = ToolFrames.completed(buildJsonObject {
            put("tool_id", "t9")
            put("name", "read_file")
            put("result", buildJsonObject { put("success", false); put("error", "no such file") })
            put("duration_s", "0.25")
        }, "unused")
        assertEquals("t9", failed.toolId)
        assertEquals(ToolStatus.FAILED, failed.status)
        assertEquals(0.25, failed.durationS)
        val ok = ToolFrames.completed(buildJsonObject {
            put("name", "read_file"); put("result", JsonPrimitive("hello"))
        }, "t10")
        assertEquals(ToolStatus.COMPLETED, ok.status)
        assertEquals("hello", ok.result)
        assertEquals("", ToolFrames.completed(JsonObject(emptyMap()), "t11").result)
    }
}
