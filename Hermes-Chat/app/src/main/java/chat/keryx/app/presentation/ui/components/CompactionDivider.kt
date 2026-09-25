package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message
import chat.keryx.core.protocol.CompactionCarryOver

/**
 * Where a compaction happened (2.13.11): the handoff row the gateway leaves at the top of the
 * continued session, drawn as a divider — "🗜 Context compacted · 09:56" — instead of the
 * agent's own several-thousand-word notes in a chat bubble. The live banner is gone the moment
 * the compaction ends; this is the mark that stays, so "did it compact?" has an answer later.
 * Tap opens the summary itself: what the agent now remembers of everything above the line.
 */
@Composable
fun CompactionDivider(message: Message) {
    var open by rememberSaveable(message.id) { mutableStateOf(false) }
    val clock = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(message.timestamp))
    }
    val summary = remember(message.content) { CompactionCarryOver.summary(message.content) }
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                // The whole band is the target: a 11sp label alone is too small to aim at.
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .clickable(onClickLabel = if (open) "Hide the summary" else "Read the summary") { open = !open },
        ) {
            val line = meta.copy(alpha = 0.18f)
            Box(Modifier.weight(1f).height(1.dp).background(line))
            Text(
                text = "🗜 Context compacted · $clock",
                color = meta.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(line))
        }
        AnimatedVisibility(visible = open) {
            Text(
                text = summary,
                color = meta,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .background(meta.copy(alpha = 0.06f))
                    .padding(12.dp),
            )
        }
    }
}
