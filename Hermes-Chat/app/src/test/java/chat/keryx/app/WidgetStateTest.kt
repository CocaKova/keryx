package chat.keryx.app

import chat.keryx.app.widget.WidgetState
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.LinkState
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.RunActivity
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget (2.13) is a projection of what the process already holds. These pin the words it
 * earns from each input — which session it follows, what "the last reply" is when the tail
 * ends in wings or a ghost, how a run is told — so the card can be trusted from across the room.
 */
class WidgetStateTest {

    private fun room(id: String, name: String = id, stamp: Long = 0L, source: String = "", preview: String = "") =
        RoomProfile(id = id, name = name, type = RoomType.DIRECT_MESSAGE, timestamp = stamp, source = source, preview = preview)

    private fun msg(
        content: String,
        sender: SenderType = SenderType.HERMES,
        streaming: Boolean = false,
        wings: List<Delegation> = emptyList(),
        tools: List<ToolCall> = emptyList(),
        mediaKind: MediaKind? = null,
        fileName: String = "",
    ) = Message(
        id = "m", roomId = "s1", sender = sender, content = content, timestamp = 1L,
        isStreaming = streaming, delegations = wings, toolCalls = tools, mediaKind = mediaKind, fileName = fileName,
    )

    private fun wing(state: DelegationState) = Delegation(key = "w-$state", goal = "look", state = state)

    private val now = 100_000L

    // --- the link ---------------------------------------------------------------------------

    @Test
    fun link_mapsTheThreeStates() {
        assertEquals(WidgetState.Link.LIVE, WidgetState.from(LinkState.CONNECTED, null, emptyList(), emptyMap(), now).link)
        assertEquals(WidgetState.Link.RECONNECTING, WidgetState.from(LinkState.CONNECTING, null, emptyList(), emptyMap(), now).link)
        assertEquals(WidgetState.Link.OFFLINE, WidgetState.from(LinkState.DISCONNECTED, null, emptyList(), emptyMap(), now).link)
        assertEquals(WidgetState.Link.OFFLINE, WidgetState.from(null, null, emptyList(), emptyMap(), now).link)
    }

    @Test
    fun noRoom_isTheEmptyCard_andSaysWhy() {
        val live = WidgetState.from(LinkState.CONNECTED, null, emptyList(), emptyMap(), now)
        assertNull(live.roomId)
        assertEquals("Keryx", live.title)
        assertEquals("Nothing said yet", live.preview)
        assertNull(live.run)
        val off = WidgetState.from(LinkState.DISCONNECTED, null, emptyList(), emptyMap(), now)
        assertEquals("Open Keryx to connect", off.preview)
    }

    // --- which session ------------------------------------------------------------------------

    @Test
    fun pick_newestByStamp_notTheRosterHead() {
        // The roster puts a bot row first regardless of age; the widget follows activity.
        val rooms = listOf(room("bot", stamp = 10L, source = "bot"), room("old", stamp = 50L), room("new", stamp = 90L))
        assertEquals("new", WidgetState.pick(rooms, emptyMap())?.id)
    }

    @Test
    fun pick_aRunningSessionWins_oldestRunFirst() {
        val rooms = listOf(room("a", stamp = 90L), room("b", stamp = 10L), room("c", stamp = 20L))
        val runs = mapOf(
            "b" to RunActivity(sessionId = "b", startedAt = 5_000L),
            "c" to RunActivity(sessionId = "c", startedAt = 1_000L),
        )
        assertEquals("c", WidgetState.pick(rooms, runs)?.id)
    }

    @Test
    fun pick_runInAnUnlistedSession_fallsBackToNewest() {
        val rooms = listOf(room("a", stamp = 90L))
        val runs = mapOf("ghost" to RunActivity(sessionId = "ghost", startedAt = 1L))
        assertEquals("a", WidgetState.pick(rooms, runs)?.id)
    }

    @Test
    fun pick_emptyRoster_isNull() {
        assertNull(WidgetState.pick(emptyList(), emptyMap()))
    }

    // --- the preview ---------------------------------------------------------------------------

