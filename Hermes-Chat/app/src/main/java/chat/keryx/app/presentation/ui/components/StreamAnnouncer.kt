package chat.keryx.app.presentation.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.LiveStream
import chat.keryx.app.presentation.LiveStreamStatus
import chat.keryx.app.presentation.TtsText
import chat.keryx.app.transport.direct.LiveTurnIds
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.SpokenStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The streaming reply, read aloud under TalkBack (2.16) — a polite live region that carries one
 * finished stretch at a time ([SpokenStream]), never the token-by-token bubble.
 *
 * It lives outside the bubble on purpose: the live bubble leaves the list the frame the
 * committed message replaces it, and the last sentence of a reply is exactly the one that would
 * be lost with it. A new turn, or a different room, starts from nothing said.
 *
 * Costs nothing without a screen reader: no node, no loop, no scan of the list. With one, it
 * looks at the text once per spoken stretch (≥ 1.5 s), off the main thread.
 */
@Composable
fun StreamAnnouncer(
    /** The side-channel overlay, when this room's turn streams over it (Matrix door). */
    sideStream: LiveStream?,
    /** The room's messages — the direct door streams INTO the transcript. */
    messages: List<Message>,
    /** The room on screen: switching rooms drops whatever was being read. */
    roomKey: Any?,
    modifier: Modifier = Modifier,
) {
    if (!rememberScreenReaderOn()) return
    val now = remember(sideStream, messages) { LiveTurnProse.of(sideStream, messages) }
    // The turn being read outlives its live rows by the flush at its end: the overlay goes the
    // moment the turn settles, and the words still to say went with it.
    val turn = remember(roomKey) { arrayOf<String?>(null) }
    val kept = remember(roomKey) { arrayOf("") }
    if (now != null) {
        if (now.key != turn[0]) { turn[0] = now.key; kept[0] = "" }
        if (now.text.isNotEmpty()) kept[0] = now.text
    }
    val key = turn[0] ?: return
    var said by remember(roomKey, key) { mutableStateOf("") }
    val streaming by rememberUpdatedState(now?.streaming == true)
    LaunchedEffect(roomKey, key) {
        var anchor = ""
        while (true) {
            val final = !streaming
            val text = kept[0]
            val speakable = withContext(Dispatchers.Default) { TtsText.speakable(text) }
            val step = SpokenStream.next(speakable, anchor, final)
            if (step != null) {
                said = step.say
                anchor = step.anchor
            }
            if (final && step == null) break
            delay(step?.let { SpokenStream.gapMs(it.say) } ?: SpokenStream.MIN_GAP_MS)
        }
    }
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            if (said.isNotEmpty()) contentDescription = said
        },
    )
}

/** The turn in flight, as a screen reader should hear it: one key, one growing text. */
internal object LiveTurnProse {

    data class Turn(val key: String, val streaming: Boolean, val text: String)

    fun of(sideStream: LiveStream?, messages: List<Message>): Turn? {
        if (sideStream != null) {
            return Turn(
                key = "side-${sideStream.startedAt}",
                streaming = sideStream.status == LiveStreamStatus.STREAMING ||
                    sideStream.status == LiveStreamStatus.CONNECTING,
                text = sideStream.text,
            )
        }
        val answer = messages.lastOrNull { it.isStreaming && it.sender == SenderType.HERMES } ?: return null
        // The direct door seals a turn's prose into steps each time a tool starts, and streams
        // the rest into its answer row; read as one text, in order, it only ever grows.
        val turn = LiveTurnIds.turnOf(answer.id) ?: return Turn(answer.id, true, answer.content)
        val prose = messages.filter {
            it !== answer && it.sender == SenderType.HERMES && it.toolCalls.isEmpty() &&
                it.content.isNotBlank() && LiveTurnIds.turnOf(it.id) == turn
        }.map { it.content } + answer.content
        return Turn(turn, true, prose.filter { it.isNotBlank() }.joinToString("\n\n"))
    }
}

/** Is a screen reader exploring the screen right now? Follows it being switched on and off. */
@Composable
internal fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val am = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember(am) { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(listener)
        onDispose { am?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}
