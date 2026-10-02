package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.util.shareExport
import chat.keryx.core.model.ExportFormat
import kotlinx.coroutines.launch

/**
 * "Export…" from a session's long-press menu (2.16): pick the file, then the share sheet.
 *
 * The failure stays in the dialog, in the gateway's words, until you dismiss it — never a toast
 * that repeats — and the dialog stays open while the export is being made so a slow gateway
 * looks slow rather than ignored.
 */
@Composable
fun SessionExportDialog(
    viewModel: ChatViewModel,
    sessionId: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.MARKDOWN) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        shape = RoundedCornerShape(KeryxRadius.sheet),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export “${title.ifBlank { "this session" }}”", fontSize = KeryxType.titleLarge) },
        text = {
            Column(Modifier.selectableGroup()) {
                FormatRow(
                    selected = format == ExportFormat.MARKDOWN,
                    title = "Markdown",
                    detail = "The conversation as you read it: who said what, tools on one line, " +
                        "long output trimmed.",
                    onSelect = { format = ExportFormat.MARKDOWN },
                )
                FormatRow(
                    selected = format == ExportFormat.JSON,
                    title = "JSON",
                    detail = "The gateway's complete export: every row, undone turns and the " +
                        "system prompt included. Re-importable.",
                    onSelect = { format = ExportFormat.JSON },
                )
                error?.let {
                    Text(
                        it,
                        fontSize = KeryxType.caption,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                val agent = viewModel.bots.botForSession(sessionId)?.label?.takeIf { it.isNotBlank() } ?: "Hermes"
                scope.launch {
                    viewModel.exports.export(sessionId, title, format, agent).fold(
                        onSuccess = { file ->
                            if (shareExport(context, file)) onDismiss()
                            else error = "Couldn't open the share sheet."
                        },
                        onFailure = { error = it.message ?: "The export failed." },
                    )
                    busy = false
                }
            }) { Text(if (busy) "Exporting…" else "Share") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FormatRow(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(title, fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
