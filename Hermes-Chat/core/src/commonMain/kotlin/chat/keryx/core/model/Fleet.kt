package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fleet (2.11): every Hermes gateway this phone can reach, by name — the Desktop's
 * "Registered gateways" (`Settings → Gateways`), on the direct door.
 *
 * Nothing here is a new primitive. A gateway is a `hermes dashboard` URL plus the credential the
 * phone signed in with; the fleet is a named list of them and two pointers into it. The rules
 * are the Desktop's, kept exactly so the two clients read alike:
 *
 *  - every gateway has a **unique device name**, case-insensitively — `Homelab` and `homelab`
 *    cannot coexist. The name is the thing the phone shows everywhere the gateway appears.
 *  - gateways are **deduplicated on the normalised URL** (trimmed, trailing slashes stripped,
 *    scheme and host lowercased): one backend, one row.
 *  - one gateway is always **Primary** — the fallback. Removing the primary hands the pill to
 *    the first remaining gateway; removing the last gateway leaves an empty fleet.
 *  - the **active** gateway is the one the workspace is on. Sessions, cron, bots, the Hub,
 *    the archive — all of it is scoped to the active gateway, and only one is active at a
 *    time. The phone cannot show two fleets in one drawer; the Desktop chose the same.
 *
 * Where the phone differs from the Desktop, deliberately: there is no `local` kind (the phone
 * does not host a runtime), and the start-up rule defaults to *resume the last-used gateway*
 * rather than *open on Primary*. The Desktop defaults to Primary because a relaunch there is a
 * decision; on a phone the OS kills the process behind your back all day, and landing on a
 * different gateway after every one of those would read as the app forgetting where you were.
 * The toggle exists with the Desktop's wording; only its default is flipped.
 *
 * Pure: what a fleet is, and what each verb does to it. Storage, credentials and the wire are
 * the app's.
 */
data class GatewayEntry(
    /** Random, stable, never shown. Credentials and per-gateway ledgers hang off it. */
    val id: String,
    /** The device name — required, unique, ≤ [Fleet.NAME_MAX] characters. */
    val name: String,
    /** The dashboard's base URL, normalised by [Fleet.normalizeUrl]. */
    val url: String,
) {
    /** The host the row shows under the name: `spark.lan:9119`, never the scheme. */
    val hostLabel: String get() = url.removePrefix("https://").removePrefix("http://")
}

