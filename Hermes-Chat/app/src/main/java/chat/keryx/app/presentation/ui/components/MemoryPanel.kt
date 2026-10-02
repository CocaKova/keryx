package chat.keryx.app.presentation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.MemoryDelegate
import chat.keryx.core.model.MemoryEntry
import chat.keryx.core.model.MemorySearch
import chat.keryx.core.model.MemorySource
import kotlinx.coroutines.launch

/**
 * The Memory spoke (2.16): what the agent remembers, as it remembers it.
 *
 * Two shelves — what it knows about you (USER.md) first, because that is the one you are most
 * likely to want to correct, then its own notes (MEMORY.md). Search narrows both. Opening an
 * entry renders it as the markdown it is; Edit and Delete go through the gateway's journey
 * routes, which hold the memory tool's lock — the agent writing a new note while you edit an old
 * one can't lose either.
 */
@Composable
internal fun MemoryTab(viewModel: ChatViewModel) {
    val panel by viewModel.memory.book.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // After an edit the entry's id changes (it is a digest of its text): follow it by body.
    var followBody by remember { mutableStateOf<String?>(null) }
    val book = panel.data
    val entries = book?.entries
    LaunchedEffect(entries, followBody) {
        val want = followBody ?: return@LaunchedEffect
        entries?.firstOrNull { it.body.trim() == want.take(chat.keryx.core.model.MemoryParser.BODY_CAP).trim() }
            ?.let { openId = it.id; followBody = null }
    }
    val open = entries?.firstOrNull { it.id == openId }
    BackHandler(enabled = openId != null) { openId = null }

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        when {
            book == null -> PanelLoading()
            open != null -> MemoryEntryPage(
                viewModel = viewModel,
                entry = open,
                onBack = { openId = null },
                onSaved = { body -> followBody = body },
            )
            else -> MemoryShelves(book, query, onQuery = { query = it }, onOpen = { openId = it.id })
        }
    }
}

