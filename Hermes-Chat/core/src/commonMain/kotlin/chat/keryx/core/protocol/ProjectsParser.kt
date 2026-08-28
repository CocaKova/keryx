package chat.keryx.core.protocol

import chat.keryx.core.model.ProjectFolder
import chat.keryx.core.model.ProjectInfo
import chat.keryx.core.model.ProjectLane
import chat.keryx.core.model.ProjectRepo
import chat.keryx.core.model.ProjectTreeNode
import chat.keryx.core.model.ProjectsCatalog
import chat.keryx.core.model.ProjectsTree
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.RoomProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `projects.list` / `projects.tree` / `projects.project_sessions` payloads → domain. Pure;
 * fixture-tested against captured live responses (projects_*_live.json — the wire truth, not
 * hand-written ideals). The wire speaks epoch SECONDS — everything leaves here in MILLIS.
 * A malformed node is dropped, never a crash: the drawer must render even if one row is strange.
 */
object ProjectsParser {

    /** `projects.list` (also the shape `projects.archive`/`projects.delete` answer with). */
    fun parseCatalog(res: JsonElement): ProjectsCatalog? {
        val o = res as? JsonObject ?: return null
        val projects = (o["projects"] as? JsonArray)?.mapNotNull { parseProject(it) } ?: return null
        return ProjectsCatalog(projects = projects, activeId = o.str("active_id"))
    }

    /** One `Project.to_dict()` shape (`projects.get`/`create`/`update` answer `{project: …}`). */
    fun parseProject(el: JsonElement?): ProjectInfo? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        return ProjectInfo(
            id = id,
            slug = o.str("slug") ?: "",
            name = o.str("name") ?: id,
            description = o.str("description"),
            icon = o.str("icon"),
            color = o.str("color"),
            boardSlug = o.str("board_slug"),
            primaryPath = o.str("primary_path"),
            archived = o.bool("archived") ?: false,
            createdAtMs = o.epochMs("created_at") ?: 0L,
            folders = (o["folders"] as? JsonArray)?.mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                ProjectFolder(
                    path = fo.str("path") ?: return@mapNotNull null,
                    label = fo.str("label"),
                    isPrimary = fo.bool("is_primary") ?: false,
                )
            } ?: emptyList(),
        )
    }

    /** `projects.tree` overview and `projects.project_sessions` (whose payload is one node
     *  under `project`, same shape but with hydrated lane sessions). */
    fun parseTree(res: JsonElement): ProjectsTree? {
        val o = res as? JsonObject ?: return null
        val projects = (o["projects"] as? JsonArray)?.mapNotNull { parseTreeNode(it) } ?: return null
        return ProjectsTree(
            projects = projects,
            activeId = o.str("active_id"),
            scopedSessionIds = (o["scoped_session_ids"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),
        )
    }

    fun parseTreeNode(el: JsonElement?): ProjectTreeNode? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        return ProjectTreeNode(
            id = id,
            label = o.str("label") ?: id,
            path = o.str("path"),
            color = o.str("color"),
            icon = o.str("icon"),
            isAuto = o.bool("isAuto") ?: false,
            isNoProject = o.bool("isNoProject") ?: false,
            sessionCount = o.long("sessionCount") ?: 0L,
            lastActiveMs = o.epochMs("lastActive") ?: 0L,
            totalTokens = o.long("totalTokens") ?: 0L,
            repos = (o["repos"] as? JsonArray)?.mapNotNull { repoEl ->
                val r = repoEl as? JsonObject ?: return@mapNotNull null
                ProjectRepo(
                    id = r.str("id") ?: return@mapNotNull null,
                    label = r.str("label") ?: "",
                    path = r.str("path"),
                    sessionCount = r.long("sessionCount") ?: 0L,
                    lanes = (r["groups"] as? JsonArray)?.mapNotNull { laneEl ->
                        val g = laneEl as? JsonObject ?: return@mapNotNull null
                        ProjectLane(
                            id = g.str("id") ?: return@mapNotNull null,
                            label = g.str("label") ?: "",
                            isMain = g.bool("isMain") ?: false,
                            isKanban = g.bool("isKanban") ?: false,
                            sessions = (g["sessions"] as? JsonArray)
                                ?.mapNotNull { sessionSummary(it) } ?: emptyList(),
                        )
                    } ?: emptyList(),
                )
            } ?: emptyList(),
            previewSessions = (o["previewSessions"] as? JsonArray)
                ?.mapNotNull { sessionSummary(it) } ?: emptyList(),
        )
    }

    /** Session dicts inside tree payloads are the sessions-list projection — same fields the
     *  REST rows carry, so they map onto the drawer's own [RoomProfile]. */
    fun sessionSummary(el: JsonElement?): RoomProfile? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: ""
        val preview = o.str("preview") ?: ""
        return RoomProfile(
            id = id,
            name = title.ifBlank { preview.ifBlank { id } },
            type = RoomType.DIRECT_MESSAGE,
            timestamp = o.epochMs("last_active") ?: 0L,
            messageCount = o.long("message_count") ?: 0L,
            source = o.str("source") ?: "",
            isActive = o.bool("is_active") ?: false,
            preview = preview,
        )
    }

    // -- defensive extraction (same discipline as KanbanParser) ---------------------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.takeIf { it != "null" }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonElement)?.let {
            runCatching { it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.doubleOrNull?.toLong() }.getOrNull()
        }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonElement)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    /** Epoch seconds on the wire (sometimes fractional) → millis, null when absent/zero. */
    private fun JsonObject.epochMs(key: String): Long? {
        val raw = (this[key] as? JsonElement)
            ?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return null
        if (raw <= 0.0) return null
        return (raw * 1000.0).toLong()
    }
}
