package chat.keryx.core.model

/**
 * The reminders an empty chat leads with (2.17.3): things that already want you, from what the
 * app knows — no model call. At most [MAX], most pressing first: missions waiting on you, then
 * conversations with replies you have not read, then the next routine due within [ROUTINE_HORIZON_MS].
 */
object StarterReminders {
    const val MAX = 2
    const val ROUTINE_HORIZON_MS = 12 * 3_600_000L

    sealed class Action {
        data object Missions : Action()
        data object Runs : Action()
        data class Room(val id: String, val title: String) : Action()
    }

    data class Reminder(val label: String, val action: Action)

    /** A scheduled job as the picker needs it: [nextRunIso] as the gateway gives it. */
    data class Routine(val name: String, val nextRunIso: String?, val enabled: Boolean)

    fun pick(
        needsYou: Int,
        rooms: List<RoomProfile>,
        currentRoomId: String?,
        routines: List<Routine>,
        nowMs: Long,
    ): List<Reminder> {
        val out = mutableListOf<Reminder>()
        if (needsYou > 0) {
            out += Reminder("$needsYou mission${if (needsYou == 1) "" else "s"} need${if (needsYou == 1) "s" else ""} you", Action.Missions)
        }
        val unread = rooms.filter { it.hasUnread && it.id != currentRoomId }.sortedByDescending { it.timestamp }
        when (unread.size) {
            0 -> Unit
            1 -> out += Reminder("Unread: ${unread[0].name}", Action.Room(unread[0].id, unread[0].name))
            else -> out += Reminder("${unread.size} chats with unread replies", Action.Room(unread[0].id, unread[0].name))
        }
        routines
            .filter { it.enabled }
            .mapNotNull { r -> CronTriage.parseIso(r.nextRunIso)?.let { at -> r to at } }
            .filter { (_, at) -> at >= nowMs && at - nowMs <= ROUTINE_HORIZON_MS }
            .minByOrNull { (_, at) -> at }
            ?.let { (r, _) ->
                val label = BotRoster.routineLabel(r.name).ifBlank { r.name }
                val whenText = r.nextRunIso?.let { CronHumanize.nextIn(it, nowMs) }.orEmpty()
                out += Reminder(listOf(label, whenText).filter { it.isNotBlank() }.joinToString(" · "), Action.Runs)
            }
        return out.take(MAX)
    }
}
