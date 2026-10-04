package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Keep an expander's header on screen while its body opens (2.17.1).
 *
 * The transcript is a bottom-anchored list (reverseLayout), so a section that grows pushes its
 * own header UP: tap "Why render natively?" near the bottom and the line you tapped slid off
 * the top while the body took its place, which read as the tap doing nothing. Put this on the
 * header; for the length of the reveal it asks the list to keep the header in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.keepInViewWhenOpened(open: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val opened = remember { booleanArrayOf(open) }
    LaunchedEffect(open) {
        val wasOpen = opened[0]
        opened[0] = open
        if (!open || wasOpen) return@LaunchedEffect
        // The reveal animates over a few hundred ms; follow it until it settles. Not a frame
        // ticker: a scroll request every 40 ms, ten times, then nothing.
        repeat(10) {
            kotlinx.coroutines.delay(40)
            runCatching { requester.bringIntoView() }
        }
    }
    return this.bringIntoViewRequester(requester)
}
