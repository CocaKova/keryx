package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.core.model.ModelCatalog
import chat.keryx.core.model.ModelChoice
import chat.keryx.core.model.ModelPicker
import chat.keryx.core.model.ModelPricing
import kotlinx.coroutines.launch

/**
 * The model picker (2.8.1): a sheet, not a dropdown. The plan is [ModelPicker.plan] — pure and
 * tested — so this file only draws it: the live brain as a card up top, a search field, a rail
 * of section chips that jump the list, then the sections: Recent, then where-it-runs (this
 * machine → cloud logins → aggregators split by lab → virtual routes), each lab's featured few
 * shown and its tail folded behind "N more". The Spire machine roster keeps its own section at
 * the foot ("Machines"): swapping the brain under the gateway is a different verb from routing
 * a session, and the two must never look like one list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    catalog: ModelCatalog?,
    loading: Boolean,
    recents: List<String>,
    brains: HermesStreamClient.Brains?,
    onDismiss: () -> Unit,
    onPick: (ModelChoice) -> Unit,
    onBrainPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalKeryxHaptics.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // Which folds are open, as one saveable string (a Set has no default saver).
    var expandedKeys by rememberSaveable { mutableStateOf("") }
    val expanded = remember(expandedKeys) { expandedKeys.split(KEY_SEP).filter { it.isNotEmpty() }.toSet() }
    fun toggle(key: String) {
        expandedKeys = (if (key in expanded) expanded - key else expanded + key).joinToString(KEY_SEP.toString())
    }

    val plan = remember(catalog, recents, query, expanded) { ModelPicker.plan(catalog, recents, query, expanded) }
    val roster = brains?.brains.orEmpty()

    // The list is flat rows with stable keys so folds animate open (animateItem) rather than pop.
    val rows = remember(plan, roster, query) { buildRows(plan, roster, query.isNotBlank()) }
    val listState = rememberLazyListState()
    // The rail follows the list: the chip lit is the section the top row belongs to.
    val sectionAtTop by remember(rows) {
        derivedStateOf { rows.getOrNull(listState.firstVisibleItemIndex)?.section }
    }

    fun pick(m: ModelChoice) {
        haptics.commit()
        onPick(m)
        onDismiss()
    }

    KeryxSheet(onDismiss = onDismiss, title = "Model", sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(6.dp))
            CurrentBrainCard(plan.current, catalog, loading, onRefresh)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Find a model, a lab, a provider…", fontSize = 13.sp) },
                leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    AnimatedVisibility(query.isNotEmpty(), enter = keryxPop(), exit = keryxVanish()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(KeryxGlyphs.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(KeryxRadius.field),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                ),
                textStyle = TextStyle(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // Section rail: one chip per section, in list order; tap = jump.
            val sections = remember(rows) { rows.mapNotNull { it.section }.distinct() }
            AnimatedVisibility(sections.size > 1, enter = keryxReveal(), exit = keryxConceal()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                    items(sections, key = { it.key }) { sec ->
                        SectionChip(
                            title = sec.title,
                            kind = sec.kind,
                            lit = sec.key == sectionAtTop?.key,
                            onClick = {
                                val at = rows.indexOfFirst { it is PickerRow.Head && it.section.key == sec.key }
                                if (at >= 0) scope.launch { listState.animateScrollToItem(at) }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        ) {
            if (rows.isEmpty()) item(key = "empty") {
                Text(
                    when {
                        loading && catalog == null -> "Reading the catalog…"
                        query.isNotBlank() -> "Nothing answers to “${query.trim()}”"
                        else -> "No routes from the gateway"
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            items(rows, key = { it.key }, contentType = { it::class }) { row ->
                Box(Modifier.animateItem()) {
                    when (row) {
                        is PickerRow.Head -> SectionHead(row.section)
                        is PickerRow.Lab -> LabHead(row.title)
                        is PickerRow.Model -> ModelRow(
                            m = row.choice, current = plan.current == row.choice,
                            kind = row.section.kind, onClick = { pick(row.choice) },
                        )
                        is PickerRow.Fold -> FoldRow(row.count) { toggle(row.groupKey) }
                        is PickerRow.MachinesHead -> SectionHead(
                            ModelPicker.Section("machines", "Machines", "the brains the Spire can swap in", null, null, emptyList()),
                        )
                        is PickerRow.Machine -> MachineRow(row.entry, active = row.entry.name == brains?.active) {
                            haptics.commit(); onBrainPick(row.entry.name); onDismiss()
                        }
                    }
                }
            }
        }
    }
}

private const val KEY_SEP = '\u001F'

// ---- rows ---------------------------------------------------------------------------------------

private sealed interface PickerRow {
    val key: String
    val section: ModelPicker.Section?

    data class Head(override val section: ModelPicker.Section) : PickerRow { override val key = "head/${section.key}" }
    data class Lab(override val section: ModelPicker.Section, val groupKey: String, val title: String) : PickerRow { override val key = "lab/$groupKey" }
    data class Model(override val section: ModelPicker.Section, val choice: ModelChoice) : PickerRow {
        override val key = "m/${section.key}/${choice.provider}|${choice.name}"
    }
    data class Fold(override val section: ModelPicker.Section, val groupKey: String, val count: Int) : PickerRow { override val key = "fold/$groupKey" }
    data object MachinesHead : PickerRow { override val key = "head/machines"; override val section: ModelPicker.Section? = null }
    data class Machine(val entry: HermesStreamClient.BrainEntry) : PickerRow {
        override val key = "machine/${entry.name}"; override val section: ModelPicker.Section? = null
    }
}

private fun buildRows(plan: ModelPicker.Plan, roster: List<HermesStreamClient.BrainEntry>, searching: Boolean): List<PickerRow> {
    val out = ArrayList<PickerRow>()
    for (sec in plan.sections) {
        out += PickerRow.Head(sec)
        for (g in sec.groups) {
            g.title?.let { out += PickerRow.Lab(sec, g.key, it) }
            for (m in g.shown) out += PickerRow.Model(sec, m)
            if (g.folded > 0) out += PickerRow.Fold(sec, g.key, g.folded)
        }
    }
    // Machines never match a model search: they are not models.
    if (roster.isNotEmpty() && !searching) {
        out += PickerRow.MachinesHead
        for (b in roster) out += PickerRow.Machine(b)
    }
    return out
}

// ---- pieces -------------------------------------------------------------------------------------

/** The kind's colour: where a model runs, as one dot the eye can sort by. */
@Composable
private fun kindTint(kind: ModelPicker.Kind?): Color = when (kind) {
    ModelPicker.Kind.LOCAL -> KeryxStatus.good
    ModelPicker.Kind.CLOUD -> MaterialTheme.colorScheme.primary
    ModelPicker.Kind.AGGREGATOR -> MaterialTheme.colorScheme.tertiary
    ModelPicker.Kind.VIRTUAL -> KeryxStatus.idle
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun kindWord(kind: ModelPicker.Kind?): String = when (kind) {
    ModelPicker.Kind.LOCAL -> "this machine"
    ModelPicker.Kind.CLOUD -> "cloud"
    ModelPicker.Kind.AGGREGATOR -> "aggregator"
    ModelPicker.Kind.VIRTUAL -> "virtual"
    null -> ""
}

@Composable
private fun CurrentBrainCard(current: ModelChoice?, catalog: ModelCatalog?, loading: Boolean, onRefresh: () -> Unit) {
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    val provider = catalog?.usable?.firstOrNull { p -> current != null && p.slug == current.provider }
    val kind = provider?.let(ModelPicker::kindOf)
    KeryxCard(breathing = loading) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(kindTint(kind).copy(alpha = breathingAlpha(active = current != null, low = 0.5f))))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW", fontSize = 9.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
                // The name slides up when the brain changes — the card answers the pick.
                AnimatedContent(
                    targetState = current?.shortName ?: catalog?.model?.ifBlank { null } ?: "no model",
                    transitionSpec = {
                        (fadeIn(KeryxMotion.settle) + slideInVertically(KeryxMotion.settleInt) { it / 2 })
                            .togetherWith(fadeOut(KeryxMotion.leave) + slideOutVertically(KeryxMotion.leaveInt) { -it / 2 })
                    },
                    label = "currentModel",
                ) { name ->
                    Text(
                        name, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val line = listOfNotNull(
                    provider?.name,
                    kindWord(kind).takeIf { it.isNotEmpty() },
                    current?.lab?.takeIf { it.isNotEmpty() }?.let(ModelPicker::labName),
                ).joinToString(" · ")
                if (line.isNotBlank()) Text(line, fontSize = 11.sp, color = meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current?.let { MetaLine(it, muted = meta) }
            }
            // Refresh turns while the catalog is being read.
            val spin by animateFloatAsState(if (loading) 360f else 0f, animationSpec = KeryxMotion.glide, label = "catalogSpin")
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(
                    KeryxGlyphs.Refresh, contentDescription = "Re-read the catalog", tint = meta,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin },
                )
            }
        }
    }
}

