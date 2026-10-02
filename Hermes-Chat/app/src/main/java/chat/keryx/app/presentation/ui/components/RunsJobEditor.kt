package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.RunsEditorDelegate
import chat.keryx.core.model.CronDelivery
import chat.keryx.core.model.CronHumanize
import chat.keryx.core.model.CronJobDraft
import chat.keryx.core.model.CronJobForm
import chat.keryx.core.model.CronScheduleForm
import chat.keryx.core.model.DeliveryOption
import chat.keryx.core.model.ScheduleDraft
import chat.keryx.core.model.ScheduleKind
import chat.keryx.core.protocol.CronBlueprint
import chat.keryx.core.protocol.CronBlueprints
import chat.keryx.core.protocol.CronHistoryRun

/**
 * The job editor (2.16) — the one form that creates and edits scheduled work, opened from the
 * Runs page (its + and a job's sheet) and from the Hub's Jobs spoke. It replaced two Hub
 * dialogs that asked for a raw cron line and a raw delivery string: here the schedule is a
 * picker that writes the cron line (and reads one back — anything it cannot draw stays as
 * typed, under Custom), the delivery is chosen from the places the gateway says a report can
 * go, and a model can be pinned where the gateway takes one. A new job can start from one of
 * the gateway's blueprints instead of a blank form.
 *
 * Every offer degrades with the gateway: no delivery list → the picker keeps save-only, this
 * room and the job's own value; no dashboard cron routes → no model row, no blueprints. Nothing
 * here polls; the dashboard is asked once per visit (see [RunsEditorDelegate.probe]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobEditorSheet(viewModel: ChatViewModel, job: HubJob?, onDismiss: () -> Unit) {
    val editor = viewModel.runsEditor
    LaunchedEffect(Unit) { editor.probe() }
    val extras by editor.extras.collectAsState()
    val rooms by viewModel.rooms.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    val roomName: (String) -> String? = remember(rooms) { { id -> rooms.firstOrNull { it.id == id }?.name } }

    val original = remember(job) { job?.let { RunsEditorDelegate.draftOf(it) } }
    // "This room" is a Matrix delivery (`matrix:<room>`); a gateway session id in that slot is a
    // job whose output goes nowhere, so the direct door offers the gateway's own list instead.
    val roomOption = currentRoom?.takeIf { !viewModel.transportIsDirect }?.let { r ->
        DeliveryOption("matrix:${r.id}", "This room · ${r.name}", "Each run's report posts into the open chat")
    }

    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var schedule by remember { mutableStateOf(CronScheduleForm.parse(original?.schedule ?: NEW_JOB_SCHEDULE)) }
    var deliver by remember { mutableStateOf(original?.deliver ?: roomOption?.value ?: CronDelivery.LOCAL) }
    var model by remember { mutableStateOf(original?.model.orEmpty()) }
    var provider by remember { mutableStateOf(original?.provider.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var blueprint by remember { mutableStateOf<CronBlueprint?>(null) }

    val draft = CronJobDraft(
        name = name, prompt = prompt, schedule = CronScheduleForm.build(schedule), deliver = deliver,
        model = model, provider = provider, scriptOnly = job?.scriptOnly == true,
    )
    val problem = CronJobForm.problem(draft) ?: CronScheduleForm.problem(schedule)
    val changed = original == null || CronJobForm.changes(original, draft).isNotEmpty()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    KeryxSheet(
        onDismiss = onDismiss,
        title = blueprint?.title ?: if (job == null) "New job" else "Edit job",
        sheetState = sheetState,
    ) {
        val bp = blueprint
        if (bp != null) {
            BlueprintForm(
                bp = bp,
                deliverLabel = { CronDelivery.label(it, extras.targets.orEmpty(), roomName) },
                saving = saving,
                onBack = { blueprint = null },
                onCreate = { values ->
                    saving = true
                    editor.createFromBlueprint(bp, values) { ok -> saving = false; if (ok) onDismiss() }
                },
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                // Starting points: only for a new job, only where the gateway serves a catalog.
                val catalog = extras.blueprints.orEmpty()
                if (job == null && catalog.isNotEmpty()) {
                    EditorLabel("Start from a blueprint")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    ) {
                        catalog.forEach { b -> BlueprintTile(b) { blueprint = b } }
                    }
                    EditorLabel("Or write your own")
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    shape = RoundedCornerShape(KeryxRadius.field),
                    supportingText = if (original != null && name.trim() != original.name) ({
                        // Runs are grouped by the name they were written under; say so before it bites.
                        Text("Past runs keep the old name", fontSize = KeryxType.micro)
                    }) else null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(if (job?.scriptOnly == true) "Prompt (optional — this job runs a script)" else "What the agent does each run")
                    },
                    minLines = 4, maxLines = 10,
                    shape = RoundedCornerShape(KeryxRadius.field),
                )
                Spacer(Modifier.height(16.dp))
                EditorLabel("When")
                SchedulePicker(schedule) { schedule = it }
                Spacer(Modifier.height(16.dp))
                EditorLabel("Where the report goes")
                DeliveryPicker(
                    options = CronDelivery.options(extras.targets, deliver, roomOption, roomName),
                    value = deliver,
                    label = CronDelivery.label(deliver, extras.targets.orEmpty(), roomName),
                    onPick = { deliver = it },
                )
                // A model pin only where the gateway takes one (the dashboard's cron routes);
                // Hermes Link's job PATCH has no model field.
                if (extras.dashboard) {
                    Spacer(Modifier.height(16.dp))
                    EditorLabel("Model")
                    ModelPicker(viewModel, model, provider) { m, p -> model = m; provider = p }
                }
                Spacer(Modifier.height(18.dp))
                problem?.let {
                    Text(it, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = problem == null && changed && !saving,
                        onClick = {
                            saving = true
                            editor.save(job, draft) { ok -> saving = false; if (ok) onDismiss() }
                        },
                    ) { Text(if (job == null) "Schedule" else "Save") }
                }
            }
        }
    }
}

/** A new job opens on "daily at 08:00" — a shape, so the picker has something to show. */
private const val NEW_JOB_SCHEDULE = "0 8 * * *"

