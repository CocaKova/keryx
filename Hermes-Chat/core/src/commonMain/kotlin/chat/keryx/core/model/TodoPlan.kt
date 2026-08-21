package chat.keryx.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The agent's OWN plan — stock hermes' `todo` tool (`tools/todo_tool.py`): the model
 * decomposes a task into `{id, content, status}` items and re-writes the list as it works.
 * Every tool call returns the FULL current list, so the newest `todo` result — live via
 * `tool.complete`, hydrated from a `role:"tool"` row — is always the whole truth. Keryx
 * pins it as the Flight Plan: the 1-2-3-4 the agent announced, ticking itself off.
 */
data class TodoItem(
    val id: String,
    val content: String,
    /** pending | in_progress | completed | cancelled (the tool validates; we trust). */
    val status: String,
)

data class TodoPlan(val items: List<TodoItem>) {
    val total: Int get() = items.size
    val done: Int get() = items.count { it.status == "completed" || it.status == "cancelled" }
    val active: TodoItem? get() = items.firstOrNull { it.status == "in_progress" }
    val allDone: Boolean get() = items.isNotEmpty() && items.all {
        it.status == "completed" || it.status == "cancelled"
    }
}

object TodoPlanParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The tool's result text → plan. `{"todos":[…]}` per todo_tool.py; anything else →
     *  null (an error string from the tool must not blank a good plan). */
    fun parse(resultText: String): TodoPlan? {
        val root = runCatching { json.parseToJsonElement(resultText).jsonObject }.getOrNull()
            ?: return null
        val rows = runCatching { root["todos"]?.jsonArray }.getOrNull() ?: return null
        val items = rows.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            TodoItem(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                content = o["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                status = o["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
            )
        }
        return TodoPlan(items)
    }

    /** The post-compaction re-injection row (todo_tool.TODO_INJECTION_HEADER) arrives on
     *  the USER role — machinery, never the human's voice. FOURTH instance of the
     *  role:"user"-machinery pattern (compaction 0.6.2, delegation 0.6.9, bot-mode 0.6.11). */
    fun isTodoInjection(content: String): Boolean =
        content.trimStart().startsWith("[Your active task list was preserved across context compression]")
}
