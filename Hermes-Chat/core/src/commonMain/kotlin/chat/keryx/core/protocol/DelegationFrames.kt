package chat.keryx.core.protocol

import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationBeat
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.Theater
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The gateway's `subagent.*` frames folded into a [Delegation] (moved out of the Direct
 * transport's event pump in 3.0). Every frame carries the same identity block and adds what
 * only it knows, so identity folds in once and the type decides state + activity line.
 */
object DelegationFrames {
    val TYPES = setOf(
        "subagent.spawn_requested", "subagent.start", "subagent.thinking",
        "subagent.tool", "subagent.progress", "subagent.complete",
    )

    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.int(k: String) = str(k)?.toDoubleOrNull()?.toInt()
    private fun JsonObject?.list(k: String) = (this?.get(k) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** The child session this frame is about, when it names one. */
    fun childSession(p: JsonObject?): String? = p.str("child_session_id")?.takeIf { it.isNotBlank() }

    /** `subagent_id` is optional on the wire (older emitters omit it); the task index is the
     *  stable fallback within one dispatch. */
    fun key(p: JsonObject?): String = p.str("subagent_id")?.takeIf { it.isNotBlank() }
        ?: "${Delegation.FALLBACK_PREFIX}${p.str("task_index") ?: "0"}"

    fun fold(prev: Delegation, type: String, p: JsonObject?, nowMs: Long): Delegation {
        val text = p.str("text").orEmpty().trim()
        val withIdentity = prev.copy(
            goal = p.str("goal")?.takeIf { it.isNotBlank() } ?: prev.goal,
            taskIndex = p.int("task_index") ?: prev.taskIndex,
            taskCount = p.int("task_count") ?: prev.taskCount,
            model = p.str("model")?.takeIf { it.isNotBlank() } ?: prev.model,
            sessionId = childSession(p) ?: prev.sessionId,
            depth = p.int("depth") ?: prev.depth,
            toolCount = p.int("tool_count") ?: prev.toolCount,
            // The first frame about a child is when this client learned it exists.
            // Stamped once, never moved — see Delegation.elapsedSeconds.
            startedAtMs = if (prev.startedAtMs == 0L) nowMs else prev.startedAtMs,
        )
        return when (type) {
            "subagent.spawn_requested" -> withIdentity.copy(state = DelegationState.SPAWNING)
            "subagent.start" -> withIdentity.copy(state = DelegationState.RUNNING, activity = "")
            // The child's own tool: name it, with its preview as the object.
            // Kept twice — once as the live line, once in the record that
            // outlives the flight (Theater.trailWith bounds both doors alike).
            "subagent.tool" -> {
                val beat = DelegationBeat(
                    kind = "tool",
                    name = p.str("tool_name")?.takeIf { it.isNotBlank() }.orEmpty(),
                    text = (p.str("tool_preview") ?: text).takeIf { it.isNotBlank() }.orEmpty(),
                )
                withIdentity.copy(
                    state = DelegationState.RUNNING,
                    activity = beat.line,
                    trail = Theater.trailWith(withIdentity.trail, beat),
                )
            }
            // A progress note is something the child chose to say about its work,
            // so it is kept; a thinking fragment is texture and is not.
            "subagent.progress" -> withIdentity.copy(
                state = DelegationState.RUNNING,
                activity = text.ifBlank { prev.activity },
                trail = Theater.trailWith(withIdentity.trail, DelegationBeat("progress", text = text)),
            )
            "subagent.thinking" -> withIdentity.copy(
                state = DelegationState.RUNNING,
                activity = text.ifBlank { prev.activity },
            )
            else -> withIdentity.copy(
                state = DelegationState.fromWire(p.str("status")),
                activity = "",
                summary = p.str("summary")?.takeIf { it.isNotBlank() } ?: text,
                durationSeconds = p.str("duration_seconds")?.toDoubleOrNull() ?: prev.durationSeconds,
                inputTokens = p.int("input_tokens") ?: prev.inputTokens,
                outputTokens = p.int("output_tokens") ?: prev.outputTokens,
                reasoningTokens = p.int("reasoning_tokens") ?: prev.reasoningTokens,
                apiCalls = p.int("api_calls") ?: prev.apiCalls,
                filesRead = p.list("files_read") ?: prev.filesRead,
                filesWritten = p.list("files_written") ?: prev.filesWritten,
            )
        }
    }
}
