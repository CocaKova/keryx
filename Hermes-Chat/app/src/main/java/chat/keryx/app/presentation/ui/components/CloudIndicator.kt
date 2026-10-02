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
import androidx.compose.ui.graphics.isSpecified
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
    /** The ground the cloud stands on (2.16) — see [CloudFloor]. Unspecified draws none. */
    floor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    // Three frame-clock clients for one banner, so Battery Saver takes all three at once: the
    // cloud keeps its shape and its scalloped edge, it just stops drifting, bobbing and breathing.
    val reduced by rememberReducedMotion()
    val orbit: Float
    val bobT: Float
    val breath: Float
    if (!reduced) {
        val t = rememberInfiniteTransition(label = "cloudBanner")
        // Scallops travel once around the edge every ~11s — slow and dreamlike.
        orbit = t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart),
            label = "orbit",
        ).value
        // Independent gentle bob.
        bobT = t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
            label = "bob",
        ).value
        // Slow breath: bump sizes swell and relax a touch, out of phase with the bob.
        breath = t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4300, easing = LinearEasing), RepeatMode.Reverse),
            label = "breath",
        ).value
    } else {
        orbit = 0f
        // sin(0.5 * PI) == 1 would hold the bob at the top of its travel; 0f sits it level.
        bobT = 0f
        breath = 0.5f
    }

    Box(
        modifier = modifier
            .graphicsLayer { translationY = sin(bobT * PI.toFloat()) * 2.5f.dp.toPx() }
            .drawBehind {
                if (floor.isSpecified) drawCloudFloor(orbit, breath, floor)
                drawCloudBanner(orbit, breath, fill, border, border2)
                drawThoughtTrail(bobT, breath, fill, border)
            }
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

/**
 * The working cloud's floor (2.16). The cloud floats over the transcript, and its glow, its
 * scalloped rim and the gaps between its bumps are all part-transparent, so whatever text
 * scrolled under it read straight through the halo — the composer's bug at the other edge of the
 * screen (2.11.8). The floor is the cloud's own silhouette grown past the glow, in the theme
 * surface, with one fainter pass beyond it so the clearing meets the sky softly instead of
 * cutting the text under it on a hard edge. [PaperContrastTest] holds the numbers.
 */
object CloudFloor {
    /** Opacity of the clearing (theme surface). */
    const val ALPHA = 0.96f
    /** The outer, softer pass — the clearing's feathered edge. */
    const val FEATHER_ALPHA = 0.5f
    /** How far the glow's outer pass reaches past the body (x, y). */
    const val GLOW_X = 1.16f
    const val GLOW_Y = 1.34f
    /** How far the clearing reaches — past the glow, so no halo is ever drawn over text. */
    const val REACH_X = 1.22f
    const val REACH_Y = 1.46f
    /** The feather's reach, beyond the clearing. */
    const val FEATHER_X = 1.30f
    const val FEATHER_Y = 1.62f
    /** A thought-trail puff's floor, as a multiple of the puff. */
    const val PUFF_REACH = 1.7f
}

private fun DrawScope.drawCloudFloor(orbit: Float, breath: Float, floor: Color) {
    val pivot = Offset(size.width / 2f, size.height / 2f)
    scale(CloudFloor.FEATHER_X, CloudFloor.FEATHER_Y, pivot = pivot) {
        drawCloudSilhouette(orbit, breath) { floor.copy(alpha = floor.alpha * CloudFloor.FEATHER_ALPHA) }
    }
    scale(CloudFloor.REACH_X, CloudFloor.REACH_Y, pivot = pivot) {
        drawCloudSilhouette(orbit, breath) { floor }
    }
    // The thought trail's puffs hang below the box; each gets its own patch of floor.
    val r = size.height * 0.30f
    for ((c, rad) in thoughtPuffs(size.width, size.height, r)) {
        drawCircle(floor, radius = rad * CloudFloor.PUFF_REACH, center = c)
    }
}

/**
 * The cloud's outline, every part in [colorFor]: the banner draws it four times (two glow
 * passes, rim, fill) and its floor twice, all from this one geometry.
 */
private fun DrawScope.drawCloudSilhouette(orbit: Float, breath: Float, colorFor: (Offset) -> Color) {
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

private fun DrawScope.drawCloudBanner(
    orbit: Float,
    breath: Float,
    fill: Color,
    border: Color,
    border2: Color,
) {
    val w = size.width
    val h = size.height
    val cy = h / 2f
    fun silhouette(colorFor: (Offset) -> Color) = drawCloudSilhouette(orbit, breath, colorFor)

    // Rim: accent→accent2 left-to-right, sampled per bump (cheap gradient over the silhouette).
    fun rimColor(at: Offset): Color = lerp(border, border2, (at.x / w).coerceIn(0f, 1f))

    // Glow (2.14): the same silhouette, grown and faint, breathing with the bumps — the cloud
    // lit from inside rather than sitting flat on the page. Two passes read as a soft falloff
    // without a blur (RenderEffect costs a layer per frame on a banner that animates forever).
    val glow = 0.10f + 0.06f * sin(breath * PI.toFloat())
    scale(CloudFloor.GLOW_X, CloudFloor.GLOW_Y, pivot = Offset(w / 2f, cy)) { silhouette { rimColor(it).copy(alpha = glow * 0.5f) } }
    scale(1.07f, 1.15f, pivot = Offset(w / 2f, cy)) { silhouette { rimColor(it).copy(alpha = glow) } }

    silhouette(::rimColor)
    val rim = 1.6f.dp.toPx()
    val sx = ((w - 2f * rim) / w).coerceIn(0f, 1f)
    val sy = ((h - 2f * rim) / h).coerceIn(0f, 1f)
    // Fill: mostly [fill], kissed by the rim gradient so the inside isn't flat — like light
    // grazing the cloud from its colored edge — and lit from above (2.14): brighter crown,
    // faintly shaded belly, the one cue that turns a flat shape into a volume. Both kept subtle
    // to preserve label contrast in either theme.
    val crown = lerp(fill, Color.White, 0.07f)
    val belly = lerp(fill, Color.Black, 0.05f)
    fun fillColor(at: Offset): Color =
        lerp(lerp(crown, belly, (at.y / h).coerceIn(0f, 1f)), rimColor(at), 0.10f)
    scale(sx, sy, pivot = Offset(w / 2f, cy)) { silhouette(::fillColor) }
}

/**
 * The thought trail (2.14): two small puffs trailing down-left from the cloud, toward the agent's
 * side of the chat — the comic-strip grammar that says "thinking", not "loading". They light in
 * turn with the bob, so the thought reads as rising from the speaker into the cloud.
 */
private fun DrawScope.drawThoughtTrail(bobT: Float, breath: Float, fill: Color, border: Color) {
    val h = size.height
    val w = size.width
    val r = h * 0.30f
    val rim = 1.4f.dp.toPx()
    val puffs = thoughtPuffs(w, h, r).zip(listOf(0f, 0.5f)) { (c, rad), phase -> Triple(c, rad, phase) }
    for ((c, rad, phase) in puffs) {
        val lit = 0.55f + 0.45f * sin(((bobT + phase) % 1f) * PI.toFloat())
        val grow = 1f + 0.06f * sin((breath + phase) * 2f * PI.toFloat())
        drawCircle(border.copy(alpha = border.alpha * lit), radius = rad * grow, center = c)
        drawCircle(fill, radius = (rad * grow - rim).coerceAtLeast(0f), center = c)
    }
}

/** The trail's two puffs, (centre, radius) — the larger hugs the cloud, the smaller strays
 *  further. Shared by the trail and its floor so the two can never drift apart. */
private fun thoughtPuffs(w: Float, h: Float, r: Float): List<Pair<Offset, Float>> = listOf(
    Offset(w * 0.11f, h + r * 0.62f) to r * 0.30f,
    Offset(w * 0.045f, h + r * 1.30f) to r * 0.17f,
)
