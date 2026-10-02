package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient.KanbanTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Finding a card on a board that has grown past a screen (2.16): a search field and an
 * assignee filter. Pure — the board's lane math (`missionSections`) runs on what this lets
 * through, so the lane chips and their counts describe the cards you can see.
 */
object MissionFilter {
    /** The assignee filter's key for cards nobody owns yet. */
    const val UNASSIGNED = "\u0000unassigned"

    /**
     * Whether [task] passes. Every whitespace-separated term of [query] must appear somewhere in
     * what the card says — title, id, assignee, brief excerpt, its ask, its last handoff —
     * case-insensitively; a blank query passes everything. [assignee] null = anyone.
     */
    fun matches(task: KanbanTask, query: String, assignee: String?): Boolean {
        if (assignee != null) {
            val owner = task.assignee.trim()
            if (assignee == UNASSIGNED) { if (owner.isNotEmpty()) return false }
            else if (!owner.equals(assignee, ignoreCase = true)) return false
        }
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val hay = listOf(task.title, task.id, task.assignee, task.bodyExcerpt, task.ask, task.latestSummary)
            .joinToString("\n").lowercase()
        return terms.all { it.removePrefix("@") in hay }
    }

    /** The board with only passing cards; lanes left empty are dropped. */
    fun apply(tasks: Map<String, List<KanbanTask>>, query: String, assignee: String?): Map<String, List<KanbanTask>> {
        if (query.isBlank() && assignee == null) return tasks
        return tasks.mapValues { (_, cards) -> cards.filter { matches(it, query, assignee) } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Who owns cards on the board, with how many: most cards first, then by name; nobody
     * ([UNASSIGNED]) last. A card listed under two lanes counts once.
     */
    fun assignees(tasks: Map<String, List<KanbanTask>>): List<Pair<String, Int>> {
        val cards = tasks.values.flatten().distinctBy { it.id }
        val named = cards.filter { it.assignee.isNotBlank() }
            .groupingBy { it.assignee.trim() }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key to it.value }
        val nobody = cards.count { it.assignee.isBlank() }
        return if (nobody > 0) named + (UNASSIGNED to nobody) else named
    }
}

/**
 * Editing a card after it was made (2.16) — the words and the priority go through the
 * dashboard's kanban PATCH (`/api/plugins/kanban/tasks/{id}`), the owner through the plugin's
 * reassign move (it stops a running worker first, which a bare PATCH refuses).
 */
object MissionEditForm {
    /** Priority steps the picker offers; a card already above them keeps its own number. */
    val PRIORITIES = listOf(0, 1, 2, 3)

    fun priorityLabel(p: Int): String = if (p <= 0) "Normal" else "P$p"

    /** The PATCH body's fields: only what changed. A blank title is never sent. */
    fun changes(task: KanbanTask, title: String, body: String, priority: Int): Map<String, Any> = buildMap {
        val t = title.trim()
        if (t.isNotEmpty() && t != task.title.trim()) put("title", t)
        if (body.trim() != task.body.trim()) put("body", body.trim())
        if (priority != task.priority) put("priority", priority)
    }

    fun problem(title: String): String? = if (title.isBlank()) "A mission needs a title" else null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `GET /api/plugins/kanban/assignees` → profile names (`{"assignees":[{"name", "on_disk",
     * "counts"}]}`). Null = not that shape: no dashboard kanban here, so no editor.
     */
    fun parseAssignees(body: String): List<String>? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val arr = o["assignees"] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            when (el) {
                is JsonObject -> (el["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> el.contentOrNull
                else -> null
            }?.trim()?.ifEmpty { null }
        }
    }
}
