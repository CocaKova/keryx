package chat.keryx.app.transport.direct

import chat.keryx.core.model.Routed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Agent plugins and MCP servers added by hand (3.0). Stock `hermes dashboard` routes, the ones the
 * dashboard's Plugins and MCP pages use: `/api/dashboard/plugins/hub` and `/catalog`, the
 * `/api/dashboard/agent-plugins/…` verbs, `POST /api/mcp/servers` and its OAuth flow.
 */

/** One agent plugin, as the dashboard's plugin hub lists it. */
data class AgentPlugin(
    val name: String,
    val version: String,
    val description: String,
    /** "user", "git", "bundled", "catalog:…", "project". */
    val source: String,
    /** "enabled", "inactive" (installed, not switched on) or "disabled". */
    val status: String,
    /** The user's own install under the plugins folder: it can be removed. */
    val canRemove: Boolean,
    /** A command the plugin wants run once to sign in (`hermes auth …`), when it needs one. */
    val authCommand: String?,
    /** Why the catalog pulled this plugin, when it did. */
    val removedReason: String?,
) {
    val enabled: Boolean get() = status == "enabled"
    /** Ships with Hermes: the long tail of providers and platforms, folded away on screen. */
    val bundled: Boolean get() = source == "bundled"
}

/** One entry of the curated plugin catalog, with whether it is already here. */
data class PluginCatalogEntry(
    val name: String,
    val title: String,
    val description: String,
    val repo: String,
    /** "official", "community", … */
    val tier: String,
    val requiresEnv: List<String>,
    val installed: Boolean,
)

/** How an install answered: [restartRequired] when the gateway must restart to load it. */
data class PluginInstallResult(
    val name: String?,
    val restartRequired: Boolean,
    /** Environment variables the plugin still needs set before it works. */
    val missingEnv: List<String>,
    val warnings: List<String>,
)

/** One MCP sign-in in flight: open [authorizationUrl] in a browser, then poll by [flowId]. */
data class McpAuthFlow(
    val flowId: String,
    /** "starting", "authorization_required", "approved" or "error". */
    val status: String,
    val authorizationUrl: String?,
    val error: String?,
) {
    val done: Boolean get() = status == "approved" || status == "error"
}

object PluginParser {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.flag(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

    fun hub(root: JsonElement): List<AgentPlugin>? {
        val rows = (root as? JsonObject)?.get("plugins") as? JsonArray ?: return null
        return rows.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            AgentPlugin(
                name = name,
                version = o.str("version").orEmpty(),
                description = o.str("description").orEmpty(),
                source = o.str("source") ?: "user",
                status = o.str("runtime_status") ?: "inactive",
                canRemove = o.flag("can_remove"),
                authCommand = o.str("auth_command")?.takeIf { o.flag("auth_required") },
                removedReason = o.str("removed_reason"),
            )
        }
    }

    fun catalog(root: JsonElement): List<PluginCatalogEntry>? {
        val rows = (root as? JsonObject)?.get("entries") as? JsonArray ?: return null
        return rows.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            PluginCatalogEntry(
                name = name,
                title = o.str("title") ?: name,
                description = o.str("description").orEmpty(),
                repo = o.str("repo").orEmpty(),
                tier = o.str("tier") ?: "community",
                requiresEnv = (o["capabilities"] as? JsonObject)?.strings("requires_env").orEmpty(),
                installed = o.flag("installed"),
            )
        }
    }

    fun install(root: JsonElement): PluginInstallResult? {
        val o = root as? JsonObject ?: return null
        return PluginInstallResult(
            name = o.str("plugin_name"),
            restartRequired = o.flag("restart_required"),
            missingEnv = o.strings("missing_env"),
            warnings = o.strings("warnings"),
        )
    }

    fun authFlow(root: JsonElement): McpAuthFlow? {
        val o = root as? JsonObject ?: return null
        return McpAuthFlow(
            flowId = o.str("flow_id") ?: return null,
            status = o.str("status") ?: "starting",
            authorizationUrl = o.str("authorization_url"),
            error = o.str("error"),
        )
    }
}

/** How an MCP server added by URL signs in: the three the dashboard's create route accepts. */
enum class McpAuthStyle(val wire: String) { NONE("none"), TOKEN("header"), OAUTH("oauth") }

private val lenient = Json { ignoreUnknownKeys = true }
private fun parse(body: String): JsonElement? = runCatching { lenient.parseToJsonElement(body) }.getOrNull()
private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

internal suspend fun GatewayRest.pluginsHub(): Routed<List<AgentPlugin>> =
    routed("GET", "/api/dashboard/plugins/hub").map { parse(it)?.let(PluginParser::hub) }

internal suspend fun GatewayRest.pluginCatalog(): Routed<List<PluginCatalogEntry>> =
    routed("GET", "/api/dashboard/plugins/catalog").map { parse(it)?.let(PluginParser::catalog) }

/** Install from the curated catalog by [catalogName] (pinned, reviewed) or from a git [identifier]. */
internal suspend fun GatewayRest.pluginInstall(identifier: String?, catalogName: String?): Routed<PluginInstallResult> =
    routed(
        "POST", "/api/dashboard/agent-plugins/install",
        buildJsonObject {
            put("identifier", JsonPrimitive(identifier.orEmpty()))
            if (!catalogName.isNullOrBlank()) put("catalog_name", JsonPrimitive(catalogName))
            put("enable", JsonPrimitive(true))
        }.toString(),
    ).map { parse(it)?.let(PluginParser::install) }

internal suspend fun GatewayRest.pluginSetEnabled(name: String, enabled: Boolean): Routed<Unit> =
    routed("POST", "/api/dashboard/agent-plugins/" + enc(name) + if (enabled) "/enable" else "/disable", "{}").map { }

internal suspend fun GatewayRest.pluginRemove(name: String): Routed<Unit> =
    routed("DELETE", "/api/dashboard/agent-plugins/" + enc(name)).map { }

/**
 * `POST /api/mcp/servers` with a URL: [token] only for [McpAuthStyle.TOKEN], where the gateway keeps
 * it in the profile's .env and sends it as `Authorization: Bearer`. [McpAuthStyle.OAUTH] saves the
 * server; the sign-in is [mcpAuthStart] after.
 */
internal suspend fun GatewayRest.mcpAdd(name: String, url: String, auth: McpAuthStyle, token: String?): Routed<Unit> =
    routed(
        "POST", "/api/mcp/servers",
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("url", JsonPrimitive(url))
            put("auth", JsonPrimitive(auth.wire))
            if (auth == McpAuthStyle.TOKEN && !token.isNullOrBlank()) put("bearer_token", JsonPrimitive(token))
        }.toString(),
    ).map { }

internal suspend fun GatewayRest.mcpRemove(name: String): Routed<Unit> =
    routed("DELETE", "/api/mcp/servers/" + enc(name)).map { }

/** Start an MCP server's OAuth sign-in; the answer carries the page to open. */
internal suspend fun GatewayRest.mcpAuthStart(name: String): Routed<McpAuthFlow> =
    routed("POST", "/api/mcp/servers/" + enc(name) + "/auth", "{}").map { parse(it)?.let(PluginParser::authFlow) }

internal suspend fun GatewayRest.mcpAuthFlow(flowId: String): Routed<McpAuthFlow> =
    routed("GET", "/api/mcp/oauth/flows/" + enc(flowId)).map { parse(it)?.let(PluginParser::authFlow) }
