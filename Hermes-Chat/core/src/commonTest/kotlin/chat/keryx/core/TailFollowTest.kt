package chat.keryx.core

import chat.keryx.core.model.TailFollow
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ── lazy lists (2.16: Tap-In's rail) ──────────────────────────────────────────────────

    @Test
    fun `a lazy list is at its tail when the last item's end is in view`() {
        // Viewport ends at 1000; last item ends at 984 with 16 of bottom padding: exactly the end.
        assertTrue(TailFollow.lazyAtTail(lastVisibleIndex = 9, totalItems = 10, lastItemEnd = 984, afterPadding = 16, viewportEnd = 1000))
        // A hair past it still counts.
        assertTrue(TailFollow.lazyAtTail(9, 10, lastItemEnd = 1004, afterPadding = 16, viewportEnd = 1000, slopPx = slop))
    }

    @Test
    fun `a lazy list whose last item runs well past the viewport is not at its tail`() {
        // A long "Saying" block still growing below the fold.
        assertFalse(TailFollow.lazyAtTail(9, 10, lastItemEnd = 1600, afterPadding = 16, viewportEnd = 1000, slopPx = slop))
    }

    @Test
    fun `a lazy list whose last item is not laid out is far from its tail`() {
        assertFalse(TailFollow.lazyAtTail(lastVisibleIndex = 4, totalItems = 10, lastItemEnd = 900, afterPadding = 0, viewportEnd = 1000, slopPx = slop))
        assertEquals(null, TailFollow.lazyOvershoot(4, 10, 900, 0, 1000))
    }

    @Test
    fun `an empty or short lazy list is always at its tail`() {
        assertTrue(TailFollow.lazyAtTail(-1, 0, 0, 0, 1000))
        assertTrue(TailFollow.lazyAtTail(2, 3, lastItemEnd = 400, afterPadding = 16, viewportEnd = 1000))
    }
}
