package chat.keryx.core

import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationState
import chat.keryx.core.protocol.DelegationFrames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `subagent.*` frames as the wing and the sheet see them. */
class DelegationFramesTest {
    private fun frame(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) ->
        k to when (v) {
            is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test
    fun the_key_is_the_gateway_id_else_the_task_index() {
        assertEquals("sa-1", DelegationFrames.key(frame("subagent_id" to "sa-1", "task_index" to 2)))
        assertEquals("${Delegation.FALLBACK_PREFIX}2", DelegationFrames.key(frame("task_index" to 2)))
        assertEquals("${Delegation.FALLBACK_PREFIX}0", DelegationFrames.key(null))
    }

    @Test
    fun identity_folds_in_and_the_first_sighting_is_stamped_once() {
        val spawned = DelegationFrames.fold(
            Delegation(key = "sa-1"), "subagent.spawn_requested",
            frame("goal" to "read the repo", "task_index" to 0, "task_count" to 3, "child_session_id" to "c1"), 1_000,
        )
        assertEquals(DelegationState.SPAWNING, spawned.state)
        assertEquals("read the repo", spawned.goal)
        assertEquals(3, spawned.taskCount)
        assertEquals("c1", spawned.sessionId)
        assertEquals(1_000, spawned.startedAtMs)
        val started = DelegationFrames.fold(spawned, "subagent.start", frame("goal" to ""), 9_000)
        assertEquals(DelegationState.RUNNING, started.state)
        assertEquals("read the repo", started.goal) // a blank field never wipes a known one
        assertEquals(1_000, started.startedAtMs)
    }

    @Test
    fun tools_and_progress_are_kept_in_the_trail_but_thinking_is_not() {
        var d = Delegation(key = "sa-1")
        d = DelegationFrames.fold(d, "subagent.tool", frame("tool_name" to "read_file", "tool_preview" to "README.md"), 1)
        assertEquals("read_file README.md", d.activity)
        d = DelegationFrames.fold(d, "subagent.thinking", frame("text" to "hmm"), 2)
        assertEquals("hmm", d.activity)
        d = DelegationFrames.fold(d, "subagent.progress", frame("text" to "half done"), 3)
        assertEquals(listOf("read_file README.md", "half done"), d.trail.map { it.line })
    }

    @Test
    fun completion_reads_the_status_and_the_report() {
        val d = DelegationFrames.fold(
            Delegation(key = "sa-1", activity = "busy"), "subagent.complete",
            frame("status" to "timeout", "summary" to "", "text" to "ran out of time", "api_calls" to 4,
                "files_read" to listOf("a.kt")),
            5,
        )
        assertEquals(DelegationState.FAILED, d.state)
        assertEquals("", d.activity)
        assertEquals("ran out of time", d.summary)
        assertEquals(4, d.apiCalls)
        assertEquals(listOf("a.kt"), d.filesRead)
        assertNull(DelegationFrames.childSession(frame("child_session_id" to "")))
    }
}