/** A field group's caption, in the section voice. */
@Composable
private fun EditorLabel(text: String) {
    KeryxSectionHeader(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

/** A pickable chip — accent-filled when chosen. Shared by the job and mission editors. */
@Composable
internal fun EditorChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        label,
        fontSize = KeryxType.caption,
        maxLines = 1,
        softWrap = false,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) contrastColorFor(accent) else keryxAccentInk(accent),
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(if (selected) accent else accent.copy(alpha = 0.12f))
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private val KIND_LABELS = listOf(
    ScheduleKind.INTERVAL to "Every…",
    ScheduleKind.DAILY to "Daily",
    ScheduleKind.WEEKDAYS to "Weekdays",
    ScheduleKind.WEEKLY to "Weekly",
    ScheduleKind.MONTHLY to "Monthly",
    ScheduleKind.CUSTOM to "Custom",
)

/** Interval presets, in minutes, with the words a person uses for them. */
private val INTERVALS = listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", 360 to "6 h", 720 to "12 h")

/** Cron day numbers in week order, Sunday first, as the gateway counts them. */
private val DAYS = listOf(0 to "S", 1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S")
private val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/**
 * The schedule picker: a kind, then only that kind's controls, then the result in words with
 * the line the gateway will store. Custom takes anything the gateway's parser reads.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedulePicker(draft: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    var clockOpen by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KIND_LABELS.forEach { (kind, label) ->
            EditorChip(label, selected = draft.kind == kind) {
                // Into Custom with the current line already typed, so it is an edit, not a blank.
                onChange(
                    if (kind == ScheduleKind.CUSTOM && draft.raw.isBlank()) draft.copy(kind = kind, raw = CronScheduleForm.build(draft))
                    else draft.copy(kind = kind),
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    when (draft.kind) {
        ScheduleKind.INTERVAL -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                INTERVALS.forEach { (mins, label) ->
                    EditorChip(label, selected = draft.everyMinutes == mins) { onChange(draft.copy(everyMinutes = mins)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            // The field keeps what is typed (an empty box mid-edit stays empty); a preset tap
            // rewrites it.
            var text by remember { mutableStateOf(draft.everyMinutes.toString()) }
            LaunchedEffect(draft.everyMinutes) {
                if ((text.toIntOrNull() ?: 0) != draft.everyMinutes) text = draft.everyMinutes.toString()
            }
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() }.take(6)
                    onChange(draft.copy(everyMinutes = text.toIntOrNull() ?: 0))
                },
                label = { Text("Minutes between runs") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(KeryxRadius.field),
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
        ScheduleKind.CUSTOM -> OutlinedTextField(
            value = draft.raw,
            onValueChange = { onChange(draft.copy(raw = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Schedule") },
            supportingText = { Text("cron (0 9 * * 1-5), every 2h, in 30m, or 2026-10-03T09:00", fontSize = KeryxType.micro) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = KeryxType.body),
            shape = RoundedCornerShape(KeryxRadius.field),
        )
        else -> {
            if (draft.kind == ScheduleKind.WEEKLY) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEach { (n, letter) ->
                        val on = n in draft.days
                        val accent = MaterialTheme.colorScheme.primary
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (on) accent else accent.copy(alpha = 0.10f))
                                .clickable { onChange(draft.copy(days = if (on) draft.days - n else draft.days + n)) }
                                .semanticsLabel(DAY_NAMES[n] + if (on) ", on" else ", off"),
                        ) {
                            Text(
                                letter, fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold,
                                color = if (on) contrastColorFor(accent) else keryxAccentInk(accent),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (draft.kind == ScheduleKind.MONTHLY) {
                    var dayText by remember { mutableStateOf(draft.dayOfMonth.toString()) }
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { v ->
                            dayText = v.filter { it.isDigit() }.take(2)
                            onChange(draft.copy(dayOfMonth = dayText.toIntOrNull() ?: 0))
                        },
                        label = { Text("Day of month") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(KeryxRadius.field),
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text("at", fontSize = KeryxType.body, color = quiet)
                Spacer(Modifier.width(8.dp))
                EditorChip(CronScheduleForm.clockText(draft.hour, draft.minute), selected = false) { clockOpen = true }
            }
        }
    }
    // The result, in words — then the line itself, for whoever reads cron.
    val line = CronScheduleForm.build(draft)
    if (line.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        val words = CronHumanize.schedule(line)
        Text(
            if (words == line) line else "$words  ·  $line",
            fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, color = quiet,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    if (clockOpen) {
        ClockDialog(draft.hour, draft.minute, onPick = { h, m -> clockOpen = false; onChange(draft.copy(hour = h, minute = m)) }) {
            clockOpen = false
        }
    }
}

/** A spoken name for a glyph-sized control whose visible text is one letter ("T" is two days). */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.then(Modifier.clearAndSetSemantics { contentDescription = label })

/** The Material clock, 24-hour, in a plain dialog — schedules are written in 24-hour time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(hour: Int, minute: Int, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = hour.coerceIn(0, 23), initialMinute = minute.coerceIn(0, 59), is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(KeryxRadius.sheet), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(state.hour, state.minute) }) { Text("Set") }
                }
            }
        }
    }
}

/** A field-shaped row that opens a menu: what is chosen, and why it matters. */
@Composable
private fun PickerRow(label: String, detail: String?, warning: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.field))
            .border(1.dp, outline.copy(alpha = 0.5f), RoundedCornerShape(KeryxRadius.field))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = KeryxType.body, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it, fontSize = KeryxType.micro,
                    color = if (warning) KeryxStatus.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(KeryxGlyphs.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/**
 * Where the report goes: the gateway's own targets as words, never a typed `matrix:<room>`.
 * "Other target…" still takes a raw value — an operator's exact room is a real choice.
 */
@Composable
private fun DeliveryPicker(options: List<DeliveryOption>, value: String, label: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.value == value }
    Box {
        PickerRow(label, chosen?.detail, chosen?.warning == true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(o.label, fontSize = KeryxType.body, fontWeight = if (o.value == value) FontWeight.SemiBold else FontWeight.Normal)
                            o.detail?.let {
                                Text(it, fontSize = KeryxType.micro, color = if (o.warning) KeryxStatus.bad else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = { open = false; typing = false; onPick(o.value) },
                )
            }
            DropdownMenuItem(
                text = { Text("Other target…", fontSize = KeryxType.body) },
                onClick = { open = false; typing = true },
            )
        }
    }
    if (typing) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onPick,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target") },
            supportingText = { Text("local, a platform (matrix), platform:chat_id, or a comma list", fontSize = KeryxType.micro) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = KeryxType.caption),
            shape = RoundedCornerShape(KeryxRadius.field),
        )
    }
}

