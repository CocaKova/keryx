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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Ember Drift — three motes breathing over a rising ember field, offered as a "Loading animation"
 * alongside the Caduceus, the Braille snake, the dot ring and the wave.
 *
 * It wears whoever is thinking. [accent]/[accent2] arrive from [HermesThinkingAnimation], which
 * defaults them to the user's own picked accents (`colorScheme.primary` / `tertiary`) and hands a
 * herald its own light in a council room — so the accent picker, a herald override and a theme
 * flip all reach this spinner through the one path that already exists.
 *
 * Three things about how it is built, because they are the whole cost story:
 *
 *  1. **One canvas, not thirteen composables.** Ten particles as layout nodes would be ten sets of
 *     measure/place work and ten animation subscriptions. They are draw calls instead.
 *  2. **One clock, read in the draw phase.** [withInfiniteAnimationFrameNanos] drives a single
 *     seconds float. Because the only read of it is inside `onDrawBehind`, a frame invalidates
 *     *draw* and nothing else — no recomposition, no relayout, while tokens are streaming into the
 *     list behind it.
 *  3. **Nothing allocates per frame.** The star path and both brushes are built once by
 *     [drawWithCache] and rebuilt only if the size or the colours change. `Offset` and `Color` are
 *     value classes; `withTransform` is inline. The halo is a radial gradient, deliberately not a
 *     `BlurEffect` — a real blur forces an offscreen layer every frame for a 24dp decoration.
 *
 * Under Battery Saver the motes keep breathing and the ember field and halo pulse stop. That is
 * the app's standing rule rather than a new one: motion that carries information keeps moving,
 * motion that decorates stops, and a stilled spinner reads as a hung agent.
 */
