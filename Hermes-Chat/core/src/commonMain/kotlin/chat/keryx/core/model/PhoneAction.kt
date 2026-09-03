package chat.keryx.core.model

/**
 * One thing the agent asked the phone to do — the hands (2.8.1). Emitted by the AGENT in its
 * message text as `⟦keryx:do|<kind>|<arg>|<arg>…⟧`, the way ⟦keryx:ask⟧ is; parsed here into
 * a kind with checked arguments, rendered by the app as a tile (and a notification button)
 * that performs the action ON A TAP. The protocol's consent model is the tap: the agent can
 * propose "call this number" and the phone never dials until a thumb says so. Nothing runs in
 * the background, nothing runs twice, and a marker on a client without hands is stripped to
 * nothing (the same degrade contract every ⟦keryx:…⟧ marker keeps).
 *
 * Kinds are the actions a phone can take through its own system intents — no accessibility
 * service, no screen reading, no dangerous permission. Unknown kinds and malformed arguments
 * are NOT actions: the marker stays literal text, so the agent (or a person reading the
 * transcript) can see what was asked for and why nothing happened.
 */
data class PhoneAction(val kind: Kind, val args: List<String>) {

    enum class Kind(val token: String, val minArgs: Int, val maxArgs: Int) {
        /** Open a web link. `url|https://…` */
        URL("url", 1, 1),
        /** Bring up the dialer with a number. `dial|+15125550100` */
        DIAL("dial", 1, 1),
        /** Compose a text. `sms|+1512…|body` */
        SMS("sms", 1, 2),
        /** Compose an email. `email|to|subject|body` */
        EMAIL("email", 1, 3),
        /** Draft a calendar event. `calendar|title|start ISO-8601|end ISO-8601|where` */
        CALENDAR("calendar", 1, 4),
        /** Set an alarm. `alarm|HH:MM|label` (24 h). */
        ALARM("alarm", 1, 2),
        /** Start a timer. `timer|10m|label` — seconds, or `Nh`/`Nm`/`Ns` shorthand. */
        TIMER("timer", 1, 2),
        /** Navigate to a place. `navigate|query or address` */
        NAVIGATE("navigate", 1, 1),
        /** Web search. `search|query` */
        SEARCH("search", 1, 1),
        /** Play from the music app. `play|query` */
        PLAY("play", 1, 1),
        /** Launch an installed app by name. `open|Spotify` */
        OPEN("open", 1, 1),
        /** Put text on the clipboard. `copy|text` */
        COPY("copy", 1, 1),
        /** Flashlight. `torch|on` / `torch|off` */
        TORCH("torch", 1, 1),
        /** Hand text to the share sheet. `share|text` */
        SHARE("share", 1, 1);

        companion object {
            fun of(token: String): Kind? = entries.firstOrNull { it.token == token.trim().lowercase() }
        }
    }

    /** The first argument — every kind has one. */
    val primary: String get() = args.first()

    /** What the tile says: a verb and the thing, short enough for a notification button. */
    val label: String
        get() = when (kind) {
            Kind.URL -> "Open " + hostOf(primary)
            Kind.DIAL -> "Call $primary"
            Kind.SMS -> "Text $primary"
            Kind.EMAIL -> "Email $primary"
            Kind.CALENDAR -> "Add to calendar · " + primary.ellipsize(28)
            Kind.ALARM -> "Alarm " + primary + (args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Kind.TIMER -> "Timer " + prettySeconds(timerSeconds(primary) ?: 0) +
                (args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Kind.NAVIGATE -> "Navigate to " + primary.ellipsize(28)
            Kind.SEARCH -> "Search “" + primary.ellipsize(24) + "”"
            Kind.PLAY -> "Play " + primary.ellipsize(28)
            Kind.OPEN -> "Open $primary"
            Kind.COPY -> "Copy “" + primary.ellipsize(24) + "”"
            Kind.TORCH -> if (primary.lowercase() == "on") "Torch on" else "Torch off"
            Kind.SHARE -> "Share “" + primary.ellipsize(24) + "”"
        }

    companion object {
        const val MAX_PER_MESSAGE = 4

        /**
         * The inside of one marker (`kind|arg|arg`) → an action, or null when it is not one:
         * an unknown kind, too few / too many arguments, or an argument the kind can't use
         * (an alarm at "25:99", a timer of "soon", a torch set to "maybe"). Arguments are
         * trimmed; an empty trailing argument is dropped so `sms|+1…|` still texts.
         */
        fun parse(inner: String): PhoneAction? {
            val fields = inner.split('|').map { it.trim() }
            val kind = Kind.of(fields.firstOrNull() ?: return null) ?: return null
            val args = fields.drop(1).dropLastWhile { it.isEmpty() }
            if (args.size < kind.minArgs || args.size > kind.maxArgs) return null
            if (args.first().isEmpty()) return null
            val ok = when (kind) {
                Kind.URL -> args[0].startsWith("http://") || args[0].startsWith("https://")
                Kind.ALARM -> clockOf(args[0]) != null
                Kind.TIMER -> (timerSeconds(args[0]) ?: 0) > 0
                Kind.TORCH -> args[0].lowercase() in setOf("on", "off")
                Kind.CALENDAR -> args.size < 2 || isoLike(args[1])
                else -> true
            }
            return if (ok) PhoneAction(kind, args) else null
        }

        /** `HH:MM` (24 h) → hour to minute, or null. */
        fun clockOf(s: String): Pair<Int, Int>? {
            val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) h to min else null
        }

        /** `90`, `90s`, `10m`, `1h`, `1h30m`, `1:30` (m:ss) → seconds, or null. */
        fun timerSeconds(s: String): Int? {
            val t = s.trim().lowercase()
            t.toIntOrNull()?.let { return it }
            Regex("""^(\d{1,3}):(\d{2})$""").find(t)?.let { m ->
                return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            }
            val m = Regex("""^(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?$""").find(t) ?: return null
            if (m.groupValues.drop(1).all { it.isEmpty() }) return null
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val sec = m.groupValues[3].toIntOrNull() ?: 0
            return h * 3600 + min * 60 + sec
        }

        fun prettySeconds(total: Int): String {
            val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
            return listOfNotNull(
                "${h} h".takeIf { h > 0 },
                "${m} min".takeIf { m > 0 },
                "${s} s".takeIf { s > 0 && h == 0 },
            ).joinToString(" ").ifEmpty { "0 s" }
        }

        /** A date-time the phone can hand to the calendar: `2026-09-04T18:30` with optional seconds / offset. */
        fun isoLike(s: String): Boolean =
            Regex("""^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:?\d{2})?)?$""").matches(s.trim())

        fun hostOf(url: String): String {
            var s = url.substringAfter("://").substringBefore('/').substringBefore('?')
            s = s.substringAfter('@').substringBefore(':')
            return s.removePrefix("www.").ifEmpty { url.take(24) }
        }

        private fun String.ellipsize(n: Int): String = if (length <= n) this else take(n - 1).trimEnd() + "…"
    }
}
