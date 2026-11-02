package chat.keryx.core.model

import chat.keryx.core.i18n.CoreWords
import chat.keryx.core.i18n.Phrase

/**
 * A transport error in words a person can act on (2.19.1). Panels showed what the HTTP stack
 * said ("HTTP 404", "Unable to resolve host … No address associated with hostname"), which
 * names the failure but not what to do about it. Unknown text passes through unchanged.
 */
object FriendlyError {
    fun of(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return CoreWords.t(Phrase.ERR_GENERIC)
        val low = text.lowercase()
        val code = Regex("""\bhttp (\d{3})\b""").find(low)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code == 404 -> CoreWords.t(Phrase.ERR_404)
            code == 401 || code == 403 -> CoreWords.t(Phrase.ERR_AUTH, code.toString())
            code != null && code >= 500 -> CoreWords.t(Phrase.ERR_SERVER, code.toString())
            "unable to resolve host" in low || "no address associated" in low || "unknownhost" in low ->
                CoreWords.t(Phrase.ERR_NO_HOST)
            "failed to connect" in low || "connection refused" in low || "econnrefused" in low ->
                CoreWords.t(Phrase.ERR_REFUSED)
            "timeout" in low || "timed out" in low -> CoreWords.t(Phrase.ERR_TIMEOUT)
            "socket" in low || "not connected" in low -> CoreWords.t(Phrase.ERR_NOT_CONNECTED)
            else -> text
        }
    }
}
