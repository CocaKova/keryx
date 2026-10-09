package chat.keryx.core

import chat.keryx.core.model.KnownWire
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.model.ToolWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [KnownWire] is what `tools/hermes_drift.py` checks against Hermes; a name missing here goes unchecked. */
class KnownWireTest {

    private fun covered(name: String) =
        name in KnownWire.TOOLS || name in KnownWire.LEGACY_TOOLS || name in KnownWire.META_TOOLS ||
            KnownWire.TOOL_PREFIXES.any { name.startsWith(it) }

    @Test
    fun `every tool the grammar names is declared`() {
        val missing = ToolGrammar.namedTools.filterNot(::covered)
        assertEquals(emptyList(), missing.sorted())
    }

    @Test
    fun `every tool with a preview argument is declared`() {
        assertEquals(emptyList(), KnownWire.PRIMARY_ARGS.keys.filterNot(::covered).sorted())
    }

    @Test
    fun `the renamed five are legacy names`() {
        assertTrue(KnownWire.LEGACY_TOOLS.containsAll(ToolWire.LEGACY_ALIASES.keys))
        assertTrue(KnownWire.TOOLS.containsAll(ToolWire.LEGACY_ALIASES.values))
    }

    @Test
    fun `a name is current or legacy, never both`() {
        assertEquals(emptySet(), KnownWire.TOOLS intersect KnownWire.LEGACY_TOOLS)
        assertEquals(emptySet(), KnownWire.EVENTS intersect KnownWire.LEGACY_EVENTS)
    }
}
