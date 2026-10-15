package chat.keryx.app.transport.direct

import chat.keryx.core.model.FolderPage
import chat.keryx.core.model.ProjectInfo
import chat.keryx.core.model.ProjectTreeNode
import chat.keryx.core.model.ProjectsCatalog
import chat.keryx.core.model.ProjectsTree
import chat.keryx.core.protocol.FolderCompletionParser
import chat.keryx.core.protocol.ProjectsParser
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Projects and folders over the gateway's `projects.*` and `complete.path` (split out of
 * [DirectTransport] in 3.0). [onMoved] re-reads the session list after a session changes project.
 */
internal class ProjectOps(
    private val gw: GatewayCalls,
    private val onMoved: suspend () -> Unit,
) {
    suspend fun tree(): Result<ProjectsTree> = runCatching {
        ProjectsParser.parseTree(
            gw.request("projects.tree", buildJsonObject { put("preview_limit", JsonPrimitive(3)) })
        ) ?: error("unrecognized projects.tree payload")
    }

    suspend fun sessions(projectId: String): Result<ProjectTreeNode?> = runCatching {
        val res = gw.request("projects.project_sessions", buildJsonObject {
            put("project_id", JsonPrimitive(projectId))
        })
        ProjectsParser.parseTreeNode(res["project"])
    }

    suspend fun catalog(): Result<ProjectsCatalog> = runCatching {
        ProjectsParser.parseCatalog(gw.request("projects.list"))
            ?: error("unrecognized projects.list payload")
    }

    suspend fun create(name: String, folderPath: String?): Result<ProjectInfo> = runCatching {
        val res = gw.request("projects.create", buildJsonObject {
            put("name", JsonPrimitive(name))
            if (!folderPath.isNullOrBlank()) {
                put("primary_path", JsonPrimitive(folderPath))
                put("folders", buildJsonArray { add(JsonPrimitive(folderPath)) })
            }
        })
        ProjectsParser.parseProject(res["project"]) ?: error("create returned no project")
    }

    suspend fun delete(projectId: String): Result<Unit> = runCatching {
        gw.request("projects.delete", buildJsonObject { put("id", JsonPrimitive(projectId)) })
        Unit
    }

    suspend fun archive(projectId: String, restore: Boolean): Result<Unit> = runCatching {
        gw.request("projects.archive", buildJsonObject {
            put("id", JsonPrimitive(projectId))
            if (restore) put("restore", JsonPrimitive(true))
        })
        Unit
    }

    // `complete.path` in `@folder:` mode is the only stock directories-only listing. Its
    // `text` is rebased on the gateway's completion cwd — only `display` is a usable name,
    // so the caller keeps the prefix. Hard 30-item cap, unannounced (FolderPage.truncated).
    suspend fun listFolders(query: String): Result<FolderPage> = runCatching {
        FolderCompletionParser.parse(
            gw.request("complete.path", buildJsonObject { put("word", JsonPrimitive("@folder:$query")) })
        )
    }

    suspend fun folderExists(path: String): Result<Boolean> = runCatching {
        val clean = path.trim().trimEnd('/')
        if (clean.isBlank()) return@runCatching false
        // Ask the PARENT for a child named exactly this — a listing of the folder itself
        // can't tell "empty" from "absent".
        val name = clean.substringAfterLast('/')
        listFolders(clean).getOrThrow().names.any { it == name }
    }

    suspend fun moveSession(sessionId: String, cwd: String): Result<Unit> = runCatching {
        // Keys on the STORED id (session_key) — no live agent needed.
        gw.request("session.workspace.move", buildJsonObject {
            put("session_key", JsonPrimitive(sessionId))
            put("cwd", JsonPrimitive(cwd))
        })
        onMoved()
    }
}
