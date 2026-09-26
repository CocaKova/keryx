package chat.keryx.core

import chat.keryx.core.model.CompactionGauge
import chat.keryx.core.model.CompactionLineage
import chat.keryx.core.model.CompactionTimings
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

    // 2.13.11 — the ring measures the way to the gateway's auto-compaction trigger.

    @Test
    fun theTriggerIsTheGatewaysNumberNotAFractionOfTheWindow() {
        // Measured 2026-09-25: a 327,680 window compacting at 136,500 — 42%, not the config's 60%.
        val m = SessionMeta(contextUsed = 105_000, contextMax = 327_680, compactAt = 136_500)
        assertEquals(136_500L, m.compactionTrigger)
        // Unknown, and a trigger that does not fit the window it came with, are no trigger.
        assertNull(SessionMeta(contextUsed = 1, contextMax = 10).compactionTrigger)
        assertNull(SessionMeta(contextUsed = 1, contextMax = 100_000, compactAt = 200_000).compactionTrigger)
    }

    @Test
    fun fullMeansTheNextCallCompacts() {
        val g = CompactionGauge.of(105_000, 136_500)!!
        assertEquals(31_500L, g.left)
        assertEquals(105_000f / 136_500f, g.fraction, 1e-6f)
        // The check runs before the NEXT call, so a turn can overshoot: full, none left.
        val over = CompactionGauge.of(141_413, 136_500)!!
        assertEquals(1f, over.fraction)
        assertEquals(0L, over.left)
        assertNull(CompactionGauge.of(105_000, null))
        assertNull(CompactionGauge.of(0, 136_500))
    }

    @Test
    fun aSeedCarriesTheTriggerEvenToALitGauge() {
        val seeded = SessionMeta().seedGauge(50_000, 327_680, compactAt = 182_000)
        assertEquals(182_000L, seeded.compactAt)
        // A lit gauge keeps its reading but learns a trigger it never had...
        val lit = SessionMeta(contextUsed = 84_000, contextMax = 327_680, model = "m")
        assertEquals(lit.copy(compactAt = 136_500), lit.seedGauge(1, 2, compactAt = 136_500))
        // ...and a trigger it already has is not overwritten by a seed.
        val known = lit.copy(compactAt = 136_500)
        assertEquals(known, known.seedGauge(1, 2, compactAt = 182_000))
    }

    @Test
    fun usuallyIsTheMedianOfWhatThisPhoneSaw() {
        assertNull(CompactionTimings.typical(emptyList()))
        assertEquals(41, CompactionTimings.typical(listOf(41)))
        // One slow outlier does not become "usually".
        assertEquals(41, CompactionTimings.typical(listOf(38, 41, 600)))
        assertEquals(40, CompactionTimings.typical(listOf(38, 42)))
        // A flicker and a banner that outlived its compaction are not samples; five are kept.
        assertEquals(listOf(41), CompactionTimings.record(listOf(41), 1))
        assertEquals(listOf(41), CompactionTimings.record(listOf(41), CompactionTimings.MAX_SECONDS + 1))
        assertEquals(listOf(10, 11, 12, 13, 14), CompactionTimings.record(listOf(9, 10, 11, 12, 13), 14))
        assertEquals(listOf(41, 38), CompactionTimings.decode(CompactionTimings.encode(listOf(41, 38))))
        assertEquals(listOf(41), CompactionTimings.decode("41,x,"))
    }

    @Test
    fun aResumeOfARotatedOutParentLandsOnItsTip() {
        // Measured on the live gateway 2026-09-25: asked for the parent, answered with the tip.
        val parent = "cron_8caaffdb3122_20260925_065516"
        val tip = "20260925_095619_0809e6"
        assertEquals(tip, CompactionLineage.rotatedTip(parent, sessionKey = tip, resumed = tip))
        assertEquals(tip, CompactionLineage.rotatedTip(parent, sessionKey = null, resumed = tip))
        assertEquals(tip, CompactionLineage.rotatedTip(parent, sessionKey = "", resumed = tip))
        // A session that was never compacted resumes as itself: no rotation.
        assertNull(CompactionLineage.rotatedTip(tip, sessionKey = tip, resumed = tip))
        assertNull(CompactionLineage.rotatedTip(tip, sessionKey = null, resumed = null))
    }

    @Test
    fun aForwardIsFollowedThroughEveryCompactionAndACycleStops() {
        val links = mapOf("a" to "b", "b" to "c")
        assertEquals("c", CompactionLineage.follow("a") { links[it] })
        assertEquals("c", CompactionLineage.follow("c") { links[it] })
        assertEquals("z", CompactionLineage.follow("z") { links[it] })
        val loop = mapOf("x" to "y", "y" to "x")
        assertEquals("y", CompactionLineage.follow("x") { loop[it] })
    }
}
