package chat.keryx.app

import androidx.compose.ui.graphics.Color
import chat.keryx.app.presentation.ui.components.ROOM_TINTS_PAPER
import chat.keryx.app.presentation.ui.components.ROOM_TINTS_VOID
import chat.keryx.app.presentation.ui.components.roomLightIndex
import chat.keryx.app.presentation.ui.components.roomLightRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The 3.0 room plates: a monogram is ink, not a smudge, and no room changes colour family. */
class RoomTintTest {
    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every monogram clears 4_5 on its plate, on paper and on the void`() {
        for ((i, t) in (ROOM_TINTS_PAPER + ROOM_TINTS_VOID).withIndex()) {
            val c = contrast(t.ink, t.ground)
            assertTrue("tint $i scores $c", c >= 4.5)
        }
    }

    @Test
    fun `the light a room carries reads on its background`() {
        val parchment = Color(0xFFF6F2EA)
        val void = Color(0xFF000000)
        ROOM_TINTS_PAPER.forEach { assertTrue("paper ink ${it.ink}", contrast(it.ink, parchment) >= 3.0) }
        ROOM_TINTS_VOID.forEach { assertTrue("void ink ${it.ink}", contrast(it.ink, void) >= 3.0) }
    }

    @Test
    fun `the tints follow the lights one for one`() {
        assertEquals(8, ROOM_TINTS_PAPER.size)
        assertEquals(8, ROOM_TINTS_VOID.size)
        // Same index as the raw light: a room keeps its hue family across the redesign.
        for (name in listOf("Ping pong", "Write X post on ComfyUI 0.40", "Run uptime, df -h")) {
            assertEquals(roomLightRaw(name), listOf(
                Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
                Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFF06292),
            )[roomLightIndex(name)])
        }
    }
}
