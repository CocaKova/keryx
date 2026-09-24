package chat.keryx.app

import chat.keryx.app.transport.direct.ClarifyBatch
import chat.keryx.app.transport.direct.GatewayRpc
import chat.keryx.core.model.BlockingKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend's questions to the phone (hermes `tui_gateway/server_requests.py`, ebe8cda8): a
 * JSON-RPC request FROM the gateway with a string id, answered by a response frame carrying it.
 * Frames shaped like the wire on 2026-09-24. Before 2.13.6 the string-id request fell through
 * the integer-id parse and was dropped — Sy asked three times, the phone showed nothing.
 */
class ServerRequestsTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `a string-id request frame is the backend asking, not a dropped response`() {
        val inbound = GatewayRpc.classify(obj("""
            {"jsonrpc":"2.0","id":"srq-3f9a1c2b4d5e","method":"clarify",
             "params":{"session_id":"3bd04825","question":"Which lane?","choices":["a","b"]}}
        """))
        val req = (inbound as GatewayRpc.Inbound.Request).request
        assertEquals("srq-3f9a1c2b4d5e", req.id)
        assertEquals("clarify", req.method)
        assertEquals("3bd04825", req.params["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an integer-id frame with a result is still our answer`() {
        val inbound = GatewayRpc.classify(obj("""{"jsonrpc":"2.0","id":17,"result":{"status":"streaming"}}"""))
        val res = inbound as GatewayRpc.Inbound.Response
        assertEquals(17L, res.id)
        assertNotNull(res.result)
        assertNull(res.error)
    }

    @Test
    fun `an integer-id error frame carries the error`() {
        val inbound = GatewayRpc.classify(obj("""{"jsonrpc":"2.0","id":4,"error":{"code":4007,"message":"no such session"}}"""))
        val res = inbound as GatewayRpc.Inbound.Response
        assertEquals(4L, res.id)
        assertNotNull(res.error)
    }

    @Test
    fun `event frames stay events, request-cancel included`() {
        val inbound = GatewayRpc.classify(obj("""
            {"jsonrpc":"2.0","method":"event",
             "params":{"type":"request.cancel","session_id":"3bd04825","payload":{"id":"srq-1","method":"clarify","reason":"timeout"}}}
        """))
        val ev = (inbound as GatewayRpc.Inbound.Event).event
        assertEquals("request.cancel", ev.type)
        assertEquals("3bd04825", ev.sessionId)
        assertEquals("srq-1", ev.payload?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `a method with no id is a notification we ignore, never a request`() {
        assertEquals(GatewayRpc.Inbound.Ignored, GatewayRpc.classify(obj("""{"jsonrpc":"2.0","method":"gateway.ping"}""")))
        // A method with an INTEGER id is not the backend's shape either (its ids are srq-… strings).
        assertEquals(GatewayRpc.Inbound.Ignored, GatewayRpc.classify(obj("""{"jsonrpc":"2.0","id":9,"method":"clarify","params":{}}""")))
        assertEquals(GatewayRpc.Inbound.Ignored, GatewayRpc.classify(obj("""{"jsonrpc":"2.0"}""")))
    }

    // ---- batch clarify: one request, several questions, one card at a time ----

    private val batchParams = obj("""
        {"session_id":"3bd04825",
         "questions":[
           {"qid":"q0","question":"Where should the digest land?","choices":["Office (Recommended)","DM"]},
           {"qid":"q1","question":"How often?","choices":["Event-driven","Daily","Weekly"]},
           {"qid":"q2","question":"Anything else?"}
         ]}
    """)

    @Test
    fun `single-question params are not a batch`() {
        assertNull(ClarifyBatch.fromParams("srq-1", obj("""{"session_id":"s","question":"Which repo?"}""")))
        assertNull(ClarifyBatch.fromParams("srq-1", obj("""{"session_id":"s","questions":[]}""")))
    }

    @Test
    fun `the batch is walked in order and says where it is`() {
        val b = ClarifyBatch.fromParams("srq-1", batchParams)!!
        assertEquals(3, b.size)
        val first = b.current()!!
        assertEquals(BlockingKind.CLARIFY, first.kind)
        assertEquals("srq-1", first.requestId)
        assertEquals("q0", first.questionId)
        assertEquals(1, first.ordinal)
        assertEquals(3, first.total)
        assertEquals(listOf("Office (Recommended)", "DM"), first.choices)

        b.lock("q0", "Office")
        val second = b.current()!!
        assertEquals("q1", second.questionId)
        assertEquals(2, second.ordinal)
        assertEquals(listOf("q1", "q2"), b.remaining)

        b.lock("q1", "Daily")
        val third = b.current()!!
        assertEquals("q2", third.questionId)
        assertTrue(third.choices.isEmpty())

        b.lock("q2", "")
        assertNull(b.current())
        assertTrue(b.remaining.isEmpty())
    }

    @Test
    fun `a reconnect replay skips the answers the server already holds`() {
        val replayed = obj("""
            {"session_id":"3bd04825",
             "questions":[{"qid":"q0","question":"A"},{"qid":"q1","question":"B"},{"qid":"q2","question":"C"}],
             "answers":{"q0":"done","q1":"done"}}
        """)
        val b = ClarifyBatch.fromParams("srq-2", replayed)!!
        assertEquals("q2", b.current()!!.questionId)
        assertEquals(3, b.current()!!.ordinal)
    }

    @Test
    fun `the server's remaining list wins over the local view`() {
        val b = ClarifyBatch.fromParams("srq-3", batchParams)!!
        b.lock("q0", "x")
        // Another surface answered q2 meanwhile; the lock ack says only q1 is still open.
        b.syncRemaining(listOf("q1"))
        assertEquals(listOf("q1"), b.remaining)
        assertEquals("q1", b.current()!!.questionId)
    }

    @Test
    fun `multi-select needs choices and a qid is minted when absent`() {
        val b = ClarifyBatch.fromParams("srq-4", obj("""
            {"session_id":"s","questions":[
              {"question":"Pick several","choices":["a","b"],"multi_select":true},
              {"question":"Free text","multi_select":true}
            ]}
        """))!!
        val first = b.current()!!
        assertEquals("q0", first.questionId)
        assertTrue(first.multiSelect)
        b.lock("q0", "a, b")
        val second = b.current()!!
        assertEquals("q1", second.questionId)
        assertTrue(!second.multiSelect)
    }
}
