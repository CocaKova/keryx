package chat.keryx.app

import chat.keryx.app.presentation.ui.components.THINKING_STYLES
import chat.keryx.app.presentation.ui.components.thinkingStyleOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The loading animations (3.0): Quill and Constellation joined, Dots and the ASCII wave retired.
 * Someone who had picked a retired one lands on the Caduceus, and Settings offers exactly what
 * the animation draws.
 */
class ThinkingStylesTest {

    @Test
    fun `the five on offer, in Settings order`() {
        assertEquals(listOf("Caduceus", "Quill", "Constellation", "Braille", "Ember Drift"), THINKING_STYLES)
    }

    @Test
    fun `a retired or unknown style reads as the Caduceus, a current one as itself`() {
        assertEquals("Caduceus", thinkingStyleOf("Dots"))
        assertEquals("Caduceus", thinkingStyleOf("ASCII Wave"))
        assertEquals("Caduceus", thinkingStyleOf(""))
        assertEquals("Caduceus", thinkingStyleOf(null))
        for (s in THINKING_STYLES) assertEquals(s, thinkingStyleOf(s))
    }

    @Test
    fun `Settings offers exactly the styles the animation draws`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val dialog = root.walkTopDown().first { it.name == "SettingsDialog.kt" }.readText()
        val row = dialog.substringAfter("SettingsChoiceLabel(\"Loading animation\")").substringBefore("selected = animationStyle")
        val offered = Regex(""""([^"]+)" to """").findAll(row).map { it.groupValues[1] }.toList()
        assertEquals(THINKING_STYLES, offered)
    }
}
