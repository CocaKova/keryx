package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * How the transcript crosses from one room to the next (3.0, Settings › Appearance). [DISSOLVE]
 * is the 2.x dream dissolve and keeps its own path in ChatScreen; the other four are drawn here.
 */
enum class RoomTransition(val key: String, val label: String, val blurb: String) {
    DISSOLVE("dissolve", "Dissolve", "The room re-forms through a soft blur, with the braille wake."),
    PASSAGE("passage", "Passage", "The new room slides in on a lit seam while the old one steps back."),
    WEAVE("weave", "Weave", "Amber and violet ribbons cross upward and the room changes behind them."),
    BLOOM("bloom", "Bloom", "The new room opens as a circle from the row you tapped."),
    RISE("rise", "Rise", "The old room tips back and sinks; the new one rises into place.");

    companion object {
        fun of(key: String?): RoomTransition = entries.firstOrNull { it.key == key } ?: DISSOLVE
    }
}

/**
 * The drawer's half of a crossing. The drawer covers the transcript while it is open, so that is
 * when the room you are leaving is photographed ([snapshots] ticks); and the last press inside the
 * drawer is where [RoomTransition.BLOOM] opens from. UI-only, process-wide, never persisted.
 */
object RoomCrossingCue {
    /** Bumped each time the drawer starts opening. */
    val snapshots = MutableStateFlow(0)

    /** The last press inside the drawer, in root coordinates, and when it landed. */
    @Volatile var tap: Offset? = null
        private set
    @Volatile var tapAt: Long = 0L
        private set

    fun drawerOpening() { snapshots.value = snapshots.value + 1 }

    fun noteTap(rootPosition: Offset) {
        tap = rootPosition
        tapAt = System.currentTimeMillis()
    }

    /** A press is the origin only while it is fresh: a switch from a notification has none. */
    fun freshTap(now: Long = System.currentTimeMillis()): Offset? = tap?.takeIf { now - tapAt < TAP_FRESH_MS }

    private const val TAP_FRESH_MS = 2_500L
}

/** One crossing's state: the live transcript's layer, the frozen old room, the clock. */
@Stable
class RoomCrossing internal constructor(
    internal val scope: CoroutineScope,
    internal val live: GraphicsLayer,
    internal val old: GraphicsLayer,
) {
    /** 0 → 1 over the whole crossing, linear: each element applies its own easing. */
    internal val progress = Animatable(1f)
    internal var style by mutableStateOf(RoomTransition.DISSOLVE)

    /** The room you are leaving, photographed while the drawer covered it, and which room it was. */
    internal var oldFrame: ImageBitmap? = null
    internal var oldFrameKey: String? = null

    /** A room change composed but not yet started: draw it as progress 0, never one frame of 1. */
    internal var pending = false
    internal var composedKey: String? = null

    internal var topLeftInRoot = Offset.Zero
    internal var origin: Offset? = null

    internal fun snapshot(roomKey: String?) {
        if (live.size == IntSize.Zero) return
        scope.launch {
            runCatching { live.toImageBitmap() }.onSuccess {
                oldFrame = it
                oldFrameKey = roomKey
            }
        }
    }

    internal fun release() {
        oldFrame = null
        oldFrameKey = null
    }
}

@Composable
fun rememberRoomCrossing(): RoomCrossing {
    val scope = rememberCoroutineScope()
    val live = rememberGraphicsLayer()
    val old = rememberGraphicsLayer()
    return remember(scope, live, old) { RoomCrossing(scope, live, old) }
}

/**
 * Drives [crossing] for [roomKey]: photographs the open room when the drawer starts opening, and
 * plays [style] when the room changes. [DISSOLVE] and reduced motion leave it idle (ChatScreen's
 * own dissolve, or nothing, takes the beat).
 *
 * [arrived] says whether the transcript already holds [roomKey]'s messages. The list follows the
 * room a beat later than the room itself (hydration), and a crossing started before that opened
 * onto the room being left (emulator, 10-14). Until it holds, or [ARRIVAL_WAIT_MS] passes (a new,
 * empty chat never fills), the old room simply stays where it is.
 */
@Composable
fun RoomCrossingEffect(
    crossing: RoomCrossing,
    roomKey: String?,
    style: RoomTransition,
    reduced: Boolean,
    arrived: (String) -> Boolean,
) {
    val active = style != RoomTransition.DISSOLVE && !reduced
    SideEffect {
        if (roomKey != crossing.composedKey) {
            val first = crossing.composedKey == null
            crossing.composedKey = roomKey
            if (active && !first && roomKey != null) crossing.pending = true
        }
    }
    LaunchedEffect(crossing, active) {
        if (!active) return@LaunchedEffect
        RoomCrossingCue.snapshots.collect { n -> if (n > 0) crossing.snapshot(crossing.composedKey) }
    }
    var lastKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(roomKey) {
        val leaving = lastKey
        lastKey = roomKey
        if (!active || leaving == null || roomKey == null || roomKey == leaving) {
            crossing.pending = false
            crossing.progress.snapTo(1f)
            return@LaunchedEffect
        }
        // Only the photo of the room being left; a stale one from an earlier drawer visit would
        // fly the wrong room out.
        if (crossing.oldFrameKey != leaving) crossing.release()
        crossing.style = style
        crossing.origin = RoomCrossingCue.freshTap()?.let { it - crossing.topLeftInRoot }
        crossing.progress.snapTo(0f)
        crossing.pending = false
        // The drawer clears the stage first, and the new room's messages have to be there to
        // cross into.
        val waited = System.currentTimeMillis()
        withTimeoutOrNull(ARRIVAL_WAIT_MS) { snapshotFlow { arrived(roomKey) }.first { it } }
        val left = HOLD_MS - (System.currentTimeMillis() - waited)
        if (left > 0) delay(left)
        crossing.progress.animateTo(1f, tween(durationOf(style), easing = LinearEasing))
        crossing.release()
    }
}

