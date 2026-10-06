package chat.keryx.core

import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.StarterReminders
import chat.keryx.core.model.StarterReminders.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class StarterRemindersTest {
    private val now = 1_800_000_000_000L
    private fun room(id: String, unread: Boolean, ts: Long = 0) =
        RoomProfile(id = id, name = id.uppercase(), type = RoomType.DIRECT_MESSAGE, timestamp = ts, unread = unread)
    private fun iso(ms: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toString()

    @Test fun nothingPendingMeansNoReminders() {
        assertEquals(emptyList(), StarterReminders.pick(0, listOf(room("a", false)), null, emptyList(), now))
    }

    @Test fun missionsLeadThenUnreadAndAtMostTwo() {
        val r = StarterReminders.pick(
            needsYou = 2,
            rooms = listOf(room("a", true, 1), room("b", true, 5)),
            currentRoomId = null,
            routines = listOf(StarterReminders.Routine("[bot:juno] Inbox", iso(now + 3_600_000), true)),
            nowMs = now,
        )
        assertEquals(listOf("2 missions need you", "2 chats with unread replies"), r.map { it.label })
        assertEquals(Action.Room("b", "B"), r[1].action)
    }

    @Test fun theOpenChatIsNotItsOwnReminder() {
        val r = StarterReminders.pick(0, listOf(room("a", true)), "a", emptyList(), now)
        assertEquals(emptyList(), r)
    }

    @Test fun onlyAnEnabledRoutineDueSoonCounts() {
        val routines = listOf(
            StarterReminders.Routine("Far", iso(now + 30 * 3_600_000L), true),
            StarterReminders.Routine("Paused", iso(now + 600_000), false),
            StarterReminders.Routine("[bot:juno] Inbox summary", iso(now + 2 * 3_600_000L), true),
        )
        val r = StarterReminders.pick(0, emptyList(), null, routines, now)
        assertEquals(listOf("Inbox summary · next in 2h"), r.map { it.label })
        assertEquals(Action.Runs, r[0].action)
    }
}
