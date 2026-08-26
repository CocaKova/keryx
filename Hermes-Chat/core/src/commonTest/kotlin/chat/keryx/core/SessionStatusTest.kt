package chat.keryx.core

import chat.keryx.core.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compaction is minutes of silence unless the app knows it is happening (2.5.7). The two wires
 * tag it differently — the direct door re-tags only the line with the gateway's own marker,
 * the side-channel classifies on the gateway — so the classification the banner trusts lives
 * here and is pinned to the agent's real template wording.
 */
class SessionStatusTest {

    @Test
    fun `the gateway's own tags are kept`() {
        assertTrue(SessionStatus.of("compacting", "⠋ compressing 42 messages (~92,000 tok)…").isCompacting)
        assertTrue(SessionStatus.of("compressing", "x").isCompacting)
        assertFalse(SessionStatus.of("warning", "⚠ something").isCompacting)
    }

    @Test
    fun `a generic lifecycle line is classified by the agent's template glyphs`() {
        // agent/conversation_compression.py — PRE_API / PREFLIGHT / IDLE / RETRY templates.
        assertTrue(SessionStatus.of("lifecycle", "📦 Pre-API compression: ~123,456 tokens near the context/output limit. Compacting before the next model call.").isCompacting)
        assertTrue(SessionStatus.of("lifecycle", "📦 Preflight compression: ~120,000 tokens >= 100,000 threshold. This may take a moment.").isCompacting)
        assertTrue(SessionStatus.of("lifecycle", "💤 Resumed after 3600s idle — compacting ~120,000 tokens before continuing.").isCompacting)
        assertTrue(SessionStatus.of("lifecycle", "🗜️ Context too large (~250,000 tokens) — compressing (1/3)...").isCompacting)
        assertTrue(SessionStatus.of("lifecycle", "🗜️ Compacting context — summarizing earlier conversation so I can continue...").isCompacting)
        assertFalse(SessionStatus.of("lifecycle", "❌ Non-retryable error (HTTP 400): boom").isCompacting)
        assertFalse(SessionStatus.of("lifecycle", "⏳ Working…").isCompacting)
    }

    @Test
    fun `the headline carries the size of the job`() {
        assertEquals(
            "Compressing context (~123k tokens)",
            SessionStatus.of("lifecycle", "📦 Pre-API compression: ~123,456 tokens near the limit").headline,
        )
        assertEquals(
            "Compressing context (~92k tokens)",
            SessionStatus.of("compacting", "⠋ compressing 42 messages (~92,000 tok)…").headline,
        )
        assertEquals("Compressing context (~1.2M tokens)", SessionStatus.of("compacting", "~1,234,567 tokens").headline)
        assertEquals("Compressing context (~2.5k tokens)", SessionStatus.of("compacting", "~2,480 tokens").headline)
        assertEquals("Compressing context", SessionStatus.of("compacting", "compacting…").headline)
        assertNull(SessionStatus.of("compacting", "compacting…").tokens)
    }

    @Test
    fun `a non-compaction status headlines as its own words`() {
        assertEquals("⚠ disk full", SessionStatus.of("warning", "⚠ disk full").headline)
    }
}
