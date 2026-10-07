package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * Bot Mode group chats (2.18): a room of 2–6 bots on this gateway, hosted by the gateway itself
 * (`groups.*`). The gateway owns everything that matters — the roster, the monotonic room log,
 * and the driver that runs each member's turn, which keeps going with the phone closed. Keryx
 * reads the log, appends the user's messages, and renders; nothing here holds room state the
 * gateway does not.
 */
data class GroupMember(
    val memberId: String,
    val profile: String,
    val handle: String,
    val displayName: String = "",
) {
    val label: String get() = displayName.ifBlank { BotRoster.pretty(profile) }
}

data class GroupRoom(
    val roomId: String,
    val name: String,
    val members: List<GroupMember>,
    val latestSeq: Long = 0L,
    /** Epoch millis. */
    val updatedAt: Long = 0L,
    val disbanded: Boolean = false,
) {
    fun member(id: String?): GroupMember? = members.firstOrNull { it.memberId == id }
}

data class GroupEvent(
    val seq: Long,
    val eventId: String,
    val kind: String,
    val actorKind: String,
    val actorId: String,
    val payload: JsonObject,
    /** Epoch millis. */
    val createdAt: Long,
) {
    fun str(key: String): String? = (payload[key] as? JsonPrimitive)?.contentOrNull
}

data class GroupLogPage(val events: List<GroupEvent>, val latestSeq: Long, val hasMore: Boolean)

/** An approval a member's turn is waiting on — answered with `groups.approve`. */
data class GroupApproval(
    val memberId: String,
    val taskId: String,
    val executionGeneration: Int,
    val requestId: String,
    val description: String,
)

/** `HostedRoomService.status`: whether turns are running, and what is waiting on the user. */
data class GroupDriver(
    val running: Boolean = false,
    val working: Boolean = false,
    val blocked: Boolean = false,
    val approvals: List<GroupApproval> = emptyList(),
    /** Task ids the driver could not settle and will retry only when the user says so. */
    val retries: List<String> = emptyList(),
)

/** What one row of a group transcript shows. */
sealed class GroupLine {
    abstract val seq: Long
    abstract val at: Long

    data class Mine(override val seq: Long, override val at: Long, val text: String, val threadId: String) : GroupLine()

    data class Member(
        override val seq: Long,
        override val at: Long,
        val memberId: String,
        val member: GroupMember?,
        val text: String,
        val threadId: String,
    ) : GroupLine() {
        val name: String get() = member?.label ?: memberId
    }

    /** Room machinery worth a line: created, renamed, a member that failed or could not run. */
    data class Note(override val seq: Long, override val at: Long, val text: String, val warn: Boolean = false) : GroupLine()
}

object GroupChats {
    const val MIN_MEMBERS = 2
    const val MAX_MEMBERS = 6

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    /** The gateway stamps seconds (a float); anything already in millis passes through. */
    private fun JsonObject.epochMs(key: String): Long {
        val p = this[key] as? JsonPrimitive ?: return 0L
        val d = p.doubleOrNull ?: return 0L
        return if (d > 100_000_000_000.0) d.toLong() else (d * 1000).toLong()
    }

    // ---- wire → model -------------------------------------------------------------------------

    fun room(o: JsonObject): GroupRoom? {
        val id = o.str("room_id") ?: return null
        val members = (o["members"] as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val profile = m.str("profile") ?: return@mapNotNull null
            GroupMember(
                memberId = m.str("member_id") ?: profile,
                profile = profile,
                handle = m.str("handle") ?: profile,
                displayName = m.str("display_name").orEmpty(),
            )
        }
        return GroupRoom(
            roomId = id,
            name = o.str("name").orEmpty().ifBlank { "Group chat" },
            members = members,
            latestSeq = o.long("latest_seq") ?: 0L,
            updatedAt = o.epochMs("updated_at"),
            disbanded = o["disbanded_at"].let { it != null && it !is kotlinx.serialization.json.JsonNull },
        )
    }

    fun rooms(result: JsonObject): List<GroupRoom> =
        (result["rooms"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::room) }.filterNot { it.disbanded }

    fun event(o: JsonObject): GroupEvent? {
        val actor = o["actor"] as? JsonObject
        return GroupEvent(
            seq = o.long("seq") ?: return null,
            eventId = o.str("event_id").orEmpty(),
            kind = o.str("kind") ?: return null,
            actorKind = actor?.str("kind").orEmpty(),
            actorId = actor?.str("id").orEmpty(),
            payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            createdAt = o.epochMs("created_at"),
        )
    }

