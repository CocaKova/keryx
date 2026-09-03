package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Bot Mode (2.8): a Bot IS a Hermes profile — isolated config, memory, skills and chat history
 * under `~/.hermes/profiles/<name>/` — and every Bot has ONE forever conversation, the session on
 * its profile titled exactly "Bot Chat". Nothing here is a new primitive: the roster is
 * `profiles.list`, the chat is a real gateway session opened by title, and a Bot's routines are
 * cron jobs named `[bot:<name>] …`. This file is the pure half — what a roster row is, how the
 * gateway's answer becomes one, and the rules the desktop's Bots pane lives by (the forever-chat
 * never resets; @mentions resolve against the live roster; a bot is "active" inside a 90 s
 * window of its last word). The transport does the RPCs; the UI draws; this decides.
 */
data class BotChatRef(
    /** The registry row: the stored session id of the profile's "Bot Chat". */
    val id: String,
    /** The compression-lineage tip — what actually opens. Equal to [id] until a compaction. */
    val resolvedId: String,
    val preview: String = "",
    /** Epoch millis of the last activity; 0 when unknown. */
    val lastActive: Long = 0L,
    val messageCount: Long = 0L,
) {
    /** The id to open. The registry row stays the identity, the tip holds the transcript. */
    val openId: String get() = resolvedId.ifBlank { id }
}

data class BotProfile(
    /** The profile name — the one stable key (`default`, `theo`, `research-buddy`). */
    val name: String,
    /** `hermes profile rename`'s friendly name, when set. */
    val displayName: String = "",
    /** The profile's stated purpose (profile.yaml `description`). */
    val description: String = "",
    /** Bot Mode's own title (`ui_meta.hermes-bots.title`), when the desktop or Keryx set one. */
    val title: String = "",
    val model: String = "",
    val provider: String = "",
    val hasAvatar: Boolean = false,
    val isDefault: Boolean = false,
    /**
     * Whether this profile carries the `ui_meta.hermes-bots` block. The GATEWAY reads this
     * flag: while any profile on the install carries it, every canonical Bot Chat gets the
     * `message_agent` tool and the teammate protocol at prompt-build time. Unmanaged
     * profiles still list and open — they just cannot message each other yet.
     */
    val managed: Boolean = false,
    /** Display-only: taken out of the roster by the user (desktop parity, `hermes-bots.hidden`). */
    val hidden: Boolean = false,
    /** The canonical Bot Chat, when it exists yet. Null = never opened; the first tap creates it. */
    val canonical: BotChatRef? = null,
    /**
     * The raw `ui_meta.hermes-bots` block as the gateway holds it. The gateway merges ui_meta
     * KEY-wise, so writing `hermes-bots` replaces the whole block — every write-back must start
     * from this or it wipes the desktop's shape, colour, groups and creation stamp.
     */
    val meta: JsonObject? = null,
) {
    /** The @-handle. The desktop's mention middleware aliases the default profile as @hermes. */
    val handle: String get() = if (name == "default") "hermes" else name

    /** What the row is called: the Bot Mode title, else the friendly name, else the profile name. */
    val label: String get() = title.ifBlank { displayName.ifBlank { BotRoster.pretty(name) } }

    /** Every tag this bot answers to, lowercase: profile name, @hermes alias, slug of title/name. */
    val tags: Set<String> get() = buildSet {
        add(name.lowercase())
        add(handle.lowercase())
        BotRoster.slug(title).takeIf { it.isNotBlank() }?.let { add(it); add(it.replace("-", "")) }
        BotRoster.slug(displayName).takeIf { it.isNotBlank() }?.let { add(it); add(it.replace("-", "")) }
    }
}

/** One `profiles.list` answer: the roster plus the install's `agent.bot_mode_protocol` switch. */
data class BotRosterSnapshot(
    val bots: List<BotProfile>,
    /** config.yaml `agent.bot_mode_protocol` (default on): the gateway injects the protocol. */
    val protocolEnabled: Boolean,
    val fetchedAt: Long,
) {
    /** The gate the gateway applies: any managed profile ⇒ Bot Chats carry `message_agent`. */
    val messagingArmed: Boolean get() = protocolEnabled && bots.any { it.managed }
    fun byName(name: String): BotProfile? = bots.firstOrNull { it.name == name }
}

object BotRoster {
    /** The canonical per-bot conversation title — the registry key, never localized. */
    const val CANONICAL_TITLE = "Bot Chat"

    /** A bot that wrote inside this window is "active now" (desktop's `ACTIVE_WINDOW_S`). */
    const val ACTIVE_WINDOW_MS = 90_000L

    /** Cron jobs a bot owns are namespaced `[bot:<name>] <routine>`. */
    fun routineTag(name: String): String = "[bot:$name]"

