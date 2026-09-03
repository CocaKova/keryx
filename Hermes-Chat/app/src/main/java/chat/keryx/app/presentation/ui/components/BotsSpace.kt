package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRoster
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * BOTS — Bot Mode (2.8), the way the desktop's Bots pane does it: one row per profile,
 * each a tap from its forever-chat. A Bot IS a profile; this place is a UI over that
 * primitive, so everything here is visible from the CLI too (`hermes -p <bot> chat` opens
 * the same conversation, a Bot's routines are `hermes cron list` jobs named `[bot:<name>]`).
 *
 * Above the roster an "Active now" strip names every bot working this minute; under it the
 * rows, activity-ordered, wearing the bot's light, its role line (or the last thing said in
 * its chat), when, and a news dot. Long-press a row for its verbs: pin to the top of the
 * session list, rename, hide, routines. A quiet card at the foot says whether the gateway is
 * armed for bot-to-bot messaging and arms it in one tap — the `hermes-bots` block on each
 * profile is exactly the flag the gateway reads before it hands a Bot Chat `message_agent`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsSpace(
    viewModel: ChatViewModel,
    /** The floor has the bot's chat open — the place can step aside. */
    onOpened: () -> Unit,
    onOpenRuns: () -> Unit,
    onClose: () -> Unit,
) {
    val bots = viewModel.bots
    val panel by bots.roster.collectAsState()
    val seen by bots.seenAt.collectAsState()
    val busy by bots.busyNames.collectAsState()
    val pinned by bots.pinned.collectAsState()
    val showHidden by bots.showHidden.collectAsState()
    val routineCounts by bots.routineCounts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<BotProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var routinesFor by remember { mutableStateOf<BotProfile?>(null) }
    var enabling by remember { mutableStateOf(false) }

    // The place's own cadence: rosters move on the gateway's clock (a bot's last word, a
    // desktop edit), and profiles.list is the one call that knows.
    DisposableEffect(Unit) {
        val job = bots.poll(15_000)
        viewModel.hub.refreshJobs()
        onDispose { job.cancel() }
    }
    // "Active now" is a 90 s window: keep a clock so chips retire without a refetch.
    val now by produceState(System.currentTimeMillis()) {
        while (isActive) { delay(5_000); value = System.currentTimeMillis() }
    }

    val snap = panel.data
    val all = snap?.bots ?: emptyList()
    val ordered = remember(all, now / 5_000, busy, showHidden, query) {
        BotRoster.order(all, now, busy, showHidden).filter { BotRoster.matches(it, query) }
    }
    val active = remember(all, now / 5_000, busy) { BotRoster.active(all, now, busy) }
    val anyHidden = all.any { it.hidden }

    fun open(bot: BotProfile) {
        bots.open(bot)
        onOpened()
    }

    KeryxSpace(
        title = "Bots",
        onClose = onClose,
        standalone = false,
        liveSlot = {
            if (active.isNotEmpty()) ActiveNowStrip(active = active, viewModel = viewModel, onOpen = ::open)
        },
        actions = {
            if (anyHidden) {
                IconButton(onClick = { bots.setShowHidden(!showHidden) }) {
                    Icon(
                        KeryxGlyphs.Scope,
                        contentDescription = if (showHidden) "Hide hidden bots" else "Show hidden bots",
                        tint = if (showHidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            IconButton(onClick = { creating = true }) {
                Icon(KeryxGlyphs.Plus, contentDescription = "New agent", tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        PanelErrorLine(panel.error)
        when {
            snap == null -> PanelLoading()
            all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No profiles on this gateway yet.\nTap + to make the first bot.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (all.size > 5) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search bots", fontSize = 13.sp) },
                            leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            shape = RoundedCornerShape(KeryxRadius.field),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(ordered, key = { it.name }) { bot ->
                    BotRow(
                        bot = bot,
                        viewModel = viewModel,
                        active = bot.name in busy || BotRoster.isActive(bot, now, busy),
                        unread = BotRoster.unread(bot, seen),
                        pinned = bot.name in pinned,
                        routines = routineCounts[bot.name.lowercase()] ?: 0,
                        onOpen = { open(bot) },
                        onPin = { bots.setPinned(bot.name, !(bot.name in pinned)) },
                        onEdit = { editing = bot },
                        onHide = { bots.configure(bot, hidden = !bot.hidden) { err -> err?.let(viewModel::toast) } },
                        onRoutines = { routinesFor = bot },
                    )
                }
                item(key = "messaging") {
                    Spacer(Modifier.height(6.dp))
                    MessagingCard(
                        armed = snap.messagingArmed,
                        protocolOn = snap.protocolEnabled,
                        managed = all.count { it.managed },
                        total = all.size,
                        busy = enabling,
                        onEnable = {
                            enabling = true
                            bots.enableMessaging { err ->
                                enabling = false
                                viewModel.toast(err?.let { "Couldn't arm every bot — $it" } ?: "Bot messaging armed on this gateway")
                            }
                        },
                    )
                }
            }
        }
    }

    editing?.let { bot ->
        BotEditSheet(
            bot = bot,
            existing = all,
            viewModel = viewModel,
            onDismiss = { editing = null },
        )
    }
    if (creating) {
        BotEditSheet(
            bot = null,
            existing = all,
            viewModel = viewModel,
            onDismiss = { creating = false },
            onCreated = onOpened,
        )
    }
    routinesFor?.let { bot ->
        BotRoutinesSheet(bot = bot, viewModel = viewModel, onOpenRuns = { routinesFor = null; onOpenRuns() }, onDismiss = { routinesFor = null })
    }
}

/** Every bot working this minute, as chips that open its chat. Gone when the fleet is idle. */
@Composable
private fun ActiveNowStrip(active: List<BotProfile>, viewModel: ChatViewModel, onOpen: (BotProfile) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        KeryxBreathingDot(color = KeryxStatus.good, alive = true)
        Spacer(Modifier.width(8.dp))
        Text(
            "ACTIVE NOW",
            fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        active.forEach { bot ->
            val light = botLightFor(bot.name, bot.label, bot.isDefault)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .background(light.accent.copy(alpha = 0.14f))
                    .clickable { onOpen(bot) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                BotFace(bot = bot, viewModel = viewModel, size = 18.dp, working = true)
                Spacer(Modifier.width(6.dp))
                Text(bot.label, fontSize = 12.sp, color = light.accent, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * The bot's face: its uploaded avatar when it has one, else its sigil on a disc of its own
 * light. [working] breathes the ring — the same rhythm everything alive in Keryx keeps.
 */
@Composable
fun BotFace(bot: BotProfile, viewModel: ChatViewModel, size: androidx.compose.ui.unit.Dp, working: Boolean = false) {
    val light = botLightFor(bot.name, bot.label, bot.isDefault)
    val avatar by produceState<ByteArray?>(null, bot.name, bot.hasAvatar) {
        value = if (bot.hasAvatar) viewModel.bots.avatar(bot) else null
    }
    val bitmap = remember(avatar) {
        avatar?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    val alpha = if (working) breathingAlpha(active = true, low = 0.45f) else 1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(light.accent.copy(alpha = 0.32f), light.accent2.copy(alpha = 0.22f))),
            )
            .border(1.dp, light.accent.copy(alpha = 0.55f * alpha), CircleShape),
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = bot.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HeraldSigil(light, fontSize = (size.value * 0.5f).sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRow(
    bot: BotProfile,
    viewModel: ChatViewModel,
    active: Boolean,
    unread: Boolean,
    pinned: Boolean,
    routines: Int,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onRoutines: () -> Unit,
) {
    val haptics = LocalKeryxHaptics.current
    var menu by remember { mutableStateOf(false) }
    val light = botLightFor(bot.name, bot.label, bot.isDefault)
    val shape = RoundedCornerShape(KeryxRadius.card)
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (bot.hidden) 0.18f else 0.35f)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .keryxShimmerBorder(active = active, baseColor = light.accent.copy(alpha = if (unread) 0.45f else 0.2f), shape = shape)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { haptics.press(); menu = true },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BotFace(bot = bot, viewModel = viewModel, size = 40.dp, working = active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bot.label,
                        fontSize = 15.sp,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (bot.hidden) 0.55f else 1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot.isDefault) Chip("main", MaterialTheme.colorScheme.primary)
                    if (pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(KeryxGlyphs.PinFilled, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    }
                    if (routines > 0) Chip("$routines routine${if (routines == 1) "" else "s"}", light.accent)
                }
                val line = bot.canonical?.preview?.takeIf { it.isNotBlank() }
                    ?: bot.description.takeIf { it.isNotBlank() }
                    ?: "Tap to start ${bot.label}'s chat"
                Text(
                    line,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    bot.canonical?.lastActive?.takeIf { it > 0 }?.let { append(relativeWhen(it)) }
                    if (bot.model.isNotBlank()) { if (isNotEmpty()) append(" · "); append(bot.model) }
                    if (!bot.managed) { if (isNotEmpty()) append(" · "); append("not armed") }
                }
                if (meta.isNotEmpty()) Text(meta, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
            }
            if (unread) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(light.accent))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open chat") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin from sessions" else "Pin to top of sessions") },
                onClick = { menu = false; onPin() },
            )
            DropdownMenuItem(text = { Text("Edit name & role") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("Routines" + if (routines > 0) " · $routines" else "") }, onClick = { menu = false; onRoutines() })
            DropdownMenuItem(
                text = { Text(if (bot.hidden) "Unhide" else "Hide from roster") },
                onClick = { menu = false; onHide() },
            )
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Spacer(Modifier.width(6.dp))
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Whether bots can message each other here. The gateway injects `message_agent` into a
 * canonical Bot Chat only while some profile on the install carries the `hermes-bots`
 * block and `agent.bot_mode_protocol` is on — this card reads that gate and flips it.
 */
@Composable
private fun MessagingCard(armed: Boolean, protocolOn: Boolean, managed: Int, total: Int, busy: Boolean, onEnable: () -> Unit) {
    KeryxCard(tint = if (armed) KeryxStatus.good else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeryxBreathingDot(color = if (armed) KeryxStatus.good else KeryxStatus.idle, alive = false)
            Spacer(Modifier.width(8.dp))
            Text(
                if (armed) "Bot-to-bot messaging is on" else "Bot-to-bot messaging is off",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                !protocolOn -> "The gateway's agent.bot_mode_protocol switch is off in config.yaml — flip it there and every Bot Chat learns the teammate protocol."
                armed -> "$managed of $total profiles carry the Bot Mode block. Their Bot Chats can @mention each other with message_agent; a reply lands as a message from that bot."
                else -> "Arm it once and every Bot Chat gets the message_agent tool: bots can hand work to each other by @name, and replies arrive attributed. Regular sessions are untouched."
            },
            fontSize = 11.sp, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (protocolOn && managed < total) {
            TextButton(onClick = onEnable, enabled = !busy) {
                Text(if (busy) "Arming…" else if (armed) "Arm the remaining ${total - managed}" else "Turn on bot messaging", fontSize = 12.sp)
            }
        }
    }
}

/**
 * New Agent / Edit Profile: the desktop's quick path (name, title, role) — a bot exists in
 * seconds and introduces itself as the first message of its new chat. Editing renames the
 * title and role of a live profile; the profile name itself is the one thing that never
 * changes (it is the key everything else hangs from).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotEditSheet(
    bot: BotProfile?,
    existing: List<BotProfile>,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onCreated: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(bot?.name ?: "") }
    var title by rememberSaveable { mutableStateOf(bot?.title ?: "") }
    var role by rememberSaveable { mutableStateOf(bot?.description ?: "") }
    var cloneFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val slug = BotRoster.slug(name)
    val taken = bot == null && existing.any { it.name == slug }
    KeryxSheet(onDismiss = onDismiss, title = if (bot == null) "New agent" else "Edit ${bot.label}", sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(6.dp))
            if (bot == null) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    supportingText = {
                        Text(
                            when {
                                taken -> "A profile named $slug already exists"
                                slug.isNotBlank() && slug != name.trim() -> "Profile: $slug"
                                else -> "The profile's name — lowercase, dashes ok"
                            },
                            fontSize = 11.sp,
                        )
                    },
                    isError = taken,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") },
                supportingText = { Text("What the roster calls it (\"Research Buddy\") — also its @tag", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = role, onValueChange = { role = it },
                label = { Text("Role") },
                supportingText = { Text("One line teammates read before choosing whom to message", fontSize = 11.sp) },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bot == null && existing.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                KeryxSectionHeader("Start from")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ClonePill("Fresh profile", cloneFrom == null) { cloneFrom = null }
                    existing.filter { !it.hidden }.forEach { src ->
                        ClonePill(src.label, cloneFrom == src.name) { cloneFrom = src.name }
                    }
                }
                Text(
                    if (cloneFrom == null) "Bundled skills, a clean memory." else "Copies ${existing.firstOrNull { it.name == cloneFrom }?.label}'s config, skills and SOUL.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = KeryxStatus.bad, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                TextButton(
                    enabled = !busy && (bot != null || (slug.isNotBlank() && !taken)),
                    onClick = {
                        busy = true; error = null
                        if (bot == null) {
                            viewModel.bots.create(slug, title, role, cloneFrom) { err ->
                                busy = false
                                if (err != null) error = err else { onDismiss(); onCreated() }
                            }
                        } else {
                            viewModel.bots.configure(
                                bot,
                                title = title,
                                description = role.takeIf { it != bot.description },
                            ) { err ->
                                busy = false
                                if (err != null) error = err else onDismiss()
                            }
                        }
                    },
                ) { Text(if (busy) (if (bot == null) "Creating…" else "Saving…") else if (bot == null) "Create" else "Save") }
            }
        }
    }
}

@Composable
private fun ClonePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        label,
        fontSize = 12.sp,
        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A bot's routines: its `[bot:<name>]` cron jobs, read here, managed in Runs / Jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotRoutinesSheet(bot: BotProfile, viewModel: ChatViewModel, onOpenRuns: () -> Unit, onDismiss: () -> Unit) {
    val jobsPanel by viewModel.hub.jobs.collectAsState()
    LaunchedEffect(Unit) { viewModel.hub.refreshJobs() }
    val jobs = remember(jobsPanel.data) { viewModel.bots.routines(bot) }
    KeryxSheet(onDismiss = onDismiss, title = "${bot.label} · routines") {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(6.dp))
            if (jobs.isEmpty()) {
                Text(
                    "No routines yet. Ask ${bot.label} in its chat to schedule one — a job named \"${BotRoster.routineTag(bot.name)} …\" shows up here and runs in its own chat history.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                jobs.forEach { job ->
                    KeryxCard(tint = if (job.enabled) null else KeryxStatus.idle) {
                        Text(BotRoster.routineLabel(job.name), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append(job.scheduleDisplay)
                                if (!job.enabled) append(" · paused")
                                job.nextRunAt?.takeIf { it.isNotBlank() }?.let { append(" · next ").append(it) }
                            },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenRuns) { Text("Open Runs") }
            }
        }
    }
}

private fun relativeWhen(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000}m ago"
        d < 86_400_000 -> "${d / 3_600_000}h ago"
        else -> "${d / 86_400_000}d ago"
    }
}
