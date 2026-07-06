package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Braille glyphs from sparse -> dense; the snake head is densest and the tail fades to sparse. */
private val BRAILLE_RAMP = listOf("⠁", "⠉", "⠋", "⠛", "⠟", "⠿", "⡿", "⣿")

/**
 * A "snake" of Braille glyphs that travels along the contour of a shape without ever filling it in.
 * The default shape is a lemniscate (figure-8) — continuous and evocative of "connecting".
 *
 * Pass a custom [pathProvider] later to trace a real logo outline.
 */
@Composable
fun BrailleSnakeAnimation(
    modifier: Modifier = Modifier,
    color: Color,
    color2: Color = color,
    running: Boolean = true,
    snakeLength: Int = 22,
    periodMillis: Int = 5200,
    glyphSize: Float = 7f,
    pathProvider: (Size) -> Path = ::wingPath,
) {
    // The infinite transition only exists while actually animating: an idle/hidden snake (the
    // drawer emblem with the drawer closed, Battery Saver on) must not keep a frame-clock client
    // alive rendering ornament at 60fps — that's a measurable battery cost for zero visible pixels.
    val reducedMotion by rememberReducedMotion()
    val animate = running && !reducedMotion
    val head = if (animate) {
        val transition = rememberInfiniteTransition(label = "braille-snake")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Gentle ease-in-out so the conga line breathes rather than marches.
                animation = tween(durationMillis = periodMillis, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "head",
        ).value
    } else 0.25f
    val pathMeasure = remember { PathMeasure() }
    // Coverage of the contour by the conga line; the remaining fraction is the "pause" gap as the
    // head wraps around. Higher coverage -> a smidge shorter pause.
    val spacing = 0.88f / snakeLength // fraction of the path between dots

    Canvas(modifier) {
        val path = pathProvider(size)
        pathMeasure.setPath(path, true)
        val length = pathMeasure.length
        if (length <= 0f) return@Canvas

        val headPos = head
        // A thin "conga line" of dots tracing the contour: a bright, slightly larger head fading
        // to small, faint dots at the tail. Drawn as circles so each dot sits exactly on the path.
        for (i in 0 until snakeLength) {
            val raw = headPos - i * spacing
            val frac = ((raw % 1f) + 1f) % 1f
            val pos = pathMeasure.getPosition(frac * length)
            val t = 1f - i / snakeLength.toFloat() // 1 at head -> 0 at tail
            val radius = (glyphSize / 2f) * (0.40f + 0.60f * t)
            // Head wears accent 1; the tail cools into accent 2 — one gradient creature.
            drawCircle(
                color = androidx.compose.ui.graphics.lerp(color2, color, t).copy(alpha = 0.10f + 0.90f * t),
                radius = radius,
                center = pos,
            )
        }
    }
}

/**
 * A stylized Hermes wing (as on the winged sandal): a smooth leading sweep up to the trailing tip,
 * then three feather scallops back down to the base. Traced as one closed contour.
 */
private fun wingPath(size: Size): Path {
    val w = size.width
    val h = size.height
    val path = Path()
    fun x(fx: Float) = fx * w
    fun y(fy: Float) = fy * h
    // A clean Hermes wing: one long upper sweep from the base to the trailing tip, then three
    // smoothly-curved feather tips flowing back to the base. Smooth curves only (no notches), so
    // the Braille beads trace a continuous, well-fitting silhouette.
    path.moveTo(x(0.12f), y(0.58f))
    // Upper leading edge sweeping up to the tip.
    path.cubicTo(x(0.30f), y(0.24f), x(0.62f), y(0.12f), x(0.92f), y(0.20f))
    // Feather 1 (longest, top).
    path.cubicTo(x(0.78f), y(0.30f), x(0.70f), y(0.34f), x(0.60f), y(0.42f))
    // Feather 2 (middle).
    path.cubicTo(x(0.74f), y(0.40f), x(0.60f), y(0.50f), x(0.46f), y(0.58f))
    // Feather 3 (lower).
    path.cubicTo(x(0.60f), y(0.56f), x(0.46f), y(0.66f), x(0.32f), y(0.74f))
    // Trailing underside back to the base.
    path.cubicTo(x(0.40f), y(0.66f), x(0.22f), y(0.70f), x(0.12f), y(0.58f))
    path.close()
    return path
}

/** A Gerono lemniscate (figure-8) sampled into a Compose [Path], centered and padded within [size]. */
@Suppress("unused")
private fun lemniscatePath(size: Size): Path {
    val path = Path()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val ax = size.width / 2f * 0.82f
    val ay = size.height / 2f * 0.82f
    val steps = 160
    for (i in 0..steps) {
        val theta = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
        // x = sin(t), y = sin(t)cos(t)  -> a clean horizontal figure-8
        val x = cx + ax * sin(theta)
        val y = cy + ay * sin(theta) * cos(theta)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
