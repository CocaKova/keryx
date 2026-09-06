package chat.keryx.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import chat.keryx.app.presentation.ui.components.contrastRatio
import chat.keryx.app.presentation.ui.components.readableGradient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gradient bubble's fill spans the whole message, so on a tall one (a long cron prompt, a
 * pasted log) whole paragraphs land on the far stop. The text colour used to be picked against
 * the first stop only; with the default amber → dusk neither ink nor white cleared AA on every
 * stop, and a long bubble in dark mode put lines on the losing end (Jonny, 2026-09-05).
 *
 * The law: one ink, readable on EVERY stop, whatever the bubble's height.
 */
class BubbleGradientContrastTest {

    private val AA = 4.5f
    private val amber = Color(0xFFE55A00)
    private val dusk = Color(0xFF8B5CF6)

    private fun sunset(a: Color, b: Color) = listOf(a, lerp(a, b, 0.55f), lerp(b, Color.Black, 0.12f))

    @Test
    fun `default accents - every stop clears AA for the chosen ink`() {
        val r = readableGradient(sunset(amber, dusk))
        r.stops.forEach { assertTrue("${r.textColor} on $it", contrastRatio(r.textColor, it) >= AA) }
        assertEquals(3, r.stops.size)
    }

    @Test
    fun `pale accent over a dark partner - ink would fail on the far stop, so the far stop is lifted`() {
        val paleGold = Color(0xFFF2D28C)
        val r = readableGradient(sunset(paleGold, Color(0xFF2A1B4F)))
        r.stops.forEach { assertTrue(contrastRatio(r.textColor, it) >= AA) }
    }

    @Test
    fun `a gradient that already reads comes back untouched`() {
        val stops = listOf(Color(0xFF12121A), Color(0xFF1D1D28), Color(0xFF000000))
        val r = readableGradient(stops)
        assertEquals(stops, r.stops)
        assertEquals(Color.White, r.textColor)
    }

    @Test
    fun `pressing moves a stop the least it must - the hue survives`() {
        val r = readableGradient(sunset(amber, dusk))
        // The amber start is pressed toward black just far enough for white; it stays warm.
        val start = r.stops.first()
        assertTrue(start.red > start.blue && start.red > start.green)
        assertTrue(contrastRatio(r.textColor, start) < AA + 0.6f)
    }
}
