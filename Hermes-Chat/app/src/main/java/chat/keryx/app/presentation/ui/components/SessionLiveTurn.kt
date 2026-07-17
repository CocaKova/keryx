package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel

/**
 * The live agent turn a Sessions-tab Resume streams into its transcript view (1.21 — the 1.20
 * Run Console distilled to the one place it earned): status heartbeat, reasoning tail, recent
 * tool lines, and the tail-windowed transcript, with Stop while the turn is in flight.
 * Renders from [ChatViewModel.console]; the caller decides whether the live turn is *this*
 * session's (`console.runId == "session:<id>"`).
 */
@Composable
fun SessionLiveTurn(viewModel: ChatViewModel) {
    val console by viewModel.console.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    when (console.status) {
                        "completed" -> Color(0xFF4CAF50)
                        "starting", "streaming", "waiting" -> Color(0xFFE8A33D)
                        "cancelled", "lost" -> Color(0x66FFFFFF)
                        else -> Color(0xFFE0524D)
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (console.status) {
                    "starting" -> "starting…"
                    "streaming" -> "agent is working"
                    "waiting" -> "waiting for approval"
                    "completed" -> "turn complete"
                    "lost" -> "connection lost" + (console.error?.let { " — $it" } ?: "")
                    "failed" -> "failed" + (console.error?.let { " — $it" } ?: "")
                    else -> console.status
                },
                fontSize = 12.sp,
                color = if (console.status == "failed") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (console.live) {
                TextButton(onClick = { viewModel.consoleStop() }) {
                    Icon(Icons.Default.Stop, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { viewModel.consoleReset() }) { Text("Clear", fontSize = 12.sp) }
            }
        }
        if (console.reasoningTail.isNotBlank()) {
            Text(
                console.reasoningTail,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        console.tools.takeLast(3).forEach { line ->
            Text(
                line,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (console.transcript.isNotBlank()) {
            // Bounded, tail-following: the panel sits between transcript and composer, so it
            // must never take the screen — the full exchange lands in the transcript above
            // when the turn commits (the caller re-pulls messages on "completed").
            val scroll = rememberScrollState()
            LaunchedEffect(console.transcript) { scroll.scrollTo(scroll.maxValue) }
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 260.dp)
                    .verticalScroll(scroll),
            ) {
                MessageContent(
                    content = console.transcript,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isStreaming = console.live,
                )
            }
        }
    }
}
