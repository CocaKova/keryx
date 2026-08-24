package chat.keryx.app

import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.ToolRunEntry
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.withLiveTheater
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.Theater
import chat.keryx.core.model.TheaterEvent
import chat.keryx.core.model.TheaterState
import chat.keryx.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.1 §A1 — the side-channel's tool frames are a producer, not a second renderer.
 *
 * The bug these pin: a watched Matrix turn showed every call twice, because the gateway narrates
 * each call as a real message (`tool_progress`, on by default) AND the side-channel reports the
 * same call as an `event: tool` frame, and until 3.1 each had its own renderer a few dp apart.
 * The rule is that the transcript's row wins and the frame only fills in what the text could not
 * carry — so what matters here is the ARITHMETIC of the fold, not the rendering.
 */
class LiveTheaterFoldTest {

    private var ts = 0L
    private fun msg(sender: SenderType, content: String) =
        Message(id = "e${ts}", roomId = "room", sender = sender, content = content, timestamp = ts++)

    private fun group(vararg chrono: Message) = groupChatItems(chrono.toList().asReversed())

    private fun runOf(items: List<ChatRenderItem>) =
        items.filterIsInstance<ChatRenderItem.ToolRun>().single()

    /** The theater as it would stand after the gateway announced (and optionally closed) these. */
    private fun theater(vararg names: String, closed: Int = 0): TheaterState {
        var s = TheaterState()
        names.forEach { s = Theater.reduce(s, TheaterEvent(phase = "start", name = it, preview = "x")) }
        repeat(closed) { s = Theater.reduce(s, TheaterEvent(phase = "end", name = names[it], ok = true)) }
        return s
    }

    // --- the double, which is the whole point -------------------------------------------------

