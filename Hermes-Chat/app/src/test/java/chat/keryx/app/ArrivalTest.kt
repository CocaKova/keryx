package chat.keryx.app

import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ui.components.ARRIVAL_QUIET_MS
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.isArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arrival (2.3 §3) — the rule that decides when an agent turn is a herald walking in rather than
 * an answer. Getting this wrong in either direction is bad: a false banner on every scrollback
 * boundary is noise, a missed one loses the whole point of the feature.
 */
class ArrivalTest {

    private val t0 = 1_700_000_000_000L
    private val quiet = ARRIVAL_QUIET_MS + 1_000L

    private fun mine(id: String, at: Long) = Message(id, "!r", SenderType.ME, "hey", at)
    private fun agent(id: String, at: Long, text: String = "the deploy finished") =
        Message(id, "!r", SenderType.HERMES, text, at)
    private fun human(id: String, at: Long) = Message(id, "!r", SenderType.OTHER, "hi all", at)

    // --- the rule ------------------------------------------------------------------------------

    @Test
    fun `an agent turn after a long quiet spell is an arrival`() {
        assertTrue(isArrival(agent("a", t0 + quiet), agent("b", t0)))
    }

    @Test
    fun `a reply to me is never an arrival, however long I took to ask`() {
        assertFalse(isArrival(agent("a", t0 + quiet), mine("m", t0)))
    }

    @Test
    fun `a prompt answered quickly is not an arrival`() {
        assertFalse(isArrival(agent("a", t0 + 5_000L), agent("b", t0)))
    }

    @Test
    fun `exactly the quiet threshold counts — the boundary is inclusive`() {
        assertTrue(isArrival(agent("a", t0 + ARRIVAL_QUIET_MS), agent("b", t0)))
        assertFalse(isArrival(agent("a", t0 + ARRIVAL_QUIET_MS - 1), agent("b", t0)))
    }

    @Test
    fun `my own message is never an arrival`() {
        assertFalse(isArrival(mine("m", t0 + quiet), agent("b", t0)))
    }

    @Test
    fun `another human speaking is never an arrival`() {
        assertFalse(isArrival(human("h", t0 + quiet), agent("b", t0)))
    }

    @Test
    fun `a turn after another human's message can still be an arrival`() {
        // Nobody *asked the agent* — a human talking to the room is not a prompt.
        assertTrue(isArrival(agent("a", t0 + quiet), human("h", t0)))
    }

    @Test
    fun `telemetry never arrives — cron check-ins are already quiet rows`() {
        val checkIn = Message("t", "!r", SenderType.HERMES, "⏳ Working…", t0 + quiet)
        // Only meaningful if the fixture really is telemetry; otherwise this test proves nothing.
        assertTrue(
            "fixture must be recognised as telemetry",
            chat.keryx.app.presentation.ui.components.isTelemetryMessage(checkIn),
        )
        assertFalse(isArrival(checkIn, agent("b", t0)))
    }

    @Test
    fun `nothing before it is not an arrival — that is an unpaged window, not silence`() {
        assertFalse(isArrival(agent("a", t0), null))
    }

    // --- the grouping pass ---------------------------------------------------------------------

    private fun group(chrono: List<Message>) = groupChatItems(chrono.asReversed())

    @Test
    fun `the mark is emitted directly above the bubble it announces`() {
        val items = group(listOf(agent("a1", t0), agent("a2", t0 + quiet))).asReversed()
        val idx = items.indexOfFirst { it is ChatRenderItem.Arrival }
        assertTrue("expected an arrival mark", idx >= 0)
        val announced = (items[idx] as ChatRenderItem.Arrival).message.id
        val next = items[idx + 1]
        assertTrue(next is ChatRenderItem.Single)
        assertEquals(announced, (next as ChatRenderItem.Single).message.id)
        assertEquals("a2", announced)
    }

    @Test
    fun `an ordinary answered conversation grows no marks`() {
        val items = group(
            listOf(
                mine("m1", t0),
                agent("a1", t0 + 2_000L),
                mine("m2", t0 + quiet),
                agent("a2", t0 + quiet + 2_000L),
            )
        )
        assertEquals(0, items.count { it is ChatRenderItem.Arrival })
    }

    @Test
    fun `the first message of a window is never marked`() {
        val items = group(listOf(agent("a1", t0), mine("m1", t0 + 1_000L)))
        assertEquals(0, items.count { it is ChatRenderItem.Arrival })
    }

    @Test
    fun `a day boundary lands above the mark, never between it and its bubble`() {
        val day = 24L * 60L * 60L * 1000L
        val items = group(listOf(agent("a1", t0), agent("a2", t0 + day))).asReversed()
        val arrivalAt = items.indexOfFirst { it is ChatRenderItem.Arrival }
        val headerAt = items.indexOfFirst { it is ChatRenderItem.DayHeader }
        assertTrue("expected an arrival mark", arrivalAt >= 0)
        assertTrue("expected a day header", headerAt >= 0)
        assertTrue("the header must precede the mark", headerAt < arrivalAt)
        assertTrue(items[arrivalAt + 1] is ChatRenderItem.Single)
    }
}
