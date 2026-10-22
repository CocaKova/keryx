package chat.keryx.core

import chat.keryx.core.model.ContextOutlook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat-memory readout (3.0): quiet below half way, "~N left" from 60%, and the sheet's
 * sentence in replies. The estimate is the chat's own growth per turn, so it starts unknown.
 */
class ContextOutlookTest {
    @Test
    fun `readings keep growth, and a compaction starts them over`() {
        var r = emptyList<Long>()
        for (u in listOf(10_000L, 14_000L, 14_000L, 19_000L)) r = ContextOutlook.record(r, u)
        assertEquals(listOf(10_000L, 14_000L, 19_000L), r)          // a repeat reading adds nothing
        r = ContextOutlook.record(r, 6_000L)                         // the summary landed
        assertEquals(listOf(6_000L), r)
        assertEquals(listOf(6_000L), ContextOutlook.record(r, 0L))   // no reading is not a reading
        var long = emptyList<Long>()
        for (i in 1..20) long = ContextOutlook.record(long, i * 1_000L)
        assertEquals(ContextOutlook.KEEP, long.size)
    }

    @Test
    fun `replies left is room over the median turn`() {
        assertNull(ContextOutlook.repliesLeft(200_000, emptyList()))
        assertNull(ContextOutlook.repliesLeft(200_000, listOf(50_000)))
        // Turns of 4k, 5k and a 40k outlier (a pasted log): the median ignores the outlier.
        assertEquals(40, ContextOutlook.repliesLeft(200_000, listOf(10_000, 14_000, 19_000, 59_000)))
        assertEquals(0, ContextOutlook.repliesLeft(0, listOf(10_000, 20_000)))
        assertEquals(999, ContextOutlook.repliesLeft(10_000_000, listOf(1_000, 1_001)))
    }

    @Test
    fun `the ring hides below half way and labels itself from sixty percent`() {
        assertFalse(ContextOutlook.showRing(0.30f, compacting = false))
        assertTrue(ContextOutlook.showRing(0.30f, compacting = true))   // a compaction always shows
        assertTrue(ContextOutlook.showRing(0.50f, compacting = false))
        assertNull(ContextOutlook.shortLabel(0.55f, 12, 50_000))
        assertEquals("~12 left", ContextOutlook.shortLabel(0.70f, 12, 50_000))
        assertEquals("filling up", ContextOutlook.shortLabel(0.70f, null, 50_000))
        assertEquals("summary next", ContextOutlook.shortLabel(1f, 0, 0))
    }

    @Test
    fun `the sheet says it in replies, and names who summarizes`() {
        assertEquals(
            "Room for about 40 more replies. Then Atlas summarizes the older messages so the chat can keep going.",
            ContextOutlook.sentence(40, 200_000, "Atlas", hasTrigger = true),
        )
        assertEquals(
            "Room for about 1 more reply. Then the agent summarizes the older messages so the chat can keep going.",
            ContextOutlook.sentence(1, 5_000, null, hasTrigger = true),
        )
        assertTrue(ContextOutlook.sentence(0, 0, "Atlas", hasTrigger = true).startsWith("The next reply starts a summary"))
        assertTrue(ContextOutlook.sentence(null, 90_000, null, hasTrigger = true).startsWith("The chat fills up as you talk."))
        // Without a summary point the only line is the model's window.
        assertEquals("Room for about 7 more replies before the chat is full.", ContextOutlook.sentence(7, 9_000, null, hasTrigger = false))
    }

    @Test
    fun `the small print has the figures`() {
        assertEquals("84k of 300k before the summary · window 524k", ContextOutlook.smallPrint(84_000, 300_000, 524_288))
        assertEquals("84k of 300k before the summary", ContextOutlook.smallPrint(84_000, 300_000, 300_000))
        assertEquals("18k of 200k the model can hold", ContextOutlook.smallPrint(18_000, 0, 200_000))
    }
}
