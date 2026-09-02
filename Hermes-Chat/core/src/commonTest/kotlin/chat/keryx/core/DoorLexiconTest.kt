package chat.keryx.core

import chat.keryx.core.model.DoorLexicon
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The direct door must never say "room": a gateway session has no members, no invites and no
 * homeserver, and the one place it used to say it — the long-press menu — named a feature
 * (Quick Rooms) that only the Matrix door has.
 */
class DoorLexiconTest {

    private val allWords: (DoorLexicon) -> List<String> = { l ->
        listOf(
            l.noun, l.plural, l.pinnedHeader, l.listHeader, l.pinVerb, l.unpinVerb, l.pinHint,
            l.emptyList, l.emptyChat, l.shareTitle, l.openOneFirst,
        )
    }

    @Test
    fun directDoor_neverSaysRoom() {
        val words = allWords(DoorLexicon.DIRECT)
        for (w in words) assertFalse(w.contains("room", ignoreCase = true), "direct lexicon says room: $w")
    }

    @Test
    fun matrixDoor_neverSaysSession() {
        val words = allWords(DoorLexicon.MATRIX)
        for (w in words) assertFalse(w.contains("session", ignoreCase = true), "matrix lexicon says session: $w")
    }

    @Test
    fun forDoor_picksByTransport() {
        assertEquals(DoorLexicon.DIRECT, DoorLexicon.forDoor(direct = true))
        assertEquals(DoorLexicon.MATRIX, DoorLexicon.forDoor(direct = false))
    }

    @Test
    fun capitalNoun_isTitleCase() {
        assertEquals("Session", DoorLexicon.DIRECT.nounTitle)
        assertEquals("Room", DoorLexicon.MATRIX.nounTitle)
    }

    @Test
    fun directPin_explainsItself_matrixPinDoesNot() {
        assertTrue(DoorLexicon.DIRECT.pinHint.isNotBlank())
        assertTrue(DoorLexicon.MATRIX.pinHint.isBlank())
    }

    @Test
    fun hasUnread_readsEitherSignal() {
        val base = RoomProfile(id = "s", name = "n", type = RoomType.DIRECT_MESSAGE)
        assertFalse(base.hasUnread)
        assertTrue(base.copy(unreadCount = 2).hasUnread)
        assertTrue(base.copy(unread = true).hasUnread)
    }
}
