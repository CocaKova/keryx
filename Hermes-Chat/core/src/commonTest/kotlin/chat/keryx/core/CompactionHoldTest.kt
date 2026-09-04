package chat.keryx.core

import chat.keryx.core.model.CompactionHold
import chat.keryx.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gateway announces a compaction once and then goes quiet for as long as the summary model
 * runs. These pin the one property that failure depended on: the hold outlives the ordinary
 * no-reply window by a wide margin, so the working banner cannot settle in the middle of a
 * compaction that is still going.
 */
class CompactionHoldTest {

    // ChatViewModel.NO_REPLY_MS — the window the hold has to beat.
    private val noReplyMs = 240_000L

    @Test
    fun `an unarmed hold never holds`() {
        assertFalse(CompactionHold.holds(null, now = 1_000L))
        assertEquals(0L, CompactionHold.remaining(null, now = 1_000L))
    }

    @Test
    fun `the hold outlasts the no-reply window`() {
        val start = 10_000L
        // The exact shape of the 09-03 report: four minutes in, 2.8.2 cleared the banner.
        assertTrue(CompactionHold.holds(start, now = start + noReplyMs + 1))
        // Twelve minutes in — the compaction that was still running when this was diagnosed.
        assertTrue(CompactionHold.holds(start, now = start + 12 * 60_000L))
        assertTrue(CompactionHold.CEILING_MS > noReplyMs)
    }

    @Test
    fun `the ceiling is a bound on lost signals, not on work`() {
        val start = 0L
        assertTrue(CompactionHold.holds(start, now = CompactionHold.CEILING_MS - 1))
        assertFalse(CompactionHold.holds(start, now = CompactionHold.CEILING_MS))
        assertFalse(CompactionHold.holds(start, now = CompactionHold.CEILING_MS + 60_000L))
    }

    @Test
    fun `remaining counts down and floors at zero`() {
        val start = 5_000L
        assertEquals(CompactionHold.CEILING_MS, CompactionHold.remaining(start, now = start))
        assertEquals(CompactionHold.CEILING_MS - 1_000L, CompactionHold.remaining(start, now = start + 1_000L))
        assertEquals(0L, CompactionHold.remaining(start, now = start + CompactionHold.CEILING_MS))
        assertEquals(0L, CompactionHold.remaining(start, now = start + CompactionHold.CEILING_MS + 999_999L))
    }

    @Test
    fun `the status the gateway actually sent arms the hold`() {
        // The two lines from the 09-03 sessions, verbatim from the dashboard journal.
        val preflight = SessionStatus.of(
            "lifecycle",
            "📦 Preflight compression: ~214,173 tokens >= 124,212 threshold. This may take a moment.",
        )
        val compacting = SessionStatus.of(
            "lifecycle",
            "🗜️ Compacting context — summarizing earlier conversation so I can continue...",
        )
        assertTrue(preflight.isCompacting)
        assertTrue(compacting.isCompacting)
        // …and a status that is not a compaction must not arm it.
        assertFalse(SessionStatus.of("lifecycle", "Thinking…").isCompacting)
    }
}