    private val ROUTINE_RE = Regex("^\\[bot:([a-z0-9][a-z0-9_-]*)]\\s*", RegexOption.IGNORE_CASE)

    /** The bot a job name is tagged for, or null for an untagged job. */
    fun routineOwner(jobName: String): String? =
        ROUTINE_RE.find(jobName.trim())?.groupValues?.get(1)?.lowercase()

    /** The routine's own name with the `[bot:x]` tag stripped. */
    fun routineLabel(jobName: String): String = jobName.trim().replace(ROUTINE_RE, "").ifBlank { jobName.trim() }

    /** Job names (from the cron page) that belong to [name], in the order given. */
    fun routinesOf(jobNames: List<String>, name: String): List<String> =
        jobNames.filter { routineOwner(it) == name.lowercase() }

    /** Whether a session row is a profile's canonical chat — the desktop's `isCanonicalBotChatHistory`. */
    fun isCanonicalRow(title: String?, rootTitle: String?): Boolean {
        val root = rootTitle.orEmpty().trim()
        val t = title.orEmpty().trim()
        return root == CANONICAL_TITLE || (root.isEmpty() && t == CANONICAL_TITLE)
    }

    /** `research-buddy` from "Research Buddy!" — the desktop's tag slug. Empty for empty input. */
    fun slug(text: String): String =
        text.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /** "mc-builder" → "Mc Builder"; "theo" → "Theo". A profile name shown before it has a title. */
    fun pretty(name: String): String =
        name.split('-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /** Profile names are slugs: lowercase, digits, `-` / `_`, starting alphanumeric. */
    fun validName(name: String): Boolean = Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(name)

    /**
     * Roster order: active first (busy or inside the window), then by last activity, newest
     * first; the default profile leads a tie so a fresh install reads "your agent, then the
     * rest". Hidden bots drop out unless [showHidden]; when shown they trail the list dimmed.
     */
    fun order(
        bots: List<BotProfile>,
        now: Long,
        busy: Set<String> = emptySet(),
        showHidden: Boolean = false,
    ): List<BotProfile> {
        val visible = bots.filter { showHidden || !it.hidden }
        return visible.sortedWith(
            compareBy<BotProfile> { it.hidden }
                .thenByDescending { isActive(it, now, busy) }
                .thenByDescending { it.canonical?.lastActive ?: 0L }
                .thenByDescending { it.isDefault }
                .thenBy { it.label.lowercase() },
        )
    }

    /** Active now: a live turn in its Bot Chat, or a last word inside [ACTIVE_WINDOW_MS]. */
    fun isActive(bot: BotProfile, now: Long, busy: Set<String> = emptySet()): Boolean {
        if (bot.name in busy) return true
        val last = bot.canonical?.lastActive ?: return false
        return last > 0L && now - last in 0..ACTIVE_WINDOW_MS
    }

    fun active(bots: List<BotProfile>, now: Long, busy: Set<String> = emptySet()): List<BotProfile> =
        bots.filter { !it.hidden && isActive(it, now, busy) }

    /** Search: name, handle, title, friendly name, description — any substring, case-blind. */
    fun matches(bot: BotProfile, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return bot.name.lowercase().contains(q) || bot.handle.lowercase().contains(q) ||
            bot.label.lowercase().contains(q) || bot.description.lowercase().contains(q)
    }

    /**
     * News: the Bot Chat moved after you last looked. The gateway keeps a read watermark for
     * sessions it lists, but a hidden cross-profile row never reaches the phone's list call,
     * so the phone keeps its own per-bot "looked at" stamp — [seenAt] by profile name.
     */
    fun unread(bot: BotProfile, seenAt: Map<String, Long>): Boolean {
        val last = bot.canonical?.lastActive ?: return false
        if (last <= 0L) return false
        return last > (seenAt[bot.name] ?: 0L)
    }

    private val MENTION_RE = Regex("(?:^|\\s)@([a-z0-9][a-z0-9_-]*)", RegexOption.IGNORE_CASE)

    /** Bots the text @-tags, resolved against the live roster (each at most once, text order). */
    fun mentions(text: String, bots: List<BotProfile>): List<BotProfile> {
        if (!text.contains('@')) return emptyList()
        val out = ArrayList<BotProfile>()
        for (m in MENTION_RE.findAll(text)) {
            val tag = m.groupValues[1].lowercase()
            val bot = bots.firstOrNull { tag in it.tags } ?: continue
            if (out.none { it.name == bot.name }) out += bot
        }
        return out
    }

    /**
     * The identification note the desktop's composer appends when a message @-tags a bot:
     * who the tag means, so the agent can hand off with `message_agent` — and never forward
     * the user's words verbatim. Empty when nothing resolved.
     */
    fun mentionNote(mentioned: List<BotProfile>): String {
        if (mentioned.isEmpty()) return ""
        val lines = mentioned.joinToString("; ") { bot ->
            val title = bot.title.ifBlank { bot.displayName }
            "@${bot.handle} = agent profile \"${bot.name}\"" + (if (title.isNotBlank()) " (\"$title\")" else "")
        }
        return "\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: " + lines +
            ". If they want one of these agents contacted, compose your own message and send it with your " +
            "message_agent tool; never forward the user’s text verbatim. If this session has no " +
            "message_agent tool, agent messaging is unavailable here — say so.]"
    }

    private val SLASH_NEW_RE = Regex("^/(new|reset)\\s*$")

    /**
     * The forever-chat rule: `/new` (or `/reset`) inside a canonical Bot Chat would fork the
     * relationship into a scratch session — the one thing Bot Mode promises never happens.
     * Rerouted to `/compact` (fresh working context, SAME conversation). Regular sessions on
     * the same profile keep full `/new` freedom. Returns the text to send, or null = unchanged.
     */
    fun reroute(text: String, inCanonicalChat: Boolean): String? =
        if (inCanonicalChat && SLASH_NEW_RE.matches(text.trim())) "/compact" else null

    /** The message a brand-new bot gets first, so it introduces itself (desktop's kickoff). */
    const val KICKOFF = "Hey, tell me about yourself!"
}

/** Where the gateway's JSON becomes roster rows — one place, tested, shared by both doors. */
object BotsJson {
    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** Epoch seconds (float or int) → millis. */
    private fun JsonObject.epochMs(key: String): Long {
        val p = this[key] as? JsonPrimitive ?: return 0L
        p.longOrNull?.let { return if (it > 100_000_000_000L) it else it * 1000L }
        val d = p.doubleOrNull ?: return 0L
        return (d * 1000).toLong()
    }

