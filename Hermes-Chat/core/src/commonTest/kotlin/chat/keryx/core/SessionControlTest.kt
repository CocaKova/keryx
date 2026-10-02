package chat.keryx.core

import chat.keryx.core.model.BranchPoint
import chat.keryx.core.model.RedirectOutcome
import chat.keryx.core.model.SessionControls
import chat.keryx.core.protocol.MessageRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2.16 gateway hands: standing orders, branch points, redirect outcomes. Shapes from hermes
 *  `tui_gateway/methods_session_control.py` and `methods_session.py`. */
class SessionControlTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun aGoalSnapshotParsesAndReadsAsOneLine() {
        val c = SessionControls.parse(obj("""{"goal":{"title":"Ship the parser","status":"active",
            "turns_used":3,"max_turns":20,"contract":{},"subgoals":["tests","docs",""],
            "gates":[{"command":"pytest","timeout_seconds":60,"max_retries":2,"attempts":1,"last_exit_code":0},
                     {"command":"ruff .","timeout_seconds":60,"max_retries":2,"attempts":0,"last_exit_code":null}],
            "last_verdict":"continue"},"loop":null,"heartbeat":null,"revision":"abc"}"""))!!
        val g = c.goal!!
        assertEquals(listOf("tests", "docs"), g.subgoals)
        assertTrue(g.gates[0].passed)
        assertNull(g.gates[1].lastExitCode)
        assertEquals("◎ Ship the parser · turn 3/20 · gates 1/2", SessionControls.headline(g))
        assertEquals(listOf("goal.pause", "goal.clear"), SessionControls.actions(g).map { it.first })
        assertEquals("abc", c.revision)
    }

    @Test
    fun theVerdictStampsTheLine() {
        val done = SessionControls.parse(obj("""{"goal":{"title":"T","status":"done","turns_used":5,"max_turns":20,
            "subgoals":[],"gates":[],"last_verdict":"done"},"revision":"x"}"""))!!.goal!!
        assertEquals("◎ T · turn 5/20 · ✓ done", SessionControls.headline(done))
        val paused = SessionControls.parse(obj("""{"goal":{"title":"T","status":"paused","max_turns":0,
            "wait_barrier":{"type":"until","until_at":1,"reason":"CI running"}}}"""))!!.goal!!
        assertEquals("◎ T · paused", SessionControls.headline(paused))
        assertEquals("CI running", paused.waitReason)
        assertEquals(listOf("goal.resume", "goal.unwait", "goal.clear"), SessionControls.actions(paused).map { it.first })
    }

    @Test
    fun nothingSetIsEmptyAndGarbageIsNull() {
        assertTrue(SessionControls.parse(obj("""{"goal":null,"loop":null,"heartbeat":null,"revision":""}"""))!!.isEmpty)
        assertNull(SessionControls.parse(null))
        // A goal without a title is no goal.
        assertNull(SessionControls.parse(obj("""{"goal":{"status":"active"}}"""))!!.goal)
        val loop = SessionControls.parse(obj("""{"loop":{"prompt":"check","status":"active","ticks_fired":2,"max_ticks":5}}"""))!!
        assertEquals(2, loop.loop!!.ticksFired)
        assertEquals(5, loop.loop!!.maxTicks)
    }

    private fun row(id: Long, role: String, content: String) =
        MessageRow(id = id, role = role, content = content, toolName = null, timestamp = id, reasoning = null)

    private val rows = listOf(
        row(1, "user", "hi"),
        row(2, "assistant", ""), // a tool-call-only row: not counted
        row(3, "tool", "{\"output\":\"x\"}"),
        row(4, "assistant", "hello"),
        row(5, "user", "again"),
        row(6, "assistant", "sure"),
    )

    @Test
    fun theBranchCountIsTheVisiblePlaceFromTheStart() {
        assertEquals(1, BranchPoint.count(rows, "1", "hi", mine = true))
        assertEquals(2, BranchPoint.count(rows, "4", "hello", mine = false))
        assertEquals(2, BranchPoint.count(rows, "tools-4", "", mine = false))
        assertEquals(4, BranchPoint.count(rows, "6", "sure", mine = false))
        // A live row is found by its words, from the end.
        assertEquals(3, BranchPoint.count(rows, "live-99-final", "again", mine = true))
        assertNull(BranchPoint.count(rows, "live-99-final", "never said", mine = false))
        assertNull(BranchPoint.count(rows, "live-99-final", "", mine = false))
    }

    @Test
    fun redirectOutcomesAreNeverGuessed() {
        assertEquals(RedirectOutcome.REDIRECTED, RedirectOutcome.ofStatus("redirected"))
        assertEquals(RedirectOutcome.QUEUED, RedirectOutcome.ofStatus("queued"))
        assertEquals(RedirectOutcome.REJECTED, RedirectOutcome.ofStatus("rejected"))
        assertEquals(RedirectOutcome.REJECTED, RedirectOutcome.ofStatus(null))
        assertEquals(RedirectOutcome.UNSUPPORTED, RedirectOutcome.ofErrorCode(4010))
        assertEquals(RedirectOutcome.UNSUPPORTED, RedirectOutcome.ofErrorCode(-32601))
        assertNull(RedirectOutcome.ofErrorCode(5000))
    }
}
