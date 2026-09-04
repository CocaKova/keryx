package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
/**
 * Status colours, per ground (2.5).
 *
 * These were four fixed values chosen against black, and on parchment three of the four failed:
 * `good` 2.66:1, `warn` 2.07:1, and `idle` — a flat 40% white — measured **1.04:1**, which is to
 * say the "idle" state was not dim in light mode, it was absent. A disconnected platform and a
 * healthy one looked identical.
 *
 * Composable getters rather than a CompositionLocal so every existing `KeryxStatus.good` reads the
 * same at the call site; this is the idiom `MaterialTheme.colorScheme` already uses.
 *
 * ⚠️ These read the THEME's background, not the surface underfoot. Two places are dark whatever
 * the theme says — the Call screen and the media lightbox — so on parchment they would be handed
 * paper colours to paint on black. Both keep their own literals on purpose; do not "tidy" them
 * into this object without giving it a way to be told which ground it is standing on.
 */
object KeryxStatus {
    private val voidGood = Color(0xFF4CAF50)
    private val voidWarn = Color(0xFFE8A33D)
    private val voidBad = Color(0xFFE0524D)
    private val voidIdle = Color(0x66FFFFFF)

    // Same signals, darkened onto parchment until each clears 4.5:1. Idle stays the quietest of
    // the four — but quiet now means faded ink, not invisible.
    private val paperGood = Color(0xFF307D33)
    private val paperWarn = Color(0xFF94651F)
    private val paperBad = Color(0xFFC63F3A)
    private val paperIdle = Color(0x8C4A4438)

    private val onVoid: Boolean
        @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val good: Color @Composable get() = if (onVoid) voidGood else paperGood
    val warn: Color @Composable get() = if (onVoid) voidWarn else paperWarn
    val bad: Color @Composable get() = if (onVoid) voidBad else paperBad
    val idle: Color @Composable get() = if (onVoid) voidIdle else paperIdle
}

/**
 * The accent, made safe to **read**.
 *
 * The accent is a light, and a light behaves differently on the two grounds. On the void it is
 * already legible on its own — the default amber measures 4.9:1 on the deep surface. On
 * parchment it is a highlighter: amber over `SurfaceLight` measures **3.50:1**, and that is the
 * colour [KeryxSectionHeader] sets EVERY section heading in the app in, at 10sp. Small text
 * needs 4.5. Inside a chip whose ground is a 14% wash of the same accent (the Bot Chat and
 * profile badges) it falls to **2.77:1**.
 *
 * So on paper the accent is pressed toward the theme's own ink until it clears the bar, hue
 * kept — the same move [roomLight] makes for a room's light, and for the same reason: the hue
 * is the identity, the ground decides only how hard it is pressed. Default amber lands at
 * ≈#A64606, which reads 5.8:1 on the leaf and 4.6:1 inside its own chip.
 *
 * Pressed by measurement rather than by a fixed factor, because the accent is user-chosen: a
 * flat 60% saves amber and still loses a pale yellow. The loop is a handful of cheap lerps and
 * runs only in light mode.
 *
 * ⚠️ This is the TEXT pass. A fill — a chip's ground, a dot, a rim, a bubble's gilt — keeps the
 * raw accent: pressing those to ink would put out the light this whole language is built on.
 */
@Composable
fun keryxAccentInk(accent: Color = MaterialTheme.colorScheme.primary): Color {
    val cs = MaterialTheme.colorScheme
    if (cs.background.luminance() < 0.5f) return accent
    return paperAccentInk(accent, cs.onSurface)
}

/**
 * [keryxAccentInk]'s arithmetic, without a theme — so PaperContrastTest can hold it to the same
 * bar it holds every other paper hue to. Straight sRGB channel blending and WCAG's own
 * luminance, deliberately: this is the number the test measures, not a colour-space opinion.
 */
