package chat.keryx.core

import chat.keryx.core.protocol.ForeignFollow
import chat.keryx.core.protocol.ForeignFollow.Action
import chat.keryx.core.protocol.ForeignFollow.State
import chat.keryx.core.protocol.SessionPulse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A room open on the phone must follow a turn that another door started. These pin the
 * decision, not the timer: when the gateway's row count moves past what the phone rendered,
 * and no local turn explains it, the transcript is re-read.
 */
class ForeignFollowTest {

    private fun pulse(count: Long, ended: Boolean = false, lastActiveMs: Long = 1_000L, working: Boolean = false) =
        SessionPulse(messageCount = count, lastActiveMs = lastActiveMs, ended = ended, working = working)

    @Test
    fun `first pulse is a baseline, never a re-read`() {
        val (s, a) = ForeignFollow.step(State(), pulse(105), busyLocally = false)
        assertEquals(Action.REBASELINE, a)
        assertEquals(State(seenCount = 105, rebaseline = false), s)
    }

    @Test
    fun `a foreign turn growing the row count re-reads the transcript`() {
        val base = State(seenCount = 105, rebaseline = false)
        val (s, a) = ForeignFollow.step(base, pulse(108), busyLocally = false)
        assertEquals(Action.REHYDRATE, a)
        assertEquals(108, s.seenCount)
        // Same count again: nothing to do.
        assertEquals(Action.HOLD, ForeignFollow.step(s, pulse(108), busyLocally = false).second)
    }

    @Test
    fun `a local turn is not foreign - its rows re-baseline instead of re-reading`() {
        var state = State(seenCount = 10, rebaseline = false)
        // Busy: the count is ignored entirely, even though it grew.
        val busy = ForeignFollow.step(state, pulse(14), busyLocally = true)
        assertEquals(Action.HOLD, busy.second)
        state = busy.first
        // First quiet pulse after our own turn: baseline to what the local runtime rendered.
        val quiet = ForeignFollow.step(state, pulse(16), busyLocally = false)
        assertEquals(Action.REBASELINE, quiet.second)
        assertEquals(16, quiet.first.seenCount)
        // Only growth AFTER that is someone else's.
        assertEquals(Action.REHYDRATE, ForeignFollow.step(quiet.first, pulse(17), busyLocally = false).second)
    }

    @Test
    fun `a shrinking count is a new baseline, not a re-read`() {
        val (s, a) = ForeignFollow.step(State(seenCount = 40, rebaseline = false), pulse(30), busyLocally = false)
        assertEquals(Action.REBASELINE, a)
        assertEquals(30, s.seenCount)
    }

    @Test
    fun `a failed fetch holds without losing the baseline`() {
        val base = State(seenCount = 40, rebaseline = false)
        assertEquals(base to Action.HOLD, ForeignFollow.step(base, null, busyLocally = false))
    }

    @Test
    fun `polling follows the session's own tempo`() {
        val now = 10_000_000L
        assertEquals(ForeignFollow.HOT_POLL_MS, ForeignFollow.nextDelayMs(pulse(5, lastActiveMs = now - 1_000), now, Action.HOLD))
        assertEquals(ForeignFollow.HOT_POLL_MS, ForeignFollow.nextDelayMs(pulse(5, lastActiveMs = 0), now, Action.REHYDRATE))
        assertEquals(ForeignFollow.COLD_POLL_MS, ForeignFollow.nextDelayMs(pulse(5, lastActiveMs = 0), now, Action.HOLD))
        assertEquals(ForeignFollow.COLD_POLL_MS, ForeignFollow.nextDelayMs(pulse(5, ended = true, lastActiveMs = now), now, Action.HOLD))
        assertEquals(ForeignFollow.WARM_POLL_MS, ForeignFollow.nextDelayMs(null, now, Action.HOLD))
    }

    /**
     * A foreign turn's long silent tool call writes no rows, so row growth alone would let
     * the working banner settle. The gateway's own mid-turn label carries it — bounded by
     * the heartbeat, so a label a crashed run left behind ages out instead of lighting forever.
     */
    @Test
    fun `the gateway's mid-turn label means live, until it goes stale or the session ends`() {
        val now = 10_000_000L
        assertTrue(ForeignFollow.isLive(pulse(5, working = true, lastActiveMs = now - 30_000), now))
        assertFalse(ForeignFollow.isLive(pulse(5, working = false, lastActiveMs = now - 30_000), now))
        assertFalse(ForeignFollow.isLive(pulse(5, working = true, ended = true, lastActiveMs = now), now))
        assertFalse(ForeignFollow.isLive(pulse(5, working = true, lastActiveMs = now - ForeignFollow.RECENT_MS - 1), now))
        assertFalse(ForeignFollow.isLive(null, now))
        // Live = tail it like a stream.
        assertEquals(ForeignFollow.HOT_POLL_MS, ForeignFollow.nextDelayMs(pulse(5, working = true, lastActiveMs = now - 30_000), now, Action.HOLD))
    }
}
