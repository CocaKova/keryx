package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The model's structured thinking as a quiet scaffold disclosure above the reply — the shape
 * the direct transport's `Message.reasoning` field renders as (harvest, plan §3 "Reasoning:
 * Talaria"). While the thought streams it reads "Thinking…" with a live seconds count,
 * auto-open on a capped preview that follows its own tail; when the turn lands it collapses
 * to "Thought for Ns" (or just "Thought" for hydrated turns — the gateway persists no
 * duration). The first explicit tap wins over the auto behavior from then on.
 *
 * The Matrix path keeps its gathered per-run reasoning block; this is the per-message form
 * the structured field feeds. Scaffold voice throughout: agent-state chrome, never prose.
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
    val reduced by rememberReducedMotion()
    val dotAlpha = when {
        !streaming -> 0.8f
        reduced -> 0.9f
        else -> rememberInfiniteTransition(label = "think_breathe").animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "think_breathe_alpha",
        ).value
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .alpha(if (streaming) 1f else 0.72f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .clickable { userOpen = !open }
                .padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = dotAlpha)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = quiet.copy(alpha = if (streaming) 0.9f else 0.7f),
            )
            if (streaming && seconds != null && seconds >= 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${seconds}s",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = quiet.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "▾" else "▸",
                fontSize = 9.5.sp,
                color = quiet.copy(alpha = 0.55f),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val scroll = rememberScrollState()
            // Streaming preview follows its own tail — the newest thought is the interesting
            // one. Height-capped so a long think never shoves the transcript around.
            if (streaming) LaunchedEffect(reasoning.length) { scroll.scrollTo(scroll.maxValue) }
            Text(
                reasoning.trim(),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = quiet.copy(alpha = 0.62f),
                modifier = Modifier
                    .padding(start = 2.dp, top = 2.dp, bottom = 4.dp)
                    .then(if (streaming) Modifier.heightIn(max = 150.dp).verticalScroll(scroll) else Modifier),
            )
        }
    }
}

/** 12s · 1m 5s — whole seconds, minutes past 60. */
internal fun formatThought(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
