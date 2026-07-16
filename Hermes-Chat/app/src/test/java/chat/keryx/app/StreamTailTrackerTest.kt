package chat.keryx.app

import chat.keryx.app.presentation.StreamTailTracker
import chat.keryx.app.presentation.ui.components.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * StreamTailTracker must be byte-identical to the O(n)-per-tick pipeline it replaces:
 * `streamTailWindow(sanitizeStreamingTail(raw), max)` in sanitize mode and
 * `streamTailWindow(raw, max)` in raw mode. The MessageParser functions stay as the reference
 * oracle; these properties are what let the incremental version be trusted with the overlay.
 */
class StreamTailTrackerTest {

    private fun oracleSanitized(raw: String, max: Int): String =
        MessageParser.streamTailWindow(MessageParser.sanitizeStreamingTail(raw), max)

    private fun oracleRaw(raw: String, max: Int): String =
        MessageParser.streamTailWindow(raw, max)

    /** Stream fragments covering every construct the sanitize/window rules care about: prose,
     *  blank lines (incl. whitespace-only), fences (```/~~~, tagged, backticks-being-typed),
     *  ⟦keryx⟧ markers (complete, unterminated, closed later), and reasoning-tag prefixes. */
    private val pieces = listOf(
        "The deploy finished cleanly. ", "alpha beta gamma", "counting tokens as they stream",
        "\n", "\n\n", "  \n", "\t\n", "\n\n\n",
        "```\n", "```kotlin\n", "~~~\n", "val x = 1\n", "fun f() = 2\n",
        "`\n", "``\n", "`` \n", "` ", "``",
        "⟦keryx:ask|Ship it|Hold⟧", "⟦keryx:v1⟧", "⟦keryx:tel", "⟧", "⟦",
        "<think>", "</think>", "<thin", "</think", "◁think▷", "◁thi", "◁/think▷",
        "< not a tag ", "a < b and c ",
        "— tail punctuation. ", "…", "final words",
    )

    private fun randomStream(rng: Random, pieceCount: Int): String =
        buildString { repeat(pieceCount) { append(pieces[rng.nextInt(pieces.size)]) } }

    private fun checkEquivalence(text: String, window: Int, sanitize: Boolean, rng: Random) {
        val tracker = StreamTailTracker(window, sanitize = sanitize)
        var fed = 0
        while (fed < text.length) {
            val take = minOf(1 + rng.nextInt(17), text.length - fed)
            tracker.append(text.substring(fed, fed + take))
            fed += take
            val raw = text.substring(0, fed)
            val expected = if (sanitize) oracleSanitized(raw, window) else oracleRaw(raw, window)
            assertEquals(
                "window mismatch (sanitize=$sanitize, window=$window) after ${fed} chars of: " +
                    raw.takeLast(120).replace("\n", "\\n"),
                expected,
                tracker.windowText(),
            )
            if (sanitize) {
                assertEquals(
                    "sanitized full-text mismatch after $fed chars",
                    MessageParser.sanitizeStreamingTail(raw),
                    tracker.sanitizedFullText(),
                )
            }
            assertEquals(raw, tracker.rawText())
        }
    }

    @Test
    fun randomStreams_sanitizeMode_matchOracle() {
        val rng = Random(20260716)
        repeat(120) { checkEquivalence(randomStream(rng, 40), window = 160, sanitize = true, rng = rng) }
    }

    @Test
    fun randomStreams_rawMode_matchOracle() {
        val rng = Random(716)
        repeat(120) { checkEquivalence(randomStream(rng, 40), window = 160, sanitize = false, rng = rng) }
    }

    @Test
    fun productionWindowSize_longMarathonTurn_matchesOracle() {
        val rng = Random(514)
        // A marathon-shaped turn: well past the 8 KB production window.
        checkEquivalence(randomStream(rng, 900), window = 8_000, sanitize = true, rng = rng)
    }

    @Test
    fun clear_behavesLikeAFreshTracker() {
        // Mid-turn segment commits reset the buffer while the SSE channel stays live.
        val rng = Random(42)
        val tracker = StreamTailTracker(160, sanitize = true)
        tracker.append(randomStream(rng, 30))
        tracker.clear()
        assertEquals("", tracker.windowText())
        assertEquals("", tracker.rawText())
        assertEquals(true, tracker.isBlank())
        val next = randomStream(rng, 30)
        tracker.append(next)
        assertEquals(oracleSanitized(next, 160), tracker.windowText())
        assertEquals(MessageParser.sanitizeStreamingTail(next), tracker.sanitizedFullText())
    }

    @Test
    fun giantUnterminatedFence_fallsBackToWholeText() {
        val text = "intro\n\n```\n" + "code line that never ends\n".repeat(40)
        val tracker = StreamTailTracker(120, sanitize = true)
        tracker.append(text)
        assertEquals(oracleSanitized(text, 120), tracker.windowText())
    }

    @Test
    fun trailingBlankLines_rawMode_cutAtTail() {
        // Raw (reasoning) mode has no trimEnd, so lines() sees trailing blank/virtual lines —
        // the tracker must reproduce the oracle's cut there too.
        for (text in listOf("thoughts\n".repeat(40) + "\n\n", "x".repeat(200) + "\n", "  \n".repeat(80))) {
            val tracker = StreamTailTracker(100, sanitize = false)
            tracker.append(text)
            assertEquals(oracleRaw(text, 100), tracker.windowText())
        }
    }

    @Test
    fun unterminatedMarker_thenClosedLater_matchesOracleAtBothPoints() {
        val tracker = StreamTailTracker(120, sanitize = true)
        val part1 = "prose before the ask\n\n⟦keryx:ask|Yes|No"
        tracker.append(part1)
        assertEquals(oracleSanitized(part1, 120), tracker.windowText())
        assertEquals(MessageParser.sanitizeStreamingTail(part1), tracker.sanitizedFullText())
        tracker.append("⟧ and prose after")
        val full = part1 + "⟧ and prose after"
        assertEquals(oracleSanitized(full, 120), tracker.windowText())
        assertEquals(MessageParser.sanitizeStreamingTail(full), tracker.sanitizedFullText())
    }

    @Test
    fun halfTypedThinkTag_heldBack() {
        val tracker = StreamTailTracker(8_000, sanitize = true)
        tracker.append("answer text ")
        tracker.append("<thin")
        assertEquals("answer text", tracker.windowText())
        tracker.append("g remains prose") // "<thing…" is not a tag prefix — it must come back
        assertEquals("answer text <thing remains prose", tracker.windowText())
    }

    @Test
    fun segmentBreakAppends_matchOracle() {
        // The SegmentBreak event appends "\n\n" when not already present — the tracker sees the
        // same appends the StringBuilder did.
        val tracker = StreamTailTracker(160, sanitize = true)
        tracker.append("first segment of the turn")
        if (tracker.isNotEmpty() && !tracker.endsWith("\n\n")) tracker.append("\n\n")
        tracker.append("second segment")
        val raw = "first segment of the turn\n\nsecond segment"
        assertEquals(raw, tracker.rawText())
        assertEquals(oracleSanitized(raw, 160), tracker.windowText())
    }
}
