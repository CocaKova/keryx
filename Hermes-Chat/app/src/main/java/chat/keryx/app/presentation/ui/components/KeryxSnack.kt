package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.KeryxNotice
import chat.keryx.app.presentation.KeryxNoticeQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The notice's ground, as numbers (2.16) — apart from the composable so PaperContrastTest can do
 * the arithmetic, the way it does for [chat.keryx.app.presentation.ui.ComposerFloor].
 *
 * A notice floats over the transcript, the drawer, a sheet. The system Toast it replaces was a
 * translucent grey pill: a reply scrolling under it read through it, and in paper mode it was the
 * one grey thing on the page. This one stands on its own floor — opaque, so nothing underneath
 * is ever a second line of text behind the words.
 */
object KeryxSnackFloor {
    /** The card's own opacity. 1: whatever passes under it must not show through. */
    const val ALPHA = 1f
    /** surfaceVariant pressed onto the surface, so the card reads as raised off a leaf-coloured
     *  bubble rather than as more of the bubble. */
    const val TINT_ALPHA = 0.85f
}

/** Per window: where that window's input edge is (the composer, Tap-In's steer bar), so a
 *  notice can sit above it instead of on its text. Window coordinates; null = no input. */
@Stable
class KeryxSnackSlot internal constructor() {
    /** Every input in this window reports its own top (2.16: Tap-In's steer bar now shares the
     *  activity window with the composer under it); the notice clears the highest of them. */
    internal val inputTops = androidx.compose.runtime.mutableStateMapOf<Any, Float>()
    internal val inputTopPx: Float? get() = inputTops.values.minOrNull()
}

/**
 * The app's notice line: one queue, shown in whichever window is on top.
 *
 * A Toast floats over every window by itself; a Compose snackbar lives in ONE window, and Keryx
 * has several — the activity (chat, drawer, places), each bottom sheet, each standalone space
 * (Tap-In), the media lightbox. A notice raised while a sheet is open would be drawn under that
 * sheet's scrim, Undo and all. So every window that can be on top wraps its content in a
 * [KeryxSnackLayer]; layers register in the order they open, and the newest is where the notice
 * is drawn. Closing a sheet hands the notice back to the window under it mid-display.
 *
 * Timing lives here, not in a host composable, so a notice that changes windows keeps its clock.
 */
@Stable
class KeryxSnack internal constructor(
    private val scope: CoroutineScope?,
    /** Where a notice goes when no layer is on screen (and the only voice a scope-less default
     *  has) — never silence. */
    private val fallback: ((String) -> Unit)?,
    /** Accessibility stretch: the system's recommended timeout for this much text/controls. */
    private val stretch: (Long, Boolean) -> Long = { ms, _ -> ms },
) {
    internal val layers = mutableStateListOf<KeryxSnackSlot>()

    /** The notice on screen now, if any. */
    var current by mutableStateOf<KeryxNotice?>(null)
        private set

    private var pending: List<KeryxNotice> = emptyList()
    private var signal: CompletableDeferred<Unit>? = null
    private var pump: Job? = null

    fun show(text: String) = show(KeryxNotice(text))

    /** Safe from any thread: the queue is only ever touched on the composition's dispatcher. */
    fun show(notice: KeryxNotice) {
        val s = scope
        if (s == null) {
            fallback?.invoke(notice.text) ?: android.util.Log.w("KeryxSnack", "unhosted notice: ${notice.text}")
            return
        }
        s.launch { enqueue(s, notice) }
    }

    private fun enqueue(s: CoroutineScope, notice: KeryxNotice) {
        if (layers.isEmpty()) {
            fallback?.invoke(notice.text)
            return
        }
        val d = KeryxNoticeQueue.admit(current, pending, notice)
        if (d.dropped) return
        pending = d.pending
        if (d.supersede) signal?.complete(Unit)
        if (pump?.isActive != true) pump = s.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val next = pending.firstOrNull() ?: break
            pending = pending.drop(1)
            val done = CompletableDeferred<Unit>()
            signal = done
            current = next
            withTimeoutOrNull(stretch(KeryxNotice.durationMillis(next), next.hasAction)) { done.await() }
            current = null
            signal = null
            // The exit's breath: two notices back to back read as one that changed its words.
            delay(BETWEEN_MS)
        }
    }

    /** The action was tapped: the notice leaves and its action runs, once. */
    fun act(notice: KeryxNotice) {
        if (current !== notice) return
        signal?.complete(Unit)
        current = null
        notice.onAction?.invoke()
    }

    fun dismiss(notice: KeryxNotice) {
        if (current === notice) signal?.complete(Unit)
    }

    internal fun attach(slot: KeryxSnackSlot) { if (slot !in layers) layers.add(slot) }
    internal fun detach(slot: KeryxSnackSlot) { layers.remove(slot) }
    internal val top: KeryxSnackSlot? get() = layers.lastOrNull()

    private companion object {
        const val BETWEEN_MS = 180L
    }
}

/**
 * The notice line for whatever composes below it. Provided once at the app root (HermesApp);
 * the default only exists so a preview or a stray composition logs instead of crashing.
 * Composables that used to `Toast.makeText(context, …)` call `LocalKeryxSnack.current.show(…)`.
 */
val LocalKeryxSnack = staticCompositionLocalOf { KeryxSnack(scope = null, fallback = null) }

internal val LocalKeryxSnackSlot = compositionLocalOf<KeryxSnackSlot?> { null }

