package chat.keryx.app.transport.direct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Hermes 0.22.4's dashboard fences every session edit (rename, archive, pin, hide, read state,
 * delete): the request must name a `request_id` and the session's `expected_revision`, read from
 * `GET /api/sessions/{id}/mutation-snapshot`, or the owner answers 409 `client_upgrade_required`
 * and writes nothing. An unfenced Keryx had every one of those edits refused from the update on:
 * opening a chat never cleared its unread mark. This is the official web client's flow
 * (`apps/shared/src/session-http-mutations.ts`): snapshot, then the edit carrying its identity.
 *
 * A gateway with no snapshot route (Hermes before the fence) gets the edit exactly as before.
 * The edits sent through here are absolute values (read now, pinned = x, this title), so one
 * revision conflict is answered by a fresh snapshot and the same intent again, never by a loop.
 */
internal object SessionFence {

    data class Identity(val requestId: String, val revision: Long, val generation: Long?)

    sealed interface Snapshot {
        /** No fence on this gateway: send the edit unfenced, as before 0.22.4. */
        data object Unfenced : Snapshot
        /** The session is gone; nothing to edit. */
        data object Gone : Snapshot
        data class Fenced(val identity: Identity) : Snapshot
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseSnapshot(code: Int, body: String, newRequestId: () -> String): Snapshot {
        // 404/405: the route does not exist here (an older gateway). FastAPI answers an unknown
        // /api path with 404 JSON; a 2xx that is not the snapshot shape is a page, not a fence.
        if (code == 404 || code == 405) return Snapshot.Unfenced
        if (code !in 200..299) error("HTTP $code for mutation-snapshot" + body.take(160).let { if (it.isBlank()) "" else " — $it" })
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return Snapshot.Unfenced
        val exists = (o["exists"] as? JsonPrimitive)?.booleanOrNull ?: return Snapshot.Unfenced
        if (!exists) return Snapshot.Gone
        val revision = (o["runtime_revision"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            ?: return Snapshot.Unfenced
        val generation = (o["runtime_generation"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        return Snapshot.Fenced(Identity(newRequestId(), revision, generation))
    }

    /** The PATCH body with its identity (the web client sends expected_generation on a PATCH too). */
    fun withIdentity(body: JsonObject, id: Identity?): JsonObject {
        if (id == null) return body
        return JsonObject(body + buildMap {
            put("request_id", JsonPrimitive(id.requestId))
            put("expected_revision", JsonPrimitive(id.revision))
            id.generation?.let { put("expected_generation", JsonPrimitive(it)) }
        })
    }

    /** DELETE carries its identity in the query, beside `profile`. */
    fun deleteQuery(profile: String?, id: Identity?): String {
        val params = buildList {
            profile?.trim()?.takeIf { it.isNotEmpty() }?.let { add("profile" to it) }
            if (id != null) {
                add("request_id" to id.requestId)
                add("expected_revision" to id.revision.toString())
                id.generation?.let { add("expected_generation" to it.toString()) }
            }
        }
        if (params.isEmpty()) return ""
        return "?" + params.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    /** The owner refused this precondition, not the edit: a fresh snapshot may succeed. */
    fun isConflict(code: Int, body: String): Boolean =
        code == 409 && ("revision_conflict" in body || "stale_generation" in body)

    /**
     * One fenced edit. [exchange] is the REST round trip (method, path, JSON body) → (code, body).
     * [path] is the session's route, [snapshotQuery] the `?profile=` for the snapshot GET.
     */
    suspend fun mutate(
        exchange: suspend (method: String, path: String, body: String?) -> Result<Pair<Int, String>>,
        method: String,
        path: String,
        snapshotQuery: String,
        build: (Identity?) -> Pair<String, String?>,
        newRequestId: () -> String = { java.util.UUID.randomUUID().toString() },
    ): Result<String> = runCatching {
        var retried = false
        lateinit var answer: String
        while (true) {
            val (sCode, sBody) = exchange("GET", "$path/mutation-snapshot$snapshotQuery", null).getOrThrow()
            val id = when (val snap = parseSnapshot(sCode, sBody, newRequestId)) {
                Snapshot.Gone -> error("session not found: $path")
                Snapshot.Unfenced -> null
                is Snapshot.Fenced -> snap.identity
            }
            val (target, body) = build(id)
            val (code, reply) = exchange(method, target, body).getOrThrow()
            if (code in 200..299) { answer = reply; break }
            if (id != null && !retried && isConflict(code, reply)) { retried = true; continue }
            error("HTTP $code for $target" + reply.take(160).let { if (it.isBlank()) "" else " — $it" })
        }
        answer
    }
}
