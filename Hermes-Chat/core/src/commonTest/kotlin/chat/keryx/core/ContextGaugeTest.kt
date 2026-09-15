package chat.keryx.core

import chat.keryx.core.model.SessionMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextGaugeTest {
    @Test
    fun aRealReadingIsThePair() {
        assertEquals(84_000L to 128_000L, SessionMeta(contextUsed = 84_000, contextMax = 128_000).contextGauge)
    }

    @Test
    fun halfAReadingIsUnknownNotEmpty() {
        assertNull(SessionMeta().contextGauge)
        assertNull(SessionMeta(contextUsed = 12_000).contextGauge)
        assertNull(SessionMeta(contextMax = 128_000).contextGauge)
        // The gateway's own "compaction just ran" sentinel never leaks as a gauge.
        assertNull(SessionMeta(contextUsed = -1, contextMax = 128_000).contextGauge)
    }

    @Test
    fun aSeedLightsOnlyADarkGauge() {
        val seeded = SessionMeta().seedGauge(50_621, 200_000, model = "GLM-5.3-Flash-EXL3")
        assertEquals(50_621L to 200_000L, seeded.contextGauge)
        assertEquals(25, seeded.contextPercent)
        assertEquals("GLM-5.3-Flash-EXL3", seeded.model)
        // A completed turn's reading outranks any later seed, and a known model is kept.
        val lit = SessionMeta(contextUsed = 84_000, contextMax = 128_000, model = "known")
        assertEquals(lit, lit.seedGauge(1, 2, model = "other"))
        // A half-reading is still no reading, and a seed never changes the model alone.
        assertEquals(SessionMeta(), SessionMeta().seedGauge(0, 128_000, model = "other"))
        assertEquals(SessionMeta(), SessionMeta().seedGauge(12_000, 0))
    }
}
