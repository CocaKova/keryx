package chat.keryx.app.transport.direct

import chat.keryx.core.model.MemoryEntry
import chat.keryx.core.model.MemoryParser
import chat.keryx.core.model.MemoryStatus
import chat.keryx.core.model.RouteProbe
import chat.keryx.core.model.Routed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The dashboard routes the 2.16 Hub tools read — memory, session export, Hermes update — on the
 * door's own REST client, so they ride its auth (session token, or the native bearer it rotates)
 * and never a second client beside it. Every call answers [Routed]: a route this gateway is too
 * old to have comes back [Routed.Missing] and hides its part of the screen instead of erroring.
 */
internal suspend fun GatewayRest.routed(method: String, path: String, jsonBody: String? = null): Routed<String> =
    exchange(method, path, jsonBody).fold(
        onSuccess = { (code, body) -> RouteProbe.classify(code, body) },
        // No answer at all: the network, or a gateway mid-restart. Status 0 says so.
        onFailure = { Routed.Failed(it.message?.take(160) ?: "The gateway didn't answer.") },
    )

private val lenient = Json { ignoreUnknownKeys = true }

private fun parse(body: String): JsonElement? = runCatching { lenient.parseToJsonElement(body) }.getOrNull()

private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

// --- Memory ---------------------------------------------------------------------------------

/** `GET /api/memory` — the active provider and the two files' sizes. */
internal suspend fun GatewayRest.memoryStatus(): Routed<MemoryStatus> =
    routed("GET", "/api/memory").map { parse(it)?.let(MemoryParser::status) }

/** `GET /api/learning/graph` — the entries, as the desktop's learning panel cards. */
internal suspend fun GatewayRest.memoryEntries(): Routed<List<MemoryEntry>> =
    routed("GET", "/api/learning/graph").map { parse(it)?.let(MemoryParser::entries) }

/** `GET /api/learning/node?id=` — one entry's full raw text, for reading past the card's clip
 *  and as the edit prefill. */
internal suspend fun GatewayRest.memoryEntryText(id: String): Routed<String> =
    routed("GET", "/api/learning/node?id=" + enc(id)).map { parse(it)?.let(MemoryParser::nodeContent) }

/** `PUT /api/learning/node {id, content}` — rewrite one entry under the memory tool's lock. */
internal suspend fun GatewayRest.memoryEdit(id: String, content: String): Routed<String> =
    routed(
        "PUT", "/api/learning/node",
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("content", JsonPrimitive(content))
        }.toString(),
    ).map { parse(it)?.let(MemoryParser::mutationMessage) ?: "" }

/** `DELETE /api/learning/node {id}` — remove one entry, same lock and same stale-id refusal. */
internal suspend fun GatewayRest.memoryDelete(id: String): Routed<String> =
    routed(
        "DELETE", "/api/learning/node",
        buildJsonObject { put("id", JsonPrimitive(id)) }.toString(),
    ).map { parse(it)?.let(MemoryParser::mutationMessage) ?: "" }
