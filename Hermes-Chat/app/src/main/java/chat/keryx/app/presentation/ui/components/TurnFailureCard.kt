package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.Message
import chat.keryx.core.model.TurnFailure

/**
 * A failed turn, as itself (2.10). Until now a turn that died came back as a grey system
 * bubble that happened to begin with "Error:", and the one question that matters — *which
 * layer broke* — was yours to guess from the wording. The gateway has said so since 2026-08-21
 * (`error_surface`: provider · endpoint · streaming · auth · billing · gateway · runtime · disk),
 * and this card says it back, with the three things you can do about it:
 *
 *  - **Retry** — the last thing you said, again. Hidden when the gateway says retrying cannot
 *    change the answer (a content refusal, a bad key).
 *  - **Gateway log** — the tail of the gateway's own log, in the viewer the Controls already had.
 *  - **Copy details** — layer, code, the words, the versions: a bug report's first paragraph.
 *
 * A gateway that predates the descriptor sends none; the card then wears the generic title and
 * keeps every action. The Hermes Desktop's failed-turn card, in the herald's voice.
 */
@Composable
internal fun TurnFailureCard(
    message: Message,
    failure: TurnFailure,
    viewModel: ChatViewModel,
    textScale: Float,
) {
    val context = LocalContext.current
    val bad = KeryxStatus.bad
    var logOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(bad.copy(alpha = 0.08f))
                .border(1.dp, bad.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KeryxGlyphs.Warning, contentDescription = null, tint = bad, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    failure.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = (14 * textScale).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (failure.layer.isNotBlank() || failure.code.isNotBlank()) {
                Text(
                    listOf(failure.layer, failure.code).filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val words = failure.message.ifBlank { message.content }
            if (words.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    words,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    fontSize = (13 * textScale).sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (failure.retryable) TextButton(onClick = { viewModel.retryLastTurn() }) {
                    Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                if (viewModel.transportIsDirect) TextButton(onClick = { logOpen = true }) {
                    Text("Gateway log", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    val details = buildString {
                        appendLine("Keryx ${chat.keryx.app.BuildConfig.VERSION_NAME}")
                        appendLine("Layer: ${failure.layer.ifBlank { "unknown" }}")
                        if (failure.code.isNotBlank()) appendLine("Code: ${failure.code}")
                        appendLine("Retryable: ${failure.retryable}")
                        appendLine(words)
                    }.trim()
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Keryx failure", details))
                    android.widget.Toast.makeText(context, "Details copied", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy details", fontSize = 12.sp)
                }
            }
        }
    }
    if (logOpen) GatewayLogViewer(viewModel = viewModel, onDismiss = { logOpen = false })
}
