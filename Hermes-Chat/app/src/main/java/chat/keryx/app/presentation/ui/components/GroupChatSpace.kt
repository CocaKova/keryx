package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.core.model.GroupLine
import chat.keryx.core.model.GroupMember

/**
 * A group chat (2.18): one visible conversation where the room's bots answer in turn. Your
 * message reaches every member unless it @-mentions some; each member speaks only when it has
 * something to add. Long-press a message to reply in its thread. The gateway runs the turns, so
 * closing this screen — or the app — never stops a discussion; reopening catches up from the log.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupChatSpace(dest: KeryxDest.Group, viewModel: ChatViewModel, onClose: () -> Unit) {
    val groups = viewModel.groups
    DisposableEffect(dest.roomId) {
        groups.open(dest.roomId)
        onDispose { groups.close() }
    }
    val view by groups.view.collectAsState()
    val animationStyle by viewModel.prefs.animationStyle.collectAsState()
    val v = view?.takeIf { it.roomId == dest.roomId }
    val room = v?.room
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var disbanding by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var replyThread by rememberSaveable { mutableStateOf<String?>(null) }
    var replySnippet by rememberSaveable { mutableStateOf("") }

    KeryxSpace(
        title = room?.name ?: dest.name,
        onClose = onClose,
        standalone = false,
        liveSlot = {
            if (room != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                ) {
                    room.members.forEach { m ->
                        val light = botLightFor(m.profile, m.label, m.profile == "default")
                        HeraldSigil(light, fontSize = KeryxType.caption, alpha = if (m.memberId in v.working) 1f else 0.7f)
                        Spacer(Modifier.width(3.dp))
                        Text(m.label, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                }
            }
        },
        actions = {
            if (v?.busy == true) {
                IconButton(onClick = groups::stop) {
                    Icon(KeryxGlyphs.StopSquare, contentDescription = stringResource(R.string.agent_room_stop), tint = MaterialTheme.colorScheme.error)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(KeryxGlyphs.Kebab, contentDescription = stringResource(R.string.agent_room_options), tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.agent_rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.agent_room_disband), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; disbanding = true })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            val listState = rememberLazyListState()
            val lines = v?.lines.orEmpty()
            // The first line of each thread, so a reply further down can say what it continues.
            val threadHeads = remember(lines) {
                lines.filterIsInstance<GroupLine.Mine>().groupBy { it.threadId }.mapValues { it.value.first() }
            }
            LaunchedEffect(lines.size, v?.busy) {
                if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (v == null || (v.loading && lines.isEmpty())) {
                    item { PanelLoading() }
                } else if (lines.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.agent_room_intro),
                            fontSize = KeryxType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                v?.error?.let { item { PanelErrorLine(it) } }
                items(lines, key = { it.seq }) { line ->
                    val prev = lines.getOrNull(lines.indexOf(line) - 1)
                    val threadId = (line as? GroupLine.Mine)?.threadId ?: (line as? GroupLine.Member)?.threadId
                    val prevThread = (prev as? GroupLine.Mine)?.threadId ?: (prev as? GroupLine.Member)?.threadId
                    val head = threadId?.let(threadHeads::get)
                    val continues = threadId != null && prevThread != null && threadId != prevThread &&
                        head != null && head.seq < line.seq
                    if (continues) {
                        Text(
                            stringResource(R.string.agent_room_in_thread, head!!.text.lineSequence().first().take(60)),
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    GroupLineRow(
                        line = line,
                        onReply = { tid, snippet -> replyThread = tid; replySnippet = snippet },
                    )
                }
                if (v?.busy == true) {
                    item(key = "thinking") {
                        // Names only when the log says who; the gateway logs a turn once it ends.
                        val names = v.workingNames
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            HermesThinkingAnimation(style = animationStyle)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (names.size) {
                                    0 -> stringResource(R.string.agent_room_thinking)
                                    1 -> stringResource(R.string.agent_room_one_thinking, names.single())
                                    else -> stringResource(R.string.agent_room_many_thinking, names.joinToString(", "))
                                },
                                fontSize = KeryxType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // What the room is waiting on: an approval a member asked for, or a turn to retry.
            v?.driver?.approvals?.forEach { a ->
                val who = room?.member(a.memberId)?.label ?: a.memberId
                KeryxCard(tint = KeryxStatus.warn, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.agent_room_wants_to_run, who), fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold)
                    Text(a.description, fontSize = KeryxType.caption, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { groups.approve(a, "deny") }) { Text(stringResource(R.string.agent_deny)) }
                        TextButton(onClick = { groups.approve(a, "once") }) { Text(stringResource(R.string.agent_allow_once)) }
                    }
                }
            }
            v?.driver?.retries?.forEach { taskId ->
                KeryxCard(tint = KeryxStatus.warn, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.agent_room_turn_unclear),
                            fontSize = KeryxType.caption,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { groups.retry(taskId) }) { Text(stringResource(R.string.agent_retry)) }
                    }
                }
            }

            // @-mention chips: one tap seeds the handle, so the next send goes to just them.
            if (room != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (room.members.map { it.handle to it.label } + ("all" to "everyone")).forEach { (handle, label) ->
                        Text(
                            "@$handle",
                            fontSize = KeryxType.micro,
                            color = keryxAccentInk(),
                            modifier = Modifier
                                .clip(RoundedCornerShape(KeryxRadius.chip))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .clickable { if (!draft.contains("@$handle")) draft = "@$handle " + draft }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            replyThread?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    Text(
                        stringResource(R.string.agent_room_replying, replySnippet),
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { replyThread = null; replySnippet = "" }) { Text(stringResource(R.string.mk_cancel)) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.agent_room_message_hint)) },
                    maxLines = 6,
                    shape = RoundedCornerShape(KeryxRadius.field),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    enabled = draft.isNotBlank() && room != null,
                    onClick = {
                        groups.send(draft, replyThread)
                        draft = ""
                        replyThread = null
                        replySnippet = ""
                    },
                ) {
                    Icon(KeryxGlyphs.ArrowUp, contentDescription = stringResource(R.string.agent_send), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (renaming && room != null) {
        var name by remember { mutableStateOf(room.name) }
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.agent_room_rename_title), fontSize = KeryxType.titleLarge) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; groups.rename(room.roomId, name) }) { Text(stringResource(R.string.agent_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.mk_cancel)) } },
        )
    }
    if (disbanding) {
        AlertDialog(
            shape = RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { disbanding = false },
            title = { Text(stringResource(R.string.agent_room_disband_title, room?.name ?: dest.name), fontSize = KeryxType.titleLarge) },
            text = {
                Text(
                    stringResource(R.string.agent_room_disband_body),
                    fontSize = KeryxType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { disbanding = false; groups.disband(dest.roomId, onClose) }) {
                    Text(stringResource(R.string.agent_room_disband), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { disbanding = false }) { Text(stringResource(R.string.mk_cancel)) } },

        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupLineRow(line: GroupLine, onReply: (threadId: String, snippet: String) -> Unit) {
    when (line) {
        is GroupLine.Note -> Text(
            line.text,
            fontSize = KeryxType.micro,
            color = if (line.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        is GroupLine.Mine -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                line.text,
                fontSize = KeryxType.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .combinedClickable(onClick = {}, onLongClick = { onReply(line.threadId, line.text.take(60)) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        is GroupLine.Member -> {
            val member: GroupMember? = line.member
            val light = botLightFor(member?.profile ?: line.memberId, line.name, member?.profile == "default")
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, light.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = {}, onLongClick = { onReply(line.threadId, line.text.take(60)) })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeraldSigil(light, fontSize = KeryxType.caption)
                    Spacer(Modifier.width(4.dp))
                    Text(line.name, fontSize = KeryxType.caption, fontWeight = FontWeight.SemiBold, color = keryxAccentInk(light.accent))
                }
                Spacer(Modifier.height(2.dp))
                GroupProse(line.text)
            }
        }
    }
}

/** A member's words, set the way a chat bubble sets them (GFM, the chat's type scale). */
@Composable
private fun GroupProse(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    val source = remember(text) { chat.keryx.core.protocol.MessageParser.extractKeryx(text).text.trim() }
    val state = com.mikepenz.markdown.model.rememberMarkdownState(
        content = source,
        flavour = MarkdownCache.flavour,
        parser = MarkdownCache.parser,
        immediate = true,
    )
    com.mikepenz.markdown.m3.Markdown(
        markdownState = state,
        colors = chatMarkdownColors(color),
        typography = chatMarkdownTypography(color),
        extendedSpans = chatExtendedSpans(),
    )
}
