package chat.keryx.core.model

/**
 * A transport error in words a person can act on (2.19.1). Panels showed what the HTTP stack
 * said ("HTTP 404", "Unable to resolve host … No address associated with hostname"), which
 * names the failure but not what to do about it. Unknown text passes through unchanged.
 */
object FriendlyError {
    fun of(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Something went wrong talking to the gateway."
        val low = text.lowercase()
        val code = Regex("""\bhttp (\d{3})\b""").find(low)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 404 -> "The gateway doesn't offer this (HTTP 404). It needs the keryx-stream plugin; check it is enabled and your Hermes Link URL points at it."
            code == 401 || code == 403 -> "The gateway turned the sign-in away (HTTP $code). Sign in again from Settings → Gateways."
            code != null && code >= 500 -> "The gateway hit an error (HTTP $code). Its log says what happened."
            "unable to resolve host" in low || "no address associated" in low || "unknownhost" in low ->
                "Can't find the gateway. Check your connection (and Tailscale, if you use it)."
            "failed to connect" in low || "connection refused" in low || "econnrefused" in low ->
                "The gateway isn't answering. Is Hermes running?"
            "timeout" in low || "timed out" in low -> "The gateway took too long to answer."
            "socket" in low || "not connected" in low -> "Not connected to the gateway right now. Keryx retries on its own."
            else -> text
        }
    }
}
