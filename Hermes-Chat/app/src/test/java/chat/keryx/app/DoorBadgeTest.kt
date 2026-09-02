package chat.keryx.app

import chat.keryx.app.presentation.ui.DoorBadge
import org.junit.Assert.assertEquals
import org.junit.Test

/** The drawer door's count badge speaks the room list's grammar: a number, capped at 99+. */
class DoorBadgeTest {
    @Test fun countsReadAsThemselves() {
        assertEquals("1", DoorBadge.label(1))
        assertEquals("42", DoorBadge.label(42))
        assertEquals("99", DoorBadge.label(99))
    }

    @Test fun pastNinetyNineTheBadgeStopsCounting() {
        assertEquals("99+", DoorBadge.label(100))
        assertEquals("99+", DoorBadge.label(4_000))
    }
}
