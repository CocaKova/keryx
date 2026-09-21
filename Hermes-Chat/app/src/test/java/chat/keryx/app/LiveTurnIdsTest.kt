package chat.keryx.app

import chat.keryx.app.transport.direct.LiveTurnIds
import chat.keryx.app.transport.direct.TranscriptPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTurnIdsTest {

    @Test
    fun `a turn's rows are all different rows`() {
        val ids = listOf(
            LiveTurnIds.item(7L, 0), LiveTurnIds.item(7L, 1),
            LiveTurnIds.answer(7L), LiveTurnIds.thought(7L),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the next turn does not reuse the last turn's names`() {
        // Folded rows stay in the list until the re-read; a second turn's step 0 beside the
        // first turn's step 0 under one key is a crash in a keyed list, not a glitch.
        assertNotEquals(LiveTurnIds.item(7L, 0), LiveTurnIds.item(8L, 0))
        assertNotEquals(LiveTurnIds.answer(7L), LiveTurnIds.answer(8L))
    }

    @Test
    fun `a live row is never mistaken for a gateway row`() {
        // TranscriptPages reads a row id off an id's numeric tail; a live row has no gateway
        // row yet, and "step 0" read as row 0 would aim the Archive at the wrong message.
        for (id in listOf(LiveTurnIds.item(7L, 0), LiveTurnIds.answer(7L), LiveTurnIds.thought(7L))) {
            assertNull(id, TranscriptPages.rowIdOf(id))
        }
    }
}
