package chat.keryx.core.pair

/**
 * A pairing code from `hermes keryx pair` (keryx-stream 0.6), as the app's scanner reads it:
 *
 *     keryx://pair?v=1&gw=<gateway url>&link=<Link url>&code=<one-time code>&name=<label>
 *
 * It holds addresses and a one-time code, never a key or a password: the gateway still signs the
 * phone in through its own page, and the code buys the phone a Link token of its own, once.
 *
 * A QR is untrusted input, so the parse is strict. Only plain `http(s)://host[:port][/path]`
 * addresses, no user info, query or fragment; a code of the shape the plugin mints; a name with
 * no control characters. And the Link must be on the gateway's own host: a code that sends the
 * Link somewhere else is the shape a forged one would take (sign in to the real gateway, hand the
 * attacker's Link the phone's traffic), so it is refused, not warned about.
 *
 * Only the scanner acts on these. `keryx://` is also the app's deep-link scheme, and a pairing
 * link arriving that way pairs nothing ([chat.keryx.core.model.KeryxLink] has no `pair`).
 */
data class PairingLink(
    val gateway: String,
    val link: String,
    val code: String,
    val name: String,
) {
    /** The machine both addresses are on: what the user confirms before connecting. */
    val host: String get() = hostOf(gateway).orEmpty()

    /** Plain http to a host on the open internet, for either address: worth a warning. */
    val unencrypted: Boolean
        get() = listOf(gateway, link).any { it.startsWith("http://") && !isPrivateHost(hostOf(it).orEmpty()) }

    sealed interface Parsed {
        data class Ok(val pairing: PairingLink) : Parsed
        data class Rejected(val why: String) : Parsed
    }

    companion object {
        const val PREFIX = "keryx://pair?"
        const val VERSION = "1"
        private const val MAX_LENGTH = 2048
        private const val MAX_NAME = 64
        private val CODE = Regex("[A-Za-z0-9_-]{16,128}")
        private val FIELDS = setOf("v", "gw", "link", "code", "name")

        const val NOT_PAIRING = "That isn't a Keryx pairing code."
        const val DAMAGED = "This pairing code is damaged. Run `hermes keryx pair` again."

        /** True for anything that claims to be a pairing link, valid or not. */
        fun looksLikePairing(raw: String?): Boolean =
            raw != null && raw.trim().startsWith("keryx://pair", ignoreCase = true)

        fun parse(raw: String?): Parsed {
            val text = raw?.trim().orEmpty()
            if (!text.startsWith(PREFIX) || text.length > MAX_LENGTH) return Parsed.Rejected(NOT_PAIRING)
            val fields = HashMap<String, String>()
            for (pair in text.substring(PREFIX.length).split('&')) {
                val key = pair.substringBefore('=', "")
                val value = decode(pair.substringAfter('=', "")) ?: return Parsed.Rejected(DAMAGED)
                // An unknown or repeated field is not something the plugin writes.
                if (key !in FIELDS || key in fields) return Parsed.Rejected(DAMAGED)
                fields[key] = value
            }
            if (fields["v"] != VERSION) {
                return Parsed.Rejected("This pairing code is from a newer keryx-stream. Update Keryx to pair with it.")
            }
            val gateway = cleanUrl(fields["gw"]) ?: return Parsed.Rejected(DAMAGED)
            val link = cleanUrl(fields["link"]) ?: return Parsed.Rejected(DAMAGED)
            val code = fields["code"]?.takeIf { CODE.matches(it) } ?: return Parsed.Rejected(DAMAGED)
            if (hostOf(gateway) != hostOf(link)) {
                return Parsed.Rejected(
                    "This code sends Hermes Link to a different machine than the gateway, so Keryx won't use it.",
                )
            }
            val host = hostOf(gateway).orEmpty()
            val name = cleanName(fields["name"]).ifEmpty { host.substringBefore('.') }
            return Parsed.Ok(PairingLink(gateway, link, code, name))
        }

        /** [raw] as a clean `http(s)://host[:port][/path]` with no trailing slash, or null. */
        internal fun cleanUrl(raw: String?): String? {
            val url = raw?.trimEnd('/') ?: return null
            if (url.length > 512 || url.any { it.isWhitespace() || it.isISOControl() || it in "?#\\" }) return null
            return url.takeIf { hostOf(it) != null }
        }

        /** The lowercase host of an `http(s)://` address, or null when it isn't one. */
        internal fun hostOf(url: String): String? {
            val rest = when {
                url.startsWith("https://") -> url.removePrefix("https://")
                url.startsWith("http://") -> url.removePrefix("http://")
                else -> return null
            }
            val authority = rest.substringBefore('/')
            if (authority.isEmpty() || '@' in authority) return null
            val host: String
            val port: String?
            if (authority.startsWith('[')) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                host = authority.substring(0, end + 1)
                val after = authority.substring(end + 1)
                if (after.isNotEmpty() && !after.startsWith(':')) return null
                port = after.removePrefix(":").takeIf { after.isNotEmpty() }
                if (host.length < 4 || !host.drop(1).dropLast(1).all { it.isLetterOrDigit() || it in ":." }) return null
            } else {
                host = authority.substringBefore(':')
                port = authority.substringAfter(':', "").takeIf { ':' in authority }
                if (host.isEmpty() || !host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-" }) {
                    return null
                }
            }
            if (port != null && (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || port.toInt() !in 1..65535)) {
                return null
            }
            return host.lowercase()
        }

        /**
         * A host only reachable on a private network or a tailnet, where plain http doesn't cross
         * the open internet. The same set the plugin warns by.
         */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
            if (h.startsWith('[')) {
                val v6 = h.trim('[', ']')
                return v6 == "::1" || v6.startsWith("fc") || v6.startsWith("fd") ||
                    v6.startsWith("fe8") || v6.startsWith("fe9") || v6.startsWith("fea") || v6.startsWith("feb")
            }
            val octets = h.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val (a, b) = octets
            return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }

        private val PRIVATE_SUFFIXES = listOf(".ts.net", ".local", ".lan", ".internal", ".home.arpa")

        private fun cleanName(raw: String?): String = raw.orEmpty()
            .filter { it.category !in UNSAFE }
            .trim()
            .take(MAX_NAME)

        private val UNSAFE = setOf(
            CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.PRIVATE_USE,
            CharCategory.SURROGATE, CharCategory.UNASSIGNED,
        )

        /** RFC 3986 percent-decoding as UTF-8 (`+` stays `+`), or null on a malformed escape. */
        private fun decode(s: String): String? {
            if ('%' !in s) return s
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%') {
                    // Two hex digits, checked one by one (toIntOrNull would take "+1").
                    if (i + 2 >= s.length) return null
                    val hi = s[i + 1].digitToIntOrNull(16) ?: return null
                    val lo = s[i + 2].digitToIntOrNull(16) ?: return null
                    out.add((hi * 16 + lo).toByte())
                    i += 3
                } else {
                    c.toString().encodeToByteArray().forEach { out.add(it) }
                    i += 1
                }
            }
            return out.toByteArray().decodeToString()
        }
    }
}
