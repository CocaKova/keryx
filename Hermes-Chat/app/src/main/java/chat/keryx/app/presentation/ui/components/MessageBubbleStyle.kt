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

/**
 * Pick ink or white for maximum contrast against a given background colour.
 *
 * ⚠️ The crossover is NOT the middle of the luminance range. Contrast is (L+0.05) ratios, so
 * white and ink tie at L = √(1.05 × 0.05) − 0.05 = **0.1791**, not at 0.5. Splitting at 0.5
 * handed white to every mid-tone in between — which is exactly where the default accent lives:
 * amber #E55A00 is L 0.2397, so a Solid bubble printed its own text at **3.62:1** (white) when
 * ink was sitting right there at 4.73:1. Body text under the 4.5 AA asks for, in the style a
 * user picks *because* it is the boldest one.
 */
fun contrastColorFor(bg: Color): Color =
    if (bg.luminance() > 0.1791f) Color(0xFF1F1B14) else Color.White

/** A text-bearing gradient fill and the one ink that clears AA on every stop of it. */
data class ReadableGradient(val stops: List<Color>, val textColor: Color)

/** WCAG 2.1 contrast ratio between two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * The invariant for any gradient that carries body text: **one text colour, readable on every
 * stop** — because which stop a given line of text lands on depends on how tall the message is,
 * and the fill cannot know that. Picks ink or white by the better *worst* stop, then presses each
 * failing stop toward the text's opposite (white text: darker; ink: lighter) in small steps until
 * it clears [aa]. A gradient that already reads comes back untouched.
 */
fun readableGradient(stops: List<Color>, aa: Float = 4.5f): ReadableGradient {
    val ink = Color(0xFF1F1B14)
    val candidates = listOf(ink, Color.White)
    val text = candidates.maxBy { c -> stops.minOf { contrastRatio(c, it) } }
    val toward = if (text == Color.White) Color.Black else Color.White
    val pressed = stops.map { stop ->
        var c = stop
        var i = 0
        while (contrastRatio(text, c) < aa && i < READABLE_STEPS) {
            c = lerp(c, toward, READABLE_PRESS)
            i++
        }
        c
    }
    return ReadableGradient(pressed, text)
}

private const val READABLE_PRESS = 0.06f
private const val READABLE_STEPS = 40

/**
 * [accent] / [accent2] default to the user's own theme accents. A herald in a council room passes
 * its own light instead, so the hairline on its bubble is *its* colour (2.3 §1) — the fills stay
 * matte either way, because color is light and light means life.
 */
@Composable
fun bubbleAppearance(
    isMine: Boolean,
    style: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    accent2: Color = MaterialTheme.colorScheme.tertiary,
    heraldRim: Boolean = false,
): BubbleAppearance {
    val cs = MaterialTheme.colorScheme
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
                // A named herald in a council room signs its bubble with its own light; the
                // primary herald (and every plain human) keeps the neutral seam of 2.2, so a
                // 1:1 room is unchanged.
                edgeBrush = if (heraldRim) Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.85f),
                        lerp(accent, accent2, 0.65f).copy(alpha = 0.28f),
                    )
                ) else Brush.verticalGradient(
                    listOf(
                        cs.onSurface.copy(alpha = 0.24f),
                        cs.onSurface.copy(alpha = 0.06f),
                    )
                ),
            )

        else -> // GRADIENT — accent melting into accent 2, the sunset-dream look
            if (isMine) {
                // The fill spans the whole bubble, so on a tall message (a cron prompt, a pasted
                // log) whole paragraphs sit on the far stop, not just a corner. The text colour
                // was chosen against the FIRST stop alone: with the default amber → dusk, white
                // is 3.62:1 on the amber and ink is 3.4:1 on the dusk end — neither clears AA
                // everywhere, and a long bubble in dark mode put lines on the losing end. One
                // ink for every stop, and the stops pressed until it reads (readableGradient).
                val readable = readableGradient(
                    listOf(accent, lerp(accent, accent2, 0.55f), lerp(accent2, Color.Black, 0.12f))
                )
                BubbleAppearance(
                    brush = Brush.linearGradient(readable.stops),
                    textColor = readable.textColor,
                    border = null,
                )
            } else BubbleAppearance(
                brush = Brush.linearGradient(
                    listOf(cs.surfaceVariant, lerp(cs.surfaceVariant, cs.surface, 0.6f))
                ),
                textColor = cs.onSurface,
                border = cs.onSurface.copy(alpha = 0.10f),
            )
    }
}
