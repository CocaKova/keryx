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
    fun `a warning the gateway tagged compacting is not a compaction`() {
        // The agent's session-start feasibility notice, verbatim shape (conversation_compression.py);
        // the gateway's substring classifier tags it "compacting" because it mentions compression.
        val notice = "⚠ Compression model qwen3.8-27b (spark) context is 229,376 tokens, but the main model " +
            "qwen3.8-27b's compression threshold was 256,000 tokens. Auto-lowered this session's " +
            "threshold to 229,376 tokens so compression can run.\n  To make this permanent, edit config.yaml"
        val s = SessionStatus.of("compacting", notice)
        assertFalse(s.isCompacting)
        assertEquals("lifecycle", s.kind)
        assertFalse(SessionStatus.of("compressing", "❌ compression failed").isCompacting)
        // Real progress under the same tag is untouched.
        assertTrue(SessionStatus.of("compacting", "🗜️ Compacting context — summarizing earlier conversation").isCompacting)
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

    // 2.13.11 — the gateway's compaction line names no size; the banner borrows the ring's.

    @Test
    fun `the banner borrows the ring's reading when the line names no size`() {
        val live = SessionStatus.of("compacting", "🗜️ Compacting context — summarizing earlier conversation so I can continue...")
        assertEquals("Compressing context", live.headline)
        assertEquals("Compressing context (~141k tokens)", live.headline(fallbackTokens = 141_413))
        assertEquals("Compressing context (~141k tokens) · usually ~41 s", live.headline(141_413, typicalSeconds = 41))
        assertEquals("Compressing context · usually ~3 min", live.headline(null, typicalSeconds = 170))
        // The line's own count wins over the ring's.
        val sized = SessionStatus.of("compacting", "⠋ compressing 42 messages (~92,000 tok)…")
        assertEquals("Compressing context (~92k tokens)", sized.headline(fallbackTokens = 141_413))
        // Not a compaction: the text, untouched.
        assertEquals("⏳ Working…", SessionStatus.of("lifecycle", "⏳ Working…").headline(141_413, 41))
    }
}
