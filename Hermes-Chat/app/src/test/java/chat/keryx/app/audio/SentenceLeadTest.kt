package chat.keryx.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceLeadTest {
    private val base = PcmPlayer.LEAD_MS
    private val cap = PcmPlayer.MAX_PREBUFFER_MS

    @Test
    fun `server keeping up costs one breath`() {
        assertEquals(base, SentenceLead.neededMs(base, rate = 1.3, remainingMs = 6_000, dryCount = 0, maxMs = cap))
        assertEquals(base, SentenceLead.neededMs(base, rate = 1.0, remainingMs = 6_000, dryCount = 0, maxMs = cap))
    }

    @Test
    fun `a slow server holds the projected shortfall at the head`() {
        // 145-char sentence, 11.3 s projected, arriving at 0.44× → 6.3 s shortfall, capped.
        assertEquals(cap, SentenceLead.neededMs(base, rate = 0.44, remainingMs = 11_300, dryCount = 0, maxMs = cap))
        // 3 s left at 0.8× → 600 ms + breath.
        assertEquals(850L, SentenceLead.neededMs(base, rate = 0.8, remainingMs = 3_000, dryCount = 0, maxMs = cap))
    }

    @Test
    fun `the 09-05 storm - an exhausted estimate no longer re-holds 265ms forever`() {
        // The estimate says nothing is left, the server is at 0.45×, the sentence just ran dry.
        val first = SentenceLead.neededMs(base, rate = 0.45, remainingMs = 0, dryCount = 1, maxMs = cap)
        val second = SentenceLead.neededMs(base, rate = 0.45, remainingMs = 0, dryCount = 2, maxMs = cap)
        val third = SentenceLead.neededMs(base, rate = 0.45, remainingMs = 0, dryCount = 3, maxMs = cap)
        assertEquals(1_050L, first)   // 1 s assumed × 0.55 + 500
        assertEquals(2_100L, second)  // 2 s assumed × 0.55 + 1000
        assertEquals(cap, third)
        assertTrue(first > base * 2)
    }

    @Test
    fun `a delivery gap at real time is bridged by time, not rate`() {
        // Sentence 13 of 09-05: rate read ~1.0 across the sentence, yet it ran dry every 5 s.
        assertEquals(500L, SentenceLead.neededMs(base, rate = 1.05, remainingMs = 0, dryCount = 1, maxMs = cap))
        assertEquals(1_000L, SentenceLead.neededMs(base, rate = 1.05, remainingMs = 0, dryCount = 2, maxMs = cap))
    }

    @Test
    fun `an estimate that still has audio left wins over the assumption`() {
        // 6 s projected to come at 0.5× → 3 s shortfall, capped — not the 1 s assumption.
        assertEquals(cap, SentenceLead.neededMs(base, rate = 0.5, remainingMs = 6_000, dryCount = 1, maxMs = cap))
    }

    @Test
    fun `never past the cap`() {
        assertEquals(cap, SentenceLead.neededMs(base, rate = 0.1, remainingMs = 60_000, dryCount = 6, maxMs = cap))
    }
}
