package chat.keryx.core.protocol

import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import chat.keryx.core.model.ToolWire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The gateway's live `tool.start` / `tool.complete` frames as [ToolCall]s (moved out of the
 * Direct transport's event pump in 3.0). A deferred tool arrives as the `tool_call` bridge;
 * [ToolWire.unwrap] draws the call inside it. [fallbackId] names a frame that carries no
 * `tool_id`.
 */
object ToolFrames {
    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull

    private fun unwrap(p: JsonObject?) = ToolWire.unwrap(
        p.str("name") ?: "tool",
        p?.get("args") as? JsonObject,
        p?.get("labels") as? JsonArray,
    )

    fun started(p: JsonObject?, fallbackId: String): ToolCall {
        val call = unwrap(p)
        return ToolCall(
            toolId = p.str("tool_id") ?: fallbackId,
            name = call.name,
            context = p.str("context") ?: ToolText.contextPreview(call.name, call.args),
            argsJson = call.args?.toString() ?: "",
        )
    }

    fun completed(p: JsonObject?, fallbackId: String): ToolCall {
        val call = unwrap(p)
        val result = p?.get("result")
        return ToolCall(
            toolId = p.str("tool_id") ?: fallbackId,
            name = call.name,
            context = ToolText.contextPreview(call.name, call.args),
            argsJson = call.args?.toString() ?: "",
            status = if (ToolText.elementLooksFailed(result)) ToolStatus.FAILED else ToolStatus.COMPLETED,
            result = ToolText.resultElementToDisplay(result),
            summary = p.str("summary") ?: "",
            durationS = p.str("duration_s")?.toDoubleOrNull(),
            inlineDiff = p.str("inline_diff") ?: "",
        )
    }
}
