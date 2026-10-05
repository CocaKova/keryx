package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel

/**
 * The drawer's one "start a conversation" surface. On Matrix: three compact rows (direct
 * message, new room, join by address) that expand in place — no navigation, no extra chrome.
 * On the direct door a conversation is a gateway session, so the sheet is a single pane: an
 * optional title and Create. Errors render inline; success closes the sheet and the room opens
 * as soon as the list surfaces it (ChatViewModel.openRoomById handles the deferral).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    // Fired once, after a conversation was actually made (or joined) — the host uses it to
    // get out of the way (close the drawer) so the new room is what you land on. A cancel
    // is only [onDismiss].
    onCreated: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Which row is expanded: "dm", "room", "join" (one at a time keeps the sheet short).
    var expanded by rememberSaveable { mutableStateOf("dm") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun done(err: String?) {
        busy = false
        error = err
        if (err == null) { onDismiss(); onCreated() }
    }

    KeryxSheet(onDismiss = onDismiss, title = if (viewModel.transportIsDirect) "New session" else "New conversation", sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(4.dp))

            if (viewModel.transportIsDirect) {
                var title by rememberSaveable { mutableStateOf("") }
                var temporary by rememberSaveable { mutableStateOf(false) }
                // Who the session runs as (2.17.3). Starts on the agent of the session you came
                // from, so "another one of these" is the default; the launch profile is "".
                var agentName by rememberSaveable {
                    mutableStateOf(viewModel.bots.agentOf(viewModel.currentRoom.value?.id)?.name.orEmpty())
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.bots.refresh() }
                val roster by viewModel.bots.roster.collectAsState()
                val agents = roster.data?.bots.orEmpty()
                    .filter { !it.hidden || it.name == agentName }
                    .sortedByDescending { it.isDefault }
                Text(
                    "A fresh gateway session. Name it now, or let the first exchange title it.",
                    fontSize = KeryxType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.field),
                )
                if (agents.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text("Agent", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        agents.forEach { bot ->
                            val key = if (bot.isDefault) "" else bot.name
                            AgentPill(bot = bot, selected = key == agentName) { agentName = key }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Temporary: a scratch conversation that owes the roster nothing. It behaves
                // like any session while the app lives; the next cold start deletes it from
                // the gateway (the drawer marks it with the hourglass meanwhile).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { temporary = !temporary }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = if (temporary) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Temporary",
                            fontSize = KeryxType.body,
                            fontWeight = FontWeight.Medium,
                            color = if (temporary) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Deleted from the gateway at the app's next launch",
                            fontSize = KeryxType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(checked = temporary, onCheckedChange = { temporary = it })
                }
                SheetActionRow(busy = busy, enabled = true, label = "Create") {
                    busy = true; error = null
                    // Picked by name, so a roster that has not landed yet still creates on it.
                    val agent = agentName.takeIf { it.isNotBlank() }?.let { n ->
                        agents.firstOrNull { it.name == n } ?: chat.keryx.core.model.BotProfile(name = n)
                    }
                    viewModel.createSession(title, temporary, profile = agent, onDone = ::done)
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("⚠ $it", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error)
                }
                return@Column
            }

            NewChatRow(
                icon = Icons.Default.Person,
                title = "Direct message",
                subtitle = "Chat one-on-one with a user",
                open = expanded == "dm",
                onClick = { expanded = "dm"; error = null },
            ) {
                var userId by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    placeholder = { Text("@user:server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.field),
                )
                SheetActionRow(busy = busy, enabled = userId.isNotBlank(), label = "Start") {
                    busy = true; error = null
                    viewModel.startDirectMessage(userId.trim(), ::done)
                }
            }

            NewChatRow(
                icon = Icons.Default.Groups,
                title = "New room",
                subtitle = "A named room; invite people now or later",
                open = expanded == "room",
                onClick = { expanded = "room"; error = null },
            ) {
                var name by rememberSaveable { mutableStateOf("") }
                var invitee by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.field),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = invitee,
                    onValueChange = { invitee = it },
                    placeholder = { Text("Invite @user:server (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.field),
                )
                SheetActionRow(busy = busy, enabled = name.isNotBlank(), label = "Create") {
                    busy = true; error = null
                    viewModel.createRoom(name.trim(), invitee.trim(), ::done)
                }
            }

            NewChatRow(
                icon = Icons.AutoMirrored.Filled.Login,
                title = "Join by address",
                subtitle = "#alias:server or !roomid:server",
                open = expanded == "join",
                onClick = { expanded = "join"; error = null },
            ) {
                var address by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("#alias:server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.field),
                )
                SheetActionRow(busy = busy, enabled = address.isNotBlank(), label = "Join") {
                    busy = true; error = null
                    viewModel.joinRoomByAddress(address.trim(), ::done)
                }
            }

            error?.let {
                Spacer(Modifier.height(6.dp))
                Text("⚠ $it", fontSize = KeryxType.caption, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NewChatRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    open: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = KeryxType.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = open, enter = keryxReveal(), exit = keryxConceal()) {
            Column(Modifier.padding(top = 8.dp, start = 32.dp)) { content() }
        }
    }
}

/** One agent to start the session as: its sigil in its own light, its name. */
@Composable
private fun AgentPill(bot: chat.keryx.core.model.BotProfile, selected: Boolean, onClick: () -> Unit) {
    val light = botLightFor(bot.name, bot.label, bot.isDefault)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.chip)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) light.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, if (selected) light.accent else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        HeraldSigil(light, fontSize = KeryxType.caption)
        Spacer(Modifier.width(5.dp))
        Text(
            bot.label,
            fontSize = KeryxType.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) keryxAccentInk(light.accent) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun SheetActionRow(busy: Boolean, enabled: Boolean, label: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        TextButton(onClick = onAction, enabled = enabled && !busy) { Text(label) }
    }
}
