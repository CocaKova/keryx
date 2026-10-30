package chat.keryx.app

import chat.keryx.app.presentation.ui.components.RoomTransition
import chat.keryx.app.presentation.ui.components.RoomTransitionSpeed
import chat.keryx.app.presentation.ui.components.durationOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3.0 crossing speed: Normal is the designed crossing, and the others only stretch its clock. */
class RoomTransitionSpeedTest {
    @Test fun normalIsTheDesignedDuration() {
        RoomTransition.entries.forEach { assertEquals(durationOf(it), RoomTransitionSpeed.NORMAL.scaled(durationOf(it))) }
    }

    @Test fun slowIsLongerAndFastIsShorter() {
        RoomTransition.entries.forEach {
            val d = durationOf(it)
            assertTrue(RoomTransitionSpeed.SLOW.scaled(d) > d)
            assertTrue(RoomTransitionSpeed.FAST.scaled(d) < d)
        }
    }

    @Test fun unknownOrMissingKeyIsNormal() {
        assertEquals(RoomTransitionSpeed.NORMAL, RoomTransitionSpeed.of(null))
        assertEquals(RoomTransitionSpeed.NORMAL, RoomTransitionSpeed.of("warp"))
        RoomTransitionSpeed.entries.forEach { assertEquals(it, RoomTransitionSpeed.of(it.key)) }
    }
}
