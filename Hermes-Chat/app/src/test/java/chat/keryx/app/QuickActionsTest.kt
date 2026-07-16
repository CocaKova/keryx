package chat.keryx.app

import chat.keryx.app.presentation.ui.components.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ⟦keryx:ask|…⟧ decision marker: options must surface as a QuickActions segment (chips /
 * notification buttons), the marker must never survive into displayed or notified text, and a
 * message without the marker must be completely unaffected (protocol degrade contract).
 */
class QuickActionsTest {

    @Test
    fun askMarker_extractsOptionsAndStripsMarker() {
        val content = "Ship 1.18 now or wait for the vision A/B?\n⟦keryx:ask|Ship it|Wait⟧"
        val k = MessageParser.extractKeryx(content)
        assertEquals(listOf("Ship it", "Wait"), k.actions)
        assertFalse(k.text.contains('⟦'))
        assertEquals("Ship 1.18 now or wait for the vision A/B?", k.text)
    }

    @Test
    fun askMarker_becomesQuickActionsSegment() {
        val segments = MessageParser.parse("Pick one.\n⟦keryx:ask|A|B|C⟧")
        val actions = segments.filterIsInstance<MessageParser.Segment.QuickActions>().single()
        assertEquals(listOf("A", "B", "C"), actions.options)
        // The visible text carries no marker residue.
        val text = segments.filterIsInstance<MessageParser.Segment.Text>().single().text
        assertEquals("Pick one.", text)
    }

    @Test
    fun noMarker_noQuickActions() {
        val segments = MessageParser.parse("Just a normal answer with | pipes | in prose.")
        assertTrue(segments.filterIsInstance<MessageParser.Segment.QuickActions>().isEmpty())
        assertTrue(MessageParser.quickActions("nothing here").isEmpty())
    }

    @Test
    fun options_trimmedDedupedAndCapped() {
        val k = MessageParser.extractKeryx("q ⟦keryx:ask| Approve |Approve|Deny|Later|Escalate|Sixth|Seventh|Eighth⟧")
        // Trimmed, "Approve" deduped, capped at 6.
        assertEquals(listOf("Approve", "Deny", "Later", "Escalate", "Sixth", "Seventh"), k.actions)
    }

    @Test
    fun unterminatedFinalMarker_recovered() {
        // Brains stop generating without writing ⟧ (finish_reason=stop mid-marker). A marker that
        // opens on the message's final line is unambiguous — recover it instead of leaking raw
        // text (or worse: rendering a question with no way to answer it).
        val k = MessageParser.extractKeryx("What do you want to do? ⟦keryx:ask|Skip for now|Check others|I'll confirm myself|")
        assertEquals(listOf("Skip for now", "Check others", "I'll confirm myself"), k.actions)
        assertFalse(k.text.contains('⟦'))
        assertEquals("What do you want to do?", k.text)
    }

    @Test
    fun unterminatedMidMessageMarker_staysRawText() {
        // Only a message-FINAL unterminated marker is recoverable; one followed by more lines has
        // no defensible boundary and must stay inert (never swallow trailing prose into options).
        val k = MessageParser.extractKeryx("q ⟦keryx:ask|A|B\nmore prose after")
        assertTrue(k.actions.isEmpty())
        assertTrue(k.text.contains("⟦keryx:ask|A|B"))
    }

    @Test
    fun blankOrEmptyOptionList_yieldsNothing() {
        assertTrue(MessageParser.quickActions("q ⟦keryx:ask|⟧").isEmpty())
        assertTrue(MessageParser.quickActions("q ⟦keryx:ask| | |⟧").isEmpty())
    }

    @Test
    fun humanSender_markerRendersAsPlainText_noChips() {
        // agentChrome=false is the human-sender path: a human QUOTING the marker must not
        // sprout actionable chips (same gate as tool/telemetry chrome).
        val segments = MessageParser.parse("look at this: ⟦keryx:ask|Approve|Deny⟧", agentChrome = false)
        assertTrue(segments.filterIsInstance<MessageParser.Segment.QuickActions>().isEmpty())
    }

    @Test
    fun markerCoexistsWithReasoningAndCitations() {
        val content = "<think>weigh options</think>Decision needed ⟦c1⟧\n" +
            "⟦cite 1|memory|bench result|abcd-20260712⟧\n" +
            "⟦keryx:ask|Roll back|Keep B⟧"
        val segments = MessageParser.parse(content)
        assertTrue(segments.any { it is MessageParser.Segment.Thinking })
        assertTrue(segments.any { it is MessageParser.Segment.Citations })
        val actions = segments.filterIsInstance<MessageParser.Segment.QuickActions>().single()
        assertEquals(listOf("Roll back", "Keep B"), actions.options)
    }

    @Test
    fun midStream_halfTypedMarker_heldBackBySanitizer() {
        // While streaming, a half-written marker at the tail must not flash as literal text.
        val sanitized = MessageParser.sanitizeStreamingTail("Answer so far ⟦keryx:ask|App")
        assertEquals("Answer so far", sanitized)
    }

    // --- Use vs. mention (2026-07-16: an explainer message's example markers hijacked the tiles
    // and were stripped from the prose). A live marker sits on the last non-blank line outside
    // code; a marker anywhere else is being talked about and must render literally, untouched.

    @Test
    fun terminatedMidMessageMarker_isMention_staysLiteralWithNoTiles() {
        val k = MessageParser.extractKeryx(
            "You write ⟦keryx:ask|Approve|Deny⟧ at the end of a message.\nThat renders as tiles.",
        )
        assertTrue(k.actions.isEmpty())
        assertTrue(k.text.contains("⟦keryx:ask|Approve|Deny⟧"))
    }

