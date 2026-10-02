package chat.keryx.core.model

/**
 * `keryx://` deep links (2.16): what a launcher shortcut, a conversation shortcut, a widget or
 * another app can ask Keryx to open.
 *
 *  - `keryx://session/<id>` opens a session; `keryx://session/<id>/tapin` (or `keryx://tapin/<id>`)
 *    opens it tapped in to the turn in flight;
 *  - `keryx://new` starts a new chat; `keryx://chat` lands on the composer, ready to ask;
 *  - `keryx://missions`, `keryx://missions/<task>`, `keryx://runs`, `keryx://archive`,
 *    `keryx://projects`, `keryx://bots` open those places.
 *
 * Anything else is null: a link Keryx does not understand opens nothing rather than guessing.
 */
sealed interface KeryxLink {
    data class Session(val id: String, val tapIn: Boolean = false) : KeryxLink
    data object NewChat : KeryxLink
    data object Composer : KeryxLink
    data class Space(val route: String) : KeryxLink
    data class Mission(val taskId: String) : KeryxLink

    companion object {
        const val SCHEME = "keryx"
        val SPACES = setOf("missions", "runs", "archive", "projects", "bots")

        fun session(id: String): String = "$SCHEME://session/${encode(id)}"

        fun parse(uri: String?): KeryxLink? {
            val raw = uri?.trim() ?: return null
            val prefix = "$SCHEME://"
            if (!raw.startsWith(prefix, ignoreCase = true)) return null
            val parts = raw.substring(prefix.length).substringBefore('?').substringBefore('#')
                .split('/').filter { it.isNotEmpty() }.map(::decode)
            val head = parts.firstOrNull()?.lowercase() ?: return null
            return when (head) {
                "session" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?.let { Session(it, tapIn = parts.getOrNull(2)?.lowercase() == "tapin") }
                "tapin" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Session(it, tapIn = true) }
                "new" -> NewChat
                "chat" -> Composer
                "missions" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::Mission) ?: Space(head)
                in SPACES -> Space(head)
                else -> null
            }
        }

        private fun encode(s: String): String = buildString {
            for (c in s) {
                if (c.isLetterOrDigit() || c in "-_.~") append(c)
                else c.toString().encodeToByteArray().forEach { b ->
                    append('%'); append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
                }
            }
        }

        private fun decode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.add(v.toByte()); i += 3; continue }
                }
                c.toString().encodeToByteArray().forEach { out.add(it) }
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}
