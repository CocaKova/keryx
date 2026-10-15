package chat.keryx.app.transport.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Writing bot profiles over the gateway's `profiles.*` (split out of [DirectTransport] in 3.0).
 * Reading the roster stays in the transport: it registers each bot's stores as it reads.
 */
internal class ProfileOps(private val gw: GatewayCalls) {
    suspend fun configure(name: String, meta: JsonObject?, description: String?): Result<Unit> = runCatching {
        val res = gw.request("profiles.configure", buildJsonObject {
            put("name", JsonPrimitive(name))
            if (meta != null) put("ui_meta", buildJsonObject { put("hermes-bots", meta) })
            if (description != null) put("description", JsonPrimitive(description))
        })
        val applied = res["applied"] as? JsonObject
        if (meta != null && (applied?.get("ui_meta") as? JsonPrimitive)?.contentOrNull == "false") {
            error("the gateway refused the bot's metadata" +
                (applied["ui_meta_conflicts"]?.let { " (edited elsewhere — try again)" } ?: ""))
        }
    }

    suspend fun create(name: String, description: String?, cloneFrom: String?): Result<Unit> = runCatching {
        gw.request("profiles.create", buildJsonObject {
            put("name", JsonPrimitive(name))
            description?.takeIf { it.isNotBlank() }?.let { put("description", JsonPrimitive(it)) }
            cloneFrom?.takeIf { it.isNotBlank() }?.let { put("clone_from", JsonPrimitive(it)) }
        }, timeoutMs = 90_000)
        Unit
    }

    suspend fun avatar(name: String): Result<ByteArray?> = runCatching {
        val res = gw.request("profiles.get_asset", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("asset", JsonPrimitive("avatar"))
        })
        val data = (res["data"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
        val b64 = data.substringAfter(",", "")
        if (b64.isBlank()) null
        else runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }
}
