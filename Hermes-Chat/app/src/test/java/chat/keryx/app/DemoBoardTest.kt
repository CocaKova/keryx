package chat.keryx.app

import chat.keryx.app.transport.demo.DemoBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo's Missions board (3.0): a working board's states, stamped the way Hermes's kanban
 * stamps (epoch seconds; milliseconds made every card read "just now"), and a detail behind
 * every card.
 */
class DemoBoardTest {
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000

    @Test
    fun `the board has a running card and two waiting on you`() {
        val board = DemoBoard.board(nowMs)
        val cards = board.tasks.values.flatten()
        assertEquals(2, board.needsYou)
        assertEquals(2, cards.count { it.needsYou })
        assertEquals(1, board.tasks["running"]?.size)
        assertTrue(cards.filter { it.needsYou }.all { it.ask.isNotBlank() })
    }

    @Test
    fun `every stamp is in seconds, in the last two days`() {
        val cards = DemoBoard.board(nowMs).tasks.values.flatten()
        for (c in cards) {
            for (t in listOfNotNull(c.createdAt, c.startedAt, c.completedAt)) {
                assertTrue(c.id, t in (nowS - 2 * 24 * 3600)..nowS)
            }
        }
    }

    @Test
    fun `every card opens on a detail, and an unknown one says so`() {
        for (c in DemoBoard.board(nowMs).tasks.values.flatten()) {
            val d = DemoBoard.detail(c.id, nowMs).getOrThrow()
            assertEquals(c.id, d.task.id)
            assertTrue(c.id, d.events.isNotEmpty())
            for (r in d.runs) assertTrue(c.id, r.startedAt <= nowS)
        }
        assertTrue(DemoBoard.detail("t_nope", nowMs).isFailure)
    }

    @Test
    fun `a card that hit a problem is on the board, and its run has ended`() {
        val cards = DemoBoard.board(nowMs).tasks.values.flatten()
        val failed = cards.single { it.lastFailureError.isNotBlank() }
        val d = DemoBoard.detail(failed.id, nowMs).getOrThrow()
        assertTrue(d.runs.all { it.endedAt != null && it.outcome == "crashed" })
    }
}
