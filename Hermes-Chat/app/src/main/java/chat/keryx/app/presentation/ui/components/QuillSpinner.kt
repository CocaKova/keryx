package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sin

/**
 * Quill (3.0) — a herald's pen writing a line of ink while the reply is drafted, then lifting
 * away before the next line. Picked from the thinking-styles canvas (10-16), with Constellation.
 *
 * It wears whoever is thinking: the nib is [accent], the shaft and the ink are [accent2], the
 * ink deepened toward the page's own ink on parchment and glinting toward white on the void, so
 * the accent picker, a herald's own colours and a theme flip all reach it.
 *
 * Same cost rules as [EmberDriftSpinner]: one seconds clock read only in the draw phase (a frame
 * is a redraw, never a recomposition), and nothing allocated per frame (one [Path], reset and
 * refilled; the nib built once by [drawWithCache]).
 *
 * battery-saver-exempt: a spinner's motion is its message.
 */
@Composable
fun QuillSpinner(
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier,
) {
    val darkRoom = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = lerp(accent2, if (darkRoom) Color.White else Color(0xFF1F1B14), if (darkRoom) 0.25f else 0.35f)

    // A monotonic seconds clock. Written every frame, read only by the draw lambda below.
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (start == 0L) start = now
                timeSec = (((now - start) / 1_000_000L) % 3_600_000L).toFloat() / 1000f
            }
        }
    }

    Box(modifier = modifier.size(width = 32.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .size(width = 32.dp, height = 13.dp)
                .drawWithCache {
                    // The line is laid out on a 40 × 16 grid, scaled to the box.
                    val u = size.width / GRID_W
                    val line = Path()
                    val nib = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(-6f * u, -1.6f * u)
                        lineTo(-6f * u, 1.6f * u)
                        close()
                    }
                    val inkStroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val shaft = 10f * u
                    val shaftWidth = 0.8.dp.toPx()

                    onDrawBehind {
                        val p = (timeSec % CYCLE_S) / CYCLE_S
                        val write = min(1f, p / WRITE_END)
                        val fade = if (p < LIFT_START) 1f else (1f - (p - LIFT_START) / (1f - LIFT_START)).coerceAtLeast(0f)
                        val end = START_X + (END_X - START_X) * write

                        line.reset()
                        line.moveTo(START_X * u, yAt(START_X) * u)
                        var x = START_X + STEP
                        while (x <= end) {
                            line.lineTo(x * u, yAt(x) * u)
                            x += STEP
                        }
                        drawPath(line, ink, alpha = fade, style = inkStroke)

                        // The nib rides the head of the line, held the way a hand holds a pen:
                        // the shaft up and to the right, swaying a little with the stroke. Turned
                        // fully along the slope it read as a spinning arrow (canary, 10-16).
                        val slope = (yAt(end + 0.1f) - yAt(end)) / 0.1f
                        val angle = PEN_ANGLE + PEN_SWAY * Math.toDegrees(atan(slope).toDouble()).toFloat()
                        withTransform({
                            translate(end * u, yAt(end) * u)
                            rotate(angle, Offset.Zero)
                        }) {
                            drawLine(accent2, Offset.Zero, Offset(-shaft, 0f), shaftWidth, StrokeCap.Round, alpha = fade)
                            drawPath(nib, accent, alpha = fade)
                        }
                    }
                }
        )
    }
}

private const val GRID_W = 40f
private const val START_X = 2f
private const val END_X = 36f
private const val STEP = 0.5f
/** The pen's hold, in degrees: the nib at the ink, the shaft rising up and to the right. */
private const val PEN_ANGLE = 125f

/** How much of the stroke's own slope the pen follows. */
private const val PEN_SWAY = 0.25f

/** One line every [CYCLE_S]: written by [WRITE_END] of it, lifted away from [LIFT_START].
 *  An unhurried hand: 3 s read as a tad quick on the phone (10-16). */
private const val CYCLE_S = 3.8f
private const val WRITE_END = 0.75f
private const val LIFT_START = 0.8f

/** The line's height at [x] on the grid: a hand's easy wave, flat where the pen first lands. */
private fun yAt(x: Float): Float = 9f + 3f * sin((x - START_X) * 0.45f) * min(1f, (x - START_X) / 6f)
