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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.ToolBeat

/**
 * The tool theater (2.4), inside the live reply: what the agent is *doing* while it works.
 *
 * Deliberately scaffold voice — monospace, low alpha, one line per beat — because this is
 * telemetry that expires. The committed message renders the same calls properly a moment
 * later ([ToolGroupCard]); if the theater competed with the answer for attention it would be
 * shouting about the means while the end arrives.
 *
 * Only the tail is shown: a long turn can run dozens of tools and the overlay must never take
 * the screen. What scrolled off is counted, never silently dropped.
 */
private const val VISIBLE_BEATS = 6

@Composable
fun TheaterStage(
    beats: List<ToolBeat>,
    live: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = beats.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val hidden = (beats.size - VISIBLE_BEATS).coerceAtLeast(0)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (hidden > 0) {
                Text(
                    "· $hidden earlier",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = baseColor.copy(alpha = 0.35f),
                )
            }
            beats.takeLast(VISIBLE_BEATS).forEach { beat ->
                TheaterRow(beat = beat, live = live, baseColor = baseColor)
            }
        }
    }
}

@Composable
private fun TheaterRow(beat: ToolBeat, live: Boolean, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.tertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (beat.depth * 10).dp, top = 1.dp, bottom = 1.dp),
    ) {
        // A running call breathes; a landed one is a fixed mark. When the turn itself has
        // stopped, a still-open call is neither — it never reported back, and a pulse that
        // outlives the turn reads as "still working" when nothing is.
        if (beat.running && live) {
            val pulse = rememberInfiniteTransition(label = "beat")
            val a by pulse.animateFloat(
                initialValue = 0.25f, targetValue = 0.9f,
                animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
                label = "beatAlpha",
            )
            Box(Modifier.size(5.dp).clip(CircleShape).background(accent.copy(alpha = a)))
        } else {
            Text(
                when {
                    beat.running -> "·"
                    beat.ok == false -> "✕"
                    else -> "✓"
                },
                fontSize = 9.sp,
                color = if (beat.ok == false) MaterialTheme.colorScheme.error
                        else baseColor.copy(alpha = 0.45f),
                modifier = Modifier.width(5.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            if (beat.subagent) "🔀" else "⚙",
            fontSize = 9.5.sp,
            color = baseColor.copy(alpha = 0.55f),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            beat.name,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (beat.subagent) FontWeight.SemiBold else FontWeight.Normal,
            color = baseColor.copy(alpha = if (beat.running) 0.85f else 0.6f),
            maxLines = 1,
        )
        if (beat.preview.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                beat.preview,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (!beat.running && beat.ms > 0L) {
            Spacer(Modifier.width(6.dp))
            Text(
                if (beat.ms >= 1000L) "${beat.ms / 1000}s" else "${beat.ms}ms",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.35f),
            )
        }
    }
    // Only a failure carries its result this far (the gateway sends it for nothing else), and
    // when a tool breaks mid-turn the reason is the one thing worth the extra line.
    if (beat.ok == false && beat.result.isNotBlank()) {
        Text(
            beat.result,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (beat.depth * 10 + 17).dp, bottom = 2.dp),
        )
    }
}
