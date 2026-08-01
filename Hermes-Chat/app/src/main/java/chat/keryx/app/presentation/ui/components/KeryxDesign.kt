package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * KeryxDesign — the app's design language as tokens + a few shared components (1.23 "One Dream").
 *
 * Nothing here invents a look. Every value is extracted from the surfaces that already read as
 * Keryx — the Agent Hub's dusk space, the wordmark's two-accent sheen, the hub/mission card — so
 * that every other surface can stop improvising. If a screen needs a radius, a card, a section
 * voice, or a full-screen space, it comes from here or it's a bug.
 */

// --- Tokens ------------------------------------------------------------------------------------

/** The one corner-radius scale. Replaces the ad-hoc 6/8/10/12/16/20dp scatter. */
object KeryxRadius {
    val chip: Dp = 8.dp
    val field: Dp = 12.dp
    val card: Dp = 14.dp
    val sheet: Dp = 20.dp
}

/** Semantic status colors — the exact values already used across the Hub and board, named. */
object KeryxStatus {
    val good = Color(0xFF4CAF50)
    val warn = Color(0xFFE8A33D)
    val bad = Color(0xFFE0524D)
    val idle = Color(0x66FFFFFF)
}

/** The dusk backdrop every full-screen Keryx space sits on: quiet surface up top melting into a
 *  10% accent-2 glow at the foot. */
@Composable
fun duskBrush(): Brush {
    val surface = MaterialTheme.colorScheme.surface
    val accent2 = MaterialTheme.colorScheme.tertiary
    return Brush.verticalGradient(
        0f to surface,
        0.55f to surface,
        1f to accent2.copy(alpha = 0.10f).compositeOver(surface),
    )
}

// --- Motion ------------------------------------------------------------------------------------

/**
 * The app's motion vocabulary (2.0): three springs and one curve. Anything that moves picks from
 * here — ad-hoc `tween(300)`s are the radius-scatter of animation, and this object is their
 * KeryxRadius. Springs over tweens because interrupted motion should redirect with momentum,
 * never restart.
 */
object KeryxMotion {
    /** Arrivals and gesture releases: soft, with just enough overshoot to feel like mass. */
    val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.85f, stiffness = 380f)

    /** Departures: quick and sure — leaving must never feel slower than arriving. */
    val leave = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 1200f)

    /** Layout drift (things making room for other things): critically damped, no bounce. */
    val glide = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 550f)

    /** The arcane curve, for the rare fade a spring can't carry (color, alpha-only). */
    val arcane = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
}

/**
 * The app's one breathing rhythm: a slow alpha pulse between [low] and 1f. Returns 1f (still,
 * fully lit) when [active] is false or the device asked for reduced motion — callers never need
 * their own battery-saver check.
 */
@Composable
fun breathingAlpha(active: Boolean, low: Float = 0.35f, periodMillis: Int = 1600): Float {
    val reduced by rememberReducedMotion()
    if (!active || reduced) return 1f
    val pulse by rememberInfiniteTransition(label = "keryxBreath").animateFloat(
        initialValue = low,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis), RepeatMode.Reverse),
        label = "keryxBreathAlpha",
    )
    return pulse
}

/**
 * The shimmer ring (2.0): a slow conic gleam of accent→accent-2 traveling a hairline border —
 * the "something alive is happening here" mark for running missions, live turns, and the reply
 * still being dreamed up. Rides over a base ring in [baseColor] (pass it with its alpha already
 * baked in; transparent means no ring, gleam only); stills to that plain ring — or to nothing —
 * when [active] is false or motion is reduced. Takes any [shape] via its outline path. Native
 * SweepGradient + local matrix because Compose's sweep brush can't rotate.
 */
@Composable
fun Modifier.keryxShimmerBorder(
    active: Boolean,
    baseColor: Color,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(KeryxRadius.card),
    strokeWidth: Dp = 1.dp,
    periodMillis: Int = 5200,
): Modifier {
    val reduced by rememberReducedMotion()
    if (!active || reduced) {
        return if (baseColor.alpha > 0f) border(strokeWidth, baseColor, shape) else this
    }
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val angle by rememberInfiniteTransition(label = "keryxShimmer").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "keryxShimmerAngle",
    )
    return drawWithCache {
        val strokePx = strokeWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val ringPath = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
        val androidPath = ringPath.asAndroidPath()
        val shader = android.graphics.SweepGradient(
            size.width / 2f,
            size.height / 2f,
            intArrayOf(
                android.graphics.Color.TRANSPARENT,
                accent.copy(alpha = 0.95f).toArgb(),
                accent2.copy(alpha = 0.85f).toArgb(),
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.09f, 0.17f, 0.27f, 1f),
        )
        val matrix = android.graphics.Matrix()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokePx
        }
        onDrawWithContent {
            drawContent()
            if (baseColor.alpha > 0f) {
                drawOutline(outline, baseColor, style = Stroke(strokePx))
            }
            matrix.setRotate(angle, size.width / 2f, size.height / 2f)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            drawContext.canvas.nativeCanvas.drawPath(androidPath, paint)
        }
    }
}

