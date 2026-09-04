package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import chat.keryx.core.model.TailFollow
import kotlinx.coroutines.flow.drop

/**
 * Binds a [ScrollState] to a growing tail under the law in [TailFollow]: it rides the newest
 * content while the reader is at the newest, lets go the moment they scroll back, and takes hold
 * again when they return to the bottom.
 *
 * Wire it to any region that is written into while it is being read — the reasoning preview, the
 * live run console — and pass the thing that changes as content arrives as [tail] (a length, the
 * text itself; anything whose equality moves when the content does).
 *
 * Returns whether it is currently following, so a caller can say so: a pane that has quietly
 * stopped moving is otherwise indistinguishable from a model that has quietly stopped thinking.
 *
 * @param enabled false where the region is not scrollable at all (a settled thought renders in
 *   full, uncapped, and has no scroll state to hold onto).
 */
@Composable
fun rememberTailFollow(scroll: ScrollState, tail: Any?, enabled: Boolean = true): Boolean {
    val slopPx = with(LocalDensity.current) { TailFollow.SLOP_DP.dp.roundToPx() }
    var following by remember(scroll) { mutableStateOf(true) }

    // Re-latch only when a scroll SETTLES — see TailFollow's note on why this cannot be a live
    // read. `drop(1)` discards the not-scrolling that snapshotFlow emits at composition, before
    // the region has been measured: answering "am I at the tail" against an unmeasured extent
    // would detach a reader who had not touched anything.
    LaunchedEffect(scroll, slopPx, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { scroll.isScrollInProgress }
            .drop(1)
            .collect { moving ->
                if (!moving) following = TailFollow.atTail(scroll.value, scroll.maxValue, slopPx)
            }
    }

    LaunchedEffect(tail, following, enabled) {
        if (enabled && TailFollow.shouldFollow(following, scroll.isScrollInProgress)) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    return !enabled || following
}
