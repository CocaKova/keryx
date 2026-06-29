package chat.keryx.app.presentation.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material3.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.domain.model.RoomType
import chat.keryx.app.domain.model.Session
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.theme.*

@Composable
fun NavigationDrawerContent(
    viewModel: ChatViewModel,
    onSessionSelected: (Session) -> Unit
) {
    val rooms by viewModel.rooms.collectAsState()
    val pinnedRoomIds by viewModel.pinnedRoomIds.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val currentAccent by viewModel.accentColor.collectAsState()
    val matrixUrl by viewModel.matrixUrl.collectAsState()
    val agentMatrixId by viewModel.agentMatrixId.collectAsState()
    val matrixToken by viewModel.matrixToken.collectAsState()
    val biometricLockEnabled by viewModel.biometricLock.collectAsState()
    val e2eeEnabled by viewModel.e2eeEnabled.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val animationStyle by viewModel.animationStyle.collectAsState()
    val bubbleStyle by viewModel.bubbleStyle.collectAsState()
    val messageTextScale by viewModel.messageTextScale.collectAsState()
    val allowInsecure by viewModel.allowInsecure.collectAsState()

    var showSettings by remember { mutableStateOf(false) }

    // Image picker for setting a Quick Room's avatar (server-side m.room.avatar).
    val context = LocalContext.current
    var pendingAvatarRoomId by remember { mutableStateOf<String?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val roomId = pendingAvatarRoomId
        if (uri != null && roomId != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) viewModel.setRoomAvatar(roomId, bytes, type)
        }
        pendingAvatarRoomId = null
    }
    
    if (showSettings) {
        chat.keryx.app.presentation.ui.components.SettingsScreen(
            currentAccentColor = currentAccent,
            onAccentColorChanged = { viewModel.setAccentColor(it) },
            currentUserId = currentUserId,
            matrixUrl = matrixUrl,
            onMatrixUrlChanged = { viewModel.setMatrixUrl(it) },
            agentMatrixId = agentMatrixId,
            onAgentMatrixIdChanged = { viewModel.setAgentMatrixId(it) },
            matrixToken = matrixToken,
            onMatrixTokenChanged = { viewModel.setMatrixToken(it) },
            allowInsecure = allowInsecure,
            onAllowInsecureChanged = { viewModel.setAllowInsecure(it) },
            biometricLockEnabled = biometricLockEnabled,
            onBiometricLockChanged = { viewModel.setBiometricLock(it) },
            e2eeEnabled = e2eeEnabled,
            onE2eeChanged = { viewModel.setE2eeEnabled(it) },
            hapticsEnabled = hapticsEnabled,
            onHapticsChanged = { viewModel.setHapticsEnabled(it) },
            animationStyle = animationStyle,
            onAnimationStyleChanged = { viewModel.setAnimationStyle(it) },
            bubbleStyle = bubbleStyle,
            onBubbleStyleChanged = { viewModel.setBubbleStyle(it) },
            messageTextScale = messageTextScale,
            onMessageTextScaleChanged = { viewModel.setMessageTextScale(it) },
            onResetAppearance = { viewModel.resetMessageAppearance() },
            onLoginRequested = { user, pass, callback ->
                viewModel.loginToMatrix(user, pass, callback)
            },
            onLogout = { showSettings = false; viewModel.logout() },
            onDismissRequest = { showSettings = false }
        )
    }
    
    // Each Matrix room is its own conversation -> one session per room.
    fun sessionFor(room: RoomProfile) = Session(room.id, room.id, room.name, 0L)

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, 0.06f),
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                )
                .padding(16.dp)
        ) {
            // Profile / identity header — the animated Keryx emblem as the brand/identity mark.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            ) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        snakeLength = 18,
                        periodMillis = 5200,
                        glyphSize = 8f,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = 22.sp)
                    currentUserId?.let {
                        Text(
                            text = it,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            var query by remember { mutableStateOf("") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Jump to…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )

            val filtered = if (query.isBlank()) rooms
                else rooms.filter { it.name.contains(query, ignoreCase = true) }
            val pinned = filtered.filter { it.id in pinnedRoomIds }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (pinned.isNotEmpty() && query.isBlank()) {
                    item { DrawerSectionHeader("Quick Rooms") }
                    item {
                        chat.keryx.app.presentation.ui.components.QuickRoomsDeck(
                            rooms = pinned,
                            selectedRoomId = currentSession?.id,
                            onRoomClick = { onSessionSelected(sessionFor(it)) },
                            avatarLoader = { viewModel.loadAvatar(it) },
                            onRoomLongPress = { room ->
                                pendingAvatarRoomId = room.id
                                avatarPicker.launch("image/*")
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }

                item { DrawerSectionHeader(if (query.isBlank()) "All Rooms" else "Results") }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = if (rooms.isEmpty()) "No rooms yet" else "No matches",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(filtered, key = { it.id }) { room ->
                    RoomRow(
                        room = room,
                        isSelected = currentSession?.id == room.id,
                        isPinned = room.id in pinnedRoomIds,
                        onClick = { onSessionSelected(sessionFor(room)) },
                        onTogglePin = { viewModel.togglePin(room.id) },
                        onSetAvatar = {
                            pendingAvatarRoomId = room.id
                            avatarPicker.launch("image/*")
                        },
                        avatarLoader = { viewModel.loadAvatar(it) },
                    )
                }
            }

            // Bottom Actions
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        val next = when (isDarkTheme) {
                            null -> true
                            true -> false
                            false -> null
                        }
                        viewModel.toggleTheme(next)
                    }
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (isDarkTheme) {
                    true -> Icons.Default.LightMode
                    false -> Icons.Default.BrightnessAuto
                    null -> Icons.Default.DarkMode
                }
                val text = when (isDarkTheme) {
                    true -> "Light Mode"
                    false -> "System Mode"
                    null -> "Dark Mode"
                }
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(16.dp))
                Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSettings = true }
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun DrawerSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, start = 8.dp)
    )
}