data class Fleet(
    val gateways: List<GatewayEntry> = emptyList(),
    /** The fallback gateway; blank only while the fleet is empty. */
    val primaryId: String = "",
    /** The workspace's gateway; blank only while the fleet is empty. */
    val activeId: String = "",
    /** Cold starts land on the last-used gateway (phone default) or on Primary (Desktop default). */
    val resumeLastGateway: Boolean = true,
) {
    val isEmpty: Boolean get() = gateways.isEmpty()
    val size: Int get() = gateways.size
    /** The drawer only grows a gateway selector once there is a choice to make. */
    val hasChoice: Boolean get() = gateways.size > 1

    val active: GatewayEntry? get() = gateways.firstOrNull { it.id == activeId }
    val primary: GatewayEntry? get() = gateways.firstOrNull { it.id == primaryId }

    fun byId(id: String): GatewayEntry? = gateways.firstOrNull { it.id == id }
    fun byUrl(url: String): GatewayEntry? {
        val n = normalizeUrl(url)
        return gateways.firstOrNull { it.url == n }
    }
    fun byName(name: String): GatewayEntry? {
        val n = name.trim()
        return gateways.firstOrNull { it.name.equals(n, ignoreCase = true) }
    }

    /**
     * The gateway a cold start opens on. Honours [resumeLastGateway]; falls back to Primary
     * when the last-used one is gone, and to the first row when even Primary is gone (a
     * fleet edited by hand, or a removal that raced a relaunch).
     */
    fun bootTarget(): GatewayEntry? = when {
        resumeLastGateway && active != null -> active
        primary != null -> primary
        else -> gateways.firstOrNull()
    }

    /** Why a save was refused — the exact violation, the way the Desktop's editor names it. */
    class RejectedException(message: String) : IllegalArgumentException(message)

    /**
     * Register a gateway. A URL already on the fleet is NOT a second row: the existing entry
     * is returned untouched (the Desktop dedupes the same way), so a re-login of a known
     * gateway keeps its name, its id and its ledgers. A blank name is derived from the host
     * and suffixed until unique (`spark.lan`, `spark.lan 2`).
     */
    fun add(url: String, name: String, newId: () -> String): Pair<Fleet, GatewayEntry> {
        val n = normalizeUrl(url)
        if (n.isBlank()) throw RejectedException("Gateway URL is required")
        byUrl(n)?.let { return this to it }
        val wanted = name.trim().ifBlank { deriveName(n) }
        if (wanted.length > NAME_MAX) throw RejectedException("Name is longer than $NAME_MAX characters")
        val unique = if (name.isBlank()) uniqueName(wanted) else wanted
        if (byName(unique) != null) throw RejectedException("The name \"$unique\" is already in use")
        var id = newId()
        while (byId(id) != null) id = newId()
        val entry = GatewayEntry(id = id, name = unique, url = n)
        val next = copy(
            gateways = gateways + entry,
            primaryId = primaryId.ifBlank { entry.id },
            activeId = activeId.ifBlank { entry.id },
        )
        return next to entry
    }

    fun rename(id: String, name: String): Fleet {
        val entry = byId(id) ?: return this
        val n = name.trim()
        if (n.isBlank()) throw RejectedException("Name is required")
        if (n.length > NAME_MAX) throw RejectedException("Name is longer than $NAME_MAX characters")
        byName(n)?.takeIf { it.id != id }?.let { throw RejectedException("The name \"${it.name}\" is already in use") }
        return copy(gateways = gateways.map { if (it.id == id) entry.copy(name = n) else it })
    }

    /** Take a gateway off the fleet. Primary falls back to the first remaining row; the active
     *  pointer likewise — the caller decides whether that is a relaunch. */
    fun remove(id: String): Fleet {
        if (byId(id) == null) return this
        val rest = gateways.filterNot { it.id == id }
        val first = rest.firstOrNull()?.id.orEmpty()
        return copy(
            gateways = rest,
            primaryId = if (primaryId == id) first else primaryId,
            activeId = if (activeId == id) first else activeId,
        )
    }

    /** "Make primary" — the fallback, NOT a switch of the current workspace (Desktop rule). */
    fun setPrimary(id: String): Fleet = if (byId(id) == null) this else copy(primaryId = id)

    fun setActive(id: String): Fleet = if (byId(id) == null) this else copy(activeId = id)

    private fun uniqueName(base: String): String {
        if (byName(base) == null) return base
        var i = 2
        while (byName("$base $i") != null) i++
        return "$base $i"
    }

    fun toJson(): String = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1),
            "primary" to JsonPrimitive(primaryId),
            "active" to JsonPrimitive(activeId),
            "resume_last" to JsonPrimitive(resumeLastGateway),
            "gateways" to JsonArray(
                gateways.map {
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(it.id),
                            "name" to JsonPrimitive(it.name),
                            "url" to JsonPrimitive(it.url),
                        ),
                    )
                },
            ),
        ),
    ).toString()

    companion object {
        const val NAME_MAX = 64

        /** The Desktop's dedupe key: trimmed, trailing slashes gone, scheme + host lowercased. A
         *  path prefix (a reverse proxy) keeps its case — paths are case-sensitive. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isBlank()) return ""
            val schemeEnd = t.indexOf("://")
            if (schemeEnd < 0) return t
            val scheme = t.substring(0, schemeEnd).lowercase()
            val rest = t.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            val authority = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
            val path = if (slash < 0) "" else rest.substring(slash)
            return "$scheme://$authority$path"
        }

        /** A name from a URL when the user gave none: the host, without port or scheme. */
        fun deriveName(url: String): String {
            val n = normalizeUrl(url)
            val host = n.substringAfter("://", n).substringBefore('/').substringBefore(':')
            return host.ifBlank { "Gateway" }.take(NAME_MAX)
        }

        fun fromJson(raw: String): Fleet {
            if (raw.isBlank()) return Fleet()
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }
                .getOrNull() ?: return Fleet()
            val gateways = o["gateways"]?.let { it as? JsonArray }?.mapNotNull { el ->
                val g = el as? JsonObject ?: return@mapNotNull null
                val id = g["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = g["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank() || url.isBlank()) return@mapNotNull null
                GatewayEntry(id = id, name = g["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { deriveName(url) }, url = url)
            }.orEmpty()
            val ids = gateways.map { it.id }.toSet()
            val first = gateways.firstOrNull()?.id.orEmpty()
            val primary = o["primary"]?.jsonPrimitive?.contentOrNull.orEmpty().takeIf { it in ids } ?: first
            val active = o["active"]?.jsonPrimitive?.contentOrNull.orEmpty().takeIf { it in ids } ?: first
            return Fleet(
                gateways = gateways,
                primaryId = primary,
                activeId = active,
                resumeLastGateway = o["resume_last"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }
}
