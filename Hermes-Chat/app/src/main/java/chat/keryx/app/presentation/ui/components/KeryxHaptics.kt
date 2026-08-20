package chat.keryx.app.presentation.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tick vocabulary (2.0 Phase 3: "one tick vocabulary — light tick on gesture commit, soft
 * double on completion — behind the existing haptics setting").
 *
 * Only half of that ever landed. Four call sites reached for [HapticFeedback] directly, three of
 * them asking for [HapticFeedbackType.LongPress] whatever the gesture meant, and — the part worth
 * fixing first — **not one of them consulted the setting**. Settings ▸ Interface ▸ "Haptic
 * Feedback" was read, written, persisted and drawn as a switch that changed nothing.
 *
 * So the vocabulary is a value now, provided once through [LocalKeryxHaptics], and the switch is
 * enforced in exactly one place: [enabled] is checked here, not at each call site, because a
 * setting honoured in four places independently is a setting that will be honoured in three of
 * them by the next release.
 *
 * Three ticks, and deliberately only three — a phone that buzzes differently at ten different
 * moments is not a vocabulary, it is noise:
 *
 *  - [commit]      something you did took effect. The light one, and the common one.
 *  - [press]       a long press opened something. Heavier, because it answers a heavier gesture.
 *  - [completion]  the agent finished. The only two-beat tick in the app, so that "it's done"
 *                  is distinguishable from "you did that" without looking.
 */
@Stable
class KeryxHaptics(
    private val haptics: HapticFeedback,
    private val enabled: Boolean,
    private val scope: CoroutineScope,
) {
    /** A gesture landing, a message leaving, an action taking effect. */
    fun commit() {
        if (!enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }

    /** A long press opening a menu, a sheet, or a confirm. */
    fun press() {
        if (!enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * The turn is done. Two soft beats about 90 ms apart — close enough to read as one event,
     * far enough apart to be countable in a pocket.
     */
    fun completion() {
        if (!enabled) return
        scope.launch {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            delay(COMPLETION_GAP_MS)
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    companion object {
        const val COMPLETION_GAP_MS = 90L
    }
}

/**
 * Silent by default. A composable that ticks before anything provides the real vocabulary should
 * do nothing rather than crash — and a missing provider must never be the thing that turns
 * haptics *on* for someone who switched them off.
 */
val LocalKeryxHaptics = staticCompositionLocalOf {
    KeryxHaptics(
        haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
        },
        enabled = false,
        scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate),
    )
}
