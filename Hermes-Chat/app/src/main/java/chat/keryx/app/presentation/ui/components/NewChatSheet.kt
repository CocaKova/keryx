package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel

/**
 * The drawer's one "start a conversation" surface: three compact rows (direct message, new room,
 * join by address) that expand in place — no navigation, no extra chrome. Errors from the
 * homeserver render inline under the active row; success closes the sheet and the room opens as
 * soon as sync surfaces it (ChatViewModel.openRoomById handles the deferral).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Which row is expanded: "dm", "room", "join" (one at a time keeps the sheet short).
    var expanded by rememberSaveable { mutableStateOf("dm") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun done(err: String?) {
        busy = false
        error = err
        if (err == null) onDismiss()
    }

    KeryxSheet(onDismiss = onDismiss, title = "New conversation", sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(4.dp))

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
                Text("⚠ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(top = 8.dp, start = 32.dp)) { content() }
        }
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
