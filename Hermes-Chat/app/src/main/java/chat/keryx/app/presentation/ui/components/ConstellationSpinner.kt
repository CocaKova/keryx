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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Constellation (3.0) — points of light joining up one by one, one thought to the next, then
 * letting go to start again. Picked from the thinking-styles canvas (10-16), with Quill.
 *
 * It wears whoever is thinking: the stars grade from [accent] to [accent2] along the figure and
 * each joining line takes the colour between its two stars, so the accent picker, a herald's own
 * colours and a theme flip all reach it. Lit stars carry a faint glow, stronger on the void.
 *
 * Same cost rules as [EmberDriftSpinner]: one seconds clock read only in the draw phase, and
 * nothing allocated per frame (the colours and points are fixed per size and accent pair).
 *
 * battery-saver-exempt: a spinner's motion is its message.
 */
@Composable
fun ConstellationSpinner(
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier,
) {
    val darkRoom = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glowAlpha = if (darkRoom) 0.28f else 0.16f

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

    Box(modifier = modifier.size(width = 30.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .size(width = 28.dp, height = 21.dp)
                .drawWithCache {
                    // The figure is laid out on a 32 × 24 grid, scaled to the box.
                    val u = size.width / GRID_W
                    val points = Array(STARS) { i -> Offset(FIGURE[i * 2] * u, FIGURE[i * 2 + 1] * u) }
                    val starColors = Array(STARS) { i -> lerp(accent, accent2, i / (STARS - 1f)) }
                    val lineColors = Array(STARS - 1) { i -> lerp(accent, accent2, (i + 0.5f) / (STARS - 1f)) }
                    val lineWidth = 1.1.dp.toPx()

                    onDrawBehind {
                        val t = timeSec
                        val p = (t % CYCLE_S) / CYCLE_S
                        val lineAlpha = if (p < LET_GO) 0.9f else (0.9f * (1f - (p - LET_GO) / (1f - LET_GO))).coerceAtLeast(0f)

                        // Each line draws out from its star toward the next, one after another.
                        for (i in 0 until STARS - 1) {
                            val grow = ((p - i * JOIN) / JOIN).coerceIn(0f, 1f)
                            if (grow <= 0f) continue
                            val a = points[i]
                            val b = points[i + 1]
                            drawLine(
                                color = lineColors[i],
                                start = a,
                                end = Offset(a.x + (b.x - a.x) * grow, a.y + (b.y - a.y) * grow),
                                strokeWidth = lineWidth,
                                cap = StrokeCap.Round,
                                alpha = lineAlpha,
                            )
                        }

                        // A star brightens as the figure reaches it, and twinkles all the while.
                        for (i in 0 until STARS) {
                            val lit = p >= i * JOIN
                            val twinkle = 0.15f * sin(t * 4f + i * 1.7f)
                            val r = (0.9f + (if (lit) 0.6f else 0f) + twinkle) * u
                            if (lit) drawCircle(starColors[i], radius = r * 2.4f, center = points[i], alpha = glowAlpha * lineAlpha / 0.9f)
                            drawCircle(starColors[i], radius = r, center = points[i], alpha = if (lit) 1f else 0.55f)
                        }
                    }
                }
        )
    }
}

private const val GRID_W = 32f
private const val STARS = 5

/** The figure, x/y pairs on the grid: a low climb, a dip, a peak, and a step down. */
private val FIGURE = floatArrayOf(4f, 18f, 10f, 8f, 17f, 14f, 24f, 5f, 29f, 15f)

/** One figure every [CYCLE_S]: each join takes [JOIN] of it, all let go from [LET_GO]. */
private const val CYCLE_S = 3.6f
private const val JOIN = 0.15f
private const val LET_GO = 0.8f
