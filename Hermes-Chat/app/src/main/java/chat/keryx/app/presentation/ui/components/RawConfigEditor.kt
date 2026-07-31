package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel

/**
 * The raw config.yaml editor (1.25) — the escape hatch under the curated knobs, for every setting
 * nobody wrote a knob spec for.
 *
 * The gateway does the real defending: it parses the YAML, insists on a mapping, makes Hermes'
 * own loader accept it, and takes a timestamped backup before writing — rolling back if the
 * loader refuses. This screen's job is to relay all of that verbatim rather than paraphrase it,
 * and to carry the base hash so a config changed elsewhere in the meantime becomes a visible
 * conflict instead of a silent overwrite.
 */
@Composable
fun RawConfigEditor(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf<String?>(null) }
    var baseHash by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf("") }
    var confirmForce by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.configRaw()
            .onSuccess {
                draft = it.content
                original = it.content
                baseHash = it.hash
                path = it.path
                loadError = null
            }
            .onFailure { loadError = it.message }
    }

    val dirty = draft != null && draft != original
    val requestClose = { if (dirty && !saving) confirmDiscard = true else onDismiss() }

    fun save(force: Boolean) {
        val text = draft ?: return
        saving = true
        status = null
        viewModel.configRawSave(text, baseHash, force) { ok, message, needsForce ->
            saving = false
            if (needsForce) {
                confirmForce = message
                return@configRawSave
            }
            statusIsError = !ok
            status = message
            if (ok) {
                original = text
                // The saved file is the new baseline; a second save must not look stale.
                baseHash = null
            }
        }
    }

    KeryxSpace(
        title = "config.yaml",
        onClose = requestClose,
        liveSlot = {
            Text(
                path.substringAfterLast('/').ifBlank { "config.yaml" } +
                    if (dirty) " · unsaved" else "",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            if (draft != null) {
                TextButton(enabled = !saving && dirty, onClick = { save(force = false) }) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            status?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            when {
                loadError != null -> Text(
                    "Couldn't load config.yaml: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                draft == null -> Text(
                    "Loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                else -> {
                    Text(
                        "Saved changes take effect on the next gateway restart for most sections.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    OutlinedTextField(
                        value = draft.orEmpty(),
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    confirmForce?.let { message ->
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmForce = null },
            title = { Text("Save anyway?", fontSize = 16.sp) },
            // The gateway's own words — it counted the sections, so it says which ones go.
            text = { Text(message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForce = null
                    save(force = true)
                }) { Text("Save anyway", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForce = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?", fontSize = 16.sp) },
            text = { Text("config.yaml on the gateway is untouched.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}
