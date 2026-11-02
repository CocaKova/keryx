package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.data.remote.HermesStreamClient.SkillDetail
import chat.keryx.app.presentation.ChatViewModel

/**
 * Opens the Skill Forge from anywhere a skill name surfaces without ViewModel access —
 * the in-chat SkillDistilled pill mainly. Provided at the app shell; default is a no-op so
 * previews and tests compose fine.
 */
val LocalSkillForgeOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The Skill Forge (1.8): view and edit a skill's SKILL.md from the phone. View mode renders the
 * markdown; Edit swaps to a raw monospace editor. Saves go through the gateway's own frontmatter
 * validation + security scan — failures surface the gateway's message verbatim, successes carry
 * its "index refreshes for new sessions" note (running sessions keep their prompt; no restart).
 * Skills in external dirs arrive `readonly` and the pencil stays hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillForgeSheet(
    skillName: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var detail by remember { mutableStateOf<SkillDetail?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(skillName) {
        viewModel.hub.skillDetail(skillName)
            .onSuccess { detail = it; loadError = null }
            .onFailure { loadError = it.message }
    }

    val dirty = editing && draft != detail?.content
    val requestClose = { if (dirty) confirmDiscard = true else onDismiss() }

    // 1.23: the Forge joins the KeryxSpace family — same dusk chrome as the Hub and Missions,
    // its skill path riding the live slot, edit/save as space actions.
    KeryxSpace(
        title = stringResource(R.string.agent_forge_title),
        onClose = requestClose,
        liveSlot = {
            Text(
                detail?.let { d ->
                    (d.category?.let { "$it/" } ?: "") + d.name +
                        if (d.readonly) " · " + stringResource(R.string.agent_forge_read_only) else ""
                } ?: skillName,
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            val d = detail
            if (d != null && !d.readonly && !editing) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.agent_forge_delete_skill),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { draft = d.content; editing = true; statusLine = null }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.agent_edit),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (editing) {
                TextButton(
                    enabled = !saving && draft.isNotBlank(),
                    onClick = {
                        saving = true
                        statusLine = null
                        viewModel.hub.skillSave(d!!.name, draft) { ok, message ->
                            saving = false
                            statusLine = message
                            if (ok) {
                                detail = d.copy(content = draft)
                                editing = false
                            }
                        }
                    },
                ) { Text(if (saving) stringResource(R.string.agent_saving) else stringResource(R.string.agent_save)) }
            }
        },
    ) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    statusLine?.let {
                        Text(
                            it,
                            fontSize = KeryxType.micro,
                            color = if (editing) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    val d = detail
                    when {
                        loadError != null -> Text(
                            stringResource(R.string.agent_forge_load_failed, loadError.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = KeryxType.caption,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                        d == null -> Text(
                            stringResource(R.string.agent_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = KeryxType.caption,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                        editing -> OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = KeryxType.caption,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            // The chat's own renderer: sync parse (the async overload flashed an
                            // empty box first), plus Keryx's tables, code blocks and math.
                            MessageContent(
                                content = d.content,
                                textColor = MaterialTheme.colorScheme.onSurface,
                                isAgent = false,
                            )
                            if (d.files.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.agent_forge_sidecar_files),
                                    fontSize = KeryxType.micro,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                d.files.forEach { f ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("·", fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            f,
                                            fontSize = KeryxType.micro,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
    }

    if (confirmDelete) {
        val d = detail
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agent_forge_delete_title), fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.agent_forge_delete_body, d?.name ?: skillName),
                    fontSize = KeryxType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.hub.skillDelete(d?.name ?: skillName) { ok, message ->
                        if (ok) onDismiss() else statusLine = message
                    }
                }) { Text(stringResource(R.string.agent_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.agent_keep_it)) }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.agent_forge_discard_title), fontSize = KeryxType.titleLarge) },
            text = { Text(stringResource(R.string.agent_forge_discard_body), fontSize = KeryxType.body) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.agent_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.agent_keep_editing)) }

            },
        )
    }
}