@Composable
fun RoomRow(
    room: RoomProfile,
    isSelected: Boolean,
    isPinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onSetAvatar: () -> Unit,
    avatarLoader: suspend (String) -> ByteArray?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        // Tap the avatar to open the room; long-press it to set a room photo (works for any room).
        Box(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onSetAvatar,
            )
        ) {
            RoomAvatar(room = room, selected = isSelected, avatarLoader = avatarLoader)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = room.name,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (room.timestamp > 0L) {
                Text(
                    text = formatRelativeTime(room.timestamp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (room.unreadCount > 0L) {
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (room.unreadCount > 99) "99+" else room.unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        IconButton(onClick = onTogglePin, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isPinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isPinned) "Unpin room" else "Pin room",
                tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * A circular room/DM avatar: the real room photo when one is set, otherwise a tasteful colored
 * monogram derived from the name (so every row reads like a real chat client).
 */
@Composable
private fun RoomAvatar(
    room: RoomProfile,
    selected: Boolean,
    avatarLoader: suspend (String) -> ByteArray?,
) {
    val url = room.avatarUrl
    val cached = remember(url) { url?.let { chat.keryx.app.presentation.ui.components.KeryxBitmapCache.get(it) } }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = cached, url) {
        if (cached != null || url == null) return@produceState
        value = avatarLoader(url)?.let { bytes ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()?.also { chat.keryx.app.presentation.ui.components.KeryxBitmapCache.put(url, it) }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = room.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(CircleShape),
        )
    } else {
        val base = roomAvatarColor(room.name)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(base.copy(alpha = if (selected) 0.95f else 0.8f)),
        ) {
            Text(
                text = room.name.trimStart('@', '#', '!').trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val ROOM_AVATAR_PALETTE = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFF7986CB), Color(0xFFF06292),
)

private fun roomAvatarColor(name: String): Color =
    ROOM_AVATAR_PALETTE[(name.hashCode() and 0x7FFFFFFF) % ROOM_AVATAR_PALETTE.size]

/** Compact relative timestamp: now, 5m, 3h, 2d, 1w. */
private fun formatRelativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "now"
        diff < 3_600_000L -> "${diff / 60_000L}m"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        diff < 604_800_000L -> "${diff / 86_400_000L}d"
        else -> "${diff / 604_800_000L}w"
    }
}