    @Test
    fun preview_isTheLastThingSaid_notTheWingsRow() {
        val tail = listOf(msg("Deploy finished."), msg("", wings = listOf(wing(DelegationState.RUNNING))))
        val s = WidgetState.from(LinkState.CONNECTED, room("s1", "Ops"), tail, emptyMap(), now)
        assertEquals("Ops", s.title)
        assertEquals("Deploy finished.", s.preview)
    }

    @Test
    fun preview_skipsAStreamingGhost() {
        val tail = listOf(msg("Done earlier."), msg("half a sent", streaming = true))
        assertEquals("Done earlier.", WidgetState.from(LinkState.CONNECTED, room("s1"), tail, emptyMap(), now).preview)
    }

    @Test
    fun preview_ownWords_getYouPrefix() {
        assertEquals("You: check the logs", WidgetState.previewOf(msg("check the logs", sender = SenderType.ME)))
    }

    @Test
    fun preview_collapsesWhitespace_andClips() {
        val long = "a ".repeat(200)
        val p = WidgetState.previewOf(msg("first  line\n\nsecond line"))
        assertEquals("first line second line", p)
        assertTrue(WidgetState.previewOf(msg(long)).length <= WidgetState.PREVIEW_MAX)
    }

    @Test
    fun preview_standIns_haveNoGlyphs() {
        assertEquals("Ran read_file", WidgetState.previewOf(msg("", tools = listOf(ToolCall(name = "read_file")))))
        assertEquals("Photo", WidgetState.previewOf(msg("", mediaKind = MediaKind.IMAGE)))
        assertEquals("notes.pdf", WidgetState.previewOf(msg("", mediaKind = MediaKind.FILE, fileName = "notes.pdf")))
        assertEquals("Thinking", WidgetState.previewOf(msg("")))
    }

    @Test
    fun preview_fallsBackToTheRowsOwnPreview_whenNothingPeeked() {
        val s = WidgetState.from(LinkState.CONNECTED, room("s1", preview = "Nightly digest"), emptyList(), emptyMap(), now)
        assertEquals("Nightly digest", s.preview)
        assertEquals("Nothing said yet", WidgetState.from(LinkState.CONNECTED, room("s1"), emptyList(), emptyMap(), now).preview)
    }

    // --- the run -------------------------------------------------------------------------------

    @Test
    fun run_countsOnlyWingsStillFlying_fromTheLastRow() {
        val tail = listOf(
            msg("Looking into it."),
            msg("", wings = listOf(wing(DelegationState.RUNNING), wing(DelegationState.SPAWNING), wing(DelegationState.DONE))),
        )
        val runs = mapOf("s1" to RunActivity(sessionId = "s1", startedAt = now - 12_000L))
        val s = WidgetState.from(LinkState.CONNECTED, room("s1"), tail, runs, now)
        assertEquals(2, s.run?.helpers)
        assertEquals("2 helpers flying", s.run?.line)
        assertTrue(s.tapIn)
    }

    @Test
    fun run_withoutWings_tellsTheClock() {
        val runs = mapOf("s1" to RunActivity(sessionId = "s1", startedAt = now - 12_000L))
        val s = WidgetState.from(LinkState.CONNECTED, room("s1"), listOf(msg("hm")), runs, now)
        assertEquals("working · 12s", s.run?.line)
        assertEquals("1 helper flying", s.run?.copy(helpers = 1)?.line)
    }

    @Test
    fun run_inAnotherSession_isNotThisCards() {
        val runs = mapOf("other" to RunActivity(sessionId = "other", startedAt = now))
        val s = WidgetState.from(LinkState.CONNECTED, room("s1"), listOf(msg("hm")), runs, now)
        assertNull(s.run)
        assertFalse(s.tapIn)
    }

    @Test
    fun run_clockNeverRunsBackwards() {
        val runs = mapOf("s1" to RunActivity(sessionId = "s1", startedAt = now + 5_000L))
        assertEquals(0L, WidgetState.from(LinkState.CONNECTED, room("s1"), emptyList(), runs, now).run?.elapsedSeconds)
    }

    @Test
    fun clock_isAGlance() {
        assertEquals("45s", WidgetState.clock(45))
        assertEquals("4m", WidgetState.clock(4 * 60 + 30))
        assertEquals("2h", WidgetState.clock(2 * 3600 + 59))
    }
}