/** How many models the menu lists before it stops — a menu is not the model picker. */
private const val MODEL_MENU_MAX = 40

/**
 * The job's model pin: the gateway's default, or one of the models the gateway can route to
 * (the same catalog the chat's model picker reads). A model the job already has that the catalog
 * does not list stays on the menu as it is.
 */
@Composable
private fun ModelPicker(viewModel: ChatViewModel, model: String, provider: String, onPick: (String, String) -> Unit) {
    val catalog by viewModel.models.catalog.collectAsState()
    var open by remember { mutableStateOf(false) }
    val choices = remember(catalog) {
        catalog?.usable.orEmpty()
            .flatMap { p -> p.models.filter { !it.unavailable }.map { it to p.name } }
            .take(MODEL_MENU_MAX)
    }
    Box {
        PickerRow(
            label = model.ifBlank { "Gateway default" },
            detail = if (model.isBlank()) "Whatever the gateway's agent runs" else provider.ifBlank { null },
            warning = false,
        ) {
            open = true
            viewModel.models.refresh()
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Gateway default", fontSize = KeryxType.body) }, onClick = { open = false; onPick("", "") })
            if (model.isNotBlank() && choices.none { it.first.name == model }) {
                DropdownMenuItem(
                    text = { Text(model, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold) },
                    onClick = { open = false },
                )
            }
            if (choices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Reading the gateway's models…", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
            }
            choices.forEach { (choice, providerName) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                choice.shortName, fontSize = KeryxType.body,
                                fontWeight = if (choice.name == model) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(providerName, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onPick(choice.name, choice.provider) },
                )
            }
        }
    }
}

