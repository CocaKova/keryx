package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A status banner whose OUTLINE is a cloud. The trick that makes it read as a fluffy cloud rather
 * than spinning discs: the round bumps are anchored ON the body's (pill) perimeter and the body is
 * painted *over* them, so only each bump's OUTER half shows — clean scalloped semicircles. The
 * scallops drift around the perimeter (and round the ends), with a gentle bob, so it looks like a
 * cloud slowly turning over.
 *
 * Believability upgrades (2026-07-02): bump radii vary per bump (real clouds aren't evenly
 * scalloped), the rim is a horizontal accent→accent2 gradient, and a whisper of the same gradient
 * washes the fill from the top — sunset light on a cloud. Fills stay near-opaque so the scalloped
 * edge keeps its crispness in light mode.
 */
@Composable
fun CloudBanner(
    modifier: Modifier = Modifier,
    fill: Color,
    border: Color,
    border2: Color = border,
    content: @Composable () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "cloudBanner")
    // Scallops travel once around the edge every ~11s — slow and dreamlike.
    val orbit by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit",
    )
    // Independent gentle bob.
    val bobT by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )
    // Slow breath: bump sizes swell and relax a touch, out of phase with the bob.
    val breath by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4300, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )

    Box(
        modifier = modifier
            .graphicsLayer { translationY = sin(bobT * PI.toFloat()) * 2.5f.dp.toPx() }
            .drawBehind { drawCloudBanner(orbit, breath, fill, border, border2) }
            // Insets so the label clears the scalloped edge.
            .padding(horizontal = 26.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** Deterministic per-bump jitter in [0,1) — stable across frames so bumps keep their identity. */
private fun jitter(i: Int): Float {
    val x = sin(i * 12.9898f + 78.233f) * 43758.547f
    return abs(x - x.toInt())
}

private fun DrawScope.drawCloudBanner(
    orbit: Float,
    breath: Float,
    fill: Color,
    border: Color,
    border2: Color,
) {
    val w = size.width
    val h = size.height
    val r = h * 0.30f                                   // nominal bump radius
    val x0 = r; val y0 = r; val x1 = w - r; val y1 = h - r
    val cy = h / 2f
    // Pill body: semicircular ends. Clamp so geometry stays valid for narrow banners.
    val cr = minOf((h - 2f * r) / 2f, (x1 - x0) / 2f).coerceAtLeast(1f)
    val straightLen = ((x1 - x0) - 2f * cr).coerceAtLeast(0f)
    val arcLen = (PI.toFloat() * cr)
    val perimeter = 2f * straightLen + 2f * arcLen

    // Map an arc-length position along the pill perimeter to a point (top→right→bottom→left).
    fun perim(sIn: Float): Offset {
        var s = sIn % perimeter
        if (s < 0f) s += perimeter
        if (s < straightLen) return Offset(x0 + cr + s, y0)
        s -= straightLen
        if (s < arcLen) {
            val a = -PI.toFloat() / 2f + s / cr
            return Offset(x1 - cr + cr * cos(a), cy + cr * sin(a))
        }
        s -= arcLen
        if (s < straightLen) return Offset(x1 - cr - s, y1)
        s -= straightLen
        val a = PI.toFloat() / 2f + s / cr
        return Offset(x0 + cr + cr * cos(a), cy + cr * sin(a))
    }

    val bumps = (perimeter / (r * 1.25f)).toInt().coerceIn(10, 30)

    /** Per-bump radius: 70–125% of nominal, plus a slow breathing swell (each bump on its own
     *  phase). Uneven sizes are what make it read as a cloud instead of a gear. */
    fun bumpRadius(i: Int): Float {
        val base = 0.70f + 0.55f * jitter(i)
        val swell = 1f + 0.08f * sin((breath + jitter(i * 7 + 3)) * 2f * PI.toFloat())
        return r * base * swell
    }

    fun silhouette(colorFor: (Offset) -> Color) {
        // Bumps first…
        for (i in 0 until bumps) {
            val s = (i.toFloat() / bumps + orbit) * perimeter
            val c = perim(s)
            drawCircle(color = colorFor(c), radius = bumpRadius(i), center = c)
        }
        // …then the body over them, hiding each bump's inner half → scalloped semicircle edge.
        // The body uses the color at the banner center so gradients stay coherent.
        drawRoundRect(
            color = colorFor(Offset(w / 2f, cy)),
            topLeft = Offset(x0, y0),
            size = Size(x1 - x0, y1 - y0),
            cornerRadius = CornerRadius(cr, cr),
        )
    }

    // Rim: accent→accent2 left-to-right, sampled per bump (cheap gradient over the silhouette).
    fun rimColor(at: Offset): Color = lerp(border, border2, (at.x / w).coerceIn(0f, 1f))
    silhouette(::rimColor)
    val rim = 1.6f.dp.toPx()
    val sx = ((w - 2f * rim) / w).coerceIn(0f, 1f)
    val sy = ((h - 2f * rim) / h).coerceIn(0f, 1f)
    // Fill: mostly [fill], kissed by the rim gradient so the inside isn't flat — like light
    // grazing the cloud from its colored edge. Kept subtle to preserve label contrast.
    fun fillColor(at: Offset): Color = lerp(fill, rimColor(at), 0.10f)
    scale(sx, sy, pivot = Offset(w / 2f, cy)) { silhouette(::fillColor) }
}
