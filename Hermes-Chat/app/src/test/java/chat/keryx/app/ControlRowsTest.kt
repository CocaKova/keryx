package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient.ConfigKnob
import chat.keryx.app.presentation.ui.components.ControlRow
import chat.keryx.app.presentation.ui.components.GROUP_BLURBS
import chat.keryx.app.presentation.ui.components.buildControlRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Controls tab's row plan (2.6.2): grouping order, the collapse state, and the search cut.
 * Pure — the LazyColumn only paints what this returns, and a chip's jump-to-group relies on
 * the group header's key being in the plan at a findable index.
 */
class ControlRowsTest {

    private fun knob(key: String, label: String, group: String, desc: String = "") = ConfigKnob(
        key = key, label = label, description = desc, kind = "bool", group = group, value = "true",
        choices = emptyList(), min = null, max = null, minF = null, maxF = null,
        applies = "next turn", locked = false,
    )

    private val knobs = listOf(
        knob("terminal.timeout", "Command timeout", "Terminal", "How long a shell command may run each turn"),
        knob("display.reasoning", "Reasoning blocks", "Display"),
        knob("display.timestamps", "Timestamps", "Display"),
        knob("zeta.thing", "Zeta thing", "Zeta"),
        knob("agent.max_turns", "Max turns", "Behavior", "Turn ceiling per message"),
    )

    private fun keys(rows: List<ControlRow>) = rows.map { it.key }

    @Test
    fun `collapsed by default - groups in the deliberate order, unknown groups last`() {
        val rows = buildControlRows(null, null, knobs, query = "", expanded = emptySet(), offerable = true)
        assertEquals(
            listOf("error", "knobs:hdr", "knobhdr:Behavior", "knobhdr:Display", "knobhdr:Terminal", "knobhdr:Zeta", "footer"),
            keys(rows),
        )
        val header = rows.filterIsInstance<ControlRow.KnobsHeader>().single()
        assertEquals(5, header.total)
        assertEquals(listOf("Behavior" to 1, "Display" to 2, "Terminal" to 1, "Zeta" to 1), header.groups)
        assertTrue(rows.filterIsInstance<ControlRow.Group>().none { it.open })
    }

    @Test
    fun `an expanded group lists its knobs right under its header`() {
        val rows = buildControlRows(null, null, knobs, query = "", expanded = setOf("Display"), offerable = true)
        val k = keys(rows)
        val hdr = k.indexOf("knobhdr:Display")
        assertEquals(listOf("knob:display.reasoning", "knob:display.timestamps"), k.subList(hdr + 1, hdr + 3))
        assertEquals("knobhdr:Terminal", k[hdr + 3])
        assertTrue(rows.filterIsInstance<ControlRow.Group>().single { it.name == "Display" }.open)
    }

    @Test
    fun `search cuts across groups by label description or key and opens every hit`() {
        // "turn" hits Behavior's label AND Terminal's description; Display and Zeta vanish.
        val rows = buildControlRows(null, null, knobs, query = "TURN", expanded = emptySet(), offerable = true)
        assertEquals(
            listOf("error", "knobs:hdr", "knobhdr:Behavior", "knob:agent.max_turns",
                "knobhdr:Terminal", "knob:terminal.timeout", "footer"),
            keys(rows),
        )
        assertTrue(rows.filterIsInstance<ControlRow.Group>().all { it.open && it.count == 1 })
        // by key
        val byKey = buildControlRows(null, null, knobs, query = "zeta.th", expanded = emptySet(), offerable = true)
        assertEquals(listOf("knobhdr:Zeta", "knob:zeta.thing"), keys(byKey).filter { it.startsWith("knobhdr:") || it.startsWith("knob:") })
    }

    @Test
    fun `a search with no hit says so instead of an empty section list`() {
        val rows = buildControlRows(null, null, knobs, query = "nope", expanded = setOf("Display"), offerable = true)
        assertEquals(listOf("error", "knobs:hdr", "knobs:nomatch", "footer"), keys(rows))
    }

    @Test
    fun `no knobs - the plugin-too-old line only while nothing is loading or failing`() {
        assertEquals(
            listOf("error", "knobs:none", "footer"),
            keys(buildControlRows(null, null, emptyList(), "", emptySet(), offerable = true)),
        )
        assertEquals(
            listOf("error", "footer"),
            keys(buildControlRows(null, null, emptyList(), "", emptySet(), offerable = false)),
        )
    }

    @Test
    fun `every group the plugin ships has a blurb`() {
        // The plugin's 13 groups (keryx_stream.py _CONFIG_KNOBS) plus Gateway, which the order
        // list reserves. A new group upstream without a blurb still renders — this pins that
        // the known ones don't silently lose theirs.
        val shipped = listOf(
            "Behavior", "Display", "Missions", "Compression", "Agent", "Memory", "Skills",
            "Tools", "Terminal", "Browser", "Delegation", "Voice", "Safety",
        )
        shipped.forEach { assertTrue("blurb for $it", GROUP_BLURBS[it].orEmpty().isNotBlank()) }
    }
}
