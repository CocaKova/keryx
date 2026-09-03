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
}
