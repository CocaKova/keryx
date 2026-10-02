package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the agent remembers (2.16): the built-in memory files the agent writes with its own
 * memory tool, read off the dashboard.
 *
 * Two routes, because neither alone is the viewer: `GET /api/memory` says which provider is
 * active and how big the two files are (sizes only, it is the reset page's preflight), and
 * `GET /api/learning/graph` carries the entries themselves, each as a card of the desktop's
 * learning panel. Edits and deletes go back through `/api/learning/node`, which takes the memory
 * tool's own lock and refuses an id whose text has moved under it, so the phone can never write
 * over something the agent stored a second earlier.
 */
enum class MemorySource(val wire: String, val fileName: String) {
    /** The agent's own notes about the world and its work. */
    NOTES("memory", "MEMORY.md"),
    /** What the agent knows about you. */
    PROFILE("profile", "USER.md");

    companion object {
        fun fromWire(s: String?): MemorySource? = entries.firstOrNull { it.wire == s }
    }
}

data class MemoryEntry(
    /**
     * The gateway's node id, `memory:<source>:<index>:<fingerprint>`. The fingerprint is a digest
     * of the entry's text, so the id names WHAT the entry says, not where it sat: an edit made
     * after the agent removed an earlier entry still lands on the one you opened (#119668).
     */
    val id: String,
    val source: MemorySource,
    /** The entry's first line, `#`s stripped, clipped at 80 chars by the server. */
    val title: String,
    /** The entry text as the graph serves it — clipped at [MemoryParser.BODY_CAP] chars. */
    val body: String,
    /** Epoch seconds (the file's mtime plus the entry's position), null when the file had none. */
    val timestamp: Long?,
) {
    /** A body at the cap may be the head of a longer entry; open it through the node route. */
    val maybeClipped: Boolean get() = body.length >= MemoryParser.BODY_CAP
}

data class MemoryProvider(
    val name: String,
    val description: String,
    /** "ready" · "needs_config" · "unavailable" · "missing" — the dashboard's own words. */
    val status: String,
)

data class MemoryStatus(
    /** The configured external provider, "" = built-in files only. */
    val active: String,
    val providers: List<MemoryProvider>,
    /** Byte sizes of MEMORY.md / USER.md; 0 when the file does not exist. */
    val notesBytes: Long,
    val profileBytes: Long,
) {
    val activeProvider: MemoryProvider? get() = providers.firstOrNull { it.name == active }
}

object MemoryParser {
    /** `agent/learning_graph.py` clips a card's body here (`chunk[:1200]`). */
    const val BODY_CAP = 1200

    /** `GET /api/memory` → status; null on a payload that isn't one. */
    fun status(el: JsonElement): MemoryStatus? {
        val o = el as? JsonObject ?: return null
        val files = o["builtin_files"] as? JsonObject
        if (files == null && o["active"] == null) return null
        return MemoryStatus(
            active = o.str("active").orEmpty(),
            providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                MemoryProvider(
                    name = po.str("name") ?: return@mapNotNull null,
                    description = po.str("description").orEmpty(),
                    status = po.str("status").orEmpty(),
                )
            },
            notesBytes = files?.long("memory") ?: 0L,
            profileBytes = files?.long("user") ?: 0L,
        )
    }

    /**
     * `GET /api/learning/graph` → the memory entries, MEMORY.md first then USER.md, in file
     * order. Ids come from the graph's own memory nodes when it sent them (one per card, same
     * order); otherwise they are minted the way `memory_node_id` mints them. A card without a
     * fingerprint (a gateway older than #119668) gets the legacy positional id, which that
     * gateway's node routes still resolve. Null when the payload has no memory list at all.
     */
    fun entries(el: JsonElement): List<MemoryEntry>? {
        val o = el as? JsonObject ?: return null
        val cards = o["memory"] as? JsonArray ?: return null
        val nodeIds = (o["nodes"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.takeIf { n -> n.str("kind") == "memory" }?.str("id") }
        val trustNodes = nodeIds.size == cards.size
        return cards.mapIndexedNotNull { i, c ->
            val co = c as? JsonObject ?: return@mapIndexedNotNull null
            val source = MemorySource.fromWire(co.str("source")) ?: return@mapIndexedNotNull null
            val fingerprint = co.str("fingerprint").orEmpty()
            val minted = "memory:${source.wire}:$i" + if (fingerprint.isNotBlank()) ":$fingerprint" else ""
            MemoryEntry(
                id = if (trustNodes) nodeIds[i] else minted,
                source = source,
                title = co.str("title").orEmpty(),
                body = co.str("body").orEmpty(),
                timestamp = (co["timestamp"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
            )
        }
    }

    /** `GET /api/learning/node?id=` → the entry's full raw text (the edit prefill). */
    fun nodeContent(el: JsonElement): String? =
        (el as? JsonObject)?.takeIf { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }?.str("content")

    /** A mutation's `{ok, message}` — the server's own sentence ("updated memory in MEMORY.md"). */
    fun mutationMessage(el: JsonElement): String? = (el as? JsonObject)?.str("message")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

object MemorySearch {
    /**
     * Every whitespace-separated term must appear in the entry (title or body, any case) — the
     * way you look for "that thing about the Spark's memory", typing two words you remember.
     * A blank query is everything.
     */
    fun filter(entries: List<MemoryEntry>, query: String): List<MemoryEntry> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return entries
        return entries.filter { e ->
            val hay = (e.title + "\n" + e.body).lowercase()
            terms.all { it in hay }
        }
    }

    /** Byte count in the dashboard's units: "0 B", "940 B", "2.1 KB", "1.4 MB". */
    fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> oneDecimal(bytes / 1024.0) + " KB"
        else -> oneDecimal(bytes / (1024.0 * 1024.0)) + " MB"
    }

    private fun oneDecimal(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