    fun chatRef(o: JsonObject?): BotChatRef? {
        o ?: return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return BotChatRef(
            id = id,
            resolvedId = o.str("resolved_id")?.takeIf { it.isNotBlank() } ?: id,
            preview = o.str("preview").orEmpty(),
            lastActive = o.epochMs("last_active"),
            messageCount = o["message_count"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /** One `profiles.list` row. */
    fun profile(o: JsonObject): BotProfile? {
        val name = o.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val meta = o.obj("ui_meta")?.obj("hermes-bots")
        return BotProfile(
            name = name,
            displayName = o.str("display_name").orEmpty().trim(),
            description = o.str("description").orEmpty().trim(),
            title = meta?.str("title").orEmpty().trim(),
            model = o.str("model").orEmpty(),
            provider = o.str("provider").orEmpty(),
            hasAvatar = o.bool("has_avatar"),
            isDefault = o.bool("is_default") || name == "default",
            managed = meta != null,
            hidden = meta?.bool("hidden") ?: false,
            canonical = chatRef(o.obj("canonical_session")),
            meta = meta,
        )
    }

    fun snapshot(result: JsonObject, now: Long): BotRosterSnapshot {
        val rows = (result["profiles"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::profile) }
            ?: emptyList()
        val protocol = result["bot_mode_protocol"]?.jsonPrimitive?.booleanOrNull ?: true
        return BotRosterSnapshot(bots = rows, protocolEnabled = protocol, fetchedAt = now)
    }

    /**
     * The `session.list {title:"Bot Chat"}` registry answer: the one row whose (root) title is
     * the canonical one, or null. Fails CLOSED on a malformed answer — a miss here mints a
     * duplicate forever-chat, so "unsure" must read as "not found" only when the list itself
     * parsed and was empty; callers treat a parse failure as an error, not a miss.
     */
    fun canonicalFromList(result: JsonObject): BotChatRef? {
        val rows = (result["sessions"] as? JsonArray) ?: return null
        for (el in rows) {
            val o = el as? JsonObject ?: continue
            if (BotRoster.isCanonicalRow(o.str("title"), o.str("root_title"))) return chatRef(o)
        }
        return null
    }

    /**
     * The `ui_meta.hermes-bots` block Keryx writes back: [existing] carried whole (the gateway
     * replaces the block, see [BotProfile.meta]), with only the keys Keryx owns overlaid. A
     * null [title] / [hidden] leaves that key as it was; a blank title removes it so the label
     * falls back to the profile's own names.
     */
    fun metaPatch(existing: JsonObject?, title: String?, hidden: Boolean?, now: Long): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        existing?.forEach { (k, v) -> m[k] = v }
        if (title != null) {
            if (title.isBlank()) m.remove("title") else m["title"] = JsonPrimitive(title.trim())
        }
        if (hidden != null) {
            if (hidden) m["hidden"] = JsonPrimitive(true) else m.remove("hidden")
        }
        // The desktop stamps a bot's birth once; a block Keryx creates gets the same stamp.
        if (!m.containsKey("created")) m["created"] = JsonPrimitive(now)
        return JsonObject(m)
    }
}
