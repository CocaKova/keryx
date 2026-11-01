package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The last context reading of each recent chat (makeover), so the ring shows the moment a chat
 * opens instead of after the gateway's `session.context_breakdown` — which rebuilds the prompt
 * and counts the transcript, and takes its time. A remembered reading is only ever a stand-in:
 * the first live one replaces it and is remembered in turn. Kept per gateway in the hub cache.
 */
class GaugeMemory(private val settings: SettingsRepository) {
    private val readings: LinkedHashMap<String, ChatViewModel.ContextUsage> by lazy { load() }

    fun get(roomId: String): ChatViewModel.ContextUsage? = synchronized(this) { readings[roomId] }

    fun put(u: ChatViewModel.ContextUsage) = synchronized(this) {
        if (u.used <= 0L || u.max <= 0L || readings[u.roomId] == u) return@synchronized
        readings.remove(u.roomId)
        readings[u.roomId] = u
        while (readings.size > KEEP) readings.remove(readings.keys.first())
        settings.putHubSnapshot(KEY, encode(readings.values))
    }

    private fun load(): LinkedHashMap<String, ChatViewModel.ContextUsage> {
        val out = LinkedHashMap<String, ChatViewModel.ContextUsage>()
        val raw = settings.hubSnapshot(KEY) ?: return out
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return out
        for ((id, el) in root) {
            val o = el as? JsonObject ?: continue
            val used = (o["u"] as? JsonPrimitive)?.longOrNull ?: continue
            val max = (o["m"] as? JsonPrimitive)?.longOrNull ?: continue
            out[id] = ChatViewModel.ContextUsage(
                roomId = id, used = used, max = max,
                model = (o["model"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                compactAt = (o["c"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
        return out
    }

    companion object {
        const val KEY = chat.keryx.app.data.repository.HubSnapshots.GAUGES
        private const val KEEP = 60

        internal fun encode(all: Collection<ChatViewModel.ContextUsage>): String = buildJsonObject {
            for (u in all) put(u.roomId, buildJsonObject {
                put("u", JsonPrimitive(u.used))
                put("m", JsonPrimitive(u.max))
                put("c", JsonPrimitive(u.compactAt))
                put("model", JsonPrimitive(u.model))
            })
        }.toString()
    }
}
