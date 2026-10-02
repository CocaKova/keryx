package chat.keryx.app

import chat.keryx.app.transport.direct.GatewayRpc
import chat.keryx.app.transport.direct.ReplayLedger
import chat.keryx.app.transport.direct.TurnEventSink
import chat.keryx.core.model.TurnEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seamless reconnect (2.16): the seq watermark, the hold-and-merge at the replay seam, the epoch
 * reset, and the reload decision. Wire shapes from hermes `tui_gateway/event_replay.py` and
 * `methods_session.py` (`session.events.since`), 2026-10-02.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayLedgerTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun ev(seq: Long?, type: String = "message.delta", sid: String = "live1", text: String = "t$seq") =
        GatewayRpc.GatewayEvent(type, sid, obj("""{"text":"$text"}"""), seq)

    private fun seqs(list: List<GatewayRpc.GatewayEvent>) = list.map { it.seq }

    // ---- the wire ---------------------------------------------------------------------------

    @Test
    fun `a live frame carries its seq off the wire`() {
        val inbound = GatewayRpc.classify(obj("""
            {"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"a1",
             "payload":{"text":"hi"},"seq":42}}
        """))
        val e = (inbound as GatewayRpc.Inbound.Event).event
        assertEquals(42L, e.seq)
        assertEquals("a1", e.sessionId)
        assertEquals("message.delta", e.type)
    }

    @Test
    fun `a session-less broadcast has no seq and an old gateway sends none`() {
        val ready = GatewayRpc.classify(obj("""
            {"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"e1"}}}
        """)) as GatewayRpc.Inbound.Event
        assertNull(ready.event.seq)
        val old = GatewayRpc.classify(obj("""
            {"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"a1","payload":{"text":"x"}}}
        """)) as GatewayRpc.Inbound.Event
        assertNull(old.event.seq)
    }

    // ---- the watermark ----------------------------------------------------------------------

    @Test
    fun `live frames always apply and the newest sets the watermark`() {
        val l = ReplayLedger()
        l.onReady("e1")
        assertEquals(listOf(1L), seqs(l.live(ev(1))))
        assertEquals(listOf(2L), seqs(l.live(ev(2))))
        assertEquals(2L, l.watermark("live1"))
        // The ring evicts whole sessions, counter included: the gateway can count a live session
        // from 1 again. Dropping that as a "duplicate" would silence the chat for good.
        assertEquals(listOf(1L), seqs(l.live(ev(1))))
        assertEquals(1L, l.watermark("live1"))
    }

    @Test
    fun `watermarks survive a reconnect to the same gateway process only`() {
        val l = ReplayLedger()
        l.onReady("e1")
        l.live(ev(7))
        l.onReady("e1")
        assertEquals(7L, l.watermark("live1"))
        assertTrue(l.canReplay("live1"))
        l.onReady("e2") // restarted: its numbering starts over
        assertNull(l.watermark("live1"))
        assertFalse(l.canReplay("live1"))
    }

    @Test
    fun `a gateway without a ring never replays`() {
        val l = ReplayLedger()
        l.onReady(null)
        l.live(ev(3))
        l.onReady(null)
        assertFalse(l.canReplay("live1"))
    }

    // ---- the seam ---------------------------------------------------------------------------

    @Test
    fun `a turn that streams through a drop applies every frame exactly once in order`() {
        val l = ReplayLedger()
        l.onReady("e1")
        (1L..5L).forEach { l.live(ev(it)) } // before the drop
        val gen = l.onReady("e1") // the new socket
        l.hold("live1")
        // Resumed: the gateway rebinds and live frames flow before the replay answer is in.
        assertTrue(l.live(ev(8)).isEmpty())
        assertTrue(l.live(ev(9)).isEmpty())
        assertTrue(l.live(ev(10)).isEmpty())
        // The ring holds 6..9 (8 and 9 were recorded the moment they were sent).
        val out = l.release("live1", gen, listOf(ev(6), ev(7), ev(8), ev(9)))
        assertEquals(listOf(6L, 7L, 8L, 9L, 10L), seqs(out))
        assertEquals(10L, l.watermark("live1"))
        // Hold over: the socket is the source again.
        assertEquals(listOf(11L), seqs(l.live(ev(11))))
    }

    @Test
    fun `the replay never re-applies what landed before the drop`() {
        val l = ReplayLedger()
        l.onReady("e1")
        (1L..5L).forEach { l.live(ev(it)) }
        val gen = l.onReady("e1")
        l.hold("live1")
        val out = l.release("live1", gen, listOf(ev(4), ev(5), ev(6), ev(6)))
        assertEquals(listOf(6L), seqs(out))
    }

    @Test
    fun `other sessions are not held`() {
        val l = ReplayLedger()
        l.onReady("e1")
        l.live(ev(1))
        l.onReady("e1")
        l.hold("live1")
        assertEquals(listOf(4L), seqs(l.live(ev(4, sid = "live2"))))
    }

    @Test
    fun `a catch-up from an older socket cannot touch the newer one`() {
        val l = ReplayLedger()
        l.onReady("e1")
        l.live(ev(1))
        val old = l.onReady("e1")
        l.hold("live1")
        l.live(ev(2))
        val now = l.onReady("e1") // the socket flapped again; its holds are void
        assertTrue(l.release("live1", old, listOf(ev(2))).isEmpty())
        assertTrue(l.abandon("live1", old).isEmpty())
        assertFalse(l.isCurrent(old))
        assertTrue(l.isCurrent(now))
        assertFalse(l.isHeld("live1"))
        // The frame held by the void catch-up was never applied: the watermark still says 1, so
        // the next socket's replay brings it back.
        assertEquals(1L, l.watermark("live1"))
    }

    @Test
    fun `giving up applies the held frames as they came and renumbers from them`() {
        val l = ReplayLedger()
        l.onReady("e1")
        l.live(ev(40))
        val gen = l.onReady("e1")
        l.hold("live1")
        l.live(ev(3))
        l.live(ev(4))
        assertEquals(listOf(3L, 4L), seqs(l.abandon("live1", gen)))
        assertEquals(4L, l.watermark("live1"))
        assertFalse(l.isHeld("live1"))
    }

    @Test
    fun `an unstamped held frame keeps its place after the numbered ones`() {
        val merged = ReplayLedger.merge(2, listOf(ev(3)), listOf(ev(null, type = "x"), ev(4)))
        assertEquals(listOf(3L, 4L, null), seqs(merged))
    }

    // ---- the decision -----------------------------------------------------------------------

    private fun since(
        epoch: String = "e1",
        truncated: Boolean = false,
        latest: Long = 9,
        events: String = "",
        open: String = "",
    ) = Result.success(obj("""
        {"events":[$events],"latest_seq":$latest,"truncated":$truncated,"count":0,
         "epoch":"$epoch","open_requests":[$open]}
    """))

    private fun frame(seq: Long) =
        """{"type":"message.delta","session_id":"live1","payload":{"text":"w$seq"},"seq":$seq}"""

    @Test
    fun `a clean answer replays the missed frames in order with the open questions`() {
        val plan = ReplayLedger.plan(
            "e1", 5,
            since(events = listOf(frame(7), frame(6), frame(5)).joinToString(","),
                open = """{"id":"srq-1","method":"approval","params":{"session_id":"live1","command":"rm x"}}"""),
        ) as ReplayLedger.Plan.Replay
        assertEquals(listOf(6L, 7L), seqs(plan.events))
        assertEquals("w6", plan.events[0].payload?.get("text")?.toString()?.trim('"'))
        assertEquals("srq-1", plan.openRequests.single().id)
        assertEquals("approval", plan.openRequests.single().method)
    }

    @Test
    fun `nothing missed is still a replay and not a reload`() {
        val plan = ReplayLedger.plan("e1", 9, since(latest = 9))
        assertTrue(plan is ReplayLedger.Plan.Replay)
        assertTrue((plan as ReplayLedger.Plan.Replay).events.isEmpty())
    }

    @Test
    fun `a truncated ring reloads`() {
        assertTrue(ReplayLedger.plan("e1", 5, since(truncated = true)) is ReplayLedger.Plan.Reload)
    }

    @Test
    fun `a changed epoch reloads`() {
        assertTrue(ReplayLedger.plan("e1", 5, since(epoch = "e2")) is ReplayLedger.Plan.Reload)
    }

    @Test
    fun `a ring that restarted the session's count reloads`() {
        assertTrue(ReplayLedger.plan("e1", 50, since(latest = 3)) is ReplayLedger.Plan.Reload)
    }

    @Test
    fun `an older gateway reloads exactly as before`() {
        val unknown = ReplayLedger.plan(
            "e1", 5, Result.failure(GatewayRpc.RpcException(-32601, "unknown method")),
        ) as ReplayLedger.Plan.Reload
        assertTrue(unknown.why.contains("predates"))
        assertTrue(ReplayLedger.plan(null, 5, since()) is ReplayLedger.Plan.Reload)
        assertTrue(ReplayLedger.plan("e1", null, since()) is ReplayLedger.Plan.Reload)
        assertTrue(
            ReplayLedger.plan("e1", 5, Result.failure(IllegalStateException("socket closed")))
                is ReplayLedger.Plan.Reload,
        )
    }

    // ---- the turn events a replay emits -----------------------------------------------------

    @Test
    fun `adjacent deltas of one session fold and nothing else moves`() {
        val out = TurnEventSink.coalesce(listOf(
            TurnEvent.Delta("a", "he"), TurnEvent.Delta("a", "llo"), TurnEvent.Break("a"),
            TurnEvent.Delta("a", "x"), TurnEvent.Delta("b", "y"), TurnEvent.End("a", "hello x", false),
        ))
        assertEquals(listOf(
            TurnEvent.Delta("a", "hello"), TurnEvent.Break("a"), TurnEvent.Delta("a", "x"),
            TurnEvent.Delta("b", "y"), TurnEvent.End("a", "hello x", false),
        ), out)
    }

    @Test
    fun `a replayed turn's end survives a burst bigger than the buffer`() = runTest {
        val sink = TurnEventSink(capacity = 4)
        val got = mutableListOf<TurnEvent>()
        backgroundScope.launch { sink.events.collect { got += it } }
        runCurrent()
        sink.batched {
            repeat(600) { sink.tryEmit(TurnEvent.Delta("a", "w")) }
            sink.tryEmit(TurnEvent.End("a", "done", false))
        }
        runCurrent()
        assertEquals(2, got.size)
        assertEquals(600, (got[0] as TurnEvent.Delta).text.length)
        assertEquals(TurnEvent.End("a", "done", false), got[1])
    }
}
