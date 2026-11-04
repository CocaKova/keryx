package chat.keryx.app.presentation.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Depth (3.0): the one light the whole app is lit by, so surfaces stand at heights instead of
 * lying in one plane.
 *
 * The light falls from above. A surface that stands up from the page ([keryxRaised]) catches it:
 * its top is a shade brighter than its foot, its upper edge holds a hairline of light, and it
 * casts a soft shadow below. A surface sunk into the page ([keryxSunken]: a search well, a
 * board's lane) is the reverse: darker than the page, shaded along its upper lip where the rim
 * hides the light, with a faint catch-light on its lower lip.
 *
 * The two grounds take it differently, as they take everything:
 * - **The void.** A shadow on pure black is invisible, so height there is told by light: the
 *   rim and the brighter crown do the work, and the shadow only shows where a card sits on a
 *   sheet or a place's dusk. Fills never go past `surfaceVariant`, the lightest ground
 *   [chat.keryx.app.theme.quietInk] holds quiet text to, so no label loses contrast.
 * - **Paper.** A leaf on parchment casts a real shadow, warm (ink-brown, never grey: grey next to
 *   amber reads as dirt), and its rim is the paper's own bright cut edge.
 *
 * Three heights only: [Sunken], [Resting] (cards, groups, doors) and [Lifted] (the thing you are
 * on: the open chat's row, a chosen card). More would be noise.
 */
object KeryxDepth {
    enum class Height { Sunken, Resting, Lifted }

    /** Paper's shadow: warm ink, so it reads as a shadow on parchment and not as smudge. */
    internal val PaperShadow = Color(0xFF4A3B22)
}

private data class DepthInks(
    val crown: Color,
    val foot: Color,
    val rimTop: Color,
    val rimFoot: Color,
    val shadow: Color,
    val elevation: Dp,
)

@Composable
private fun depthInks(height: KeryxDepth.Height, tint: Color?): DepthInks {
    val cs = MaterialTheme.colorScheme
    val void = cs.background.luminance() < 0.5f
    val lifted = height == KeryxDepth.Height.Lifted
    // Crown and foot: the gradient that says "lit from above". On the void both stay between
    // the surface and the variant (see the class note); on paper the leaf is the brightest
    // ground and the crown goes a little brighter still.
    var crown: Color
    var foot: Color
    if (void) {
        crown = lerp(cs.surface, cs.surfaceVariant, if (lifted) 0.95f else 0.80f)
        foot = lerp(cs.surface, cs.surfaceVariant, if (lifted) 0.55f else 0.30f)
    } else {
        crown = lerp(cs.surface, Color.White, 0.55f)
        foot = cs.surface
    }
    if (tint != null) {
        val wash = if (void) 0.09f else 0.07f
        crown = tint.copy(alpha = wash).compositeOver(crown)
        foot = tint.copy(alpha = wash * 0.7f).compositeOver(foot)
    }
    val rimTop = if (void) Color.White.copy(alpha = if (lifted) 0.16f else 0.10f)
    else Color.White.copy(alpha = 0.95f)
    val rimFoot = when {
        tint != null -> tint.copy(alpha = if (void) 0.22f else 0.28f)
        void -> Color.White.copy(alpha = 0.025f)
        else -> cs.onSurface.copy(alpha = 0.10f)
    }
    val shadow = if (void) Color.Black else KeryxDepth.PaperShadow
    val elevation = when {
        void && lifted -> 10.dp
        void -> 5.dp
        lifted -> 7.dp
        else -> 2.5.dp
    }
    return DepthInks(crown, foot, rimTop, rimFoot, shadow, elevation)
}

/**
 * Stand this surface up from the page: shadow, a crown-to-foot fill and a lit rim, clipped to
 * [shape]. Replaces the `.clip(shape).background(fill)` pair, so content and ripple stay inside.
 * [tint] washes the fill and colours the lower rim (a status, a room's light).
 */
@Composable
fun Modifier.keryxRaised(
    shape: Shape,
    height: KeryxDepth.Height = KeryxDepth.Height.Resting,
    tint: Color? = null,
    /** False under a parent that already casts one (a row inside a group): fill and rim only. */
    castShadow: Boolean = true,
): Modifier {
    val ink = depthInks(height, tint)
    return this
        .then(if (castShadow) Modifier.keryxCastShadow(shape, height) else Modifier)
        .clip(shape)
        .drawWithCache {
            val fill = Brush.verticalGradient(listOf(ink.crown, ink.foot))
            val w = 1.dp.toPx()
            val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
            val outline = shape.createOutline(size, layoutDirection, this)
            val rimOutline = shape.createOutline(inner, layoutDirection, this)
            // The rim's light fades out by mid-height, so the sides read as edge and the foot
            // as the far side of the surface, not as a box drawn around it.
            val rim = Brush.verticalGradient(
                0f to ink.rimTop,
                0.45f to ink.rimTop.copy(alpha = ink.rimTop.alpha * 0.25f),
                1f to ink.rimFoot,
            )
            val stroke = Stroke(w)
            onDrawBehind {
                drawOutline(outline, fill)
                translate(w / 2f, w / 2f) { drawOutline(rimOutline, rim, style = stroke) }
            }
        }
}

/**
 * Only the shadow of [keryxRaised], for a caller that has to clip before the surface is drawn
 * (a card that carries a light sweep clipped to its own shape): cast it here, outside that clip,
 * and pass `castShadow = false` to the surface.
 */
@Composable
fun Modifier.keryxCastShadow(shape: Shape, height: KeryxDepth.Height = KeryxDepth.Height.Resting): Modifier {
    val ink = depthInks(height, null)
    val void = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return shadow(
        elevation = ink.elevation,
        shape = shape,
        clip = false,
        ambientColor = ink.shadow.copy(alpha = if (void) 0.55f else 0.30f),
        spotColor = ink.shadow.copy(alpha = if (void) 0.70f else 0.38f),
    )
}

/**
 * Sink this surface into the page: a well (the search field) or a trough (a board's lane) that
 * other things sit in. Darker than the ground, shaded along the upper lip, with a faint catch of
 * light on the lower one. Clipped to [shape].
 */
@Composable
fun Modifier.keryxSunken(shape: Shape): Modifier {
    val cs = MaterialTheme.colorScheme
    val void = cs.background.luminance() < 0.5f
    val floor = if (void) Color.Black.copy(alpha = 0.38f) else cs.onSurface.copy(alpha = 0.055f)
    val lip = if (void) Color.Black.copy(alpha = 0.55f) else KeryxDepth.PaperShadow.copy(alpha = 0.13f)
    val catch = if (void) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.85f)
    return this
        .clip(shape)
        .drawWithCache {
            val shade = 7.dp.toPx().coerceAtMost(size.height / 2f)
            val lipBrush = Brush.verticalGradient(0f to lip, 1f to Color.Transparent, startY = 0f, endY = shade)
            val w = 1.dp.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
            val rimOutline = shape.createOutline(inner, layoutDirection, this)
            val rim = Brush.verticalGradient(
                0f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to catch,
            )
            val stroke = Stroke(w)
            onDrawBehind {
                drawOutline(outline, floor)
                drawRect(lipBrush, size = Size(size.width, shade))
                translate(w / 2f, w / 2f) { drawOutline(rimOutline, rim, style = stroke) }
            }
        }
}

/**
 * Light falling on a plane that is already its own colour (a sheet, the composer): only the
 * crown brightens, over the top [reach]. No fill and no rim, for surfaces that bring their own.
 */
@Composable
fun Modifier.keryxTopLight(reach: Dp = 96.dp): Modifier {
    val void = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glow = if (void) Color.White.copy(alpha = 0.045f) else Color.White.copy(alpha = 0.55f)
    return drawBehind {
        val end = reach.toPx().coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(0f to glow, 1f to Color.Transparent, startY = 0f, endY = end),
            size = Size(size.width, end),
        )
    }
}