/**
 * Records the transcript into [crossing]'s live layer every frame and, while a crossing runs,
 * draws the old room out and the new one in for [style]. Draw-phase only: nothing recomposes.
 * The transcript is transparent over the dusk sky, so the rooms are clipped against each other
 * rather than painted over: the sky stays one sky through the crossing.
 */
fun Modifier.roomCrossing(
    crossing: RoomCrossing,
    style: RoomTransition,
    amber: Color,
    violet: Color,
): Modifier = this
    .onGloballyPositioned { crossing.topLeftInRoot = it.positionInRoot() }
    // The ribbons and the bloom's rim reach past the transcript; the top bar is not theirs.
    .clipToBounds()
    .drawWithContent {
        val live = crossing.live
        live.record { this@drawWithContent.drawContent() }
        val p = if (crossing.pending) 0f else crossing.progress.value
        if (p >= 1f || style == RoomTransition.DISSOLVE) {
            resetLayer(live)
            drawLayer(live)
            return@drawWithContent
        }
        val ms = p * durationOf(crossing.style)
        when (crossing.style) {
            RoomTransition.PASSAGE -> drawPassage(crossing, ms, amber, violet)
            RoomTransition.WEAVE -> drawWeave(crossing, ms, amber, violet)
            RoomTransition.BLOOM -> drawBloom(crossing, ms, amber)
            RoomTransition.RISE -> drawRise(crossing, ms)
            RoomTransition.DISSOLVE -> { resetLayer(live); drawLayer(live) }
        }
    }

// ---- the styles ---------------------------------------------------------------------------

private const val HOLD_MS = 120L
private const val ARRIVAL_WAIT_MS = 900L
private val ARCANE: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val GLIDE_IN: Easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
private val RISE_IN: Easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.12f)
private val WEAVE_SWEEP: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val FEATHER = listOf(1f to 0.22f, 0.66f to 0.3f, 0.34f to 0.4f)

internal fun durationOf(style: RoomTransition): Int = when (style) {
    RoomTransition.DISSOLVE -> 1050
    RoomTransition.PASSAGE -> 620
    RoomTransition.WEAVE -> 760
    RoomTransition.BLOOM -> 700
    RoomTransition.RISE -> 640
}

/** [ms] into a phase that starts at [start] and lasts [length], eased, clamped to 0..1. */
private fun phase(ms: Float, start: Float, length: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((ms - start) / length).coerceIn(0f, 1f))

private fun resetLayer(l: GraphicsLayer) {
    l.alpha = 1f; l.scaleX = 1f; l.scaleY = 1f
    l.translationX = 0f; l.translationY = 0f; l.rotationX = 0f
}

/** The frozen old room as a layer, so it can take the same transforms the live one does. */
private fun DrawScope.drawOld(c: RoomCrossing, configure: GraphicsLayer.() -> Unit) {
    val frame = c.oldFrame ?: return
    val old = c.old
    old.record(IntSize(size.width.toInt(), size.height.toInt())) {
        drawImage(frame, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
    }
    resetLayer(old)
    old.configure()
    drawLayer(old)
}

private fun DrawScope.drawPassage(c: RoomCrossing, ms: Float, amber: Color, violet: Color) {
    val w = size.width
    val step = phase(ms, 0f, 560f, FastOutSlowInEasing)
    val q = phase(ms, 0f, 620f, GLIDE_IN)
    val x = w * (1f - q)
    // The old room only where the new one has not reached yet.
    clipRect(right = x) {
        drawOld(c) {
            pivotOffset = Offset(w / 2f, size.height / 2f)
            translationX = -0.24f * w * step
            scaleX = 1f - 0.06f * step; scaleY = scaleX
            alpha = 1f - step
        }
    }
    // The arriving room's own shadow, falling on the one it covers.
    val shade = 24.dp(this)
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)), startX = x - shade, endX = x),
        topLeft = Offset(x - shade, 0f), size = Size(shade, size.height),
    )
    val live = c.live
    resetLayer(live)
    live.translationX = x
    drawLayer(live)
    // The seam: a lit edge leading the room in. It lights as the room starts to move (not while
    // the crossing waits for the room's messages) and is gone by the time it lands.
    val seamAlpha = (q / 0.06f).coerceAtMost(1f) * (if (q < 0.8f) 1f else (1f - q) / 0.2f)
    val glow = 22.dp(this)
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, amber.copy(alpha = 0.35f * seamAlpha), Color.Transparent), startX = x - glow, endX = x + glow),
        topLeft = Offset(x - glow, 0f), size = Size(glow * 2, size.height),
    )
    drawRect(
        Brush.verticalGradient(listOf(amber, Color(0xFFFFB36B), violet)),
        topLeft = Offset(x - 1.5f.dp(this), 0f), size = Size(3f.dp(this), size.height), alpha = seamAlpha,
    )
}

