package chat.keryx.app.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

object BubbleStyles {
    const val SOLID = "Solid"
    const val GRADIENT = "Gradient"
    const val GLASS = "Glass"
    val ALL = listOf(SOLID, GRADIENT, GLASS)
    const val DEFAULT = GRADIENT
}

/** Resolved look for a message bubble: a fill brush, readable text color, and optional border. */
data class BubbleAppearance(val brush: Brush, val textColor: Color, val border: Color?)

/** Pick black/white text for maximum contrast against a given background color. */
fun contrastColorFor(bg: Color): Color =
    if (bg.luminance() > 0.5f) Color(0xFF1C1C1E) else Color.White

@Composable
fun bubbleAppearance(isMine: Boolean, style: String): BubbleAppearance {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val accent2 = cs.tertiary
    return when (style) {
        BubbleStyles.GLASS ->
            if (isMine) BubbleAppearance(
                brush = SolidColor(accent.copy(alpha = 0.20f)),
                textColor = cs.onBackground,
                border = accent.copy(alpha = 0.55f),
            ) else BubbleAppearance(
                brush = SolidColor(cs.onSurface.copy(alpha = 0.06f)),
                textColor = cs.onSurface,
                border = cs.onSurface.copy(alpha = 0.14f),
            )

        BubbleStyles.SOLID ->
            if (isMine) BubbleAppearance(
                brush = SolidColor(accent),
                textColor = contrastColorFor(accent),
                border = null,
            ) else BubbleAppearance(
                brush = SolidColor(cs.surfaceVariant),
                textColor = cs.onSurface,
                border = null,
            )

        else -> // GRADIENT (default) — accent melting into accent 2, the sunset-dream look
            if (isMine) BubbleAppearance(
                brush = Brush.linearGradient(
                    listOf(accent, lerp(accent, accent2, 0.55f), lerp(accent2, Color.Black, 0.12f))
                ),
                textColor = contrastColorFor(accent),
                border = null,
            ) else BubbleAppearance(
                brush = Brush.linearGradient(
                    listOf(cs.surfaceVariant, lerp(cs.surfaceVariant, cs.surface, 0.6f))
                ),
                textColor = cs.onSurface,
                border = cs.onSurface.copy(alpha = 0.10f),
            )
    }
}
