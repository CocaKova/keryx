package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    /** Open a group chat (2.18) — a place of its own on the stack, above this one. */
    onOpenGroup: (chat.keryx.core.model.GroupRoom) -> Unit = {},
    onClose: () -> Unit,
) {
    val bots = viewModel.bots
    val groupsPanel by viewModel.groups.rooms.collectAsState()
    LaunchedEffect(Unit) { viewModel.groups.refresh() }
    var creatingGroup by remember { mutableStateOf(false) }
    val panel by bots.roster.collectAsState()
    val seen by bots.seenAt.collectAsState()
    val busy by bots.busyNames.collectAsState()
    val pinned by bots.pinned.collectAsState()
    val showHidden by bots.showHidden.collectAsState()
    val routineCounts by bots.routineCounts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    // Toasts land in callbacks, where stringResource can't be called.
    val botsCtx = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf<BotProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var routinesFor by remember { mutableStateOf<BotProfile?>(null) }
    var sessionsFor by remember { mutableStateOf<BotProfile?>(null) }
    var freshFor by remember { mutableStateOf<BotProfile?>(null) }
    var enabling by remember { mutableStateOf(false) }

    // The place's own cadence: rosters move on the gateway's clock (a bot's last word, a
    // desktop edit), and profiles.list is the one call that knows.
    // On screen only: the place is what wants the fast pulse, and a Bots place left open
    // behind the launcher has no one to show it to (the roster's own slow pulse keeps
    // notifications honest meanwhile).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.hub.refreshJobs() }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // poll() lives in the ViewModel's scope, not this block's: cancel it by hand.
            val job = bots.poll(15_000)
            try { job.join() } finally { job.cancel() }
        }
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
        title = stringResource(R.string.agent_bots_title),
        hint = chat.keryx.app.presentation.tour.KeryxHints.BOTS,
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
                        contentDescription = if (showHidden) stringResource(R.string.agent_bots_hide_hidden) else stringResource(R.string.agent_bots_show_hidden),
                        tint = if (showHidden) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            IconButton(onClick = { creating = true }) {
                Icon(KeryxGlyphs.Plus, contentDescription = stringResource(R.string.agent_new_agent), tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        PanelErrorLine(panel.error)
        when {
            snap == null -> if (panel.error == null) PanelLoading()
            all.isEmpty() -> KeryxEmptyState(
                title = stringResource(R.string.agent_bots_empty_title),
                body = stringResource(R.string.agent_bots_empty_body),
                illustration = { BotsIllustration() },
                actionLabel = stringResource(R.string.agent_bots_make_first),
                onAction = { creating = true },
            )
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
                            placeholder = { Text(stringResource(R.string.agent_bots_search), fontSize = KeryxType.body) },
                            leadingIcon = { Icon(KeryxGlyphs.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            shape = RoundedCornerShape(KeryxRadius.field),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Group chats (2.18): rooms of 2–6 bots the gateway runs, above the roster.
                val groupRows = groupsPanel.data.orEmpty()
                if (groupRows.isNotEmpty() || all.size >= chat.keryx.core.model.GroupChats.MIN_MEMBERS) {
                    item(key = "groups-head") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.agent_group_chats),
                                fontSize = KeryxType.caption,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { creatingGroup = true }) { Text(stringResource(R.string.agent_new_group_chat)) }
                        }
                    }
                    items(groupRows, key = { "group:" + it.room.roomId }) { row ->
                        GroupRoomRow(row = row, onOpen = { onOpenGroup(row.room) })
                    }
                    item(key = "bots-head") {
                        Text(
                            stringResource(R.string.agent_bots_title),
                            fontSize = KeryxType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
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
                        onNewSession = {
                            viewModel.sessions.createSession("", profile = bot) { err ->
                                err?.let { viewModel.toast(botsCtx.getString(R.string.agent_bots_session_failed, bot.label, chat.keryx.core.model.FriendlyError.of(it.toString()))) }
                            }
                            onOpened()
                        },
                        onPin = { bots.setPinned(bot.name, !(bot.name in pinned)) },
                        onEdit = { editing = bot },
                        onHide = { bots.configure(bot, hidden = !bot.hidden) { err -> err?.let(viewModel::toast) } },
                        onRoutines = { routinesFor = bot },
                        onSessions = { sessionsFor = bot },
                        onFresh = { freshFor = bot },
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
                                viewModel.toast(err?.let { botsCtx.getString(R.string.agent_bots_arm_failed, it) } ?: botsCtx.getString(R.string.agent_bots_armed))
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
    if (creatingGroup) {
        NewGroupSheet(
            bots = all.filter { !it.hidden },
            viewModel = viewModel,
            onCreated = { room -> creatingGroup = false; onOpenGroup(room) },
            onDismiss = { creatingGroup = false },
        )
    }
    routinesFor?.let { bot ->
        BotRoutinesSheet(bot = bot, viewModel = viewModel, onOpenRuns = { routinesFor = null; onOpenRuns() }, onDismiss = { routinesFor = null })
    }
    sessionsFor?.let { bot ->
        BotSessionsSheet(
            bot = bot,
            viewModel = viewModel,
            onOpen = { room -> sessionsFor = null; bots.openSessionOf(bot, room); onOpened() },
            onDismiss = { sessionsFor = null },
        )
    }
    freshFor?.let { bot ->
        androidx.compose.material3.AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { freshFor = null },
            title = { Text(stringResource(R.string.agent_bots_fresh_title), fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.agent_bots_fresh_body, bot.label),
                    fontSize = KeryxType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { freshFor = null; bots.startFresh(bot); onOpened() }) { Text(stringResource(R.string.agent_bots_start_fresh)) }
            },
            dismissButton = { TextButton(onClick = { freshFor = null }) { Text(stringResource(R.string.mk_cancel)) } },
        )
    }
}

/** One group chat in the list: its members' sigils, its name, and the newest thing said. */
@Composable
private fun GroupRoomRow(row: chat.keryx.app.presentation.GroupsDelegate.RoomRow, onOpen: () -> Unit) {
    KeryxCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.room.members.take(chat.keryx.core.model.GroupChats.MAX_MEMBERS).forEach { m ->
                HeraldSigil(botLightFor(m.profile, m.label, m.profile == "default"), fontSize = KeryxType.caption)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                row.room.name,
                fontSize = KeryxType.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.room.updatedAt > 0) {
                Text(relativeWhen(row.room.updatedAt), fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            row.preview ?: row.room.members.joinToString(", ") { it.label },
            fontSize = KeryxType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Start a group chat: a name and 2–6 bots. The gateway hosts it from then on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGroupSheet(
    bots: List<BotProfile>,
    viewModel: ChatViewModel,
    onCreated: (chat.keryx.core.model.GroupRoom) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val count = picked.size
    KeryxSheet(onDismiss = onDismiss, title = stringResource(R.string.agent_new_group_chat)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.agent_room_name)) },
                shape = RoundedCornerShape(KeryxRadius.field),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.agent_group_pick, chat.keryx.core.model.GroupChats.MIN_MEMBERS, chat.keryx.core.model.GroupChats.MAX_MEMBERS, count),
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            bots.forEach { bot ->
                val on = bot.name in picked
                // A profile name with `_` can't be a room member on the gateway (2.19): shown,
                // greyed, with the reason, rather than offered and refused with a 400.
                val joinable = chat.keryx.core.model.GroupChats.canJoin(bot)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (joinable) 1f else 0.45f)
                        .clip(RoundedCornerShape(KeryxRadius.chip))
                        .clickable(enabled = joinable) {
                            picked = if (on) picked - bot.name
                            else if (count < chat.keryx.core.model.GroupChats.MAX_MEMBERS) picked + bot.name else picked
                        }
                        .padding(vertical = 8.dp),
                ) {
                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    HeraldSigil(botLightFor(bot.name, bot.label, bot.isDefault), fontSize = KeryxType.caption)
                    Spacer(Modifier.width(4.dp))
                    Text(bot.label, fontSize = KeryxType.body)
                    if (!joinable || bot.description.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (joinable) bot.description else stringResource(R.string.agent_group_cant_join),
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            error?.let { Text("⚠ $it", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    enabled = !busy && name.isNotBlank() && chat.keryx.core.model.GroupChats.canCreate(count),
                    onClick = {
                        busy = true; error = null
                        viewModel.groups.create(name, bots.filter { it.name in picked }) { room, err ->
                            busy = false
                            if (room != null) onCreated(room) else error = err
                        }
                    },
                ) { Text(if (busy) stringResource(R.string.agent_creating) else stringResource(R.string.agent_create)) }
            }
        }
    }
}