/** A small status dot that breathes while [alive]; solid and still otherwise. */
@Composable
fun KeryxBreathingDot(color: Color, alive: Boolean, size: Dp = 7.dp) {
    val alpha = breathingAlpha(active = alive)
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = color.alpha * alpha)))
}

// --- Section voice -----------------------------------------------------------------------------

/**
 * The one section-header voice: optional status dot, letter-spaced small caps, optional count.
 * Everywhere a list has a heading, it sounds like this.
 */
@Composable
fun KeryxSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    count: Int? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (dotColor != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 2.0.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = chat.keryx.app.theme.CinzelFamily,
            color = color,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "· $count",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Card --------------------------------------------------------------------------------------

/**
 * The card recipe (from the hub/mission card that already worked): soft surfaceVariant fill,
 * hairline outline, [KeryxRadius.card] corners. [tint] washes the fill and border toward a status
 * color; [breathing] makes the border pulse with the app's one rhythm (running things breathe).
 */
@Composable
fun KeryxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
    breathing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(KeryxRadius.card)
    val fill = tint?.copy(alpha = 0.08f)?.compositeOver(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val borderBase = tint ?: MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            // Running things used to pulse; now the shimmer gleam travels their border (2.0).
            .keryxShimmerBorder(active = breathing, baseColor = borderBase.copy(alpha = 0.25f), shape = shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}

// --- The space ---------------------------------------------------------------------------------

/**
 * KeryxSpace — the full-screen "a place you go" scaffold the Agent Hub pioneered (1.21), now
 * shared: dusk gradient, braille-snake emblem, letter-spaced title, a live slot under the title
 * (breathing dot + status line), optional action icons, close X, optional floating action.
 *
 * [standalone] spaces host their own Dialog window (nested viewers that overlay another space).
 * Spaces that live on the navigation stack pass false — KeryxNavHost owns their window, their
 * transition, and their back gesture (2.0 Phase 1).
 */
@Composable
fun KeryxSpace(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    liveSlot: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    floating: (@Composable () -> Unit)? = null,
    standalone: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (standalone) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            KeryxSpaceBody(title, onClose, modifier, liveSlot, actions, floating, content)
        }
    } else {
        KeryxSpaceBody(title, onClose, modifier, liveSlot, actions, floating, content)
    }
}

@Composable
private fun KeryxSpaceBody(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    liveSlot: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    floating: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Arrival breath (2.0): the space's contents rise the last few dp into place just behind the
    // nav transition — a trailing second layer of the same motion, so arriving reads as depth.
    val reduced by rememberReducedMotion()
    val arrival = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (reduced) arrival.snapTo(1f) else arrival.animateTo(1f, KeryxMotion.settle)
    }
    Box(Modifier.fillMaxSize().background(duskBrush())) {
        AmbientVoid()
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(bottom = 12.dp)
                .windowInsetsPadding(WindowInsets.systemBars)
                .graphicsLayer {
                    alpha = 0.4f + 0.6f * arrival.value
                    translationY = (1f - arrival.value) * 10.dp.toPx()
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp),
            ) {
                Box(modifier = Modifier.size(44.dp)) {
                    BrailleSnakeAnimation(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        color2 = MaterialTheme.colorScheme.tertiary,
                        snakeLength = 12,
                        periodMillis = 3600,
                        glyphSize = 8f,
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        title.uppercase(),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 5.sp,
                        fontFamily = chat.keryx.app.theme.CinzelFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    liveSlot()
                }
                actions()
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
        if (floating != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(20.dp),
            ) { floating() }
        }
    }
}

// --- Sheet chrome ------------------------------------------------------------------------------

/**
 * The one bottom-sheet shell: [KeryxRadius.sheet] corners, surface color, an optional
 * letter-spaced title row in the section voice. Every ModalBottomSheet in the app wears this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeryxSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = KeryxRadius.sheet, topEnd = KeryxRadius.sheet),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (title != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    KeryxSectionHeader(title)
                }
            }
            content()
        }
    }
}
