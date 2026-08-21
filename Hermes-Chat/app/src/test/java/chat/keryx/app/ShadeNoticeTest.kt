package chat.keryx.app

import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.BlockingKind
import chat.keryx.core.model.BlockingRequest
import chat.keryx.core.model.ShadeNotices
import chat.keryx.core.model.ShadePendingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choice sets mirror the gateway's real emissions (`_emit_approval_request` in
 *  tui_gateway/server.py): full = once/session/always/deny, tirith-flagged drops always,
 *  smart-deny narrows to once/deny. */
class ShadeNoticeTest {

    @Test
    fun `full approval choices become Approve · Always · Deny`() {
        val n = ShadeNotices.forApproval(
            ApprovalRequest("rm -rf build", "Delete the build tree", listOf("once", "session", "always", "deny")),
        )
        assertEquals(listOf("Approve", "Always", "Deny"), n.actions.map { it.label })
        assertEquals(listOf("once", "always", "deny"), n.actions.map { it.wireValue })
        assertFalse(n.freeTextReply)
        assertEquals(ShadeNotices.APPROVAL_TIMEOUT_MS, n.timeoutMs)
    }

    @Test
    fun `tirith-flagged approval (no always) offers session as the third slot`() {
        val n = ShadeNotices.forApproval(
            ApprovalRequest("curl …", "desc", listOf("once", "session", "deny")),
        )
        assertEquals(listOf("once", "session", "deny"), n.actions.map { it.wireValue })
    }

    @Test
    fun `smart-denied approval narrows to Approve · Deny`() {
        val n = ShadeNotices.forApproval(ApprovalRequest("cmd", "", listOf("once", "deny")))
        assertEquals(listOf("once", "deny"), n.actions.map { it.wireValue })
        // Blank description falls back to the (pre-redacted) command line.
        assertEquals("cmd", n.body)
    }

    @Test
    fun `free-text clarify gets inline reply, no buttons`() {
        val n = ShadeNotices.forBlocking(
            BlockingRequest(BlockingKind.CLARIFY, "r1", prompt = "Which repo?"),
        )
        assertTrue(n.freeTextReply)
        assertTrue(n.actions.isEmpty())
        assertEquals("Which repo?", n.body)
    }

    @Test
    fun `choice clarify becomes buttons, not free text`() {
        val n = ShadeNotices.forBlocking(
            BlockingRequest(BlockingKind.CLARIFY, "r1", prompt = "Pick one", choices = listOf("a", "b", "c")),
        )
        assertEquals(listOf("a", "b", "c"), n.actions.map { it.wireValue })
        assertFalse(n.freeTextReply)
    }

    @Test
    fun `multi-select and oversize choice sets fall through to tap-only`() {
        val multi = ShadeNotices.forBlocking(
            BlockingRequest(BlockingKind.CLARIFY, "r1", choices = listOf("a", "b"), multiSelect = true),
        )
        assertTrue(multi.actions.isEmpty() && !multi.freeTextReply)
        val big = ShadeNotices.forBlocking(
            BlockingRequest(BlockingKind.CLARIFY, "r1", choices = listOf("a", "b", "c", "d")),
        )
        assertTrue(big.actions.isEmpty() && !big.freeTextReply)
    }

    @Test
    fun `credentials never ride the shade`() {
        for (kind in listOf(BlockingKind.SUDO, BlockingKind.SECRET)) {
            val n = ShadeNotices.forBlocking(BlockingRequest(kind, "r1", prompt = "p", envVar = "KEY"))
            assertTrue(n.actions.isEmpty())
            assertFalse(n.freeTextReply)
        }
    }

    @Test
    fun `a blocking request outranks a co-pending approval`() {
        val entry = ShadePendingEntry(
            approval = ApprovalRequest("cmd", "d", listOf("once", "deny")),
            blocking = BlockingRequest(BlockingKind.CLARIFY, "r1", prompt = "q"),
        )
        assertEquals("The agent asks", ShadeNotices.forEntry(entry)!!.title)
        assertNull(ShadeNotices.forEntry(ShadePendingEntry()))
    }
}