@Composable
private fun MemoryShelves(
    book: MemoryDelegate.Book,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (MemoryEntry) -> Unit,
) {
    val entries = book.entries
    val shown = remember(entries, query) { MemorySearch.filter(entries.orEmpty(), query) }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        book.status?.let { st ->
            item(key = "provider") {
                val provider = st.activeProvider
                Text(
                    when {
                        st.active.isBlank() -> "Built-in memory: the two files below are all the agent keeps."
                        provider == null || provider.status == "missing" ->
                            "Configured provider “${st.active}” isn't installed — the agent is on the built-in files below."
                        else -> "Provider: ${st.active} (${provider.status.replace('_', ' ')}). Its own store lives " +
                            "there; these are the built-in files the agent also writes."
                    },
                    fontSize = KeryxType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entries == null) {
            item(key = "no-entries") {
                Text(
                    "This gateway reports its memory files but can't list their entries — a newer Hermes can.",
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            return@LazyColumn
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${entries.size} memories…", fontSize = KeryxType.caption) },
                textStyle = TextStyle(fontSize = KeryxType.body),
                singleLine = true,
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing remembered yet. The agent writes here with its memory tool, and you can ask it to.",
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            return@LazyColumn
        }
        if (shown.isEmpty()) {
            item(key = "no-match") {
                Text(
                    "Nothing matches “${query.trim()}”.",
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        for (source in listOf(MemorySource.PROFILE, MemorySource.NOTES)) {
            val rows = shown.filter { it.source == source }
            if (rows.isEmpty()) continue
            item(key = "shelf-" + source.wire) {
                val bytes = when (source) {
                    MemorySource.PROFILE -> book.status?.profileBytes
                    MemorySource.NOTES -> book.status?.notesBytes
                }
                SectionLabel(
                    (if (source == MemorySource.PROFILE) "About you" else "The agent's notes") +
                        " · " + source.fileName +
                        (bytes?.let { " · " + MemorySearch.humanBytes(it) } ?: ""),
                )
            }
            items(rows, key = { it.id }) { e ->
                KeryxCard(onClick = { onOpen(e) }) {
                    Text(
                        e.title.ifBlank { "(untitled)" },
                        fontSize = KeryxType.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val rest = e.body.lines().drop(1).joinToString(" ").trim()
                    if (rest.isNotEmpty()) {
                        Text(
                            rest,
                            fontSize = KeryxType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** One entry, read as markdown; Edit swaps to a raw editor prefilled with the WHOLE entry. */
@Composable
private fun MemoryEntryPage(
    viewModel: ChatViewModel,
    entry: MemoryEntry,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The card's body is clipped at 1,200 chars: past that, read the entry itself.
    var full by remember(entry.id) { mutableStateOf<String?>(null) }
    var editing by remember(entry.id) { mutableStateOf(false) }
    var draft by remember(entry.id) { mutableStateOf("") }
    var busy by remember(entry.id) { mutableStateOf(false) }
    var statusLine by remember(entry.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(entry.id) {
        if (entry.maybeClipped) viewModel.memory.entryText(entry.id).onSuccess { full = it }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { if (!busy) onBack() }) { Text("← All memories", fontSize = KeryxType.caption) }
            Spacer(Modifier.weight(1f))
            if (!editing) {
                TextButton(enabled = !busy, onClick = {
                    // Never edit the card's copy: a clipped body saved back would cut the entry
                    // short. The prefill is the gateway's own full text, or no editor at all.
                    busy = true
                    statusLine = null
                    scope.launch {
                        viewModel.memory.entryText(entry.id)
                            .onSuccess { draft = it; full = it; editing = true }
                            .onFailure { statusLine = it.message ?: "Couldn't open the entry for editing." }
                        busy = false
                    }
                }) { Text("Edit", fontSize = KeryxType.caption) }
                TextButton(enabled = !busy, onClick = { confirmDelete = true }) {
                    Text("Delete", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(enabled = !busy, onClick = { editing = false; statusLine = null }) {
                    Text("Cancel", fontSize = KeryxType.caption)
                }
                TextButton(enabled = !busy && draft.isNotBlank() && draft.trim() != full?.trim(), onClick = {
                    busy = true
                    statusLine = null
                    val body = draft.trim()
                    viewModel.memory.save(entry.id, body) { ok, message ->
                        busy = false
                        if (ok) { editing = false; full = body; onSaved(body) } else statusLine = message
                    }
                }) { Text(if (busy) "Saving…" else "Save", fontSize = KeryxType.caption) }
            }
        }
        Text(
            (if (entry.source == MemorySource.PROFILE) "About you" else "The agent's notes") + " · " + entry.source.fileName,
            fontSize = KeryxType.micro,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        statusLine?.let {
            Text(
                it,
                fontSize = KeryxType.micro,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
            )
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
                textStyle = TextStyle(fontSize = KeryxType.caption, fontFamily = FontFamily.Monospace),
            )
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                MessageContent(
                    content = (full ?: entry.body).trim(),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isAgent = false,
                )
                if (full == null && entry.maybeClipped) {
                    Text(
                        "…the rest is loading",
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text("Forget this?", fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    "The entry is removed from ${entry.source.fileName} on the gateway. New sessions " +
                        "start without it; there is no undo from the phone.",
                    fontSize = KeryxType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    viewModel.memory.delete(entry.id) { ok, message ->
                        busy = false
                        if (ok) onBack() else statusLine = message
                    }
                }) { Text("Forget it", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

/** The spoke row's second line: what is behind the door before you open it. */
@Composable
internal fun memorySubtitle(viewModel: ChatViewModel): String {
    val panel by viewModel.memory.book.collectAsState()
    val book = panel.data ?: return "What the agent remembers"
    val n = book.entries?.size
    val provider = book.status?.active?.takeIf { it.isNotBlank() }
    return listOfNotNull(
        n?.let { if (it == 1) "1 memory" else "$it memories" },
        provider?.let { "provider $it" },
    ).joinToString(" · ").ifBlank { "What the agent remembers" }
}
