package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.keryx.core.model.CompactionGauge
import chat.keryx.core.model.ContextOutlook

/**
 * Chat memory (3.0): what the context ring means, said once and plainly. A bar for the model's
 * whole window with the summary point marked on it, one sentence ("Room for about 40 more
 * replies. Then <agent> summarizes the older messages so the chat can keep going."), and the
 * exact figures in small print. On the direct door, "What's using it" opens the itemised
 * breakdown the ring used to open straight away.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatMemorySheet(
    used: Long,
    max: Long,
    compactAt: Long,
    repliesLeft: Int?,
    agent: String?,
    onWhatsUsing: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val trigger = compactAt.takeIf { it in 1..max } ?: 0L
    val gauge = CompactionGauge.of(used, trigger.takeIf { it > 0L })
    val toTrigger = gauge?.fraction ?: (used.toFloat() / max.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    val left = gauge?.left ?: (max - used).coerceAtLeast(0L)
    val tone = when {
        toTrigger > 0.90f -> KeryxStatus.bad
        toTrigger > 0.75f -> KeryxStatus.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val sentence = ContextOutlook.sentence(repliesLeft, left, agent, hasTrigger = trigger > 0L)
    KeryxSheet(onDismiss = onDismiss, title = "Chat memory") {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MemoryBar(
                filled = (used.toFloat() / max.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                marker = if (trigger > 0L) trigger.toFloat() / max.toFloat() else null,
                tone = tone,
                modifier = Modifier.semantics { contentDescription = sentence },
            )
            if (trigger in 1 until max) {
                Text(
                    "The line marks the summary point.",
                    fontSize = KeryxType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(sentence, fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "After that the agent works from a summary of the older messages, so early details " +
                    "may come back shorter. A long chat can be summarized more than once.",
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ContextOutlook.smallPrint(used, trigger, max) +
                    (if (repliesLeft != null) " · the reply count is an estimate from this chat" else ""),
                fontSize = KeryxType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onWhatsUsing != null) {
                OutlinedButton(onClick = onWhatsUsing) { Text("What's using it", fontSize = KeryxType.caption) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** The model's window as one bar: [filled] of it used, the summary point at [marker]. */
@Composable
private fun MemoryBar(filled: Float, marker: Float?, tone: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(track, cornerRadius = r)
        if (filled > 0f) drawRoundRect(tone, size = Size(size.width * filled, size.height), cornerRadius = r)
        marker?.let { m ->
            val x = (size.width * m).coerceIn(1f, size.width - 1f)
            drawLine(ink, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}
