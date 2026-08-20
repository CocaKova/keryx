package chat.keryx.app.domain.model

/**
 * A herald's colours: every agent account Keryx knows gets a stable hue, so a room with
 * several agents (the Council) reads as several lives — "each life has its own color".
 *
 * Pure Kotlin (ARGB longs, no Compose) so the repository layer and tests can use it.
 */
data class Heraldry(
    /** Stable key — the MXID localpart, lowercased ("milo"). */
    val key: String,
    /** Display name if known, else the localpart. */
    val name: String,
    val accentArgb: Long,
    val accent2Argb: Long,
    /** The first configured agent keeps the user's own theme accents — a 1:1 room looks like 2.2. */
    val primary: Boolean,
)

object Heralds {
    /** The sigil: the herald's staff (κηρύκειον — which *is* the caduceus). */
    const val SIGIL = "☤"

    /** Parse the configured agent id setting: one id, or several separated by commas / newlines / spaces. */
    fun parseIds(cfg: String): List<String> =
        cfg.split(',', '\n', ';', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun localpart(id: String): String = id.trim().removePrefix("@").substringBefore(':').lowercase()

    /** True when [senderId] matches any configured id — exact (case-insensitive) or by bare localpart. */
    fun isHerald(senderId: String, ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        val senderLocal = localpart(senderId)
        return ids.any { cfg ->
            cfg.equals(senderId, ignoreCase = true) ||
                localpart(cfg).let { it.isNotEmpty() && it == senderLocal }
        }
    }

    /** Curated council palette: (accent, accent2) pairs that sit well on the gilded void. */
    val PALETTE: List<Pair<Long, Long>> = listOf(
        0xFF5FD3BCL to 0xFF1E6F8CL, // verdigris → deep teal
        0xFFB08CFFL to 0xFF5B2EA6L, // dusk violet → ink
        0xFFE98A8AL to 0xFFB0453FL, // rose gold → oxblood
        0xFF8FD0FFL to 0xFF3A6FB0L, // ice → cobalt
        0xFFC9E36BL to 0xFF5E8A1CL, // chartreuse → moss
        0xFFF0B429L to 0xFFE55A00L, // gilt → ember
    )

    /** FNV-1a over the key — stable across launches and devices, unlike hashCode() contracts. */
    fun stableHash(key: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in key) { h = (h xor ch.code) * 0x01000193 }
        return h and 0x7FFFFFFF
    }

    /**
     * Assign palette slots to the configured ids in order: each takes its hashed slot unless an
     * earlier herald already holds it, then the next free one — so a room of ≤6 never shares a hue,
     * and a herald's hue does not change when another is added after it.
     */
    fun assignSlots(ids: List<String>): Map<String, Int> {
        val taken = HashSet<Int>()
        val out = LinkedHashMap<String, Int>()
        for (id in ids) {
            val key = localpart(id)
            if (key.isEmpty() || key in out) continue
            var slot = stableHash(key) % PALETTE.size
            var tries = 0
            while (slot in taken && tries < PALETTE.size) { slot = (slot + 1) % PALETTE.size; tries++ }
            taken += slot
            out[key] = slot
        }
        return out
    }

    /** Darken an ARGB colour toward black by [t] (0..1) for an accent2 derived from an override. */
    fun shade(argb: Long, t: Float): Long {
        val a = (argb shr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF) * (1f - t)
        val g = ((argb shr 8) and 0xFF) * (1f - t)
        val b = (argb and 0xFF) * (1f - t)
        return (a shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /**
     * Resolve a herald's colours.
     * @param ids configured agent ids (order matters: first = primary)
     * @param overrides localpart → user-chosen accent ARGB
     * @param themeAccent / themeAccent2 the user's own theme accents (used for the primary herald
     *        and for the blank-config legacy case)
     */
    fun resolve(
        senderId: String,
        senderName: String,
        ids: List<String>,
        overrides: Map<String, Long>,
        themeAccent: Long,
        themeAccent2: Long,
    ): Heraldry {
        val key = localpart(senderId)
        val name = senderName.takeIf { it.isNotBlank() && !it.startsWith("@") } ?: key.ifEmpty { senderId }
        val primaryKey = ids.firstOrNull()?.let { localpart(it) }
        val primary = ids.isEmpty() || key == primaryKey
        overrides[key]?.let { return Heraldry(key, name, it, shade(it, 0.45f), primary) }
        if (primary) return Heraldry(key, name, themeAccent, themeAccent2, true)
        val slot = assignSlots(ids)[key] ?: (stableHash(key) % PALETTE.size)
        val (a, a2) = PALETTE[slot]
        return Heraldry(key, name, a, a2, false)
    }
}

/**
 * How a room's avatar should read in the drawer and the Quick Rooms deck.
 *
 * 2.2's rule was "color is light, light means life"; 2.3's is "each life has its own color". A
 * room avatar is the one place where those two meet a list, so it answers a different question
 * per shape:
 *
 * - [Stack] — several heralds sit here, so the room is a *council*: one staff per herald, each
 *   in its own hue. Which lives are in the room is the interesting fact; the room is the venue.
 * - [Single] — one herald, which is every ordinary agent room. Here the herald's hue is the
 *   *wrong* colour to paint: every such room would come out identical, and a drawer of seven
 *   identical circles says nothing. The room IS that life, so the staff wears the room's own
 *   hue and the row stays readable.
 * - [None] — no herald known, so nothing changes: the ordinary lettered monogram.
 */
sealed interface RoomSigil {
    data object None : RoomSigil
    data class Single(val heraldKey: String) : RoomSigil
    data class Stack(val heraldKeys: List<String>) : RoomSigil
}

object RoomSigils {
    /** Past this a stacked row stops being legible at avatar size. */
    const val MAX_STACK = 3

    /**
     * @param heraldsInRoom configured heralds present, already filtered by [Heralds.isHerald]
     */
    fun of(heraldsInRoom: List<String>): RoomSigil {
        val keys = heraldsInRoom.map(Heralds::localpart).filter { it.isNotEmpty() }.distinct()
        return when {
            keys.isEmpty() -> RoomSigil.None
            keys.size == 1 -> RoomSigil.Single(keys[0])
            else -> RoomSigil.Stack(keys.take(MAX_STACK))
        }
    }

    /** The heralds among a room's members — the repository's half of the same decision. */
    fun heraldsAmong(memberIds: Collection<String>, configuredIds: List<String>): List<String> =
        if (configuredIds.isEmpty()) emptyList()
        else memberIds.filter { Heralds.isHerald(it, configuredIds) }.distinct()

    /**
     * The blank-config case: who the herald is when nobody has named one.
     *
     * The agent id setting ships empty, and it turns out most installs never fill it in — so the
     * drawer answered "no heralds anywhere" while the transcript in those very rooms rendered the
     * other member AS the agent, off the same-shaped rule (`senderTypeOf`'s legacy fallback: a
     * gateway install, a room with one other member). One app, two answers to one question.
     *
     * So this is that rule, held in the one place both halves can read: in a room with exactly one
     * other member, that member is its herald. A group room stays [None] — with several members
     * and nothing configured there is no way to tell an agent from a person, and a wrong staff is
     * worse than a letter.
     */
    fun soloHerald(memberIds: Collection<String>, myId: String): List<String> {
        val others = memberIds.filter { !it.equals(myId, ignoreCase = true) }.distinct()
        return if (others.size == 1) others else emptyList()
    }
}
