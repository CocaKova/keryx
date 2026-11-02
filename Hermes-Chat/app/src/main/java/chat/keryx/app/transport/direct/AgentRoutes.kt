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
import kotlinx.serialization.json.intOrNull

/**
 * The dashboard routes behind the agent page (makeover): the MCP servers the agent can reach
 * (and the catalog of ones it could), and the SOUL.md it wakes up with. Stock `hermes dashboard` routes, the same ones the Desktop
 * and Hermes Mobile read — nothing here needs keryx-stream.
 */

/** One configured MCP server, as `GET /api/mcp/servers` summarises it (secrets already redacted). */
data class McpServer(
    val name: String,
    /** "http", "stdio" or "unknown". */
    val transport: String,
    val url: String?,
    val command: String?,
    val args: List<String>,
    val envCount: Int,
    /** "oauth", "bearer", "header", … or null for none. */
    val auth: String?,
    val enabled: Boolean,
    /** The plugin that provides it, when it isn't the user's own config — such a row can't be toggled. */
    val plugin: String?,
) {
    /** What it runs or where it lives, for the row's second line. */
    val target: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
}

/** One credential a catalog entry asks for before it can run — its name and the prompt to show. */
data class McpEnvSpec(val name: String, val prompt: String, val required: Boolean)

/**
 * One entry of the dashboard's MCP catalog (`GET /api/mcp/catalog`): the Nous-approved
 * manifests, each with what it would run, so the user can read that before installing.
 */
data class McpCatalogEntry(
    val name: String,
    val description: String,
    val transport: String,
    /** "none", "oauth", "env", … — oauth needs a browser sign-in on the dashboard after install. */
    val authType: String,
    val requiredEnv: List<McpEnvSpec>,
    val command: String?,
    val args: List<String>,
    val url: String?,
    /** A git clone + build step runs first; the gateway does it in the background. */
    val needsInstall: Boolean,
    val installUrl: String?,
    val installed: Boolean,
    val enabled: Boolean,
) {
    val target: String get() = url ?: listOfNotNull(command).plus(args).joinToString(" ")
}

/** `POST /api/mcp/servers/{name}/test`: connect, list the tools, disconnect. */
data class McpProbe(
    val ok: Boolean,
    val error: String?,
    /** (name, description) */
    val tools: List<Pair<String, String>>,
    val prompts: Int,
    val resources: Int,
)

/** The profile's SOUL.md: [exists] false is a profile that never wrote one (the stock default). */
data class SoulText(val profile: String, val content: String, val exists: Boolean)