    @Test
    fun markerInsideCodeFence_isMention() {
        val content = "The protocol looks like this:\n```\n⟦keryx:ask|Approve|Deny⟧\n```"
        val k = MessageParser.extractKeryx(content)
        assertTrue(k.actions.isEmpty())
        assertTrue(k.text.contains("⟦keryx:ask|Approve|Deny⟧"))
    }

    @Test
    fun markerInInlineCode_isMention_evenOnLastLine() {
        val k = MessageParser.extractKeryx("End with `⟦keryx:ask|Yes|No⟧` to offer tiles.")
        assertTrue(k.actions.isEmpty())
        assertTrue(k.text.contains("`⟦keryx:ask|Yes|No⟧`"))
    }

    @Test
    fun explainerWithExamples_realAskOnLastLineWins() {
        // The exact incident shape: a message ABOUT the protocol, examples in prose and a fence,
        // plus a genuine decision on the final line. Only the final marker is live; every example
        // survives in the displayed text.
        val content = "Ask markers like ⟦keryx:ask|Option A|Option B⟧ become tiles:\n" +
            "```\n⟦keryx:ask|Approve|Deny⟧\n```\n" +
            "Want a deeper dive?\n" +
            "⟦keryx:ask|Yes, keep going|No, that covers it⟧"
        val k = MessageParser.extractKeryx(content)
        assertEquals(listOf("Yes, keep going", "No, that covers it"), k.actions)
        assertTrue(k.text.contains("⟦keryx:ask|Option A|Option B⟧"))
        assertTrue(k.text.contains("⟦keryx:ask|Approve|Deny⟧"))
        assertFalse(k.text.contains("Yes, keep going"))
    }

    @Test
    fun citeMarkerInsideCodeFence_isMention() {
        // Family invariant: EVERY marker quoted in code is a mention — a fenced cite example must
        // neither register as a citation nor vanish from the code block.
        val content = "Plugin emits:\n```\n⟦cite 1|web|Docs|https://x⟧\n```\ndone ⟦cite 2|memory|real|live⟧"
        val k = MessageParser.extractKeryx(content)
        assertEquals(1, k.citations.size)
        assertEquals("real", k.citations.single().label)
        assertTrue(k.text.contains("⟦cite 1|web|Docs|https://x⟧"))
        assertFalse(k.text.contains("⟦cite 2"))
    }

    @Test
    fun unterminatedFence_isStillCode_markerInsideStaysMention() {
        // Streaming intermediate: the fence hasn't closed yet — its contents are already code,
        // so a half-streamed example must never flash into live tiles.
        val k = MessageParser.extractKeryx("Example:\n```\n⟦keryx:ask|Approve|Deny⟧")
        assertTrue(k.actions.isEmpty())
    }

    // --- Gateway chrome below the marker (2026-07-16, hours after the mention fix): the gateway
    // appends its runtime footer to the committed message BELOW the model's marker line, so tiles
    // rendered mid-stream then demoted to raw text on commit. A live ask is followed only by
    // chrome (footer/subtext/markers/blanks), not literally last.

    @Test
    fun markerFollowedByRuntimeFooter_isStillLive() {
        val k = MessageParser.extractKeryx(
            "Deploy now or wait?\n⟦keryx:ask|Deploy|Wait⟧\nqwen35b · 42% · ~/workspace",
        )
        assertEquals(listOf("Deploy", "Wait"), k.actions)
        assertFalse(k.text.contains('⟦'))
        assertTrue(k.text.contains("qwen35b · 42% · ~/workspace"))
    }

    @Test
    fun markerFollowedByFooterSubtextAndMarkers_isStillLive() {
        val k = MessageParser.extractKeryx(
            "Pick one ⟦c1⟧\n⟦keryx:ask|A|B⟧\n⟦cite 1|web|Docs|https://x⟧\n-# quiet aside\nqwen35b · 42% · ~/dir",
        )
        assertEquals(listOf("A", "B"), k.actions)
        assertEquals(1, k.citations.size)
        assertFalse(k.text.contains("keryx:ask"))
    }

    @Test
    fun unterminatedMarkerAboveFooter_recovered() {
        // finish_reason=stop mid-marker, THEN the gateway appends its footer: the unterminated
        // marker is no longer message-final, but it still ends the dialogue — recover it.
        val k = MessageParser.extractKeryx(
            "What next?\n⟦keryx:ask|Skip|Check others\nqwen35b · 42% · ~/dir",
        )
        assertEquals(listOf("Skip", "Check others"), k.actions)
        assertFalse(k.text.contains('⟦'))
    }

    @Test
    fun markerFollowedByProse_notLive_evenWithFooterBelow() {
        // Prose between the marker and the chrome keeps it a mention — the chrome gate must not
        // grant tile status to an example just because the message also ends with a footer.
        val k = MessageParser.extractKeryx(
            "Use ⟦keryx:ask|Yes|No⟧ to offer tiles.\nMore explanation here.\nqwen35b · 42% · ~/dir",
        )
        assertTrue(k.actions.isEmpty())
        assertTrue(k.text.contains("⟦keryx:ask|Yes|No⟧"))
    }

    @Test
    fun quickActions_helperMatchesSegmentPath() {
        val content = "Do it?\n⟦keryx:ask|Yes|No⟧"
        assertEquals(
            MessageParser.parse(content)
                .filterIsInstance<MessageParser.Segment.QuickActions>().single().options,
            MessageParser.quickActions(content),
        )
    }
}
