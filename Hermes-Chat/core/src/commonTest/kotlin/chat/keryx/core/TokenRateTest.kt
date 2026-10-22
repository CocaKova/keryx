package chat.keryx.core

import chat.keryx.core.model.LiveRate
import chat.keryx.core.transport.StreamRateMeter
import chat.keryx.core.model.TokenRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Honest tok/s (2.16): a token figure is a token figure only when the gateway counted it. */
class TokenRateTest {

    @Test
    fun uncalibratedLiveRateReadsTokensAtTheDefaultRatio() {
        // 2.16.1: tokens, not characters, from the first turn; calibration refines the ratio.
        assertEquals("≈40 tok/s", TokenRate.liveLabel(160f, charsPerToken = 0f))
        assertEquals("≈40 tok/s", TokenRate.liveLabel(160f, charsPerToken = 4f))
        assertEquals("≈50 tok/s", TokenRate.liveLabel(160f, charsPerToken = 3.2f))
    }

    @Test
    fun nothingWorthSayingIsNull() {
        assertNull(TokenRate.liveLabel(0f, 4f))
        assertNull(TokenRate.liveLabel(2f, 4f)) // half a token a second
        assertNull(TokenRate.liveLabel(3f, 0f)) // under a token a second at the default ratio
    }

    @Test
    fun aStallFallsTowardZeroInsteadOfFreezing() {
        val at = 10_000L
        assertEquals(100f, TokenRate.decayed(100f, at, at + 1_000)) // held
        val later = TokenRate.decayed(100f, at, at + TokenRate.HOLD_MS + 900)
        assertTrue(later in 49f..51f, "one half-life after the hold: $later")
        assertEquals(0f, TokenRate.decayed(100f, at, at + 15_000))
        assertNull(LiveRate(100f, at, 4f).label(at + 15_000))
        assertEquals("≈25 tok/s", LiveRate(100f, at, 4f).label(at + 200))
    }

    @Test
    fun calibrationMeasuresAgainstTheRealCount() {
        assertEquals(3.5f, TokenRate.calibrate(0f, chars = 3_500, tokens = 1_000))
        // Blends with what it knew.
        assertEquals(3.75f, TokenRate.calibrate(4f, chars = 3_500, tokens = 1_000))
        // Too short to say anything.
        assertEquals(4f, TokenRate.calibrate(4f, chars = 40, tokens = 10))
        // Implausible (a turn that was mostly tool-call arguments): ignored.
        assertEquals(4f, TokenRate.calibrate(4f, chars = 100, tokens = 1_000))
        assertEquals(0f, TokenRate.calibrate(0f, chars = 100_000, tokens = 1_000))
    }

    @Test
    fun settledIsRealTokensOverFlowingTime() {
        assertEquals(40.0, TokenRate.settled(tokens = 400, activeMs = 10_000))
        assertNull(TokenRate.settled(tokens = null, activeMs = 10_000))
        assertNull(TokenRate.settled(tokens = 5, activeMs = 10_000))
        assertNull(TokenRate.settled(tokens = 400, activeMs = 0))
        assertEquals("40 tok/s", TokenRate.settledLabel(40.0))
        assertEquals("7.5 tok/s", TokenRate.settledLabel(7.46))
    }

    @Test
    fun meterReadsCharactersOverTimeForASteadyStream() {
        val m = StreamRateMeter()
        for (i in 0..40) m.onChars(10, 1_000L + 100L * i) // 10 chars every 100 ms for 4 s
        assertEquals(100f, m.cps)
        assertEquals(410L, m.chars)
        assertEquals(4_000L, m.activeMs)
    }

    @Test
    fun burstsWithTheSameMeanReadTheSameRate() {
        // 30 characters every 300 ms, delivered as three frames 20 ms apart: 100 chars/s, the
        // same as the steady stream. The old EMA of per-frame rates read this as ~415.
        val m = StreamRateMeter()
        for (k in 0 until 14) for (j in 0 until 3) m.onChars(10, 1_000L + 300L * k + 20L * j)
        assertEquals(100f, m.cps)
    }

    @Test
    fun aToolGapStartsANewWindowAndIsNotFlowingTime() {
        val m = StreamRateMeter()
        for (i in 0..10) m.onChars(10, 1_000L + 100L * i) // 100 chars/s for a second
        for (i in 0..10) m.onChars(10, 9_000L + 200L * i) // after a 7 s tool run: 50 chars/s
        assertEquals(50f, m.cps) // the pause neither dilutes the rate nor carries the old one
        assertEquals(3_000L, m.activeMs) // 1 s before + 2 s after; the 7 s gap is not counted
        assertEquals(220L, m.chars)
    }

    @Test
    fun tooShortASpanSaysNothingYet() {
        val m = StreamRateMeter()
        m.onChars(10, 1_000)
        m.onChars(10, 1_100) // 100 ms is noise, not a rate
        assertEquals(0f, m.cps)
        m.reset()
        assertEquals(0L, m.chars)
        assertEquals(0f, m.cps)
    }

    @Test
    fun onlyAOneCallTurnCarriesAPerReplyRate() {
        assertTrue(TokenRate.singleCall(calls = 1, usedTools = false))
        assertTrue(TokenRate.singleCall(calls = 1, usedTools = true)) // the call count decides
        assertTrue(!TokenRate.singleCall(calls = 3, usedTools = true)) // a tool loop
        assertTrue(!TokenRate.singleCall(calls = 2, usedTools = false))
        assertTrue(TokenRate.singleCall(calls = null, usedTools = false)) // no count: no tool ran
        assertTrue(!TokenRate.singleCall(calls = null, usedTools = true))
    }

    @Test
    fun aFinishedAnswerIsFoundByIdOrByItsText() {
        val key = TokenRate.answerKey("room", "  The answer.\n")
        val rates = mapOf("live-answer-1" to 41.0, key to 41.0)
        assertEquals(41.0, TokenRate.lookup(rates, "live-answer-1", "room", ""))
        assertEquals(41.0, TokenRate.lookup(rates, "server-row-9", "room", "The answer."))
        assertNull(TokenRate.lookup(rates, "server-row-9", "other", "The answer."))
        assertNull(TokenRate.lookup(emptyMap(), "x", "room", "The answer."))
    }
}

class CompactionDrainTest {
    @Test
    fun theRingDrainsOverTheTypicalLengthAndHoldsAtTheFloor() {
        val g = chat.keryx.core.model.CompactionGauge
        assertEquals(0.9f, g.drained(0.9f, since = null, typicalSeconds = 30, nowMs = 5_000))
        assertEquals(0.9f, g.drained(0.9f, since = 1_000, typicalSeconds = 30, nowMs = 1_000))
        val half = g.drained(0.9f, since = 0, typicalSeconds = 10, nowMs = 5_000)
        assertTrue(half in 0.48f..0.50f, "half way: $half")
        assertEquals(g.DRAIN_FLOOR, g.drained(0.9f, since = 0, typicalSeconds = 10, nowMs = 60_000))
        // A ring already below the floor does not fill up to drain.
        assertEquals(0.05f, g.drained(0.05f, since = 0, typicalSeconds = 10, nowMs = 60_000))
    }
}