object AgentParser {
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun mcpServers(root: JsonElement): List<McpServer>? {
        val arr = (root as? JsonObject)?.get("servers") as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            McpServer(
                name = name,
                transport = o.str("transport") ?: "unknown",
                url = o.str("url"),
                command = o.str("command"),
                args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                envCount = (o["env"] as? JsonObject)?.size ?: 0,
                auth = o.str("auth"),
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                plugin = o.str("plugin"),
            )
        }
    }

    fun mcpCatalog(root: JsonElement): List<McpCatalogEntry>? {
        val arr = (root as? JsonObject)?.get("entries") as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            McpCatalogEntry(
                name = name,
                description = o.str("description").orEmpty(),
                transport = o.str("transport") ?: "unknown",
                authType = o.str("auth_type") ?: "none",
                requiredEnv = (o["required_env"] as? JsonArray)?.mapNotNull { e ->
                    val eo = e as? JsonObject ?: return@mapNotNull null
                    val key = eo.str("name") ?: return@mapNotNull null
                    McpEnvSpec(key, eo.str("prompt") ?: key, (eo["required"] as? JsonPrimitive)?.booleanOrNull ?: true)
                }.orEmpty(),
                command = o.str("command"),
                args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                url = o.str("url"),
                needsInstall = (o["needs_install"] as? JsonPrimitive)?.booleanOrNull ?: false,
                installUrl = o.str("install_url"),
                installed = (o["installed"] as? JsonPrimitive)?.booleanOrNull ?: false,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun mcpProbe(root: JsonElement): McpProbe? {
        val o = root as? JsonObject ?: return null
        val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return McpProbe(
            ok = ok,
            error = o.str("error"),
            tools = (o["tools"] as? JsonArray)?.mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                (to.str("name") ?: return@mapNotNull null) to to.str("description").orEmpty()
            }.orEmpty(),
            prompts = (o["prompts"] as? JsonPrimitive)?.intOrNull ?: 0,
            resources = (o["resources"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** `POST /api/mcp/catalog/install` → true when the gateway runs it in the background (a git build). */
    fun mcpInstallBackground(root: JsonElement): Boolean =
        ((root as? JsonObject)?.get("background") as? JsonPrimitive)?.booleanOrNull ?: false

    /** `GET /api/profiles/active` → the profile this dashboard is scoped to. */
    fun currentProfile(root: JsonElement): String? {
        val o = root as? JsonObject ?: return null
        return o.str("current")?.takeIf { it != "custom" } ?: o.str("active")
    }

    fun soul(profile: String, root: JsonElement): SoulText? {
        val o = root as? JsonObject ?: return null
        val content = (o["content"] as? JsonPrimitive)?.contentOrNull ?: return null
        return SoulText(profile, content, (o["exists"] as? JsonPrimitive)?.booleanOrNull ?: content.isNotEmpty())
    }
}

private val lenient = Json { ignoreUnknownKeys = true }
private fun parse(body: String): JsonElement? = runCatching { lenient.parseToJsonElement(body) }.getOrNull()
private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

internal suspend fun GatewayRest.mcpServers(): Routed<List<McpServer>> =
    routed("GET", "/api/mcp/servers").map { parse(it)?.let(AgentParser::mcpServers) }

/** Takes effect on the agent's next session; the server stays in config either way. */
internal suspend fun GatewayRest.mcpSetEnabled(name: String, enabled: Boolean): Routed<Unit> =
    routed(
        "PUT", "/api/mcp/servers/" + enc(name) + "/enabled",
        buildJsonObject { put("enabled", JsonPrimitive(enabled)) }.toString(),
    ).map { }

/** `GET /api/mcp/catalog` — what can be installed, each marked installed/enabled. */
internal suspend fun GatewayRest.mcpCatalog(): Routed<List<McpCatalogEntry>> =
    routed("GET", "/api/mcp/catalog").map { parse(it)?.let(AgentParser::mcpCatalog) }

/**
 * `POST /api/mcp/catalog/install {name, env, enable}` — the entry's declared env vars only (the
 * route refuses any other). Answers whether the install went to the background.
 */
internal suspend fun GatewayRest.mcpInstall(name: String, env: Map<String, String>): Routed<Boolean> =
    routed(
        "POST", "/api/mcp/catalog/install",
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("env", buildJsonObject { env.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
            put("enable", JsonPrimitive(true))
        }.toString(),
    ).map { body -> parse(body)?.let(AgentParser::mcpInstallBackground) ?: false }

/** `POST /api/mcp/servers/{name}/test` — a probe that failed to connect is still an answer ([McpProbe.ok] false). */
internal suspend fun GatewayRest.mcpTest(name: String): Routed<McpProbe> =
    routed("POST", "/api/mcp/servers/" + enc(name) + "/test", "{}").map { parse(it)?.let(AgentParser::mcpProbe) }

internal suspend fun GatewayRest.currentProfile(): Routed<String> =
    routed("GET", "/api/profiles/active").map { parse(it)?.let(AgentParser::currentProfile) }

internal suspend fun GatewayRest.soul(profile: String): Routed<SoulText> =
    routed("GET", "/api/profiles/" + enc(profile) + "/soul").map { body -> parse(body)?.let { AgentParser.soul(profile, it) } }

internal suspend fun GatewayRest.saveSoul(profile: String, content: String): Routed<Unit> =
    routed(
        "PUT", "/api/profiles/" + enc(profile) + "/soul",
        buildJsonObject { put("content", JsonPrimitive(content)) }.toString(),
    ).map { }

// --- Skills ---------------------------------------------------------------------------------

/**
 * `GET /api/skills` — the dashboard's list: name, description, and whether it is on. The Link's
 * `/v1/skills` is the other source; this one is read first on the direct door because it knows
 * enabled state (and because stock `/v1/skills` raised on every call after a Hermes refactor
 * dropped the `include_editorial` argument it still passes — 11-01).
 */
internal suspend fun GatewayRest.dashboardSkills(): Routed<List<chat.keryx.app.data.remote.HermesStreamClient.HubSkill>> =
    routed("GET", "/api/skills").map { body ->
        (parse(body) as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            chat.keryx.app.data.remote.HermesStreamClient.HubSkill(
                name = name,
                description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                usage = (o["usage"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            )
        }
    }

/** `PUT /api/skills/toggle` — a disabled skill stays installed; new sessions stop loading it. */
internal suspend fun GatewayRest.skillToggle(name: String, enabled: Boolean): Routed<Unit> =
    routed(
        "PUT", "/api/skills/toggle",
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("enabled", JsonPrimitive(enabled))
        }.toString(),
    ).map { }