    fun page(result: JsonObject): GroupLogPage = GroupLogPage(
        events = (result["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::event) },
        latestSeq = result.long("latest_seq") ?: 0L,
        hasMore = result.bool("has_more"),
    )

    fun driver(result: JsonObject): GroupDriver {
        val d = result["driver_status"] as? JsonObject ?: return GroupDriver()
        val actions = (d["pending_actions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return GroupDriver(
            running = d.bool("running"),
            working = d.bool("working"),
            blocked = d.bool("blocked"),
            // The driver's action: `{kind, member_id, task_id, execution_generation, request_id,
            // approval: {command?, description?, choices: [once, deny]}}`.
            approvals = actions.filter { it.str("kind") == "approval" }.mapNotNull { a ->
                val detail = a["approval"] as? JsonObject
                GroupApproval(
                    memberId = a.str("member_id") ?: return@mapNotNull null,
                    taskId = a.str("task_id") ?: return@mapNotNull null,
                    executionGeneration = a.long("execution_generation")?.toInt() ?: 0,
                    requestId = a.str("request_id") ?: detail?.str("request_id").orEmpty(),
                    description = detail?.str("command") ?: detail?.str("description") ?: "A command needs your approval",
                )
            },
            retries = actions.filter { it.str("kind") == "retry" }.mapNotNull { it.str("task_id") },
        )
    }

    // ---- log → transcript ----------------------------------------------------------------------

    fun lines(events: List<GroupEvent>, room: GroupRoom?): List<GroupLine> = events.mapNotNull { e ->
        when (e.kind) {
            "message.user" -> GroupLine.Mine(e.seq, e.createdAt, e.str("text").orEmpty(), e.str("thread_id").orEmpty())
            "message.member" -> {
                val text = e.str("text").orEmpty()
                // A member that chose silence keeps its turn but shows nothing (the Bot Chat rule).
                if (text.isBlank() || SilenceTokens.isSilent(text)) return@mapNotNull null
                val id = e.str("member_id") ?: e.actorId
                GroupLine.Member(e.seq, e.createdAt, id, room?.member(id), text, e.str("thread_id").orEmpty())
            }
            "turn.failed" -> {
                val who = room?.member(e.str("member_id"))?.label ?: e.str("member_id") ?: "A member"
                val why = e.str("error")?.lineSequence()?.firstOrNull()?.take(160)?.ifBlank { null }
                GroupLine.Note(e.seq, e.createdAt, "$who hit an error" + (why?.let { " — $it" } ?: ""), warn = true)
            }
            "turn.deferred" -> {
                val who = room?.member(e.str("member_id"))?.label ?: "A member"
                GroupLine.Note(e.seq, e.createdAt, "$who couldn't run this turn", warn = true)
            }
            "room.created" -> GroupLine.Note(e.seq, e.createdAt, "Room created")
            "room.renamed" -> GroupLine.Note(e.seq, e.createdAt, "Renamed to \"${e.str("name").orEmpty()}\"")
            "room.members_changed" -> GroupLine.Note(e.seq, e.createdAt, "Members changed")
            "room.stop_requested" -> GroupLine.Note(e.seq, e.createdAt, "Stopped")
            else -> null // turn.started / settled / cancelled, room.activity, authority.*: state, not lines
        }
    }

    /** Members with a turn started and not yet settled, failed, cancelled or deferred. */
    fun working(events: List<GroupEvent>): Set<String> {
        val open = LinkedHashMap<String, String>() // task_id -> member_id
        for (e in events) {
            val task = e.str("task_id") ?: continue
            when (e.kind) {
                "turn.started" -> e.str("member_id")?.let { open[task] = it }
                "turn.settled", "turn.failed", "turn.cancelled", "turn.deferred" -> open.remove(task)
            }
        }
        return open.values.toSet()
    }

    /**
     * Whether the room still owes a reply: the newest user message has no `room.activity`
     * (settled/bounded) naming it and no stop after it. The gateway logs a turn only once it ends
     * (message + `turn.settled`, never `turn.started`), so [working] alone stays empty mid-turn.
     */
    fun discussionOpen(events: List<GroupEvent>): Boolean {
        val asked = events.lastOrNull { it.kind == "message.user" } ?: return false
        return events.none { e ->
            e.seq > asked.seq && (e.kind == "room.stop_requested" ||
                (e.kind == "room.activity" && e.str("discussion_event_id") == asked.eventId))
        }
    }

    /** The row's second line in a room list: the newest thing said, with who said it. */
    fun preview(lines: List<GroupLine>): String? = lines.lastOrNull { it !is GroupLine.Note }?.let {
        when (it) {
            is GroupLine.Mine -> "You: " + it.text
            is GroupLine.Member -> it.name + ": " + it.text
            is GroupLine.Note -> it.text
        }
    }?.lineSequence()?.firstOrNull()?.take(140)

    // ---- model → wire --------------------------------------------------------------------------

    /** The roster `groups.create` takes: one local member per bot, keyed by profile. */
    fun membersFor(bots: List<BotProfile>): List<JsonObject> = bots.map { b ->
        JsonObject(
            mapOf(
                "member_id" to JsonPrimitive(b.name),
                "profile" to JsonPrimitive(b.name),
                "handle" to JsonPrimitive(handleOf(b)),
                "display_name" to JsonPrimitive(b.label),
            ),
        )
    }

    /** A member's @-handle: the bot's own (the default profile is @hermes), reduced to the
     *  identifier characters the gateway accepts. */
    fun handleOf(bot: BotProfile): String =
        bot.handle.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { bot.name }

    /** A fresh client id for a room, a thread, or a send's retry key (`[A-Za-z0-9._:-]`). */
    fun newId(prefix: String, random: Random = Random.Default): String =
        prefix + "-" + (1..20).joinToString("") { "0123456789abcdefghijklmnopqrstuvwxyz"[random.nextInt(36)].toString() }

    fun canCreate(selected: Int): Boolean = selected in MIN_MEMBERS..MAX_MEMBERS
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
