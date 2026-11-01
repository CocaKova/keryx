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
 * The dashboard routes behind the agent page (makeover): the MCP servers the agent can reach,
 * and the SOUL.md it wakes up with. Stock `hermes dashboard` routes, the same ones the Desktop
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

internal suspend fun GatewayRest.currentProfile(): Routed<String> =
    routed("GET", "/api/profiles/active").map { parse(it)?.let(AgentParser::currentProfile) }

internal suspend fun GatewayRest.soul(profile: String): Routed<SoulText> =
    routed("GET", "/api/profiles/" + enc(profile) + "/soul").map { body -> parse(body)?.let { AgentParser.soul(profile, it) } }

internal suspend fun GatewayRest.saveSoul(profile: String, content: String): Routed<Unit> =
    routed(
        "PUT", "/api/profiles/" + enc(profile) + "/soul",
        buildJsonObject { put("content", JsonPrimitive(content)) }.toString(),
    ).map { }
