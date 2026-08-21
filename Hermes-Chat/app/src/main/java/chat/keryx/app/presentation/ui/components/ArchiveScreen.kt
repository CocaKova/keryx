package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.archive.ArchiveStore
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.DaySeparator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Archive (1.26 "Mnemosyne") — the room's whole history as a place: full-text search, saved
 * messages, every photo and file, and a calendar that opens any day. All of it reads from the
 * local [ArchiveStore] index; tapping anything opens a live context window around that moment
 * (see [ArchiveContextViewer]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    viewModel: ChatViewModel,
    onDismissRequest: () -> Unit,
) {
    val progress by viewModel.archiveProgress.collectAsState()
    val roomId = viewModel.currentRoom.collectAsState().value?.id
    var tab by remember { mutableStateOf(ArchiveTab.SEARCH) }
    var contextAnchor by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Every open kicks a sweep: the first ever is the big backfill, later ones just catch up on
    // what's new and stop within a few dozen events.
    LaunchedEffect(roomId) {
        viewModel.startArchiveSweep()
        viewModel.refreshSavedIds()
    }

    KeryxSpace(
        title = "Archive",
        onClose = onDismissRequest,
        standalone = false,
        liveSlot = {
            val p = progress
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    p == null -> {
                        KeryxBreathingDot(KeryxStatus.idle, alive = false)
                        Spacer(Modifier.width(6.dp))
                        StatusLine("waking the index…")
                    }
                    p.running -> {
                        KeryxBreathingDot(KeryxStatus.good, alive = true)
                        Spacer(Modifier.width(6.dp))
                        StatusLine("${"%,d".format(p.indexed)} remembered · reaching back…")
                    }
                    p.error != null -> {
                        KeryxBreathingDot(KeryxStatus.warn, alive = false)
                        Spacer(Modifier.width(6.dp))
                        StatusLine("${"%,d".format(p.indexed)} remembered · ${p.error}")
                    }
                    else -> {
                        KeryxBreathingDot(KeryxStatus.good, alive = false)
                        Spacer(Modifier.width(6.dp))
                        StatusLine("${"%,d".format(p.indexed)} messages remembered")
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = { showDatePicker = true }) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = "Jump to date",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        // Tab chips, in the board's lane-jump voice.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            ArchiveTab.entries.forEach { t ->
                val selected = t == tab
                val accent = MaterialTheme.colorScheme.primary
                Text(
                    t.label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) accent.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                        .clickable { tab = t }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        when (tab) {
            ArchiveTab.SEARCH -> SearchTab(viewModel, progress?.indexed ?: 0) { contextAnchor = it }
            ArchiveTab.SAVED -> SavedTab(viewModel) { contextAnchor = it }
            ArchiveTab.MEDIA -> MediaTab(viewModel, roomId) { contextAnchor = it }
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val utc = dateState.selectedDateMillis
                    showDatePicker = false
                    if (utc != null) scope.launch {
                        viewModel.archiveEventForDate(utcDayToLocalStart(utc))?.let { contextAnchor = it }
                            ?: viewModel.toast("Nothing indexed yet for that day")
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState, showModeToggle = false)
        }
    }

    contextAnchor?.let { anchor ->
        ArchiveContextViewer(viewModel, anchorId = anchor, onDismiss = { contextAnchor = null })
    }
}

private enum class ArchiveTab(val label: String) { SEARCH("Search"), SAVED("Saved"), MEDIA("Media") }

@Composable
private fun StatusLine(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --- Search ------------------------------------------------------------------------------------

@Composable
private fun SearchTab(
    viewModel: ChatViewModel,
    indexedCount: Int,
    onOpen: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ArchiveStore.Hit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val myId by viewModel.currentUserId.collectAsState()

    // Results follow the keystrokes, debounced; the index answers fast enough to feel live.
    LaunchedEffect(query, indexedCount) {
        if (query.isBlank()) {
            hits = emptyList(); searched = false
            return@LaunchedEffect
        }
        delay(250)
        hits = viewModel.archiveSearch(query)
        searched = true
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text("Search everything ever said…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        shape = RoundedCornerShape(KeryxRadius.field),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    )
    when {
        query.isBlank() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Every message the app has ever seen, one search away.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        searched && hits.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nothing yet for \"${query.trim()}\"",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(hits, key = { it.entry.eventId }) { hit ->
                KeryxCard(onClick = { onOpen(hit.entry.eventId) }) {
                    EntryHeader(hit.entry, myId)
                    Spacer(Modifier.height(4.dp))
                    if (hit.entry.mediaKind != null) {
                        MediaLine(hit.entry)
                        if (hit.snippet.isNotBlank()) Spacer(Modifier.height(2.dp))
                    }
                    if (hit.snippet.isNotBlank() || hit.entry.mediaKind == null) {
                        Text(
                            snippetAnnotated(hit.snippet, MaterialTheme.colorScheme.primary),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** ⟪match⟫ markers from the FTS snippet become bold accent spans. */
internal fun snippetRanges(snippet: String): Pair<String, List<IntRange>> {
    val plain = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var i = 0
    var start = -1
    while (i < snippet.length) {
        when (snippet[i]) {
            ArchiveStore.SNIP_START.single() -> start = plain.length
            ArchiveStore.SNIP_END.single() -> {
                if (start >= 0) ranges += start until plain.length
                start = -1
            }
            else -> plain.append(snippet[i])
        }
        i++
    }
    return plain.toString() to ranges
}

private fun snippetAnnotated(snippet: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val (plain, ranges) = snippetRanges(snippet)
    return buildAnnotatedString {
        append(plain)
        ranges.forEach {
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), it.first, it.last + 1)
        }
    }
}

// --- Saved -------------------------------------------------------------------------------------

@Composable
private fun SavedTab(
    viewModel: ChatViewModel,
    onOpen: (String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ArchiveStore.Entry>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val myId by viewModel.currentUserId.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshKey) {
        entries = viewModel.archiveSaved()
        loaded = true
    }
    if (loaded && entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nothing kept yet. Long-press a message and tap the bookmark.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.eventId }) { e ->
            KeryxCard(onClick = { onOpen(e.eventId) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EntryHeader(e, myId)
                        Spacer(Modifier.height(4.dp))
                        if (e.mediaKind != null) MediaLine(e)
                        if (e.body.isNotBlank()) {
                            Text(
                                e.body,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            viewModel.toggleSaved(
                                Message(
                                    id = e.eventId,
                                    roomId = e.roomId,
                                    sender = SenderType.OTHER,
                                    content = e.body,
                                    timestamp = e.timestamp,
                                    senderId = e.sender,
                                )
                            )
                            // toggleSaved is fire-and-forget on IO; give it a beat, then re-pull.
                            delay(150)
                            refreshKey++
                        }
                    }) {
                        Icon(
                            Icons.Default.BookmarkRemove,
                            contentDescription = "Remove from Saved",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// --- Media -------------------------------------------------------------------------------------

@Composable
private fun MediaTab(
    viewModel: ChatViewModel,
    roomId: String?,
    onOpen: (String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<ArchiveStore.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val progress by viewModel.archiveProgress.collectAsState()
    LaunchedEffect(roomId, progress?.indexed) {
        entries = viewModel.archiveMedia()
        loaded = true
    }
    if (loaded && entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No photos or files indexed yet.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.eventId }) { e ->
            MediaCell(e, viewModel, onOpen)
        }
    }
}

@Composable
private fun MediaCell(e: ArchiveStore.Entry, viewModel: ChatViewModel, onOpen: (String) -> Unit) {
    val shape = RoundedCornerShape(KeryxRadius.chip)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable { onOpen(e.eventId) },
    ) {
        if (e.mediaKind == MediaKind.IMAGE.name) {
            // Grid-sized decode under an archive-only cache key: the chat's full bubbles keep
            // their own 1024px entries, the grid keeps small ones — neither evicts the other.
            val cacheKey = "arch|${e.eventId}"
            val cached = remember(cacheKey) { KeryxBitmapCache.get(cacheKey) }
            val bitmap by produceState<ImageBitmap?>(initialValue = cached, cacheKey) {
                if (cached != null) return@produceState
                value = viewModel.loadMessageMedia(e.roomId, e.eventId)?.let { bytes ->
                    decodeSampled(bytes, targetPx = 384)?.also { KeryxBitmapCache.put(cacheKey, it) }
                }
            }
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = e.fileName.ifBlank { "image" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                KeryxBreathingDot(KeryxStatus.idle, alive = true)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(
                    when (e.mediaKind) {
                        MediaKind.VIDEO.name -> Icons.Default.PlayCircle
                        MediaKind.AUDIO.name -> Icons.AutoMirrored.Filled.VolumeUp
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = e.mediaKind,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(28.dp),
                )
                if (e.fileName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        e.fileName,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// --- Shared bits -------------------------------------------------------------------------------

@Composable
private fun EntryHeader(e: ArchiveStore.Entry, myId: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = senderLabel(e.sender, myId),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = archiveDate(e.timestamp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MediaLine(e: ArchiveStore.Entry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (e.mediaKind) {
                MediaKind.IMAGE.name -> Icons.Default.ImageIcon
                MediaKind.VIDEO.name -> Icons.Default.PlayCircle
                MediaKind.AUDIO.name -> Icons.AutoMirrored.Filled.VolumeUp
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            e.fileName.ifBlank { e.mediaKind?.lowercase() ?: "attachment" },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun senderLabel(sender: String, myId: String?): String =
    if (sender == myId) "You" else sender.removePrefix("@").substringBefore(':')

private fun archiveDate(ts: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

/** The M3 date picker hands back UTC midnight; the archive thinks in local days. */
internal fun utcDayToLocalStart(utcMidnight: Long): Long {
    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = utcMidnight }
    return java.util.Calendar.getInstance().apply {
        clear()
        set(
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}

// --- Context viewer ----------------------------------------------------------------------------

/**
 * A live window into the timeline around one moment: the anchor highlighted, ~25 messages each
 * way, "earlier" / "later" reaching further (server-fetching gaps as needed). Read-only on
 * purpose — this is a viewing instrument, the conversation stays in the chat.
 */
@Composable
fun ArchiveContextViewer(
    viewModel: ChatViewModel,
    anchorId: String,
    onDismiss: () -> Unit,
) {
    var items by remember(anchorId) { mutableStateOf<List<Message>>(emptyList()) }
    var loading by remember(anchorId) { mutableStateOf(true) }
    var extending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(anchorId) {
        val loadedItems = viewModel.archiveContext(anchorId).filter { visibleInContext(it) }
        items = loadedItems
        loading = false
        val anchorIndex = loadedItems.indexOfFirst { it.id == anchorId }
        if (anchorIndex >= 0) listState.scrollToItem((anchorIndex + 1).coerceAtLeast(0))
    }

    val title = items.firstOrNull { it.id == anchorId }
        ?.let { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(it.timestamp)) }
        ?: "Context"

    KeryxSpace(title = title, onClose = onDismiss) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KeryxBreathingDot(KeryxStatus.good, alive = true, size = 10.dp)
            }
            return@KeryxSpace
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "That moment couldn't be loaded (it may predate this login's keys).",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            return@KeryxSpace
        }
        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "earlier") {
                ReachButton("Reach earlier", extending) {
                    val first = items.firstOrNull() ?: return@ReachButton
                    scope.launch {
                        extending = true
                        val older = viewModel.archiveContext(first.id, before = 25, after = 0)
                            .filter { visibleInContext(it) }
                        items = (older + items).distinctBy { it.id }.sortedBy { it.timestamp }
                        extending = false
                    }
                }
            }
            itemsIndexed(items, key = { _, m -> m.id }) { index, m ->
                Column {
                    val prev = items.getOrNull(index - 1)
                    if (prev == null || !sameDay(prev.timestamp, m.timestamp)) DaySeparator(m.timestamp)
                    ContextRow(m, anchor = m.id == anchorId, viewModel)
                }
            }
            item(key = "later") {
                ReachButton("Reach later", extending) {
                    val last = items.lastOrNull() ?: return@ReachButton
                    scope.launch {
                        extending = true
                        val newer = viewModel.archiveContext(last.id, before = 0, after = 25)
                            .filter { visibleInContext(it) }
                        items = (items + newer).distinctBy { it.id }.sortedBy { it.timestamp }
                        extending = false
                    }
                }
            }
        }
    }
}

/** Same visibility rule the chat applies: telemetry and empty tool-carrier chunks stay out.
 *  Marker-stripping is agent-only — a human quoting a ⟦keryx⟧ marker is using words (mention,
 *  not use), so their text is judged and rendered as-is. */
private fun visibleInContext(m: Message): Boolean {
    if (m.sender == SenderType.HERMES && isTelemetryMessage(m)) return false
    if (m.mediaKind != null) return true
    return if (m.sender == SenderType.HERMES) {
        MessageParser.extractKeryx(m.content).text.isNotBlank()
    } else {
        m.content.isNotBlank()
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun ReachButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            KeryxBreathingDot(KeryxStatus.good, alive = true)
        } else {
            TextButton(onClick = onClick) {
                Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ContextRow(m: Message, anchor: Boolean, viewModel: ChatViewModel) {
    val isMe = m.sender == SenderType.ME
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(KeryxRadius.card)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(shape)
                .background(
                    if (isMe) accent.copy(alpha = 0.13f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
                .let {
                    if (anchor) it.background(accent.copy(alpha = 0.10f))
                        .padding(1.dp)
                    else it
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isMe) "You" else senderLabel(m.senderName.ifBlank { m.senderId }, null),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (anchor) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(m.timestamp)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(3.dp))
            // Bound to a local: `mediaKind` is a public property of a different module now
            // (:core), and Kotlin will not smart-cast across that boundary.
            val mediaKind = m.mediaKind
            if (mediaKind != null) {
                MessageMedia(
                    loadKey = m.id,
                    kind = mediaKind,
                    fileName = m.fileName,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    loader = { viewModel.loadMessageMedia(m.roomId, m.id) },
                )
                if (MessageParser.extractKeryx(m.content).text.isNotBlank() && m.content != m.fileName) {
                    Spacer(Modifier.height(3.dp))
                }
            }
            val body = if (m.sender == SenderType.HERMES) {
                MessageParser.extractKeryx(m.content).text.trim()
            } else {
                m.content.trim()
            }
            if (body.isNotBlank() && (m.mediaKind == null || body != m.fileName.trim())) {
                MessageContent(
                    content = body,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    isAgent = m.sender == SenderType.HERMES,
                )
            }
        }
    }
}
