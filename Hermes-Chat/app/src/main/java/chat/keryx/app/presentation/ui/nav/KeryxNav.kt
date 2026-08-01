package chat.keryx.app.presentation.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keryx's navigation spine (2.0 "The Dream Rebuild", Phase 1).
 *
 * The chat is the app's permanent floor; everything else — Archive, Missions, the Agent Hub —
 * is a *place* pushed onto a real back stack above it. This ~200-line owned layer replaces the
 * scattered `remember { mutableStateOf(false) }` booleans and their full-screen Dialogs, and it
 * exists instead of Navigation Compose for one reason: the back gesture. [KeryxNavHost] renders
 * each place with a progress-driven transition that the predictive-back gesture *scrubs* — the
 * page follows the finger, springs home if released early, and sinks away on commit. Owning the
 * whole path from gesture to pixels is what makes the app feel liquid; a library host would own
 * the middle of it.
 *
 * Deep links ride the same types: every destination has a stable [KeryxDest.route] string that
 * intents (assistant doorway, widgets, notifications) can name and [KeryxDest.fromRoute] can
 * resolve, and the stack survives process recreation by saving those routes.
 */
sealed interface KeryxDest {
    /** Stable name used for state saving and deep links. Never rename casually. */
    val route: String

    data object Archive : KeryxDest { override val route = "archive" }
    data object Missions : KeryxDest { override val route = "missions" }
    data object Hub : KeryxDest { override val route = "hub" }
    data object Settings : KeryxDest { override val route = "settings" }

    companion object {
        fun fromRoute(route: String): KeryxDest? =
            listOf(Archive, Missions, Hub, Settings).firstOrNull { it.route == route }
    }
}

/** The back stack. [open] brings an already-open place to the front instead of stacking twins. */
@Stable
class KeryxNavState internal constructor(initial: List<KeryxDest>) {
    internal val stack = mutableStateListOf<KeryxDest>().apply { addAll(initial) }

    val current: KeryxDest? get() = stack.lastOrNull()

    fun open(dest: KeryxDest) {
        if (stack.lastOrNull() == dest) return
        stack.remove(dest)
        stack.add(dest)
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** Straight to the chat floor — the assist gesture's landing. */
    fun home() {
        stack.clear()
    }
}

@Composable
fun rememberKeryxNav(): KeryxNavState = rememberSaveable(
    saver = listSaver(
        save = { it.stack.map(KeryxDest::route) },
        restore = { KeryxNavState(it.mapNotNull(KeryxDest::fromRoute)) },
    ),
) { KeryxNavState(emptyList()) }

/** One place on screen: its destination plus the 0..1 arrival it's currently at. */
private class NavLayer(val dest: KeryxDest) {
    val progress = Animatable(0f)
    var exiting by mutableStateOf(false)
}

private object NavMotion {
    /** Arrival and gesture-release settle; departure. Both from the app's motion vocabulary. */
    val settle = chat.keryx.app.presentation.ui.components.KeryxMotion.settle
    val leave = chat.keryx.app.presentation.ui.components.KeryxMotion.leave

    /** How far a fully backed-out gesture scrubs arrival down before commit finishes it. */
    const val GESTURE_FLOOR = 0.45f
}

/**
 * Renders [root] (the chat floor) with every destination on [nav]'s stack layered above it.
 * Layers arrive by rising out of the void (fade + lift + settle) and leave by sinking back;
 * the system back gesture scrubs the top layer's arrival live.
 */
@Composable
fun KeryxNavHost(
    nav: KeryxNavState,
    modifier: Modifier = Modifier,
    root: @Composable () -> Unit,
    content: @Composable (KeryxDest) -> Unit,
) {
    val layers = remember { mutableStateListOf<NavLayer>() }

    // Mirror the stack into layers: new destinations get an entering layer, vanished ones flip
    // to exiting (their layer stays until the departure animation lands), survivors reorder to
    // match stack z-order. Exiting layers sort to the top — they leave *over* what remains.
    val stackNow = nav.stack.toList()
    LaunchedEffect(stackNow) {
        stackNow.forEach { dest ->
            if (layers.none { it.dest == dest && !it.exiting }) layers.add(NavLayer(dest))
        }
        layers.forEach { layer ->
            if (!layer.exiting && stackNow.none { it == layer.dest }) layer.exiting = true
        }
        layers.sortBy { layer ->
            if (layer.exiting) Int.MAX_VALUE else stackNow.indexOf(layer.dest)
        }
    }

    // Registered BEFORE the layers compose, so any BackHandler *inside* a place (a viewer, an
    // unsaved-edits guard) is registered later and wins the dispatch.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val top = layers.lastOrNull { !it.exiting }
    PredictiveBackHandler(enabled = top != null) { events ->
        val layer = top ?: return@PredictiveBackHandler
        try {
            events.collect { event ->
                layer.progress.snapTo(1f - (1f - NavMotion.GESTURE_FLOOR) * event.progress)
            }
            // One light tick as the place lets go — the gesture's full stop (2.0 haptic grammar).
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
            nav.back() // the exit animation picks up from wherever the finger left off
        } catch (e: CancellationException) {
            layer.progress.animateTo(1f, NavMotion.settle)
        }
    }

    val liftPx = with(LocalDensity.current) { 28.dp.toPx() }
    Box(modifier.fillMaxSize()) {
        root()
        layers.forEach { layer ->
            key(layer) {
                LaunchedEffect(layer.exiting) {
                    if (!layer.exiting) {
                        layer.progress.animateTo(1f, NavMotion.settle)
                    } else {
                        layer.progress.animateTo(0f, NavMotion.leave)
                        layers.remove(layer)
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = layer.progress.value
                            alpha = p
                            translationY = (1f - p) * liftPx
                            val depth = 0.985f + 0.015f * p
                            scaleX = depth
                            scaleY = depth
                            // Mid-transition the place is still *of the void*: unfocused, rising
                            // into clarity (or sinking out of it). RenderEffect needs Android 12.
                            renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && p < 0.999f) {
                                val blurPx = ((1f - p) * 14.dp.toPx()).coerceAtLeast(0.05f)
                                androidx.compose.ui.graphics.BlurEffect(
                                    blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Decal,
                                )
                            } else null
                        }
                        // The chat floor stays composed underneath; a place mid-arrival must
                        // not let stray taps fall through to the composer below it.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) { content(layer.dest) }
            }
        }
    }
}
