package chat.keryx.app

import chat.keryx.app.transport.direct.GatewayRpc
import chat.keryx.app.transport.direct.PendingAsks
import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.BlockingKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAsksTest {
    private fun approval(rid: String) = ApprovalRequest("rm -rf build", "clean", listOf("once", "deny"), requestId = rid)
    private fun req(id: String, method: String, vararg kv: Pair<String, String>) =
        GatewayRpc.ServerRequest(id, method, buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })

    @Test
    fun `a card shows in its session and in the shade, and clearing it empties the shade`() {
        val asks = PendingAsks { null }
        asks.setApproval("s1", approval("a1"))
        assertEquals("a1", asks.approval("s1").value?.requestId)
        assertEquals("a1", asks.shade().value["s1"]?.approval?.requestId)
        asks.setApproval("s1", null)
        assertTrue(asks.shade().value.isEmpty())
    }

    @Test
    fun `a withdrawn request takes down only the card wearing its id`() {
        val asks = PendingAsks { null }
        asks.onServerRequest(req("srq-1", "sudo"), "s1")
        asks.withdraw("s1", "srq-old")
        assertEquals(BlockingKind.SUDO, asks.blocking("s1").value?.kind)
        asks.withdraw("s1", "srq-1")
        assertNull(asks.blocking("s1").value)
    }

    @Test
    fun `a compaction fork carries the card and the shade entry to the continuation`() {
        val asks = PendingAsks { null }
        asks.onServerRequest(req("srq-2", "clarify", "question" to "Which branch?"), "old")
        asks.rotate("old", "new")
        assertEquals("Which branch?", asks.blocking("new").value?.prompt)
        assertEquals(setOf("new"), asks.shade().value.keys)
    }

    @Test
    fun `approval cancelled clears the session's card and any card filed under a listed id`() {
        val asks = PendingAsks { null }
        asks.setApproval("s1", approval("a1"))
        asks.setApproval("s2", approval("a2"))
        asks.setApproval("s3", approval("a3"))
        asks.approvalsCancelled("s1", setOf("a2"))
        assertNull(asks.approval("s1").value)
        assertNull(asks.approval("s2").value)
        assertEquals("a3", asks.approval("s3").value?.requestId)
        asks.clear()
        assertNull(asks.approval("s3").value)
        assertTrue(asks.shade().value.isEmpty())
    }
}
