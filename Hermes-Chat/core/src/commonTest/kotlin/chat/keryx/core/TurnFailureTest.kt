package chat.keryx.core

import chat.keryx.core.model.TurnFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The failed-turn card's words come from the gateway's layer; a gateway with no layer still gets a card. */
class TurnFailureTest {

    @Test
    fun everyLayerHasItsOwnTitle_andTheyDiffer() {
        val layers = listOf("provider", "endpoint", "streaming", "auth", "billing", "gateway", "runtime", "disk")
        val titles = layers.map { TurnFailure(it, "", true, "").title }
        assertEquals(titles.size, titles.toSet().size)
        titles.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun anUnknownOrMissingLayerReadsGenerically() {
        assertEquals("The turn failed", TurnFailure("", "", true, "x").title)
        assertEquals("The turn failed", TurnFailure("something-new", "", true, "x").title)
    }

    @Test
    fun theWireDefaultsToRetryable_andKeepsTheWords() {
        val f = TurnFailure.fromWire(layer = null, code = null, retryable = null, message = "HTTP 500")
        assertTrue(f.retryable)
        assertEquals("", f.layer)
        assertEquals("HTTP 500", f.message)
        val g = TurnFailure.fromWire("provider", "content_policy", false, "refused")
        assertTrue(!g.retryable)
        assertEquals("The provider refused", g.title)
    }
}
