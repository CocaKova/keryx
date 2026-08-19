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
    const val GILDED = "Gilded"
    val ALL = listOf(GILDED, SOLID, GRADIENT, GLASS)
    const val DEFAULT = GILDED
}

/** Resolved look for a message bubble: a fill brush, readable text color, and optional border.
 *  [edgeBrush] outranks [border] when present — the gilded hairline, a gradient stroke that reads
 *  as light caught on the rim rather than a drawn outline. */
data class BubbleAppearance(
    val brush: Brush,
    val textColor: Color,
    val border: Color?,
    val edgeBrush: Brush? = null,
)

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

        BubbleStyles.GILDED ->
            // The gilded void (2.1): color moves off the surfaces and onto the light. Both bubbles
            // go matte; identity lives in a hairline of caught light on the rim — amber-to-dusk
            // gilt on mine, a faint neutral seam on the agent's, so the accents stay reserved for
            // what's alive (streams, dust, the shimmer ring).
            if (isMine) BubbleAppearance(
                brush = SolidColor(lerp(cs.surface, accent, 0.24f)),
                textColor = cs.onBackground,
                border = null,
                edgeBrush = Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.90f),
                        lerp(accent, accent2, 0.65f).copy(alpha = 0.30f),
                    )
                ),
            ) else BubbleAppearance(
                brush = SolidColor(lerp(cs.surface, cs.surfaceVariant, 0.45f)),
                textColor = cs.onSurface,
                border = null,
                edgeBrush = Brush.verticalGradient(
                    listOf(
                        cs.onSurface.copy(alpha = 0.24f),
                        cs.onSurface.copy(alpha = 0.06f),
                    )
                ),
            )

        else -> // GRADIENT — accent melting into accent 2, the sunset-dream look
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
