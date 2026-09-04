package chat.keryx.core

import chat.keryx.core.model.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The walk-back after a model refuses a thinking level. The failure these prevent is the one the
 * refusal channel was built for and never wired to: set a level the brain's template won't
 * render, and EVERY turn dies the same way, in silence.
 */
class ReasoningEffortTest {

    private val brain = "qwen38-27b"

    @Test
    fun `a refused level steps down one rung`() {
        assertEquals("xhigh", ReasoningEffort.fallbackBelow(brain, "max", emptySet()))
        assertEquals("low", ReasoningEffort.fallbackBelow(brain, "medium", emptySet()))
    }

    @Test
    fun `a rung already known bad on this model is skipped`() {
        // The measured Qwen 3.8 case: the template renders low/medium/high and nothing else, so
        // walking back from ultra must not spend a turn on max and another on xhigh.
        val rejected = setOf(
            ReasoningEffort.rejectionKey(brain, "max"),
            ReasoningEffort.rejectionKey(brain, "xhigh"),
        )
        assertEquals("high", ReasoningEffort.fallbackBelow(brain, "ultra", rejected))
    }

    @Test
    fun `a refusal is remembered per model, not globally`() {
        val rejected = setOf(ReasoningEffort.rejectionKey("other-brain", "xhigh"))
        assertEquals("xhigh", ReasoningEffort.fallbackBelow(brain, "max", rejected))
    }

    @Test
    fun `the bottom of the scale has nowhere to fall`() {
        assertNull(ReasoningEffort.fallbackBelow(brain, "minimal", emptySet()))
    }

    @Test
    fun `off is not a rung, and never becomes one`() {
        // Thinking off is a different decision from thinking less. A walk-back must never make it.
        assertNull(ReasoningEffort.fallbackBelow(brain, "none", emptySet()))
        val allButMinimal = ReasoningEffort.LEVELS.drop(1)
            .map { ReasoningEffort.rejectionKey(brain, it) }.toSet()
        assertEquals("minimal", ReasoningEffort.fallbackBelow(brain, "low", allButMinimal))
        assertNull(ReasoningEffort.fallbackBelow(brain, "minimal", allButMinimal))
    }

    @Test
    fun `an unknown level is not walked back from`() {
        assertNull(ReasoningEffort.fallbackBelow(brain, "turbo", emptySet()))
        assertNull(ReasoningEffort.fallbackBelow(brain, "", emptySet()))
    }
}