@Composable
fun EmberDriftSpinner(
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier,
) {
    val reduced by rememberReducedMotion()

    // The ground decides how the embers are mixed. On the void they glint toward white or they
    // read as muddy; on parchment they deepen, because a sub-dp mote on bright paper loses its
    // edge long before it loses its hue. Same idiom the bubble dust uses.
    val darkRoom = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glint = if (darkRoom) Color.White else Color(0xFF1F1B14)
    val coreTop = lerp(accent, glint, if (darkRoom) 0.34f else 0.00f)
    val coreBottom = lerp(accent2, glint, if (darkRoom) 0.12f else 0.16f)
    val haloAlpha = if (darkRoom) 0.30f else 0.14f
    val fieldAlpha = if (darkRoom) 0.95f else 1.00f

    // A monotonic seconds clock. Written every frame, read only by the draw lambda below.
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (start == 0L) start = now
                // Wrapped well short of the range where a Float stops resolving milliseconds.
                timeSec = (((now - start) / 1_000_000L) % 3_600_000L).toFloat() / 1000f
            }
        }
    }

    Box(modifier = modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .size(width = 24.dp, height = 20.dp)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val starR = 3.0.dp.toPx()
                    val baseY = h * 0.66f
                    val liftPx = 2.8.dp.toPx()
                    val riseTop = h * 0.12f
                    val sparkR = 1.15.dp.toPx()
                    val driftPx = 2.0.dp.toPx()
                    val haloR = 13.dp.toPx()

                    // Built once. A four-point star at unit radius, scaled to px, centred on the
                    // origin so each mote is one translate/rotate/scale away.
                    val star = Path().apply {
                        moveTo(0f, -1.000f * starR)
                        lineTo(0.160f * starR, -0.283f * starR)
                        lineTo(0.906f * starR, 0f)
                        lineTo(0.160f * starR, 0.283f * starR)
                        lineTo(0f, 1.000f * starR)
                        lineTo(-0.160f * starR, 0.283f * starR)
                        lineTo(-0.906f * starR, 0f)
                        lineTo(-0.160f * starR, -0.283f * starR)
                        close()
                    }
                    // Local to the star, so it survives the transform intact.
                    val starBrush = Brush.verticalGradient(
                        colors = listOf(coreTop, coreBottom),
                        startY = -starR,
                        endY = starR,
                    )
                    val halo = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = haloAlpha), Color.Transparent),
                        center = Offset(w / 2f, baseY),
                        radius = haloR,
                    )

                    onDrawBehind {
                        val t = timeSec // draw-phase read: a frame costs a redraw, nothing more

                        // ── Halo ─────────────────────────────────────────────────────────
                        val haloPulse = if (reduced) 0.62f else {
                            val p = phase01(t, HALO_PERIOD, 0f)
                            0.42f + 0.58f * bell(p)
                        }
                        drawCircle(
                            brush = halo,
                            radius = haloR,
                            center = Offset(w / 2f, baseY),
                            alpha = haloPulse,
                        )

                        // ── Ember field (decoration: stops under Battery Saver) ───────────
                        if (!reduced) {
                            for (i in SPARKS.indices step 5) {
                                val sx = SPARKS[i]
                                val life = SPARKS[i + 1]
                                val delay = SPARKS[i + 2]
                                val drift = SPARKS[i + 3]
                                val scale = SPARKS[i + 4]
                                val p = phase01(t, life, delay)
                                // Leaves fast, dies slow — an ember does not ease in and out.
                                val out = 1f - (1f - p) * (1f - p) * (1f - p)
                                // Fast attack, long tail. The old CSS draft shrank these from 15%
                                // to nothing, which at bubble size was a sub-pixel and drew as
                                // nothing at all; they open to full size instead.
                                val grow = if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f
                                val fade = when {
                                    p < 0.12f -> p / 0.12f
                                    p < 0.62f -> 1f - 0.53f * ((p - 0.12f) / 0.50f)
                                    else -> 0.47f * (1f - (p - 0.62f) / 0.38f)
                                }
                                drawCircle(
                                    color = lerp(coreTop, coreBottom, sx),
                                    radius = sparkR * scale * grow,
                                    center = Offset(
                                        x = w * (0.10f + 0.80f * sx) + drift * driftPx * out,
                                        y = baseY - (baseY - riseTop) * out,
                                    ),
                                    alpha = (fade * fieldAlpha).coerceIn(0f, 1f),
                                )
                            }
                        }

                        // ── The three motes (information: always moving) ─────────────────
                        for (i in MOTES.indices step 3) {
                            val cx = w * MOTES[i]
                            val period = MOTES[i + 1]
                            val offset = MOTES[i + 2]
                            val e = bell(phase01(t, period, offset), peak = 0.44f)
                            withTransform({
                                translate(cx, baseY - liftPx * e)
                                rotate(24f * e, Offset.Zero)
                                scale(0.84f + 0.32f * e, 0.84f + 0.32f * e, Offset.Zero)
                            }) {
                                drawPath(star, starBrush, alpha = 0.40f + 0.60f * e)
                            }
                        }
                    }
                }
        )
    }
}

private const val HALO_PERIOD = 3.1f

/** x-fraction, period (s), phase offset (s) — coprime periods, so the three never re-sync. */
private val MOTES = floatArrayOf(
    0.22f, 1.7f, 0.00f,
    0.50f, 2.3f, 0.21f,
    0.78f, 2.9f, 0.43f,
)

/** x-fraction, lifetime (s), phase offset (s), drift (±1), size scale. */
private val SPARKS = floatArrayOf(
    0.00f, 2.3f, 0.00f, -1.00f, 0.95f,
    0.17f, 3.1f, 0.70f, 1.30f, 0.65f,
    0.33f, 2.7f, 1.40f, 0.60f, 1.15f,
    0.50f, 3.7f, 0.35f, -1.55f, 0.80f,
    0.67f, 2.1f, 1.10f, 0.95f, 1.05f,
    0.83f, 3.3f, 1.90f, -0.70f, 0.60f,
    1.00f, 2.5f, 0.50f, 1.50f, 1.10f,
)

/** Position within one loop, 0..1. */
private fun phase01(t: Float, period: Float, offset: Float): Float {
    val x = (t + offset) / period
    return x - floor(x)
}

/** Rise to 1 at [peak], fall back to 0, smoothstepped on both sides. */
private fun bell(p: Float, peak: Float = 0.5f): Float {
    val x = if (p < peak) p / peak else 1f - (p - peak) / (1f - peak)
    return x * x * (3f - 2f * x)
}
