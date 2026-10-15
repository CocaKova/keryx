package chat.keryx.app.presentation.tour

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.KeryxType
import chat.keryx.app.presentation.ui.components.contrastColorFor
import chat.keryx.app.presentation.ui.components.keryxAccentInk
import chat.keryx.app.presentation.ui.components.rememberReducedMotion
import chat.keryx.app.theme.CinzelFamily
import chat.keryx.core.tour.TourAction
import chat.keryx.core.tour.TourEngine
import chat.keryx.core.tour.TourPreset
import chat.keryx.core.tour.TourResult
import chat.keryx.core.tour.TourRun
import chat.keryx.core.tour.TourSide
import chat.keryx.core.tour.TourStep
import chat.keryx.core.tour.TourTarget
import kotlin.math.roundToInt

/**
 * The tour on screen (3.0): the run the engine hands back, held as state. Every way in goes
 * through [act] (the app's own tour, its buttons, later the agent), so a step reached by "Next"
 * and one reached by the agent's `next` are the same step.
 */
@Stable
class TourController(
    private val catalog: () -> List<TourTarget>,
    private val builtIn: (TourPreset, Set<String>) -> List<TourStep>,
) {
    var run by mutableStateOf<TourRun?>(null)
        private set

    fun act(action: TourAction): TourResult {
        val available = catalog()
        val ids = available.mapTo(HashSet()) { it.id }
        val (next, result) = TourEngine.apply(run, action, available) { builtIn(it, ids) }
        run = next
        return result
    }

    fun start(preset: TourPreset) = act(TourAction.Start(null, preset))
}

/** How long a step waits for its target to show (the drawer opening, a place arriving) before
 *  it says its piece mid-screen instead. A tour must never stall on a control that isn't there. */
private const val TARGET_WAIT_MS = 1_800L

/** A target counts as arrived once it has held still this long: the drawer has finished sliding. */
private const val SETTLE_MS = 120L

/**
 * Dims the app, cuts a window round the step's target, and sets the card beside it. Drawn over
 * everything in the window and swallowing every touch, so the tour is the only thing that moves
 * until it ends. [onCta] runs a stop's own button (the gateway at the end); the tour has already
 * stopped by then.
 */
@Composable
fun TourOverlay(
    controller: TourController,
    registry: TourRegistry,
    onCta: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val run = controller.run
    val reduced by rememberReducedMotion()
    // The last run stays drawn while the dim fades out, so ending is a fade, not a cut.
    var lastRun by remember { mutableStateOf<TourRun?>(null) }
    LaunchedEffect(run) { if (run != null) lastRun = run }
    val presence = remember { Animatable(0f) }
    LaunchedEffect(run != null) {
        presence.animateTo(if (run != null) 1f else 0f, tween(if (reduced) 0 else 220))
        if (run == null) lastRun = null
    }
    val display = run ?: lastRun ?: return

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val step = display.step

    // Where the window is cut (null = closed), and whether this stop's card may show. The cut
    // outlives a step: it stays on the last target until the next one has arrived and held
    // still, then glides there. It closes only when the next target isn't on screen yet (the
    // drawer still sliding in), so a stop on the same screen never blinks.
    var cutTo by remember { mutableStateOf<Rect?>(null) }
    var ready by remember(display.index, step) { mutableStateOf(false) }
    LaunchedEffect(display.index, step) {
        val id = step.target
        if (id == null) {
            cutTo = null
            ready = true
            return@LaunchedEffect
        }
        val since = System.nanoTime()
        var last: Rect? = null
        var stillSince = 0L
        // Read once a frame: a target can move on a layer (the drawer) without saying so.
        while (true) {
            val now = withFrameNanos { it }
            val b = registry.boundsOf(id)?.translate(-origin)
            if (b != last) { last = b; stillSince = now }
            when {
                b == null -> if (!ready) cutTo = null
                now - stillSince >= SETTLE_MS * 1_000_000 -> { cutTo = b; ready = true }
            }
            // Never stall on a control that isn't there: say the piece mid-screen instead.
            if (!ready && (System.nanoTime() - since) / 1_000_000 > TARGET_WAIT_MS) {
                cutTo = null
                ready = true
            }
        }
    }
    val target = cutTo?.takeIf { step.target != null }

    // The window glides from one target to the next; it opens in place from nothing.
    val cut = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val hole = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target == null) {
            hole.animateTo(0f, tween(if (reduced) 0 else 140))
            return@LaunchedEffect
        }
        if (reduced || hole.value == 0f) cut.snapTo(target)
        else cut.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 420f))
        hole.animateTo(1f, tween(if (reduced) 0 else 180))
    }
    val cardReady = ready

    val accent = MaterialTheme.colorScheme.primary
    // The void the drawer's scrim falls into, a breath of the accent in it.
    val scrim = lerp(Color.Black, accent, 0.12f).copy(alpha = 0.66f)
    val pad = with(density) { 6.dp.toPx() }
    val ring = with(density) { 2.dp.toPx() }
    val glow = with(density) { 7.dp.toPx() }
    val maxRadius = with(density) { 18.dp.toPx() }

    BackHandler(enabled = run != null) { controller.act(TourAction.Stop) }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .graphicsLayer { alpha = presence.value }
            // Every touch lands here: the app under the dim is not live while the tour talks.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(scrim)
            val h = hole.value
            if (h > 0f) {
                val r = cut.value.inflate(pad)
                val corner = CornerRadius(minOf(r.height / 2f, maxRadius))
                drawRoundRect(Color.Black.copy(alpha = h), r.topLeft, r.size, corner, blendMode = BlendMode.DstOut)
                val halo = r.inflate(glow / 2f)
                drawRoundRect(
                    accent.copy(alpha = 0.22f * h), halo.topLeft, halo.size,
                    CornerRadius(corner.x + glow / 2f), style = Stroke(glow),
                )
                drawRoundRect(accent.copy(alpha = 0.95f * h), r.topLeft, r.size, corner, style = Stroke(ring))
            }
        }

        if (cardReady) {
            val insets = WindowInsets.safeDrawing
            TourCardLayout(
                target = target,
                side = step.side,
                insetTop = insets.getTop(density),
                insetBottom = insets.getBottom(density),
            ) {
                key(display.index, step) {
                    TourCard(
                        run = display,
                        reduced = reduced,
                        onNext = { controller.act(TourAction.Next) },
                        onBack = { controller.act(TourAction.Prev) },
                        onClose = { controller.act(TourAction.Stop) },
                        onCta = { id -> controller.act(TourAction.Stop); onCta(id) },
                    )
                }
            }
        }
    }
}