fun paperAccentInk(accent: Color, ink: Color): Color {
    var c = accent
    var step = 0
    // The ceiling is set by the WORST paper ground the accent is asked to be read on — its own
    // 14% chip, not the bare leaf — so one token covers both call sites.
    while (wcagLuminance(c) > PAPER_ACCENT_CEILING && step < PAPER_ACCENT_STEPS) {
        c = Color(
            red = c.red + (ink.red - c.red) * PAPER_ACCENT_PRESS,
            green = c.green + (ink.green - c.green) * PAPER_ACCENT_PRESS,
            blue = c.blue + (ink.blue - c.blue) * PAPER_ACCENT_PRESS,
            alpha = accent.alpha,
        )
        step++
    }
    return c
}

/** WCAG 2.1 relative luminance of an sRGB colour. */
private fun wcagLuminance(c: Color): Float {
    fun ch(s: Float): Float =
        if (s <= 0.03928f) s / 12.92f
        else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
}

/** Relative luminance at which the accent clears 4.5:1 against a 14% wash of itself on paper. */
private const val PAPER_ACCENT_CEILING = 0.128f

/** How hard one press moves the hue toward the ink, and how many presses are allowed. Small
 *  steps so a hue that only just fails is only just darkened; the cap stops a white accent
 *  (which can never clear the bar) from looping — it lands on ink, which is the honest answer. */
private const val PAPER_ACCENT_PRESS = 0.12f
private const val PAPER_ACCENT_STEPS = 24

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

/**
 * The tool families' colours (2.6.2 tool-log pass). Same contract as [KeryxStatus]: a void set
 * and a paper set, picked by the ground, every paper hue clearing 4.5:1 on parchment
 * (PaperContrastTest pins it). Families come from `ToolGrammar.familyOf`; the tint goes on the
 * tool's glyph and the run header's glyph strip, never on the verdict mark — ✓/✕ keep
 * [KeryxStatus] so "failed" reads the same colour everywhere in the app.
 *
 * Hue logic: shell = gold (the terminal's prompt), files = slate blue (paper), edit = amber
 * (ink still wet), web = teal (the wire), mind = violet (memory, skills, recall), media = rose,
 * people = magenta (delegation, questions, schedules), other = faded ink.
 */
object KeryxToolTint {
    val VOID: Map<chat.keryx.core.model.ToolGrammar.Family, Color> = mapOf(
        chat.keryx.core.model.ToolGrammar.Family.SHELL to Color(0xFFE6C36A),
        chat.keryx.core.model.ToolGrammar.Family.FILES to Color(0xFF8FB4E0),
        chat.keryx.core.model.ToolGrammar.Family.EDIT to Color(0xFFF0A868),
        chat.keryx.core.model.ToolGrammar.Family.WEB to Color(0xFF6FD3C4),
        chat.keryx.core.model.ToolGrammar.Family.MIND to Color(0xFFB9A0F0),
        chat.keryx.core.model.ToolGrammar.Family.MEDIA to Color(0xFFEE9AAE),
        chat.keryx.core.model.ToolGrammar.Family.PEOPLE to Color(0xFFDE8FD6),
        chat.keryx.core.model.ToolGrammar.Family.OTHER to Color(0x99FFFFFF),
    )
    val PAPER: Map<chat.keryx.core.model.ToolGrammar.Family, Color> = mapOf(
        chat.keryx.core.model.ToolGrammar.Family.SHELL to Color(0xFF7A5A12),
        chat.keryx.core.model.ToolGrammar.Family.FILES to Color(0xFF3A5F8A),
        chat.keryx.core.model.ToolGrammar.Family.EDIT to Color(0xFF9A5418),
        chat.keryx.core.model.ToolGrammar.Family.WEB to Color(0xFF1F6F66),
        chat.keryx.core.model.ToolGrammar.Family.MIND to Color(0xFF6A4BB8),
        chat.keryx.core.model.ToolGrammar.Family.MEDIA to Color(0xFFA6405C),
        chat.keryx.core.model.ToolGrammar.Family.PEOPLE to Color(0xFF8E3E86),
        chat.keryx.core.model.ToolGrammar.Family.OTHER to Color(0xFF6B6459),
    )

    /** The tint for [family] on the current ground. */
    val of: @Composable (chat.keryx.core.model.ToolGrammar.Family) -> Color
        get() = { family ->
            val onVoid = MaterialTheme.colorScheme.background.luminance() < 0.5f
            (if (onVoid) VOID else PAPER).getValue(family)
        }

