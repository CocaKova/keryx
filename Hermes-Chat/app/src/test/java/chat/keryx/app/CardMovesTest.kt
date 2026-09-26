package chat.keryx.app

import chat.keryx.app.presentation.ui.components.CardMove
import chat.keryx.app.presentation.ui.components.movesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's moves on a card (2.14): which ones each status offers, and what they send. */
class CardMovesTest {

    @Test
    fun `a card parked in triage can be started — the create dialog parks by default`() {
        assertEquals(CardMove.START, movesFor("triage").first())
    }

    @Test
    fun `a blocked card unblocks without a reply`() {
        val moves = movesFor("blocked")
        assertEquals(CardMove.UNBLOCK, moves.first())
        assertEquals(false, CardMove.UNBLOCK.confirm)
        assertEquals(false, CardMove.UNBLOCK.noteRequired)
    }

    @Test
    fun `a running card offers to stop the run first, and asks before it does`() {
        assertEquals(CardMove.RECLAIM, movesFor("running").first())
        assertTrue(CardMove.RECLAIM.confirm)
        assertFalse(CardMove.UNBLOCK in movesFor("running"))
    }

    @Test
    fun `review keeps its verdicts in the ask panel — no second approve here`() {
        assertFalse(CardMove.DONE in movesFor("review"))
        assertFalse(CardMove.UNBLOCK in movesFor("review"))
    }

    @Test
    fun `finished cards only archive, archived ones offer nothing`() {
        assertEquals(listOf(CardMove.ARCHIVE), movesFor("done"))
        assertTrue(movesFor("archived").isEmpty())
        assertTrue(movesFor("something-new").isEmpty())
    }

    @Test
    fun `pausing needs a reason the next run reads`() {
        assertEquals(true, CardMove.PAUSE.noteRequired)
        assertEquals("block", CardMove.PAUSE.verb)
    }

    @Test
    fun `verbs match the gateway's action names`() {
        assertEquals(
            setOf("promote", "unblock", "reclaim", "reassign", "block", "complete", "archive"),
            CardMove.entries.map { it.verb }.toSet(),
        )
    }

    @Test
    fun `the toast says where the card landed`() {
        assertEquals("Handed to theo — ready", CardMove.REASSIGN.landed("ready", "theo"))
        assertEquals("Unblock — now ready", CardMove.UNBLOCK.landed("ready", "default"))
    }
}
