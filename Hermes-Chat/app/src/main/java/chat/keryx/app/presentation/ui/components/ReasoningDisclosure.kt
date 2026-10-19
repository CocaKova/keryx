package chat.keryx.app.presentation.ui.components

import chat.keryx.app.theme.quietInk
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The model's structured thinking as a quiet scaffold disclosure above the reply — the shape
 * the direct transport's `Message.reasoning` field renders as (harvest, plan §3 "Reasoning:
 * Talaria"). While the thought streams it reads "Thinking…" with a live seconds count,
 * auto-open on a capped preview that follows its own tail; when the turn lands it collapses
 * to "Thought for Ns" (or just "Thought" for hydrated turns — the gateway persists no
 * duration). The first explicit tap wins over the auto behavior from then on.
 *
 * 3.1 §B: the ONE settled reasoning voice on both doors. Both producers fill
 * `Message.reasoning` (§B1), a run's consolidated block renders through this same composable
 * (§B3), and `ReasoningCanvas` survives only as the live still-thinking form inside the
 * streaming bubble. Scaffold voice throughout: agent-state chrome, never prose.
 */
@Composable
fun ReasoningDisclosure(
    reasoning: String,
    seconds: Int?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    stateKey: String = "",
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    // null = no explicit toggle yet → follow the stream (open while thinking, closed after).
    var userOpen by rememberSaveable(stateKey) { mutableStateOf<Boolean?>(null) }
    val open = userOpen ?: streaming

    val label = when {
        streaming -> "Thinking…"
        seconds == null -> "Thought"
        seconds < 1 -> "Thought briefly"
        else -> "Thought for ${formatThought(seconds)}"
    }

    // The dot breathes while thinking — motion that carries information keeps moving; under
    // ReducedMotion it holds at the bright end rather than reading as "stopped".
    // The pulse is read while drawing the dot (3.0 perf): read here, it recomposed the whole
    // disclosure, the streaming thought included, every frame of a think.
    val reduced by rememberReducedMotion()
    val stillAlpha = if (!streaming) 0.8f else 0.9f
    val dotPulse = if (streaming && !reduced) {
        rememberInfiniteTransition(label = "think_breathe").animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "think_breathe_alpha",
        )
    } else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            // A settled thought used to recede by fading the whole block to 72%, on top of its
            // own quiet ink: "Thought" read 2.04:1 on parchment and the opened reasoning about
            // the same (3.0 accessibility pass). It recedes by its ink alone now, at 4.5:1.
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                // Only a tap the reader made pins the header; a live think opening by itself
                // must not pull the list away from the tail it is following.
                .keepInViewWhenOpened(userOpen == true && !streaming)
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .clickable(onClickLabel = if (open) "Hide the reasoning" else "Show the reasoning") { userOpen = !open }
                .semantics { stateDescription = if (open) "Open" else "Folded" }
                .padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .drawBehind { drawRect(accent.copy(alpha = dotPulse?.value ?: stillAlpha)) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontSize = KeryxType.caption,
                fontWeight = FontWeight.Medium,
                color = quietInk(alpha = if (streaming) 0.9f else 0.7f),
            )
            if (streaming && seconds != null && seconds >= 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${seconds}s",
                    fontSize = KeryxType.micro,
                    fontFamily = FontFamily.Monospace,
                    color = quietInk(alpha = 0.5f),
                    // A ticking count is for the eye; read aloud it would change every second.
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "▾" else "▸",
                fontSize = KeryxType.micro,
                color = quietInk(alpha = 0.55f),
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = keryxReveal(),
            exit = keryxConceal(),
        ) {
            // The live thought, in pieces that stop changing once cut (3.0 perf): one Text over
            // the whole thought re-laid out every line of it on each publish, and a long run's
            // thought is hundreds of kilobytes (ThoughtPieces). Remembered with the pane, so a
            // growing thought keeps its earlier pieces.
            val thought = remember { ThoughtPieces() }
            val pieces = if (streaming) remember(reasoning) { thought.of(reasoning) } else emptyList()
            // A lazy list draws only the pieces in view. It opens on the newest piece, where the
            // preview has always opened.
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (pieces.size - 1).coerceAtLeast(0))
            // The streaming preview follows the newest thought — but only from the newest.
            // Pinning to the tail on every token made a long think unreadable: you could not
            // scroll back a paragraph without the next word hauling you to the bottom, so the
            // only way to read a thought was to wait for the model to finish having it. The
            // transcript has obeyed this law for versions (ChatScreen: follow only at the
            // bottom, never under a finger); this pane now obeys the same one.
            // Height-capped so a long think never shoves the transcript around.
            val following = rememberLazyTailFollow(list, reasoning.length, enabled = streaming, startFollowing = true)
            val scope = rememberCoroutineScope()
            Box {
                val color = quietInk(alpha = 0.62f)
                if (streaming) {
                    // A live one stays plain text: re-formatting a growing think on every token
                    // is not worth its cost. Full width: a lazy list is as wide as the pieces in
                    // view, and the cue at its corner must not wander as they scroll past.
                    LazyColumn(
                        state = list,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, top = 2.dp, bottom = 4.dp)
                            .heightIn(max = 150.dp),
                    ) {
                        items(pieces.size) { i ->
                            Text(pieces[i], fontSize = KeryxType.caption, lineHeight = 17.sp, color = color)
                        }
                    }
                } else {
                    // Settled thoughts read as light markdown (bold, code, links, bullets, inline
                    // TeX) instead of raw asterisks, in full.
                    val linkColor = chatLinkColor(quiet)
                    val shown = remember(reasoning, linkColor) { quietMarkdownAnnotated(reasoning.trim(), linkColor) }
                    Text(
                        shown,
                        fontSize = KeryxType.caption,
                        lineHeight = 17.sp,
                        color = color,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                // You held your place and the thought went on without you. Say so, and offer the
                // way back — a pane that has quietly stopped moving is otherwise indistinguishable
                // from a model that has quietly stopped thinking.
                // A plain alpha rather than AnimatedVisibility: this Box sits inside a Column,
                // so the ColumnScope overload wins the implicit receiver and won't compile here.
                // Reduced motion shows it at once — it is a cue, not a flourish, and it has to
                // arrive whether or not it is allowed to fade.
                val cueTarget = if (streaming && !following) 1f else 0f
                val cueFade by animateFloatAsState(cueTarget, label = "tail_cue")
                val cueAlpha = if (reduced) cueTarget else cueFade
                if (cueAlpha > 0.01f) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .alpha(cueAlpha)
                            .padding(end = 2.dp, bottom = 6.dp)
                            .clip(RoundedCornerShape(KeryxRadius.chip))
                            .background(accent.copy(alpha = 0.16f))
                            .clickable { scope.launch { list.animateScrollToTail() } }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Text("\u25be", fontSize = KeryxType.micro, color = accent.copy(alpha = 0.9f))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "newest",
                            fontSize = KeryxType.micro,
                            fontWeight = FontWeight.Medium,
                            color = accent.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }
    }
}

/** 12s · 1m 5s — whole seconds, minutes past 60. */
internal fun formatThought(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