    @Composable
    fun forTool(name: String): Color = of(chat.keryx.core.model.ToolGrammar.familyOf(name))
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

    /** [settle] and [leave] for layout-sized transitions (expand / shrink take an IntSize spring). */
    val settleSize = spring<IntSize>(dampingRatio = 0.85f, stiffness = 380f)
    val leaveSize = spring<IntSize>(dampingRatio = 1f, stiffness = 1200f)

    /** The same pair for slides (offset-typed). */
    val settleInt = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
    val leaveInt = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 1f, stiffness = 1200f)

    /** How far a pressed surface sinks: enough to feel the tap, never enough to read as a bug. */
    const val PRESS_SCALE = 0.965f
}

/**
 * The reveal pair (2.8.1): every bar, card, row and disclosure that appears in place enters
 * with [KeryxMotion.settle] and leaves with [KeryxMotion.leave] — one arrival, one departure,
 * instead of a dozen `AnimatedVisibility()` defaults each with their own timing.
 */
fun keryxReveal(): EnterTransition = fadeIn(KeryxMotion.settle) + expandVertically(KeryxMotion.settleSize)
fun keryxConceal(): ExitTransition = fadeOut(KeryxMotion.leave) + shrinkVertically(KeryxMotion.leaveSize)

/** The pop pair: chips, badges, tiles — things that appear *as themselves*, not as a row. */
fun keryxPop(): EnterTransition = fadeIn(KeryxMotion.settle) + scaleIn(KeryxMotion.settle, initialScale = 0.86f)
fun keryxVanish(): ExitTransition = fadeOut(KeryxMotion.leave) + scaleOut(KeryxMotion.leave, targetScale = 0.9f)

/**
 * A surface that sinks under the finger: scale toward [KeryxMotion.PRESS_SCALE] while
 * [interactionSource] is pressed, springing back on release. Pair it with the same source's
 * `clickable`/`combinedClickable` so the press the ripple sees is the press the scale sees.
 * Stills to nothing under reduced motion.
 */
@Composable
fun Modifier.keryxPressScale(interactionSource: InteractionSource, scale: Float = KeryxMotion.PRESS_SCALE): Modifier {
    val reduced by rememberReducedMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed && !reduced) scale else 1f,
        animationSpec = KeryxMotion.settle,
        label = "keryxPress",
    )
    // Always the same modifier chain: a layer that swapped in and out on press would re-layout.
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** [keryxPressScale] with its own click: the tile/door/chip case with nothing else to wire. */
@Composable
fun Modifier.keryxPressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .keryxPressScale(source)
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick)
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

/**
 * Light travel (2.2): while a transition is mid-flight, a band of accent light crosses the
 * surface with it — navigation reads as light moving with you, not screens swapping.
 * [progress] is read in the draw phase only, so the sweep invalidates drawing, never layout.
 *
 * It is built like light rather than like a tint: a long dim **wake** trailing behind, the soft
 * **body**, three thin **filaments** drifting at their own speeds (what gives it texture — a flat
 * band reads as a wash, a bundle of strands reads as something passing), a **prism fringe** where
 * accent2 runs just ahead of accent, and the **blade**, the bright leading edge. Everything scales
 * with sin(pi·p): nothing at rest, brightest mid-journey.
 *
 * ⚠️ Do NOT drive this straight off a transition's progress — see [rememberSweepProgress]. A fast
 * navigation (the agent Hub) finishes in a couple of frames, and the light was gone before it
 * could be seen (Jonny, second walk: "very very quick on some actions").
 */
/**
 * The gleam's core for the ground underfoot — near-white on the void, ink on parchment. A
 * one-liner so the three call sites stay honest about which they are on.
 */
