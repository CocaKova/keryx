package chat.keryx.core

import chat.keryx.core.model.LiveRate
import chat.keryx.core.model.StreamRateMeter
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
    fun meterCountsFlowingTimeAndSkipsGaps() {
        val m = StreamRateMeter()
        m.onChars(10, 1_000)
        m.onChars(10, 1_100)
        m.onChars(10, 1_200)
        m.onChars(10, 9_200) // a tool ran for eight seconds: not generation time
        m.onChars(10, 9_300)
        assertEquals(50L, m.chars)
        assertEquals(300L, m.activeMs)
        assertEquals(100f, m.cps) // 10 chars per 100 ms, the gap never sampled
        m.reset()
        assertEquals(0L, m.chars)
        assertEquals(0f, m.cps)
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
