package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ChatViewModel

/**
 * An aside (2.16 `prompt.btw`): a question answered over a snapshot of the conversation while
 * the turn keeps going. It sits at the foot of the chat, dashed off from it in a hairline frame
 * and labelled as outside it, because it is: it never enters the history, the agent never sees
 * it, and it is gone when dismissed or when the app closes. It lives in the transcript's own
 * flow (not floating), so it covers nothing.
 */
@Composable
fun AsideBubble(aside: ChatViewModel.Aside, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, quiet.copy(alpha = 0.35f), RoundedCornerShape(KeryxRadius.card))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ASIDE · not part of the chat",
                color = quiet,
                fontSize = KeryxType.micro,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.size(width = 64.dp, height = 32.dp)) {
                Text("Dismiss", fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(aside.question, color = quiet, fontSize = KeryxType.caption, fontStyle = FontStyle.Italic)
        Spacer(Modifier.height(4.dp))
        val answer = aside.answer
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when {
                answer == null -> Text("…answering aside", color = quiet, fontSize = KeryxType.caption)
                aside.failed -> Text("The aside failed: $answer", color = MaterialTheme.colorScheme.error, fontSize = KeryxType.caption)
                answer.isBlank() -> Text("No answer came back.", color = quiet, fontSize = KeryxType.caption)
                else -> MessageContent(content = answer, textColor = ink)
            }
        }
    }
}
