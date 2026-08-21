package chat.keryx.app

import chat.keryx.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale is upstream's, not ours — these pin the port against
 * `hermes_constants.VALID_REASONING_EFFORTS` and the desktop/dashboard helpers, including the
 * two rules that are easy to get subtly wrong: `none` is off rather than a level, and an
 * unrecognized value from a newer gateway must still be able to name itself.
 */
class ReasoningEffortTest {

    @Test
    fun theScaleMatchesTheBackend() {
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            ReasoningEffort.LEVELS,
        )
        assertEquals("none", ReasoningEffort.VALUES.first())
        assertEquals(8, ReasoningEffort.VALUES.size)
    }

    @Test
    fun emptyAndUnknownNormalizeToTheHermesDefault() {
        assertEquals("medium", ReasoningEffort.normalize(""))
        assertEquals("medium", ReasoningEffort.normalize(null))
        assertEquals("medium", ReasoningEffort.normalize("  "))
        assertEquals("medium", ReasoningEffort.normalize("bogus"))
        assertEquals("high", ReasoningEffort.normalize(" HIGH "))
        assertEquals("none", ReasoningEffort.normalize("None"))
    }

    @Test
    fun anUnknownLevelStillNamesItself() {
        // A gateway that grows a level we don't know must not be relabelled as one we do.
        assertEquals("cosmic", ReasoningEffort.shortLabel("cosmic"))
        assertEquals("", ReasoningEffort.shortLabel(""))
        assertEquals("XHigh", ReasoningEffort.shortLabel("xhigh"))
        assertEquals("Off", ReasoningEffort.shortLabel("none"))
    }

    @Test
    fun noneIsThinkingOffAndEmptyInherits() {
        assertFalse(ReasoningEffort.thinkingEnabled("none"))
        assertTrue(ReasoningEffort.thinkingEnabled("minimal"))
        assertTrue(ReasoningEffort.thinkingEnabled(""))
        assertFalse(ReasoningEffort.thinkingEnabled("", fallback = "none"))
    }

    @Test
    fun statusLabelAlwaysCarriesTheLevelInForce() {
        assertEquals("qwen38-27b · High", ReasoningEffort.statusLabel("qwen38-27b", "high"))
        // No session level: the profile default is what the agent will actually use.
        assertEquals("qwen38-27b · Max", ReasoningEffort.statusLabel("qwen38-27b", "", "max"))
        // Neither: Hermes' own fallback, never a bare model name.
        assertEquals("qwen38-27b · Med", ReasoningEffort.statusLabel("qwen38-27b", null, null))
        // No model, no label — the pill says "model" on its own elsewhere.
        assertEquals("", ReasoningEffort.statusLabel("", "high"))
    }

    @Test
    fun aModelsRefusalIsRecognizedFromItsOwnWords() {
        // The exact strings a live vLLM/Qwen 3.8 brain answered when handed levels its chat
        // template doesn't implement (measured 2026-08-16, HTTP 400 both).
        assertTrue(
            ReasoningEffort.isLevelRejection(
                "Unexpected reasoning effort minimal. Supported types are xhigh (default), medium, and low.",
            ),
        )
        assertTrue(
            ReasoningEffort.isLevelRejection(
                "1 validation error:\n  {'type': 'literal_error', 'loc': ('body', 'reasoning_effort'), 'msg': ...}",
            ),
        )
    }

    @Test
    fun ordinaryFailuresAreNotRefusals() {
        // Overriding a level the user chose is expensive to get wrong, so the match stays
        // narrow: reasoning must be named AND refused.
        assertFalse(ReasoningEffort.isLevelRejection("connection reset by peer"))
        assertFalse(ReasoningEffort.isLevelRejection("The model spent a long time on reasoning effort here"))
        assertFalse(ReasoningEffort.isLevelRejection("invalid api key"))
        assertFalse(ReasoningEffort.isLevelRejection(null))
        assertFalse(ReasoningEffort.isLevelRejection(""))
    }

    @Test
    fun refusalsAreRememberedPerModel() {
        // A level refused by one model says nothing about another — the key carries both.
        assertEquals("qwen38-27b|max", ReasoningEffort.rejectionKey("qwen38-27b", "MAX"))
        assertEquals("qwen38-27b|max", ReasoningEffort.rejectionKey(" qwen38-27b ", "max"))
    }

    @Test
    fun validityIsTheWiresOwnVocabulary() {
        assertTrue(ReasoningEffort.isValid("ultra"))
        assertTrue(ReasoningEffort.isValid("none"))
        assertFalse(ReasoningEffort.isValid("bogus"))
        assertFalse(ReasoningEffort.isValid(null))
    }

    // A call's "skip thinking" asks for the quietest level the MODEL takes, learned from refusals:
    // the SGLang Qwen 3.8 template killed every call turn on `none` ("Supported types are xhigh,
    // medium, and low"), so once `none` is on that model's refusal list the call asks for `low`.
    @Test
    fun callQuietLevelStepsPastRefusedLevels() {
        val m = "qwen3.8-27b"
        assertEquals("none", ReasoningEffort.quietestFor(m, emptySet()))
        assertEquals("minimal", ReasoningEffort.quietestFor(m, setOf(ReasoningEffort.rejectionKey(m, "none"))))
        assertEquals("low", ReasoningEffort.quietestFor(m, setOf(
            ReasoningEffort.rejectionKey(m, "none"), ReasoningEffort.rejectionKey(m, "minimal"))))
        assertEquals(null, ReasoningEffort.quietestFor(m, setOf(
            ReasoningEffort.rejectionKey(m, "none"), ReasoningEffort.rejectionKey(m, "minimal"),
            ReasoningEffort.rejectionKey(m, "low"))))
        // refusals are per model — another brain still gets `none`
        assertEquals("none", ReasoningEffort.quietestFor("other", setOf(ReasoningEffort.rejectionKey(m, "none"))))
    }
}
