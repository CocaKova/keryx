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