@Composable
fun keryxSweepCore(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color(0xFF1F1B14)

fun Modifier.keryxLightSweep(
    accent: Color,
    accent2: Color,
    /**
     * The core of the gleam. On the void it is near-white — light added to darkness. On parchment
     * that is nothing at all: lightening paper is invisible, so the same pass has to travel as a
     * *shadow*, ink laid down rather than light lifted off. Same gesture, opposite sign, because
     * the physical metaphor inverts with the ground.
     */
    core: Color = Color.White,
    progress: () -> Float,
): Modifier = drawWithContent {
    drawContent()
    val p = progress()
    if (p > 0.02f && p < 0.98f) {
        val glow = kotlin.math.sin(p * Math.PI).toFloat()
        val cx = size.width * (p * 1.6f - 0.3f)
        fun band(centre: Float, halfWidth: Float, stops: Array<Pair<Float, Color>>) = drawRect(
            Brush.linearGradient(
                colorStops = stops,
                start = Offset(centre - halfWidth, size.height),
                end = Offset(centre + halfWidth, 0f),
            ),
        )
        // A halo, so the gleam has somewhere to sit and no hard edge — dim enough that on its own
        // it would not be noticed.
        band(
            cx, size.width * 0.26f,
            arrayOf(
                0f to Color.Transparent,
                0.5f to accent.copy(alpha = 0.07f * glow),
                1f to Color.Transparent,
            ),
        )
        // The gleam: ONE narrow specular pass, accent at its shoulders and near-white at its core.
        // A sheen across glass, not a body of colour crossing the page.
        band(
            cx, size.width * 0.075f,
            arrayOf(
                0f to Color.Transparent,
                0.34f to accent.copy(alpha = 0.16f * glow),
                0.5f to core.copy(alpha = 0.17f * glow),
                0.66f to accent2.copy(alpha = 0.14f * glow),
                1f to Color.Transparent,
            ),
        )
    }
}

/**
 * The sweep's own clock.
 *
 * A transition's progress is the wrong tempo to borrow. The page slide takes ~300ms, but opening
 * the agent Hub snaps in a couple of frames, and the light rode that to nothing (Jonny, second
 * walk: *"it's very very quick on some actions like the agent Hub"*). Light travelling has one
 * speed of its own.
 *
 * So the clock starts when the transition does and runs [durationMs] regardless — and the sweep
 * takes whichever of the two is **further behind**. A snap transition leaves the clock as the only
 * brake, so the light still crosses at its own pace; a slow back-drag leaves the *finger* as the
 * brake, so the light stays under the thumb instead of racing ahead of it and finishing on a
 * screen that hasn't moved yet. Taking the further-ALONG of the two would undo the whole thing:
 * a transition that lands instantly would pin the sweep at 1 and draw nothing.
 *
 * The exit direction is deliberately left riding its driver — the layer is torn down when it
 * reaches zero, so there is nothing left to light.
 */
@Composable
fun rememberSweepProgress(
    durationMs: Int = 620,
    driver: () -> Float,
): () -> Float {
    val clock = remember { Animatable(0f) }
    // Rising off rest, not "mid-flight": a fast transition can go 0 → 1 between two samples, and
    // a window that narrow is never observed at all.
    val started by remember { derivedStateOf { driver() > 0.02f } }
    LaunchedEffect(started) {
        if (started) {
            clock.snapTo(0f)
            clock.animateTo(1f, tween(durationMs, easing = LinearOutSlowInEasing))
        } else {
            clock.snapTo(0f)
        }
    }
    return { minOf(driver(), clock.value) }
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
    // The heading is TEXT, and 10sp of raw accent on parchment is 3.50:1 — under the 4.5 small
    // text needs, on every section heading in the app. [keryxAccentInk] presses the same hue
    // into the paper; on the void it hands the accent straight back, so dark mode is untouched.
    color: Color = keryxAccentInk(),
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
    Box(Modifier.fillMaxSize().keryxDuskSky()) {
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
                        KeryxGlyphs.Close,
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
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // Arrival breath (2.8.1): the sheet's contents rise the last few dp into place behind the
    // sheet's own slide — the same trailing second layer every space arrives with.
    val reduced by rememberReducedMotion()
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) arrival.snapTo(1f) else arrival.animateTo(1f, KeryxMotion.settle)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = KeryxRadius.sheet, topEnd = KeryxRadius.sheet),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            // The handle in the app's own light: a short accent hairline, not Material's grey pill.
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(36.dp).size(width = 36.dp, height = 3.dp).clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.7f), accent2.copy(alpha = 0.55f)))),
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = 0.4f + 0.6f * arrival.value
                translationY = (1f - arrival.value) * 10.dp.toPx()
            },
        ) {
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
