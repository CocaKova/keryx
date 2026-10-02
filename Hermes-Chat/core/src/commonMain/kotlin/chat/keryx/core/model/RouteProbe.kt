package chat.keryx.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One read of a route that an older gateway may not have.
 *
 * [Missing] hides that part of a panel; [Failed] is the route (or the network) saying no, and is
 * shown. [Failed.status] is 0 when no answer came back at all — the gateway restarting under an
 * update looks exactly like that, and the update watch reads it as such.
 */
sealed interface Routed<out T> {
    data class Ok<T>(val value: T) : Routed<T>
    data object Missing : Routed<Nothing>
    data class Failed(val message: String, val status: Int = 0) : Routed<Nothing>

    fun <R> map(f: (T) -> R?): Routed<R> = when (this) {
        is Ok -> f(value)?.let { Ok(it) } ?: Failed("The gateway answered with something Keryx can't read.", 200)
        Missing -> Missing
        is Failed -> this
    }

    val valueOrNull: T? get() = (this as? Ok)?.value
}

/**
 * Is this answer "the server has no such route" — or the route, saying no?
 *
 * Every panel built on a newer gateway route has to tell those apart: the first hides its part
 * of the screen (an older Hermes, an older keryx-stream), the second is real news the panel must
 * show. Getting it wrong either way is a bug someone sees: a hidden panel on a gateway that
 * merely had nothing yet, or an error line that never goes away on one that will never have it.
 *
 * What each server says when the route is missing:
 *  - the dashboard's SPA catch-all answers an unknown `GET /api/…` with 404
 *    `{"detail": "No such API endpoint: /api/…"}` (`web_server_dashboard.serve_spa`);
 *  - the same catch-all is GET-only, so an unknown POST/PUT/DELETE is a 405;
 *  - FastAPI with no catch-all (headless dashboard) and aiohttp (the API server the plugin
 *    rides) answer a bare "Not Found".
 * A route that exists and finds nothing words its own 404 — "No update receipt found …",
 * "Session not found" — and that is NOT a missing route.
 */
object RouteProbe {
    fun isMissingRoute(status: Int, body: String): Boolean {
        if (status == 405) return true
        if (status != 404) return false
        val b = body.trim()
        if (b.isEmpty()) return true
        return "No such API endpoint" in b ||
            b == "404: Not Found" ||
            b == "Not Found" ||
            b.filterNot { it.isWhitespace() } == """{"detail":"NotFound"}"""
    }

    /** One answer, classified. */
    fun classify(status: Int, body: String): Routed<String> = when {
        status in 200..299 -> Routed.Ok(body)
        isMissingRoute(status, body) -> Routed.Missing
        else -> Routed.Failed(serverWords(status, body), status)
    }

    /**
     * The server's own sentence for a refusal: FastAPI's `detail` (a string, or a validation
     * list whose first `msg` is the useful part), the plugin's `error.message`, else the status.
     * "HTTP 400" alone sends you hunting; "Replacement would put memory at 2,400/2,200 chars"
     * tells you what to do.
     */
    fun serverWords(status: Int, body: String): String {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val detail = o?.get("detail")
        val fromDetail = when (detail) {
            is JsonPrimitive -> detail.contentOrNull
            is kotlinx.serialization.json.JsonArray ->
                ((detail.firstOrNull() as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        val fromError = when (val e = o?.get("error")) {
            is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> e.contentOrNull
            else -> null
        }
        val words = (fromDetail ?: fromError ?: (o?.get("message") as? JsonPrimitive)?.contentOrNull)
            ?.trim()?.takeIf { it.isNotEmpty() }
        return words?.take(200) ?: "HTTP $status"
    }
}
