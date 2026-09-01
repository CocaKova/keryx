package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.data.remote.HermesStreamClient.ConfigKnob
import chat.keryx.app.presentation.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway Controls (1.21): the curated write-side of the Hub. Reasoning dial, operator-defined
 * brain picker, whitelisted config knobs, and a redacted log tail — all against the keryx-stream
 * plugin's /keryx control routes, all hidden or read-only when the gateway doesn't offer them
 * (vanilla installs simply see less here, never errors).
 *
 * 2.6.2: the knobs (69 on a full plugin) stopped being one flat scroll. Each gateway group is a
 * collapsible section with a count and a one-line blurb, a chip rail up top jumps to (and opens)
 * a group, and a search box cuts across all of them — a setting is found by what it does, not
 * by remembering which group the gateway filed it under.
 */
@Composable
fun ControlsTab(viewModel: ChatViewModel) {
    val caps by viewModel.hub.reasoningCaps.collectAsState()
    val brains by viewModel.hub.brains.collectAsState()
    val config by viewModel.hub.config.collectAsState()
    var swapTarget by remember { mutableStateOf<String?>(null) }
    var logsOpen by remember { mutableStateOf(false) }
    var rawOpen by remember { mutableStateOf(false) }
    // Survives rotation and tab hops; a comma list because a Set has no default Saver.
    var query by rememberSaveable { mutableStateOf("") }
    var expandedCsv by rememberSaveable { mutableStateOf("") }
    val expanded = remember(expandedCsv) { expandedCsv.split(',').filter { it.isNotBlank() }.toSet() }
    fun setExpanded(groups: Set<String>) { expandedCsv = groups.joinToString(",") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (logsOpen) {
        GatewayLogViewer(viewModel = viewModel, onDismiss = { logsOpen = false })
    }
    if (rawOpen) {
        RawConfigEditor(viewModel = viewModel, onDismiss = { rawOpen = false })
    }
    swapTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { swapTarget = null },
            title = { Text("Swap to \"$target\"?", fontSize = 16.sp) },
            text = { Text(
                "The gateway runs the operator's swap command — the brain (and possibly the " +
                    "gateway itself) restarts. Chats pause until the new brain is up.",
                fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.hub.brainSelect(target); swapTarget = null }) {
                    Text("Swap")
                }
            },
            dismissButton = { TextButton(onClick = { swapTarget = null }) { Text("Cancel") } },
        )
    }

    // The whole tab as one flat, keyed row plan — so a chip can find its group's index and
    // scroll to it, and so every row type keeps a stable key across filter changes.
    val knobs = config.data.orEmpty()
    val plan = remember(caps, brains.data, knobs, query, expanded, config.error, config.refreshing) {
        buildControlRows(caps, brains.data, knobs, query, expanded,
            offerable = config.error == null && !config.refreshing)
    }

    LazyColumn(
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(plan, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ControlRow.Error -> PanelErrorLine(config.error ?: brains.error)

                // --- Reasoning dial (write side of /keryx/capabilities) ---------------------
                is ControlRow.Reasoning -> {
                    val c = row.caps
                    SectionLabel("Reasoning")
                    Text(
                        if (c.mode == "binary") "This brain's thinking is an on/off switch."
                        else "How hard the brain thinks before answering.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChoiceChips(
                        choices = c.levels,
                        labels = c.labels,
                        selected = c.current,
                        onSelect = { viewModel.hub.reasoningSet(it) },
                    )
                    Text(
                        "applies next session",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // --- Brains (operator-configured picker; hidden when unconfigured) ----------
                is ControlRow.BrainHeader -> {
                    SectionLabel("Brain")
                    if (row.active.isNotBlank()) {
                        Row(modifier = Modifier.padding(bottom = 4.dp)) {
                            Text("Active", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(86.dp))
                            Text(row.active, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                is ControlRow.Brain -> {
                    val b = row.entry
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
                            .clickable { swapTarget = b.name }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(b.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (b.description.isNotBlank()) {
                            Text(b.description, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // --- Config knobs: search + chip rail, then one collapsible section per group --
                is ControlRow.KnobsHeader -> KnobsHeader(
                    total = row.total,
                    groups = row.groups,
                    query = query,
                    onQuery = { query = it },
                    anyExpanded = expanded.isNotEmpty(),
                    onToggleAll = {
                        setExpanded(if (expanded.isEmpty()) row.groups.map { it.first }.toSet() else emptySet())
                    },
                    onJump = { group ->
                        setExpanded(expanded + group)
                        scope.launch {
                            val idx = plan.indexOfFirst { it.key == "knobhdr:$group" }
                            if (idx >= 0) listState.animateScrollToItem(idx)
                        }
                    },
                )
                is ControlRow.Group -> GroupHeader(
                    name = row.name,
                    count = row.count,
                    expanded = row.open,
                    onToggle = { setExpanded(if (row.name in expanded) expanded - row.name else expanded + row.name) },
                )
                is ControlRow.Knob -> KnobRow(
                    knob = row.knob,
                    busy = config.refreshing,
                    onSet = { value -> viewModel.hub.configSet(row.knob.key, value) },
                )
                is ControlRow.NoMatch -> Text(
                    "Nothing matches \"${row.query}\".",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                is ControlRow.NoKnobs -> Text(
                    "This gateway doesn't offer remote controls (keryx-stream plugin 1.21+).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                // --- Logs + the raw editor under them (1.25) ------------------------------------
                is ControlRow.Footer -> {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { logsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Gateway log")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { rawOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit config.yaml")
                    }
                    Text(
                        "Everything above, plus every setting no knob covers. Backed up before each save.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** One row of the Controls tab. [key] is the LazyColumn key — stable across filtering. */
internal sealed class ControlRow(val key: String) {
    object Error : ControlRow("error")
    class Reasoning(val caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps) : ControlRow("reasoning")
    class BrainHeader(val active: String) : ControlRow("brain:hdr")
    class Brain(val entry: chat.keryx.app.data.remote.HermesStreamClient.BrainEntry) : ControlRow("brain:" + entry.name)
    /** [groups] = (name, knob count) in display order, for the chip rail. */
    class KnobsHeader(val total: Int, val groups: List<Pair<String, Int>>) : ControlRow("knobs:hdr")
    class Group(val name: String, val count: Int, val open: Boolean) : ControlRow("knobhdr:$name")
    class Knob(val knob: ConfigKnob) : ControlRow("knob:" + knob.key)
    class NoMatch(val query: String) : ControlRow("knobs:nomatch")
    object NoKnobs : ControlRow("knobs:none")
    object Footer : ControlRow("footer")
}

// Deliberate order: what you touch often first, what you touch rarely last. Groups the gateway
// invents that aren't listed here still render, alphabetically, after these.
private val GROUP_ORDER = listOf(
    "Behavior", "Display", "Missions", "Compression",
    "Agent", "Memory", "Skills", "Tools",
    "Terminal", "Browser", "Delegation", "Voice", "Safety", "Gateway",
)

/** One line under each group name — what lives there, so a section can be skipped unopened.
 *  Only for the groups the plugin ships; an unknown group simply has no blurb. */
internal val GROUP_BLURBS: Map<String, String> = mapOf(
    "Behavior" to "What a message does mid-task, and how long a turn may run.",
    "Display" to "What the chat shows: reasoning, progress, footers, timestamps.",
    "Missions" to "The dispatcher's cadence, limits and default hands.",
    "Compression" to "When context compacts and how much it protects.",
    "Agent" to "Turn timeouts, retries and the guidance the agent is given.",
    "Memory" to "What the agent remembers between turns and sessions.",
    "Skills" to "How skills are found, loaded and kept.",
    "Tools" to "Output caps and loop guards on tool calls.",
    "Terminal" to "The shell the agent runs commands in.",
    "Browser" to "The browser the agent drives, and its patience.",
    "Delegation" to "Subagents: how many, how deep, how long.",
    "Voice" to "Speech in and out.",
    "Safety" to "Approvals, confirmations and hard stops.",
    "Gateway" to "The gateway process itself.",
)

/**
 * The tab's row plan, pure so it can be pinned by tests: grouping, ordering, the search cut
 * (label, description or key — case-insensitive), and the collapse state. A search shows only
 * matching knobs and opens every group that has one; with no search, a group lists its knobs
 * only while [expanded] holds it.
 */
internal fun buildControlRows(
    caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?,
    brains: chat.keryx.app.data.remote.HermesStreamClient.Brains?,
    knobs: List<ConfigKnob>,
    query: String,
    expanded: Set<String>,
    offerable: Boolean,
): List<ControlRow> {
    val rows = mutableListOf<ControlRow>(ControlRow.Error)
    if (caps != null && caps.levels.isNotEmpty()) rows += ControlRow.Reasoning(caps)
    if (brains != null && brains.brains.isNotEmpty()) {
        rows += ControlRow.BrainHeader(brains.active)
        brains.brains.forEach { rows += ControlRow.Brain(it) }
    }
    if (knobs.isNotEmpty()) {
        val groups = knobs.groupBy { it.group }
        val ordered = GROUP_ORDER.filter { it in groups.keys } +
            groups.keys.filterNot { it in GROUP_ORDER }.sorted()
        rows += ControlRow.KnobsHeader(
            total = knobs.size,
            groups = ordered.map { it to groups.getValue(it).size },
        )
        val q = query.trim()
        val searching = q.isNotBlank()
        var shown = 0
        ordered.forEach { group ->
            val all = groups.getValue(group)
            val hits = if (!searching) all else all.filter { k ->
                k.label.contains(q, ignoreCase = true) ||
                    k.description.contains(q, ignoreCase = true) ||
                    k.key.contains(q, ignoreCase = true)
            }
            if (searching && hits.isEmpty()) return@forEach
            val open = searching || group in expanded
            rows += ControlRow.Group(group, hits.size, open)
            if (open) hits.forEach { rows += ControlRow.Knob(it) }
            shown += hits.size
        }
        if (searching && shown == 0) rows += ControlRow.NoMatch(q)
    } else if (offerable) {
        rows += ControlRow.NoKnobs
    }
    rows += ControlRow.Footer
    return rows
}

/** The knobs section's head: title with the count, a search box, and the group chip rail. */
@Composable
private fun KnobsHeader(
    total: Int,
    groups: List<Pair<String, Int>>,
    query: String,
    onQuery: (String) -> Unit,
    anyExpanded: Boolean,
    onToggleAll: () -> Unit,
    onJump: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                KeryxSectionHeader(
                    "Gateway settings",
                    count = total,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            TextButton(onClick = onToggleAll) {
                Text(if (anyExpanded) "Collapse all" else "Expand all", fontSize = 11.sp)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            placeholder = {
                Text("Find a setting…", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = if (query.isBlank()) null else ({
                TextButton(onClick = { onQuery("") }) { Text("Clear", fontSize = 11.sp) }
            }),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        // The rail: every group by name and size. A tap opens the group and scrolls to it.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            groups.forEach { (name, count) ->
                val shape = RoundedCornerShape(9.dp)
                Text(
                    "$name · $count",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), shape)
                        .clickable { onJump(name) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A group's header: name, count, blurb, and the chevron that folds it. */
@Composable
private fun GroupHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = if (expanded) 2.dp else 0.dp)
            .clip(shape)
            .background(
                if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (expanded) MaterialTheme.colorScheme.primary else Color.Unspecified)
                Spacer(Modifier.width(8.dp))
                Text("$count", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GROUP_BLURBS[name]?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            if (expanded) "▾" else "▸",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One whitelisted knob, rendered by kind: bool → switch, enum → chips, int → number + save. */
@Composable
private fun KnobRow(knob: ConfigKnob, busy: Boolean, onSet: (JsonPrimitive) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(knob.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (knob.locked) {
                        Spacer(Modifier.width(6.dp))
                        Text("locked", fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(knob.applies, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.tertiary)
                }
                Text(knob.description, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (knob.kind == "bool") {
                Switch(
                    checked = knob.boolValue,
                    onCheckedChange = { onSet(JsonPrimitive(it)) },
                    enabled = !knob.locked && !busy,
                )
            }
        }
        when (knob.kind) {
            "enum" -> ChoiceChips(
                choices = knob.choices,
                // A blank choice is a real value (profile knobs: "the dispatcher decides") —
                // it needs a visible chip.
                labels = if ("" in knob.choices) mapOf("" to "unset") else emptyMap(),
                selected = knob.value,
                enabled = !knob.locked && !busy,
                onSelect = { onSet(JsonPrimitive(it)) },
            )
            "int" -> {
                var text by remember(knob.key, knob.value) { mutableStateOf(knob.value) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.width(110.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        enabled = !knob.locked,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !knob.locked && !busy &&
                            text.toIntOrNull() != null && text != knob.value,
                        onClick = { text.toIntOrNull()?.let { onSet(JsonPrimitive(it)) } },
                    ) { Text("Save", fontSize = 12.sp) }
                    val bounds = listOfNotNull(knob.min, knob.max)
                    if (bounds.size == 2) {
                        Text("${knob.min}–${knob.max}", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            "float" -> {
                var text by remember(knob.key, knob.value) { mutableStateOf(knob.value) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { raw ->
                            // Digits and at most one dot — a phone keyboard's worth of float.
                            val filtered = raw.filter { ch -> ch.isDigit() || ch == '.' }
                            if (filtered.count { it == '.' } <= 1) text = filtered
                        },
                        modifier = Modifier.width(110.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        enabled = !knob.locked,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !knob.locked && !busy &&
                            text.toDoubleOrNull() != null && text != knob.value,
                        onClick = { text.toDoubleOrNull()?.let { onSet(JsonPrimitive(it)) } },
                    ) { Text("Save", fontSize = 12.sp) }
                    val bounds = listOfNotNull(knob.minF, knob.maxF)
                    if (bounds.size == 2) {
                        Text("${knob.minF}–${knob.maxF}", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A compact single-select chip row (the reasoning dial + enum knobs). */
@Composable
private fun ChoiceChips(
    choices: List<String>,
    labels: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        choices.forEach { choice ->
            val active = choice == selected
            val shape = RoundedCornerShape(9.dp)
            Text(
                labels[choice] ?: choice,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        shape,
                    )
                    .clickable(enabled = enabled && !active) { onSelect(choice) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** Full-screen redacted gateway-log tail: monospace, newest at the bottom, re-fetchable. */
@Composable
private fun GatewayLogViewer(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf(120) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lines) {
        viewModel.hub.logs(lines)
            .onSuccess { text = it.text; error = null }
            .onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GATEWAY LOG", fontSize = 12.sp, letterSpacing = 3.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f))
                    listOf(120, 300, 500).forEach { n ->
                        TextButton(onClick = { text = null; lines = n }) {
                            Text("$n", fontSize = 12.sp,
                                fontWeight = if (lines == n) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
                }
                when {
                    error != null -> Text("⚠ $error", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error)
                    text == null -> Text("Fetching…", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        val scroll = rememberScrollState()
                        LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
                        Text(
                            text.orEmpty(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}
