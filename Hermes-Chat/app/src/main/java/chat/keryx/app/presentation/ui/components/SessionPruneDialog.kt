package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import chat.keryx.app.data.remote.HermesStreamClient.PrunePreview
import chat.keryx.app.presentation.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AGE_PRESETS = listOf(30, 60, 90, 180)

/**
 * Session pruner (1.8): a guarded front for the gateway's bulk session delete. Three gates before
 * anything dies: (1) filters produce a dry-run PREVIEW with the true match count, (2) a destructive
 * confirm restates that count, (3) the biometric app lock (when enabled) must pass. Any filter
 * change voids the preview so the count on the red button always matches what will actually happen.
 * Only ENDED sessions are ever touched — the gateway guarantees active work survives.
 */
@Composable
fun SessionPruneDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var ageDays by remember { mutableStateOf(90) }
    var maxMessagesText by remember { mutableStateOf("") }
    var includeArchived by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PrunePreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val maxMessages = maxMessagesText.trim().toIntOrNull()
    fun voidPreview() { preview = null; previewError = null }

    fun runPreview() {
        working = true
        scope.launch {
            viewModel.hubSessionsPrunePreview(ageDays, maxMessages, includeArchived)
                .onSuccess { preview = it.preview; previewError = null }
                .onFailure { previewError = it.message?.take(120) ?: "preview failed" }
            working = false
        }
    }

    // The biometric app lock also gates permanent deletion: same authenticators as the
    // MainActivity cold-start lock. Unavailable hardware falls through — the count-restating
    // confirm stays as the gate, matching how the app lock itself degrades.
    fun withLockGate(onPassed: () -> Unit) {
        val activity = context as? androidx.fragment.app.FragmentActivity
        val lockOn = viewModel.biometricLock.value
        val canAuth = activity != null &&
            androidx.biometric.BiometricManager.from(activity).canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        if (!lockOn || !canAuth) { onPassed(); return }
        val prompt = androidx.biometric.BiometricPrompt(
            activity!!,
            androidx.core.content.ContextCompat.getMainExecutor(activity),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                ) { onPassed() }
                // Error/cancel: nothing deleted, dialog stays.
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm session prune")
                .setSubtitle("Permanently deletes ${preview?.matched ?: 0} sessions")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }

    fun executePrune() {
        working = true
        viewModel.hubSessionsPrune(ageDays, maxMessages, includeArchived) { removed ->
            // Success closes the dialog (the toast carries the count); failure re-enables it.
            if (removed != null) onDismiss() else working = false
        }
    }

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Surface(shape = RoundedCornerShape(KeryxRadius.sheet), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                KeryxSectionHeader("Prune sessions")
                Text(
                    "Deletes ended sessions from the gateway's record — permanently, transcripts included. Active sessions are never touched.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(14.dp))

                Text("Older than", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AGE_PRESETS.forEach { days ->
                        val selected = days == ageDays
                        Text(
                            "${days}d",
                            fontSize = 12.sp,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                                .clickable { ageDays = days; voidPreview() }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = maxMessagesText,
                    onValueChange = { maxMessagesText = it.filter(Char::isDigit); voidPreview() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Only sessions with ≤ N messages (optional)", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Include archived sessions",
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includeArchived,
                        onCheckedChange = { includeArchived = it; voidPreview() },
                    )
                }
                Spacer(Modifier.height(10.dp))

                previewError?.let {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                }
                preview?.let { p ->
                    if (p.matched == 0) {
                        Text(
                            "Nothing matches these filters.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${p.matched} session${if (p.matched == 1) "" else "s"} match · " +
                                "oldest ${epochDate(p.oldestStartedAt)} · newest ${epochDate(p.newestStartedAt)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        p.sample.take(5).forEach { s ->
                            Text(
                                "· ${s.title?.takeIf { it.isNotBlank() } ?: s.id.take(12)} (${s.messageCount} msgs)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
                    TextButton(onClick = { runPreview() }, enabled = !working) {
                        Text(if (working) "…" else "Preview")
                    }
                    val p = preview
                    TextButton(
                        // No preview, no delete: the count on the confirm must be current.
                        enabled = !working && p != null && p.matched > 0,
                        onClick = { confirmOpen = true },
                    ) {
                        Text(
                            "Delete${p?.matched?.let { " $it" } ?: ""}…",
                            color = if (p != null && p.matched > 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmOpen) {
        val matched = preview?.matched ?: 0
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmOpen = false },
            title = { Text("Delete $matched sessions permanently?", fontSize = 16.sp) },
            text = {
                Text(
                    "Rows and transcript files are removed from the gateway. There is no undo.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    withLockGate { executePrune() }
                }) { Text("Delete $matched", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

private fun epochDate(epochSeconds: Double?): String =
    epochSeconds?.let {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date((it * 1000).toLong()))
    } ?: "—"