    @Test
    fun narratedCallsAreNotDrawnTwice() {
        // tool_progress: all — the room carries both calls, and the theater knows both.
        val items = group(
            msg(SenderType.ME, "do it"),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\""),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n📖 read_file: \"a.txt\""),
        )
        assertEquals(2, runOf(items).callCount)
        val folded = withLiveTheater(items, theater("terminal", "read_file").beats)
        assertEquals("the narrated calls stay one row each", 2, runOf(folded).callCount)
    }

    @Test
    fun aCallTheNarrationHasNotSyncedYetShowsLive() {
        // The frame beats the Matrix event to the phone — which is the entire reason the
        // side-channel exists. The un-narrated call gets a row so the turn doesn't look stalled.
        val items = group(
            msg(SenderType.ME, "do it"),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\""),
        )
        val folded = withLiveTheater(items, theater("terminal", "read_file").beats)
        val run = runOf(folded)
        assertEquals(2, run.callCount)
        assertEquals(
            listOf("terminal", "read_file"),
            run.entries.filterIsInstance<ToolRunEntry.Call>().map { it.call.name },
        )
    }

    @Test
    fun theNarrationLandsAndTheLiveRowStopsBeingExtra() {
        val beats = theater("terminal", "read_file").beats
        val before = withLiveTheater(
            group(msg(SenderType.ME, "go"), msg(SenderType.HERMES, "⚙️ terminal: \"ls\"")),
            beats,
        )
        val after = withLiveTheater(
            group(
                msg(SenderType.ME, "go"),
                msg(SenderType.HERMES, "⚙️ terminal: \"ls\""),
                msg(SenderType.HERMES, "⚙️ terminal: \"ls\"\n📖 read_file: \"a.txt\""),
            ),
            beats,
        )
        // Same count across the sync — the row is replaced, never added to.
        assertEquals(runOf(before).callCount, runOf(after).callCount)
    }

    // --- tool_progress: off, where the frames are the only source -----------------------------

    @Test
    fun withNoNarrationTheBeatsOpenTheirOwnRun() {
        val items = group(msg(SenderType.ME, "do it"))
        assertTrue(items.none { it is ChatRenderItem.ToolRun })
        val folded = withLiveTheater(items, theater("terminal", "read_file").beats)
        assertEquals(2, runOf(folded).callCount)
    }

    @Test
    fun aRunOpenedForBeatsSitsBelowTurnOpeningProse() {
        // Newest-first: index 0 is the newest row. Prose the turn opened with came BEFORE the
        // tools, so the run must land under it, not above it.
        val items = group(
            msg(SenderType.ME, "do it"),
            msg(SenderType.HERMES, "Sure — let me look."),
        )
        val folded = withLiveTheater(items, theater("terminal").beats)
        assertTrue("the run is the newest row", folded.first() is ChatRenderItem.ToolRun)
        assertTrue("the opening prose is still above it", folded[1] is ChatRenderItem.Single)
    }

    @Test
    fun aSettledTurnNeverGrowsARunUnderItsAnswer() {
        // The turn is over and its answer is on screen. Leftover beats (narration off) must not
        // materialise a run below the answer — that is a turn that never happened.
        val items = group(
            msg(SenderType.ME, "do it"),
            msg(SenderType.HERMES, "All done — three files touched."),
        )
        val folded = withLiveTheater(items, theater("terminal").beats, live = false)
        assertSame(items, folded)
        assertNull(folded.firstOrNull { it is ChatRenderItem.ToolRun })
    }

    // --- wings ---------------------------------------------------------------------------------

    @Test
    fun liveWingsJoinTheRunAndDoNotDuplicate() {
        val items = group(msg(SenderType.ME, "go"), msg(SenderType.HERMES, "⚙️ terminal: \"ls\""))
        val wings = listOf(Delegation(key = "sub-1", goal = "read the docs"))
        val folded = withLiveTheater(items, theater("terminal").beats, wings)
        assertEquals(1, runOf(folded).entries.count { it is ToolRunEntry.Delegated })
        // Folding the same wing again (the next frame re-publishes the whole list) adds nothing.
        val twice = withLiveTheater(folded, theater("terminal").beats, wings)
        assertEquals(1, runOf(twice).entries.count { it is ToolRunEntry.Delegated })
    }

    // --- the cheap paths ------------------------------------------------------------------------

    @Test
    fun nothingLiveIsTheIdentity() {
        val items = group(msg(SenderType.ME, "hi"), msg(SenderType.HERMES, "hello"))
        assertSame(items, withLiveTheater(items, emptyList()))
    }

    @Test
    fun everythingAlreadyNarratedIsTheIdentity() {
        val items = group(msg(SenderType.ME, "go"), msg(SenderType.HERMES, "⚙️ terminal: \"ls\""))
        assertSame(items, withLiveTheater(items, theater("terminal").beats))
    }

    @Test
    fun aRunFromAnEarlierTurnIsNeverGrown() {
        // An answer landed after that run, so it is history. Beats belong to whatever is
        // happening now, and must open their own run instead of reopening a finished one.
        val items = group(
            msg(SenderType.ME, "first"),
            msg(SenderType.HERMES, "⚙️ terminal: \"ls\""),
            msg(SenderType.HERMES, "Here is what I found, and it took a while to work through."),
        )
        val old = runOf(items)
        val folded = withLiveTheater(items, theater("read_file").beats)
        val runs = folded.filterIsInstance<ChatRenderItem.ToolRun>()
        assertEquals(2, runs.size)
        assertEquals("the finished run is untouched", 1, runs.first { it.id == old.id }.callCount)
    }

    // --- the claim the unified ToolCall must keep distinguishable -------------------------------

    @Test
    fun foldedBeatsKeepTheirBatchClaim() {
        // Announced-together = one shared batchId, never `concurrent` (TheaterTest pins the
        // reducer; this pins that the fold doesn't launder the claim on its way to the screen).
        var s = TheaterState()
        s = Theater.reduce(s, TheaterEvent(phase = "start", name = "read_file", preview = "a"))
        s = Theater.reduce(s, TheaterEvent(phase = "start", name = "read_file", preview = "b"))
        val folded = withLiveTheater(group(msg(SenderType.ME, "go")), s.beats)
        val calls: List<ToolCall> = runOf(folded).entries.filterIsInstance<ToolRunEntry.Call>().map { it.call }
        assertEquals(2, calls.size)
        assertEquals(calls[0].batchId, calls[1].batchId)
        assertTrue(calls[0].batchId.isNotBlank())
        assertTrue("announcement is not execution", calls.none { it.concurrent })
    }
}