/** The app's one [KeryxSnack], tied to the composition that hosts it. */
@Composable
fun rememberKeryxSnack(): KeryxSnack {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(scope, context) {
        val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        KeryxSnack(
            scope = scope,
            // Only when no layer is composed at all (it should not happen while the app is up).
            fallback = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() },
            stretch = { ms, controls ->
                if (android.os.Build.VERSION.SDK_INT >= 29 && a11y != null) {
                    val flags = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                        (if (controls) android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
                    a11y.getRecommendedTimeoutMillis(ms.toInt(), flags).toLong()
                } else ms
            },
        )
    }
}

/**
 * A modifier (`.then(keryxSnackClearance())`, so a call site needs no import) that reports
 * its element's top edge as the window's input edge: a notice in this window sits above
 * it, never on it. The chat composer wears it (the composer-floor rule: nothing floats on the
 * text you are typing), as does Tap-In's steer bar.
 */
fun keryxSnackClearance(): Modifier = Modifier.composed {
    val slot = LocalKeryxSnackSlot.current ?: return@composed Modifier
    val key = remember { Any() }
    DisposableEffect(slot) { onDispose { slot.inputTops.remove(key) } }
    Modifier.onGloballyPositioned { slot.inputTops[key] = it.positionInWindow().y }
}

/**
 * Hosts notices for one window. Wrap a window's content in it — the activity's root, a sheet's
 * body, a standalone space's dialog. [clearInput] false sets the notice on the window's bottom
 * edge even when an input has reported one (the drawer is open over the composer, a place covers
 * the chat floor).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KeryxSnackLayer(
    modifier: Modifier = Modifier,
    clearInput: Boolean = true,
    content: @Composable () -> Unit,
) {
    val snack = LocalKeryxSnack.current
    val slot = remember { KeryxSnackSlot() }
    DisposableEffect(snack, slot) {
        snack.attach(slot)
        onDispose { snack.detach(slot) }
    }
    var layerBottomPx by remember { mutableFloatStateOf(0f) }
    // Insets an ancestor already padded for (a bottom sheet pads its body above the navigation
    // bar): the notice's rest position must not pay for them twice.
    var consumed by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    Box(
        modifier
            .onConsumedWindowInsetsChanged { consumed = it }
            .onGloballyPositioned { layerBottomPx = it.positionInWindow().y + it.size.height },
    ) {
        CompositionLocalProvider(LocalKeryxSnackSlot provides slot) { content() }
        if (snack.top === slot) {
            val density = LocalDensity.current
            val insets = WindowInsets.navigationBars.union(WindowInsets.ime)
            val restPx = with(density) { 12.dp.roundToPx() }
            val gapPx = with(density) { 8.dp.roundToPx() }
            // Placed in the layout phase, not composed: the composer moves with every keystroke
            // that wraps a line and every frame of the keyboard, and none of that should
            // recompose anything while no notice is up.
            KeryxSnackBar(
                snack,
                Modifier.align(Alignment.BottomCenter).offset {
                    val floor = insets.exclude(consumed).getBottom(this) + restPx
                    val edge = if (clearInput) slot.inputTopPx else null
                    val lift = if (edge == null) floor
                    else maxOf(floor, (layerBottomPx - edge).roundToInt() + gapPx)
                    IntOffset(0, -lift)
                },
            )
        }
    }
}

@Composable
private fun KeryxSnackBar(snack: KeryxSnack, modifier: Modifier) {
    val reduced by rememberReducedMotion()
    AnimatedContent(
        targetState = snack.current,
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val rise = if (reduced) fadeIn(KeryxMotion.settle)
            else fadeIn(KeryxMotion.settle) + slideInVertically(KeryxMotion.settleInt) { it / 2 }
            val sink = if (reduced) fadeOut(KeryxMotion.leave)
            else fadeOut(KeryxMotion.leave) + slideOutVertically(KeryxMotion.leaveInt) { it / 3 }
            (rise togetherWith sink).using(SizeTransform(clip = false))
        },
        label = "keryx-notice",
    ) { notice ->
        if (notice != null) {
            KeryxSnackCard(notice, onAction = { snack.act(notice) }, onDismiss = { snack.dismiss(notice) })
        } else {
            Box(Modifier)
        }
    }
}

/** The card itself: the Keryx card voice (radius, accent hairline), on an opaque ground. */
@Composable
private fun KeryxSnackCard(notice: KeryxNotice, onAction: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val accent2 = cs.tertiary
    val shape = RoundedCornerShape(KeryxRadius.card)
    val ground = keryxSnackGround()
    var dragX by remember(notice) { mutableFloatStateOf(0f) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .shadow(10.dp, shape)
            .clip(shape)
            .background(ground)
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(accent.copy(alpha = 0.5f), accent2.copy(alpha = 0.3f))),
                shape,
            )
            // A flick sideways sends it away — the way out when it sits on something you need.
            .pointerInput(notice) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(dragX) > size.width * 0.3f) onDismiss() else dragX = 0f },
                    onDragCancel = { dragX = 0f },
                ) { _, delta -> dragX += delta }
            }
            // Spoken when it lands, politely: TalkBack finishes its sentence, then reads it.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                dismiss { onDismiss(); true }
            }
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = if (notice.hasAction) 6.dp else 16.dp),
    ) {
        Text(
            notice.text,
            color = cs.onSurface,
            fontSize = KeryxType.body,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (notice.hasAction) {
            TextButton(onClick = onAction) {
                Text(
                    notice.actionLabel.orEmpty(),
                    color = keryxAccentInk(),
                    fontSize = KeryxType.body,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The opaque ground [KeryxSnackFloor] describes, in the live theme. */
@Composable
internal fun keryxSnackGround(): Color {
    val cs = MaterialTheme.colorScheme
    return cs.surfaceVariant.copy(alpha = KeryxSnackFloor.TINT_ALPHA)
        .compositeOver(cs.surface.copy(alpha = KeryxSnackFloor.ALPHA))
}