@Composable
private fun SectionChip(title: String, kind: ModelPicker.Kind?, lit: Boolean, onClick: () -> Unit) {
    val tint = kindTint(kind)
    val fill by animateColorAsState(
        if (lit) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        label = "chipFill",
    )
    val edge by animateColorAsState(
        if (lit) tint.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        label = "chipEdge",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(fill)
            .border(1.dp, edge, RoundedCornerShape(KeryxRadius.chip))
            .keryxPressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(
            title, fontSize = 11.sp, letterSpacing = 0.4.sp,
            color = if (lit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHead(section: ModelPicker.Section) {
    Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
        KeryxSectionHeader(
            section.title,
            dotColor = section.kind?.let { kindTint(it) },
            count = section.count.takeIf { it > 0 && section.kind != null },
        )
        if (section.subtitle.isNotBlank()) Text(
            section.subtitle, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = if (section.kind != null) 16.dp else 0.dp, top = 1.dp),
        )
    }
}

@Composable
private fun LabHead(title: String) {
    Text(
        title, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ModelRow(m: ModelChoice, current: Boolean, kind: ModelPicker.Kind?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KeryxRadius.field)
    val fill by animateColorAsState(
        if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        label = "rowFill",
    )
    val enabled = !m.unavailable
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(fill)
            .keryxPressScale(source)
            .clickable(interactionSource = source, indication = ripple(), enabled = enabled && !current, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(
                if (current) MaterialTheme.colorScheme.primary.copy(alpha = breathingAlpha(active = true, low = 0.45f))
                else kindTint(kind).copy(alpha = 0.35f),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.shortName, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            MetaLine(m, muted = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.featured) Icon(
            KeryxGlyphs.Star, contentDescription = "Featured", tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
            modifier = Modifier.size(12.dp),
        )
        if (current) {
            Spacer(Modifier.width(8.dp))
            Icon(KeryxGlyphs.Check, contentDescription = "Current", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        }
    }
}

/** Tags and price on one quiet line: `fast · thinks · $3 / $15 · free · −30%`. */
@Composable
private fun MetaLine(m: ModelChoice, muted: Color) {
    val parts = ArrayList<Pair<String, Color?>>()
    if (m.fast) parts += "fast" to null
    if (m.reasoning) parts += "thinks" to null
    if (m.canDisableReasoning == false) parts += "always thinks" to null
    m.pricing?.let { p ->
        when {
            p.free -> parts += "free" to KeryxStatus.good
            p.input.isNotBlank() || p.output.isNotBlank() ->
                parts += "${ModelPricing.compact(p.input)} / ${ModelPricing.compact(p.output)}" to null
        }
        p.discountPercent?.takeIf { it > 0 && !p.free }?.let { parts += "−$it%" to KeryxStatus.good }
    }
    if (m.unavailable) parts += "paid tier" to KeryxStatus.warn
    if (parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (text, tint) ->
            if (i > 0) Text(" · ", fontSize = 9.5.sp, color = muted.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
            Text(text, fontSize = 9.5.sp, color = tint ?: muted, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun FoldRow(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.field))
            .keryxPressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Spacer(Modifier.width(19.dp))
        Text(
            "$count more", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            KeryxGlyphs.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun MachineRow(b: HermesStreamClient.BrainEntry, active: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(KeryxRadius.field))
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .keryxPressScale(source)
            .clickable(interactionSource = source, indication = ripple(), enabled = !active, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(
                if (active) KeryxStatus.good.copy(alpha = breathingAlpha(active = true, low = 0.45f)) else KeryxStatus.idle.copy(alpha = 0.5f),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (b.description.isNotBlank()) Text(
                b.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) Icon(KeryxGlyphs.Check, contentDescription = "Serving", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
    }
}