/** Measures the card, then sets it by the target ([placeTourCard]). */
@Composable
private fun TourCardLayout(
    target: Rect?,
    side: TourSide?,
    insetTop: Int,
    insetBottom: Int,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val maxW = minOf(constraints.maxWidth - 2 * margin, 380.dp.roundToPx()).coerceAtLeast(0)
        val card = measurables.first().measure(Constraints(maxWidth = maxW))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val at = placeTourCard(
                target, card.width, card.height, constraints.maxWidth, constraints.maxHeight,
                side, margin, gap = 14.dp.roundToPx(), insetTop = insetTop, insetBottom = insetBottom,
            )
            card.place(at)
        }
    }
}

/**
 * Where the card goes: on the [side] asked for when it fits there, else below the target, else
 * above, else on whichever half has more room (it may then cover part of the screen, never the
 * edges). No target = centred narration. Kept pure for the test.
 */
internal fun placeTourCard(
    target: Rect?,
    w: Int,
    h: Int,
    width: Int,
    height: Int,
    side: TourSide?,
    margin: Int,
    gap: Int,
    insetTop: Int,
    insetBottom: Int,
): IntOffset {
    val minY = insetTop + margin
    val maxY = (height - insetBottom - margin - h).coerceAtLeast(minY)
    fun clampX(x: Float) = x.roundToInt().coerceIn(margin, (width - margin - w).coerceAtLeast(margin))
    fun clampY(y: Float) = y.roundToInt().coerceIn(minY, maxY)
    if (target == null) return IntOffset(clampX((width - w) / 2f), clampY((height - h) / 2f))
    val below = target.bottom + gap
    val above = target.top - gap - h
    val fitsBelow = below + h <= height - insetBottom - margin
    val fitsAbove = above >= minY
    val centredX = clampX(target.center.x - w / 2f)
    when (side) {
        TourSide.TOP -> if (fitsAbove) return IntOffset(centredX, above.roundToInt())
        TourSide.BOTTOM -> if (fitsBelow) return IntOffset(centredX, below.roundToInt())
        TourSide.LEFT -> if (target.left - gap - w >= margin) {
            return IntOffset((target.left - gap - w).roundToInt(), clampY(target.center.y - h / 2f))
        }
        TourSide.RIGHT -> if (target.right + gap + w <= width - margin) {
            return IntOffset((target.right + gap).roundToInt(), clampY(target.center.y - h / 2f))
        }
        null -> Unit
    }
    return when {
        fitsBelow -> IntOffset(centredX, below.roundToInt())
        fitsAbove -> IntOffset(centredX, above.roundToInt())
        target.center.y > height / 2f -> IntOffset(centredX, clampY(above))
        else -> IntOffset(centredX, clampY(below))
    }
}

@Composable
private fun TourCard(
    run: TourRun,
    reduced: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onCta: (String) -> Unit,
) {
    val step = run.step
    // Each stop arrives: a short rise into place. Reduced motion: it is simply there.
    val arrive = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f)) }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(KeryxRadius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        shadowElevation = 10.dp,
        modifier = Modifier
            .widthIn(max = 380.dp)
            .graphicsLayer {
                alpha = arrive.value.coerceIn(0f, 1f)
                translationY = (1f - arrive.value) * rise
            }
            // Read out as each stop arrives; the pane title tells TalkBack where it is.
            .semantics {
                paneTitle = "Tour"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    step.title.orEmpty(),
                    fontFamily = CinzelFamily,
                    fontSize = KeryxType.title,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        KeryxGlyphs.Close,
                        contentDescription = "End the tour",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            step.text?.let {
                Text(
                    it,
                    fontSize = KeryxType.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (run.paged && run.steps.size > 1) {
                    Text(
                        "${run.index + 1} of ${run.steps.size}",
                        fontSize = KeryxType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (run.paged && !run.isFirst) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.width(4.dp))
                }
                val fill = keryxAccentInk()
                val cta = step.cta?.takeIf { run.isLast }
                // The last stop hands over to the next thing to do, on a row of its own below
                // (beside Back and Done it wrapped); "Done" stays for anyone who only wanted the
                // look around.
                if (cta != null) TextButton(onClick = onNext) { Text("Done") }
                else Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = contrastColorFor(fill)),
                ) {
                    Text(
                        when {
                            !run.paged -> "Got it"
                            run.isLast -> "Done"
                            else -> "Next"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            step.cta?.takeIf { run.isLast }?.let { cta ->
                val fill = keryxAccentInk()
                Button(
                    onClick = { onCta(cta.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = contrastColorFor(fill)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp),
                ) {
                    Text(cta.label, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
