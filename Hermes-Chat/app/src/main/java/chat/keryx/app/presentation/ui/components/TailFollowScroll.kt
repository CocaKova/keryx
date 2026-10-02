package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
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
import kotlinx.coroutines.flow.first

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

/**
 * [rememberTailFollow] for a lazy list (2.16) — Tap-In's rail, which grew a row per call below
 * the fold and never moved to show it. Same law: ride the newest while the reader is at the
 * newest, let go when they scroll back, take hold again when they return.
 *
 * One difference, on purpose: a lazy list opens at its top, so it starts following only if its
 * first layout already shows the end. Opening Tap-In onto a long turn lands on the headline,
 * not thrown past it to the bottom; reaching the bottom is what hands the list the wheel.
 */
@Composable
fun rememberLazyTailFollow(list: LazyListState, tail: Any?, enabled: Boolean = true): Boolean {
    val slopPx = with(LocalDensity.current) { TailFollow.SLOP_DP.dp.roundToPx() }
    var following by remember(list) { mutableStateOf(false) }

    LaunchedEffect(list, slopPx, enabled) {
        if (!enabled) return@LaunchedEffect
        // The first answer waits for a real layout, for the same reason the scroll version
        // drops its first emission: an unmeasured list has no end to be at.
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
        following = list.atTail(slopPx)
        snapshotFlow { list.isScrollInProgress }
            .drop(1)
            .collect { moving -> if (!moving) following = list.atTail(slopPx) }
    }

    LaunchedEffect(tail, following, enabled) {
        if (enabled && TailFollow.shouldFollow(following, list.isScrollInProgress)) list.scrollToTail()
    }

    return !enabled || following
}

private fun LazyListState.atTail(slopPx: Int): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return info.totalItemsCount == 0
    return TailFollow.lazyAtTail(
        lastVisibleIndex = last.index,
        totalItems = info.totalItemsCount,
        lastItemEnd = last.offset + last.size,
        afterPadding = info.afterContentPadding,
        viewportEnd = info.viewportEndOffset,
        slopPx = slopPx,
    )
}

/** Bring the very end into view: the last item, then the rest of it if it is taller than the
 *  room left (a growing "Saying" block is). */
private suspend fun LazyListState.scrollToTail() {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    val lastShown = layoutInfo.visibleItemsInfo.lastOrNull()
    if (lastShown == null || lastShown.index < total - 1) scrollToItem(total - 1)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val over = TailFollow.lazyOvershoot(last.index, info.totalItemsCount, last.offset + last.size,
        info.afterContentPadding, info.viewportEndOffset) ?: return
    if (over > 0) scrollBy(over.toFloat())
}
