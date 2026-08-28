package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel

/**
 * A folder ON THE GATEWAY, picked rather than typed.
 *
 * A project's folder is a path on someone else's machine, and the phone keyboard is the
 * worst possible instrument for it: `projects.create` stores any string it is handed
 * without ever looking at the disk, so a typo only surfaces later, as a failed move
 * ("working directory does not exist") against a project that already exists. Live-caught
 * 2026-08-17 — one typo, then a second project born from retrying it.
 *
 * So the field browses. Every keystroke asks the gateway what is actually there
 * ([ChatViewModel.browseFolders] → stock `complete.path` in `@folder:` mode), the answer
 * is tappable, and the field says plainly whether what it holds right now is a real
 * directory. [suggestions] seed it with folders the gateway already claims as workspaces,
 * so the common case is one tap and no typing at all.
 */
/** How many browse rows a dialog can spare before the keyboard is the better filter. */
private const val BROWSE_ROWS = 6

@Composable
fun GatewayFolderField(
    viewModel: ChatViewModel,
    value: String,
    onValueChange: (String) -> Unit,
    /** Folders the gateway already knows (project anchors, discovered repo roots). */
    suggestions: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf<chat.keryx.core.model.FolderPage?>(null) }
    // null = not asked yet / unknowable (a gateway too old to answer never blocks the form).
    var exists by remember { mutableStateOf<Boolean?>(null) }

    // One debounce for both questions: "what's under here" and "is THIS a folder". They are
    // separate calls because a listing can't tell an empty directory from an absent one.
    LaunchedEffect(value) {
        val typed = value.trim()
        if (typed.isBlank()) { page = null; exists = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(220)
        viewModel.projects.browseFolders(typed) { page = it }
        viewModel.projects.browseFolders(typed.trimEnd('/')) { p ->
            exists = p?.names?.contains(typed.trimEnd('/').substringAfterLast('/'))
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Folder on the gateway (e.g. ~/workspace/keryx)") },
            singleLine = true,
            shape = RoundedCornerShape(KeryxRadius.field),
            modifier = Modifier.fillMaxWidth(),
        )

        // The verdict, in the same breath as the typing. Absent verdict says nothing at all
        // rather than guessing — silence beats a wrong claim about someone else's disk.
        val verdict = when {
            value.isBlank() -> null
            exists == true -> "✓ folder found" to MaterialTheme.colorScheme.secondary
            exists == false -> "no folder there yet" to MaterialTheme.colorScheme.error
            else -> null
        }
        verdict?.let { (word, tint) ->
            Text(
                word, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = tint,
                modifier = Modifier.padding(start = 4.dp, top = 3.dp),
            )
        }

        // Nothing typed: offer the workspaces the gateway already believes in.
        if (value.isBlank() && suggestions.isNotEmpty()) {
            Text(
                "Known workspaces",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
            )
            suggestions.take(5).forEach { path ->
                // Trailing slash on purpose: land INSIDE the workspace with its children
                // already listed, rather than one row showing the folder you just picked.
                FolderRow(label = path, mono = true) { onValueChange(path.trimEnd('/') + "/") }
            }
            return@Column
        }

        // A plain Column, not a lazy list: this field lives inside dialogs, and a scrollable
        // child of the same orientation as a scrollable parent is a crash, not a layout.
        // Six rows is the browse window; narrowing is what the keyboard is for.
        val names = page?.names.orEmpty()
        names.take(BROWSE_ROWS).forEach { name ->
            FolderRow(label = name) {
                // Replace the last (possibly partial) segment, then open it: the field
                // always reads as a directory you are standing in.
                val parent = value.trim().substringBeforeLast('/', missingDelimiterValue = "")
                onValueChange(if (parent.isEmpty()) "$name/" else "$parent/$name/")
            }
        }
        val hidden = names.size - BROWSE_ROWS
        if (hidden > 0 || page?.truncated == true) {
            Text(
                // No silent caps — ours at six rows, and the gateway's own at thirty.
                if (page?.truncated == true) "more than 30 folders here — keep typing to narrow"
                else "+$hidden more — keep typing to narrow",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun FolderRow(label: String, mono: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.Folder, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = if (mono) 11.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
