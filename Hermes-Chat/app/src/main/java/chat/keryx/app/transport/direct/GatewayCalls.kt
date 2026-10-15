package chat.keryx.app.transport.direct

import kotlinx.serialization.json.JsonObject

/**
 * The live socket as the split-out gateway calls see it (3.0): whichever [GatewayRpc] the
 * transport holds right now, or "gateway not connected". A reconnect swaps the socket, so the
 * calls ask for it per request instead of keeping one.
 */
internal class GatewayCalls(private val rpc: () -> GatewayRpc?) {
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
    ): JsonObject = (rpc() ?: error("gateway not connected")).request(method, params, timeoutMs)
}