private fun DrawScope.drawWeave(c: RoomCrossing, ms: Float, amber: Color, violet: Color) {
    val out = phase(ms, 200f, 360f)
    drawOld(c) {
        pivotOffset = Offset(size.width / 2f, size.height / 2f)
        alpha = 1f - out
        scaleX = 1f - 0.02f * out; scaleY = scaleX
    }
    val inT = phase(ms, 400f, 360f, ARCANE)
    val live = c.live
    resetLayer(live)
    live.alpha = inT
    live.translationY = (1f - inT) * 14f.dp(this)
    drawLayer(live)
    ribbon(phase(ms, 0f, 760f, WEAVE_SWEEP), -24f, amber, Color(0xFFFFB36B))
    ribbon(phase(ms, 50f, 710f, WEAVE_SWEEP), 24f, violet, Color(0xFFC4B2FF))
}

/** One serpent: a soft band crossing the room diagonally, bottom to top, as [t] runs 0 → 1. */
private fun DrawScope.ribbon(t: Float, degrees: Float, color: Color, core: Color) {
    if (t <= 0f || t >= 1f) return
    val band = 140f.dp(this)
    val travel = size.height * 0.85f
    val dy = travel - 2f * travel * t
    val brush = Brush.horizontalGradient(
        0f to Color.Transparent, 0.32f to color, 0.5f to core, 0.68f to color, 1f to Color.Transparent,
        startX = -0.6f * size.width, endX = 1.6f * size.width,
    )
    rotate(degrees, pivot = center) {
        translate(top = dy) {
            // Feathered across its width too: three nested passes, faint and wide to dense and
            // narrow, read as a soft band where one crisp rect read as a sticker.
            for ((frac, alpha) in FEATHER) {
                val h = band * frac
                drawRoundRect(
                    brush,
                    topLeft = Offset(-0.6f * size.width, center.y - h / 2f),
                    size = Size(2.2f * size.width, h),
                    cornerRadius = CornerRadius(h / 2f),
                    alpha = alpha,
                )
            }
        }
    }
}

private fun DrawScope.drawBloom(c: RoomCrossing, ms: Float, amber: Color) {
    val q = phase(ms, 0f, 700f, ARCANE)
    val o = c.origin?.takeIf { it.x in 0f..size.width && it.y in 0f..size.height }
        ?: Offset(size.width * 0.14f, size.height * 0.28f)
    val far = max(
        max(hypot(o.x, o.y), hypot(size.width - o.x, o.y)),
        max(hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y)),
    )
    val r = far * q
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(o, r)) }
    // The old room recedes outside the circle; the new one is only ever inside it.
    clipPath(circle, clipOp = ClipOp.Difference) {
        drawOld(c) {
            pivotOffset = Offset(size.width / 2f, size.height / 2f)
            scaleX = 1f - 0.04f * q; scaleY = scaleX
            alpha = 1f - 0.35f * q
        }
    }
    val live = c.live
    resetLayer(live)
    clipPath(circle) { drawLayer(live) }
    val fade = 1f - q
    drawCircle(amber.copy(alpha = 0.25f * fade), radius = r, center = o, style = Stroke(width = 14f.dp(this)))
    drawCircle(amber.copy(alpha = 0.95f * fade), radius = r, center = o, style = Stroke(width = 2f.dp(this)))
}

private fun DrawScope.drawRise(c: RoomCrossing, ms: Float) {
    val out = phase(ms, 0f, 460f, FastOutSlowInEasing)
    // Both rooms are text on the same sky: the old one is mostly gone before the new one shows,
    // or the overlap reads as doubled type (emulator, 10-14).
    val gone = phase(ms, 0f, 280f, FastOutSlowInEasing)
    drawOld(c) {
        pivotOffset = Offset(size.width / 2f, 0f)
        cameraDistance = 12f * density
        rotationX = 10f * out
        scaleX = 1f - 0.12f * out; scaleY = scaleX
        alpha = 1f - gone
    }
    val rise = phase(ms, 140f, 500f, RISE_IN)
    val shown = phase(ms, 180f, 240f)
    val live = c.live
    resetLayer(live)
    live.translationY = (1f - rise) * size.height * 0.22f
    live.alpha = shown
    drawLayer(live)
}

private fun Float.dp(scope: DrawScope): Float = this * scope.density
private fun Int.dp(scope: DrawScope): Float = this * scope.density
