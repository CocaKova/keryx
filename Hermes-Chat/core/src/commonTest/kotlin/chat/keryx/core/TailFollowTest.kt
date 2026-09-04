package chat.keryx.core

import chat.keryx.core.model.TailFollow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These pin the property the streaming reasoning pane failed: a reader who has scrolled up is
 * left where they are, and a reader at the bottom is carried along.
 */
class TailFollowTest {

    private val slop = 24

    @Test
    fun `a region with nothing to scroll is always at its tail`() {
        // Content shorter than its viewport: the first token must still be followed, or a short
        // think would never move at all.
        assertTrue(TailFollow.atTail(offset = 0, extent = 0, slopPx = slop))
        assertTrue(TailFollow.atTail(offset = 0, extent = -1, slopPx = slop))
    }

    @Test
    fun `the tail is the tail, and a hair above it`() {
        assertTrue(TailFollow.atTail(offset = 900, extent = 900, slopPx = slop))
        assertTrue(TailFollow.atTail(offset = 880, extent = 900, slopPx = slop))
        assertTrue(TailFollow.atTail(offset = 876, extent = 900, slopPx = slop))
    }

    @Test
    fun `reading back detaches`() {
        // The whole point: one flick up through a long think and the tail lets go.
        assertFalse(TailFollow.atTail(offset = 875, extent = 900, slopPx = slop))
        assertFalse(TailFollow.atTail(offset = 0, extent = 900, slopPx = slop))
    }

    @Test
    fun `a negative slop cannot widen the tail`() {
        assertFalse(TailFollow.atTail(offset = 899, extent = 900, slopPx = -50))
        assertTrue(TailFollow.atTail(offset = 900, extent = 900, slopPx = -50))
    }

    @Test
    fun `nothing moves under a finger`() {
        assertTrue(TailFollow.shouldFollow(following = true, gestureInFlight = false))
        assertFalse(TailFollow.shouldFollow(following = true, gestureInFlight = true))
        assertFalse(TailFollow.shouldFollow(following = false, gestureInFlight = false))
    }
}
