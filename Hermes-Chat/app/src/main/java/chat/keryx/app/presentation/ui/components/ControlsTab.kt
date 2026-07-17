package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.keryx.app.data.remote.HermesStreamClient.ConfigKnob
import chat.keryx.app.presentation.ChatViewModel
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway Controls (1.21): the curated write-side of the Hub. Reasoning dial, operator-defined
 * brain picker, whitelisted config knobs, and a redacted log tail — all against the keryx-stream
 * plugin's /keryx control routes, all hidden or read-only when the gateway doesn't offer them
 * (vanilla installs simply see less here, never errors).
 */
@Composable
fun ControlsTab(viewModel: ChatViewModel) {
    val caps by viewModel.reasoningCaps.collectAsState()
    val brains by viewModel.hubBrains.collectAsState()
    val config by viewModel.hubConfig.collectAsState()
    var swapTarget by remember { mutableStateOf<String?>(null) }
    var logsOpen by remember { mutableStateOf(false) }

    if (logsOpen) {
        GatewayLogViewer(viewModel = viewModel, onDismiss = { logsOpen = false })
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
                TextButton(onClick = { viewModel.hubBrainSelect(target); swapTarget = null }) {
                    Text("Swap")
                }
            },
            dismissButton = { TextButton(onClick = { swapTarget = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, bottom = 20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PanelErrorLine(config.error ?: brains.error) }

        // --- Reasoning dial (write side of /keryx/capabilities) -----------------------------
        caps?.let { c ->
            if (c.levels.isNotEmpty()) {
                item {
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
                        onSelect = { viewModel.hubReasoningSet(it) },
                    )
                    Text(
                        "applies next session",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // --- Brains (operator-configured picker; hidden when unconfigured) ------------------
        val brainData = brains.data
        if (brainData != null && brainData.brains.isNotEmpty()) {
            item {
                SectionLabel("Brain")
                if (brainData.active.isNotBlank()) {
                    Row(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("Active", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(86.dp))
                        Text(brainData.active, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(brainData.brains, key = { "brain:" + it.name }) { b ->
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
        }

        // --- Config knobs (whitelisted, validated server-side) ------------------------------
        val knobs = config.data.orEmpty()
        if (knobs.isNotEmpty()) {
            item { SectionLabel("Gateway") }
            items(knobs, key = { "knob:" + it.key }) { knob ->
                KnobRow(
                    knob = knob,
                    busy = config.refreshing,
                    onSet = { value -> viewModel.hubConfigSet(knob.key, value) },
                )
            }
        } else if (config.error == null && !config.refreshing) {
            item {
                Text(
                    "This gateway doesn't offer remote controls (keryx-stream plugin 1.21+).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }

        // --- Logs ----------------------------------------------------------------------------
        item {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { logsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Gateway log")
            }
        }
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
                labels = emptyMap(),
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
        viewModel.hubLogs(lines)
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
