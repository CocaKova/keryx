package chat.keryx.app.presentation.ui.components

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
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

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

    LaunchedEffect(skillName) {
        viewModel.skillDetail(skillName)
            .onSuccess { detail = it; loadError = null }
            .onFailure { loadError = it.message }
    }

    val dirty = editing && draft != detail?.content
    val requestClose = { if (dirty) confirmDiscard = true else onDismiss() }

    // 1.23: the Forge joins the KeryxSpace family — same dusk chrome as the Hub and Missions,
    // its skill path riding the live slot, edit/save as space actions.
    KeryxSpace(
        title = "Skill Forge",
        onClose = requestClose,
        liveSlot = {
            Text(
                detail?.let { d ->
                    (d.category?.let { "$it/" } ?: "") + d.name +
                        if (d.readonly) " · read-only" else ""
                } ?: skillName,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            val d = detail
            if (d != null && !d.readonly && !editing) {
                IconButton(onClick = { draft = d.content; editing = true; statusLine = null }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
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
                        viewModel.skillSave(d!!.name, draft) { ok, message ->
                            saving = false
                            statusLine = message
                            if (ok) {
                                detail = d.copy(content = draft)
                                editing = false
                            }
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save") }
            }
        },
    ) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    statusLine?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = if (editing) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    val d = detail
                    when {
                        loadError != null -> Text(
                            "Couldn't load skill: $loadError",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                        d == null -> Text(
                            "Loading…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                        editing -> OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Markdown(
                                content = d.content,
                                colors = markdownColor(text = MaterialTheme.colorScheme.onSurface),
                                flavour = GFMFlavourDescriptor(),
                            )
                            if (d.files.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "Sidecar files",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                d.files.forEach { f ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            f,
                                            fontSize = 11.sp,
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

    if (confirmDiscard) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?", fontSize = 16.sp) },
            text = { Text("The edit hasn't been saved to the gateway.", fontSize = 13.sp) },
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
