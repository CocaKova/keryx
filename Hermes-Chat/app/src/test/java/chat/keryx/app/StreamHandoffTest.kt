package chat.keryx.app

import chat.keryx.app.presentation.StreamHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay→Matrix handoff rules: the swap must fire exactly when the committed event is the
 *  streamed response, despite reasoning preludes, markers, footers, and whitespace drift. */
class StreamHandoffTest {

    @Test
    fun identicalBodies_match() {
        assertTrue(StreamHandoff.matches("The deploy finished cleanly with all checks green.", "The deploy finished cleanly with all checks green."))
    }

    @Test
    fun reasoningPrelude_onMatrixSide_stillMatches() {
        val streamed = "The deploy finished cleanly with all checks green across every stage."
        val committed = "<think>let me summarize the run</think>$streamed"
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun footerAndMarkers_ignored() {
        val streamed = "Backups verified: all three snapshots restore without errors."
        val committed = "$streamed ⟦keryx:v1⟧\n\nOrnith-1.0-35B · 37% · ~/workspace"
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun whitespaceDrift_ignored() {
        val streamed = "Line one.\n\nLine two continues the thought with more detail here."
        val committed = "Line one.\nLine two   continues the thought with more detail here."
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun differentAnswers_doNotMatch() {
        assertFalse(
            StreamHandoff.matches(
                "Completely different reply about the weather patterns today.",
                "The deploy finished cleanly with all checks green.",
            )
        )
    }

    @Test
    fun shortBodies_requireExactEquality() {
        assertTrue(StreamHandoff.matches("ok", "ok"))
        assertFalse(StreamHandoff.matches("ok", "okay then"))
    }

    @Test
    fun emptyOrToolOnly_neverMatches() {
        assertFalse(StreamHandoff.matches("⚙️ terminal: \"ls\"", ""))
        assertFalse(StreamHandoff.matches("", "anything"))
    }

    @Test
    fun matchesNormalized_agreesWithMatches() {
        val streamed = "The deploy finished cleanly with all checks green across every stage."
        val committed = "<think>summarize</think>$streamed"
        val normalized = StreamHandoff.normalize(streamed, cacheable = false)
        assertTrue(StreamHandoff.matchesNormalized(committed, normalized))
        assertFalse(StreamHandoff.matchesNormalized("A different reply about the weather.", normalized))
    }

    // --- Contract guard: handoff matching must always see the FULL streamed text. The overlay's
    // --- rendered text is tail-windowed past 8 KB (its "…\n" prefix + missing head break prefix
    // --- matching), which is exactly why LiveStream.matchText / currentStreamFullText exist.

    @Test
    fun windowedOverlayText_mustNeverBeUsedForMatching() {
        val paragraph = "This marathon answer keeps going with plenty of prose in every paragraph block.\n\n"
        val full = paragraph.repeat(200) // ~16 KB — well past the 8 KB overlay window
        val windowed = chat.keryx.app.presentation.ui.components.MessageParser.streamTailWindow(full, 8_000)
        check(windowed.length < full.length && windowed.startsWith("…")) { "window did not cut" }
        // The committed Matrix body is the full text: matching against the full streamed text
        // works, matching against the windowed view must NOT be relied on.
        assertTrue(StreamHandoff.matches(full, full))
        assertFalse(StreamHandoff.matches(full, windowed))
    }

    // --- Live-tested 2026-07-02: the committed Matrix copy carried a beacon + blockquote
    // --- reasoning (with a blank line inside) and the handoff match failed → duplicate bubble.

    @Test
    fun beaconPlusBlockquoteReasoning_onMatrixSide_stillMatches() {
        val streamed = "The deploy finished cleanly with all checks green across every stage."
        val committed = "⟦keryx:v1⟧\n> 💭 **Reasoning:**\n> checking the pipeline\n\n> summarizing results\n\n$streamed"
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun reasoningPrelude_plusFooter_stillMatches() {
        val streamed = "Backups verified: all three snapshots restore without errors tonight."
        val committed = "> 💭 **Reasoning:**\n> verify snapshots\n\n$streamed\n\nOrnith-1.0-35B · 37% · ~/workspace"
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun streamedSideCarriesThinkTags_matrixSideCarriesQuoteStyle_stillMatches() {
        // The side-channel mirrors raw deltas (tag-style reasoning); the gateway commits the
        // blockquote-style prelude. Both must normalize to the same prose.
        val answer = "Here is the summary of everything that happened during the run."
        val streamed = "<think>working through the logs</think>$answer"
        val committed = "> 💭 **Reasoning:**\n> working through the logs\n\n$answer"
        assertTrue(StreamHandoff.matches(committed, streamed))
    }

    @Test
    fun normalize_stripsToolChromeAndTelemetry() {
        val body = "🧠 recalling context\n⚙️ terminal: \"ls\"\nHere is the answer.\n\nmodel · 12% · ~/x"
        assertEquals("Here is the answer.", StreamHandoff.normalize(body))
    }
}