/** One blueprint as a tile on the "start from" shelf: its name and when it would run. */
@Composable
private fun BlueprintTile(bp: CronBlueprint, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .width(168.dp)
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(KeryxRadius.card))
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(KeryxRadius.card))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(bp.title, fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (bp.scheduleHuman.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(bp.scheduleHuman, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A blueprint's own form: its slots, filled, and "Schedule it" — the gateway builds the job
 * (its prompt, schedule and skills) from them. Edit it afterwards like any other job.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlueprintForm(
    bp: CronBlueprint,
    deliverLabel: (String) -> String,
    saving: Boolean,
    onBack: () -> Unit,
    onCreate: (Map<String, String>) -> Unit,
) {
    var values by remember(bp.key) { mutableStateOf(CronBlueprints.initialValues(bp)) }
    var clockFor by remember { mutableStateOf<String?>(null) }
    val problem = CronBlueprints.problem(bp, values)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        if (bp.description.isNotBlank()) {
            Text(bp.description, fontSize = KeryxType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
        }
        bp.fields.forEach { f ->
            EditorLabel(f.label.ifBlank { f.name })
            val v = values[f.name].orEmpty()
            when {
                f.type == "time" -> EditorChip(v.ifBlank { "Pick a time" }, selected = false) { clockFor = f.name }
                f.options.isNotEmpty() -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    f.options.forEach { o ->
                        // From the dashboard, "origin" has no chat behind it: it lands in the
                        // configured home channel, and the label says that instead.
                        val text = if (f.name == "deliver") (if (o == CronDelivery.ORIGIN) "Home channel" else deliverLabel(o)) else o
                        EditorChip(text, selected = v == o) { values = values + (f.name to o) }
                    }
                }
                else -> OutlinedTextField(
                    value = v,
                    onValueChange = { values = values + (f.name to it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = f.type != "text",
                    shape = RoundedCornerShape(KeryxRadius.field),
                )
            }
            if (f.help.isNotBlank()) {
                Text(f.help, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        problem?.let {
            Text(it, fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = problem == null && !saving, onClick = { onCreate(values) }) { Text("Schedule it") }
        }
    }
    clockFor?.let { field ->
        val (h, m) = CronScheduleForm.clock(values[field].orEmpty()) ?: (8 to 0)
        ClockDialog(h, m, onPick = { hh, mm ->
            values = values + (field to CronScheduleForm.clockText(hh, mm))
            clockFor = null
        }) { clockFor = null }
    }
}

/** How many runs the job sheet's history reads. */
private const val HISTORY_SHOWN = 20

/**
 * A job's history, from the dashboard's per-job run list — every run, script fires included
 * (a `no_agent` job's fires never reached the session list, so the page showed "no runs yet"
 * for a job that runs every half hour). A session run opens like any run; a script fire has no
 * transcript, so tapping it unfolds its output instead. Hidden where the gateway has no such
 * route: the card's own run list still stands there.
 */
@Composable
internal fun JobHistorySection(viewModel: ChatViewModel, jobId: String, onOpen: (CronHistoryRun) -> Unit) {
    // (loaded, runs): runs null after loading = the route is not here.
    val state by produceState<Pair<Boolean, List<CronHistoryRun>?>>(false to null, jobId) {
        value = true to viewModel.runsEditor.history(jobId, HISTORY_SHOWN)
    }
    val (loaded, runs) = state
    if (loaded && runs == null) return
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer(Modifier.height(12.dp))
    KeryxSectionHeader("History", count = runs?.size, color = quiet)
    Spacer(Modifier.height(4.dp))
    when {
        !loaded -> Text("Reading…", fontSize = KeryxType.caption, color = quiet.copy(alpha = 0.7f))
        runs.isNullOrEmpty() -> Text("No runs yet.", fontSize = KeryxType.caption, color = quiet.copy(alpha = 0.7f))
        else -> runs.forEach { run -> HistoryRow(run, onOpen) }
    }
}

@Composable
private fun HistoryRow(run: CronHistoryRun, onOpen: (CronHistoryRun) -> Unit) {
    var unfolded by remember(run.id) { mutableStateOf(false) }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val (glyph, tint) = when {
        run.live -> "●" to MaterialTheme.colorScheme.primary
        run.failed -> "✕" to KeryxStatus.bad
        else -> "✓" to KeryxStatus.good.copy(alpha = 0.8f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.field))
            .clickable { if (run.scriptOutput) unfolded = !unfolded else onOpen(run) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, fontSize = KeryxType.micro, color = tint, modifier = Modifier.width(16.dp))
            Text(
                // Session runs are titled "<job> · <when>": the job is the sheet's own title.
                if (run.scriptOutput) run.title.ifBlank { "Script run" } else run.title.substringAfter(" · ", run.title),
                fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(historyWhen(run.startedAt), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, color = quiet.copy(alpha = 0.7f))
        }
        if (run.scriptOutput && run.preview.isNotBlank()) {
            Text(
                run.preview,
                fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace, color = quiet,
                maxLines = if (unfolded) 12 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 1.dp),
            )
        }
    }
}

/** "4h ago" for a history row — the same clock the run rows use. */
private fun historyWhen(ms: Long): String {
    if (ms <= 0L) return ""
    val mins = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}