/**
 * A bot's conversations beyond its Bot Chat (2.17.3): its side sessions and routine runs,
 * newest first (the desktop's "Open recent session", as a list), and the Bot Chats it has
 * retired. Each opens on the floor in the bot's own store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotSessionsSheet(
    bot: BotProfile,
    viewModel: ChatViewModel,
    onOpen: (chat.keryx.core.model.RoomProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val result by produceState<Result<chat.keryx.core.model.BotSessions>?>(initialValue = null, bot.name) {
        value = viewModel.bots.sessions(bot)
    }
    KeryxSheet(onDismiss = onDismiss, title = stringResource(R.string.agent_bot_sessions_title, bot.label)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(6.dp))
            val r = result
            when {
                r == null -> Text(stringResource(R.string.agent_loading), fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.isFailure -> Text(
                    stringResource(R.string.agent_bot_sessions_failed, r.exceptionOrNull()?.message?.take(100) ?: stringResource(R.string.agent_try_again)),
                    fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val data = r.getOrThrow()
                    SessionSection(stringResource(R.string.agent_recent), data.recent, stringResource(R.string.agent_bot_recent_empty), onOpen)
                    Spacer(Modifier.height(12.dp))
                    SessionSection(stringResource(R.string.agent_past_bot_chats), data.past, stringResource(R.string.agent_none_retired), onOpen)
                }
            }
        }
    }
}

@Composable
private fun SessionSection(
    title: String,
    rows: List<chat.keryx.core.model.RoomProfile>,
    empty: String,
    onOpen: (chat.keryx.core.model.RoomProfile) -> Unit,
) {
    Text(title, fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    if (rows.isEmpty()) {
        Text(empty, fontSize = KeryxType.caption, color = chat.keryx.app.theme.quietInk(alpha = 0.8f))
        return
    }
    rows.forEach { room ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .clickable { onOpen(room) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
        ) {
            Text(room.name, fontSize = KeryxType.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                if (room.timestamp > 0) append(relativeWhen(room.timestamp))
                if (room.source.isNotBlank()) { if (isNotEmpty()) append(" · "); append(room.source) }
                if (room.messageCount > 0) { if (isNotEmpty()) append(" · "); append(pluralStringResource(R.plurals.agent_session_msgs, room.messageCount.toInt(), room.messageCount.toInt())) }
            }
            if (meta.isNotEmpty()) Text(meta, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            stringResource(R.string.agent_active_now),
            fontSize = KeryxType.micro, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold,
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
                Text(bot.label, fontSize = KeryxType.caption, color = light.accent, fontWeight = FontWeight.Medium, maxLines = 1)
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
    /** A separate session on this bot's profile — the Bot Chat itself stays as it is. */
    onNewSession: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onRoutines: () -> Unit,
    /** The bot's side sessions and retired Bot Chats. */
    onSessions: () -> Unit,
    /** Retire the Bot Chat and start a new one (asks first). */
    onFresh: () -> Unit,
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
                        fontSize = KeryxType.title,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (bot.hidden) 0.55f else 1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot.isDefault) Chip(stringResource(R.string.agent_bot_main), MaterialTheme.colorScheme.primary)
                    if (pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(KeryxGlyphs.PinFilled, contentDescription = stringResource(R.string.agent_pinned), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    }
                    if (routines > 0) Chip(pluralStringResource(R.plurals.agent_routines_count, routines, routines), light.accent)
                }
                val line = bot.canonical?.preview?.takeIf { it.isNotBlank() && !chat.keryx.core.model.SilenceTokens.isSilent(it) }
                    ?: bot.description.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.agent_bot_tap_to_start, bot.label)
                Text(
                    line,
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    bot.canonical?.lastActive?.takeIf { it > 0 }?.let { append(relativeWhen(it)) }
                    if (bot.model.isNotBlank()) { if (isNotEmpty()) append(" · "); append(bot.model) }
                    if (!bot.managed) { if (isNotEmpty()) append(" · "); append(stringResource(R.string.agent_bot_not_armed)) }
                }
                if (meta.isNotEmpty()) Text(meta, fontSize = KeryxType.micro, color = chat.keryx.app.theme.quietInk(alpha = 0.7f), maxLines = 1)
            }
            if (unread) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(light.accent))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_open_chat)) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_spoke_sessions)) }, onClick = { menu = false; onSessions() })
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_new_side_session)) }, onClick = { menu = false; onNewSession() })
            if (bot.canonical != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.agent_start_fresh_bot_chat)) }, onClick = { menu = false; onFresh() })
            }
            DropdownMenuItem(
                text = { Text(if (pinned) stringResource(R.string.agent_unpin_sessions) else stringResource(R.string.agent_pin_sessions)) },
                onClick = { menu = false; onPin() },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.agent_edit_name_role)) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(if (routines > 0) stringResource(R.string.agent_routines_n, routines) else stringResource(R.string.agent_routines)) }, onClick = { menu = false; onRoutines() })
            DropdownMenuItem(
                text = { Text(if (bot.hidden) stringResource(R.string.agent_unhide) else stringResource(R.string.agent_hide_from_roster)) },
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
        // 9sp of a hue on a 14% wash of the SAME hue: 2.96:1 on parchment with the default
        // accent. The ground is the light, the label is ink pressed out of it — and on the
        // void keryxAccentInk hands the hue straight back, so dark mode is unchanged.
        color = keryxAccentInk(color),
        fontSize = KeryxType.micro,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
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
                if (armed) stringResource(R.string.agent_msg_on) else stringResource(R.string.agent_msg_off),
                fontSize = KeryxType.body, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                !protocolOn -> stringResource(R.string.agent_msg_protocol_off)
                armed -> stringResource(R.string.agent_msg_armed, managed, total)
                else -> stringResource(R.string.agent_msg_explain)
            },
            fontSize = KeryxType.micro, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (protocolOn && managed < total) {
            TextButton(onClick = onEnable, enabled = !busy) {
                Text(
                    if (busy) stringResource(R.string.agent_arming)
                    else if (armed) stringResource(R.string.agent_arm_remaining, total - managed)
                    else stringResource(R.string.agent_turn_on_messaging),
                    fontSize = KeryxType.caption,
                )
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
    KeryxSheet(onDismiss = onDismiss, title = if (bot == null) stringResource(R.string.agent_new_agent) else stringResource(R.string.agent_edit_named, bot.label), sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(6.dp))
            if (bot == null) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.agent_name_label)) },
                    supportingText = {
                        Text(
                            when {
                                taken -> stringResource(R.string.agent_profile_taken, slug)
                                slug.isNotBlank() && slug != name.trim() -> stringResource(R.string.agent_profile_slug, slug)
                                else -> stringResource(R.string.agent_profile_name_hint)
                            },
                            fontSize = KeryxType.micro,
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
                label = { Text(stringResource(R.string.agent_title_label)) },
                supportingText = { Text(stringResource(R.string.agent_bot_title_hint), fontSize = KeryxType.micro) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = role, onValueChange = { role = it },
                label = { Text(stringResource(R.string.agent_role_label)) },
                supportingText = { Text(stringResource(R.string.agent_role_hint), fontSize = KeryxType.micro) },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bot == null && existing.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                KeryxSectionHeader(stringResource(R.string.agent_start_from))
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ClonePill(stringResource(R.string.agent_fresh_profile), cloneFrom == null) { cloneFrom = null }
                    existing.filter { !it.hidden }.forEach { src ->
                        ClonePill(src.label, cloneFrom == src.name) { cloneFrom = src.name }
                    }
                }
                Text(
                    if (cloneFrom == null) stringResource(R.string.agent_fresh_profile_desc)
                    else stringResource(R.string.agent_clone_desc, existing.firstOrNull { it.name == cloneFrom }?.label.orEmpty()),
                    fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = KeryxStatus.bad, fontSize = KeryxType.caption)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.mk_cancel)) }
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
                ) {
                    Text(
                        stringResource(
                            if (busy) (if (bot == null) R.string.agent_creating else R.string.agent_saving)
                            else if (bot == null) R.string.agent_create else R.string.agent_save,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClonePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    // Selected read 2.89:1 on paper (accent on a 16% wash of itself), and 5dp of padding
    // round a 12sp line is a 24dp target on the picker you use to seed a new agent.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = 6.dp)
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .keryxPressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = KeryxType.caption,
            color = if (selected) keryxAccentInk(accent) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bot's routines: its `[bot:<name>]` cron jobs, read here, managed in Runs / Jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotRoutinesSheet(bot: BotProfile, viewModel: ChatViewModel, onOpenRuns: () -> Unit, onDismiss: () -> Unit) {
    val jobsPanel by viewModel.hub.jobs.collectAsState()
    LaunchedEffect(Unit) { viewModel.hub.refreshJobs() }
    val jobs = remember(jobsPanel.data) { viewModel.bots.routines(bot) }
    KeryxSheet(onDismiss = onDismiss, title = stringResource(R.string.agent_bot_routines_title, bot.label)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(6.dp))
            if (jobs.isEmpty()) {
                Text(
                    stringResource(R.string.agent_routines_empty, bot.label, BotRoster.routineTag(bot.name)),
                    fontSize = KeryxType.caption, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                jobs.forEach { job ->
                    KeryxCard(tint = if (job.enabled) null else KeryxStatus.idle) {
                        Text(BotRoster.routineLabel(job.name), fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append(job.scheduleDisplay)
                                if (!job.enabled) append(" · ").append(stringResource(R.string.agent_paused))
                                job.nextRunAt?.takeIf { it.isNotBlank() }?.let { append(" · ").append(stringResource(R.string.agent_next_at, it)) }
                            },
                            fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenRuns) { Text(stringResource(R.string.agent_open_runs)) }
            }
        }
    }
}

@Composable
private fun relativeWhen(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> stringResource(R.string.agent_just_now)
        d < 3_600_000 -> stringResource(R.string.agent_ago_m, (d / 60_000).toInt())
        d < 86_400_000 -> stringResource(R.string.agent_ago_h, (d / 3_600_000).toInt())
        else -> stringResource(R.string.agent_ago_d, (d / 86_400_000).toInt())
    }
}

