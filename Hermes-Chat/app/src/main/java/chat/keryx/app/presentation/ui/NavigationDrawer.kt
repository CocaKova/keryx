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
import androidx.compose.material.icons.filled.ViewKanban
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
import chat.keryx.app.domain.model.RoomInvite
import chat.keryx.app.domain.model.RoomProfile
import chat.keryx.app.domain.model.RoomType
import chat.keryx.app.domain.model.Session
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.theme.*

@Composable
fun NavigationDrawerContent(
    viewModel: ChatViewModel,
    onSessionSelected: (Session) -> Unit,
    // ModalNavigationDrawer composes its drawer content even while closed (just translated
    // offscreen), so anything permanently animated in here would burn frames invisibly. The host
    // passes the drawer's real visibility so ornament only runs while it can be seen.
    drawerVisible: Boolean = true,
) {
    val rooms by viewModel.rooms.collectAsState()
    val pinnedRoomIds by viewModel.pinnedRoomIds.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val currentAccent by viewModel.accentColor.collectAsState()
    val currentAccent2 by viewModel.accentColor2.collectAsState()
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
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    val gatewayApiKey by viewModel.gatewayApiKey.collectAsState()
    val sideChannelEnabled by viewModel.sideChannelEnabled.collectAsState()
    val sttUrl by viewModel.sttUrl.collectAsState()
    val sttApiKey by viewModel.sttApiKey.collectAsState()
    val sttModel by viewModel.sttModel.collectAsState()
    val showTelemetry by viewModel.showTelemetry.collectAsState()
    val missionAlertsEnabled by viewModel.missionAlertsEnabled.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showMissions by remember { mutableStateOf(false) }

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
    
    if (showMissions) {
        chat.keryx.app.presentation.ui.components.MissionsScreen(
            viewModel = viewModel,
            onDismissRequest = { showMissions = false },
        )
    }

    if (showSettings) {
        chat.keryx.app.presentation.ui.components.SettingsScreen(
            currentAccentColor = currentAccent,
            onAccentColorChanged = { viewModel.setAccentColor(it) },
            currentAccentColor2 = currentAccent2,
            onAccentColor2Changed = { viewModel.setAccentColor2(it) },
            currentUserId = currentUserId,
            matrixUrl = matrixUrl,
            onMatrixUrlChanged = { viewModel.setMatrixUrl(it) },
            agentMatrixId = agentMatrixId,
            onAgentMatrixIdChanged = { viewModel.setAgentMatrixId(it) },
            matrixToken = matrixToken,
            onMatrixTokenChanged = { viewModel.setMatrixToken(it) },
            allowInsecure = allowInsecure,
            onAllowInsecureChanged = { viewModel.setAllowInsecure(it) },
            gatewayUrl = gatewayUrl,
            onGatewayUrlChanged = { viewModel.setGatewayUrl(it) },
            gatewayApiKey = gatewayApiKey,
            onGatewayApiKeyChanged = { viewModel.setGatewayApiKey(it) },
            sideChannelEnabled = sideChannelEnabled,
            onSideChannelEnabledChanged = { viewModel.setSideChannelEnabled(it) },
            sttUrl = sttUrl,
            onSttUrlChanged = { viewModel.setSttUrl(it) },
            sttApiKey = sttApiKey,
            onSttApiKeyChanged = { viewModel.setSttApiKey(it) },
            sttModel = sttModel,
            onSttModelChanged = { viewModel.setSttModel(it) },
            onTestLink = { viewModel.testGatewayLink() },
            showTelemetry = showTelemetry,
            onShowTelemetryChanged = { viewModel.setShowTelemetry(it) },
            missionAlertsEnabled = missionAlertsEnabled,
            onMissionAlertsChanged = {
                viewModel.setMissionAlertsEnabled(it)
                chat.keryx.app.notify.MissionAlertsWorker.setEnabled(context, it)
            },
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
                        color2 = MaterialTheme.colorScheme.tertiary,
                        running = drawerVisible,
                        snakeLength = 18,
                        periodMillis = 5200,
                        glyphSize = 8f,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    // The petdex mascot (Hermes desktop's floating pet, drawer-header sized).
                    // Fetched lazily on first drawer open; absent entirely when the gateway has
                    // no pet configured, so the header stays exactly as before for those setups.
                    val petInfo by viewModel.petInfo.collectAsState()
                    val awaitingReply by viewModel.awaitingReply.collectAsState()
                    var petGreeting by remember { mutableStateOf(false) }
                    LaunchedEffect(drawerVisible) {
                        if (drawerVisible) {
                            viewModel.refreshPet()
                            // Wave hello when the drawer opens, then settle into the idle loop.
                            petGreeting = true
                            kotlinx.coroutines.delay(2200)
                            petGreeting = false
                        }
                    }
                    var showPetPicker by remember { mutableStateOf(false) }
                    if (showPetPicker) {
                        chat.keryx.app.presentation.ui.components.PetPickerSheet(
                            viewModel = viewModel,
                            onDismiss = { showPetPicker = false },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        chat.keryx.app.presentation.ui.components.KeryxWordmark(fontSize = 22.sp)
                        petInfo?.let { pet ->
                            Spacer(modifier = Modifier.width(10.dp))
                            chat.keryx.app.presentation.ui.components.PetSprite(
                                info = pet,
                                pose = when {
                                    awaitingReply -> chat.keryx.app.presentation.ui.components.PetPose.RUN
                                    petGreeting -> chat.keryx.app.presentation.ui.components.PetPose.WAVE
                                    else -> chat.keryx.app.presentation.ui.components.PetPose.IDLE
                                },
                                running = drawerVisible,
                                // Native frames are 192×208 — keep the aspect so the pet isn't squashed.
                                modifier = Modifier
                                    .size(width = 26.dp, height = 28.dp)
                                    // Tap your pet to adopt a different one.
                                    .clickable { viewModel.refreshPetGallery(); showPetPicker = true },
                            )
                        }
                    }
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
            // Pinned rooms live in the Quick Rooms deck — don't list them twice.
            // (While searching, show everything that matches.)
            val listRooms = if (query.isBlank()) filtered.filter { it.id !in pinnedRoomIds } else filtered

            val invites by viewModel.invites.collectAsState()

            LazyColumn(modifier = Modifier.weight(1f)) {
                // Pending invitations first — they need a decision, not a scroll hunt.
                if (invites.isNotEmpty() && query.isBlank()) {
                    item { DrawerSectionHeader("Invites") }
                    items(invites, key = { "invite-${it.id}" }) { invite ->
                        InviteRow(
                            invite = invite,
                            onAccept = { viewModel.acceptInvite(invite.id) },
                            onDecline = { viewModel.declineInvite(invite.id) },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(14.dp)) }
                }

                if (pinned.isNotEmpty() && query.isBlank()) {
                    item { DrawerSectionHeader("Quick Rooms") }
                    item {
                        chat.keryx.app.presentation.ui.components.QuickRoomsDeck(
                            rooms = pinned,
                            selectedRoomId = currentSession?.id,
                            onRoomClick = { onSessionSelected(sessionFor(it)) },
                            avatarLoader = { viewModel.loadAvatar(it) },
                            // Long-press a Quick Room to pin/unpin it — consistent with the
                            // room list below. Setting a room photo lives on the avatar
                            // long-press in the main list, so the two no longer collide.
                            onRoomLongPress = { room ->
                                viewModel.togglePin(room.id)
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }

                if (listRooms.isNotEmpty() || rooms.isEmpty() || query.isNotBlank()) {
                    item { DrawerSectionHeader(if (query.isBlank()) "Rooms" else "Results") }
                }
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
                items(listRooms, key = { it.id }) { room ->
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
                        onLeave = { viewModel.leaveRoom(room.id) },
                        avatarLoader = { viewModel.loadAvatar(it) },
                        previewLoader = { viewModel.roomPreview(room.id, room.timestamp) },
                    )
                }
            }

            // Bottom bar — theme toggle and settings side by side (was two
            // full-width stacked rows; this halves the footer's height).
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val themeIcon = when (isDarkTheme) {
                    true -> Icons.Default.LightMode
                    false -> Icons.Default.BrightnessAuto
                    null -> Icons.Default.DarkMode
                }
                val themeText = when (isDarkTheme) {
                    true -> "Light"
                    false -> "System"
                    null -> "Dark"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val next = when (isDarkTheme) {
                                null -> true
                                true -> false
                                false -> null
                            }
                            viewModel.toggleTheme(next)
                        }
                        .padding(vertical = 12.dp),
                ) {
                    Icon(
                        themeIcon, contentDescription = "Theme",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // maxLines + softWrap=false: the three equal-width cells get tight at large
                    // font scales, and a borderline fit must never wrap the label's last letter.
                    Text(themeText, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                        maxLines = 1, softWrap = false)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showMissions = true }
                        .padding(vertical = 12.dp),
                ) {
                    Icon(
                        Icons.Default.ViewKanban, contentDescription = "Missions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Missions", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                        maxLines = 1, softWrap = false)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSettings = true }
                        .padding(vertical = 12.dp),
                ) {
                    Icon(
                        Icons.Default.Settings, contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                        maxLines = 1, softWrap = false)
                }
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
    onLeave: (() -> Unit)? = null,
    avatarLoader: suspend (String) -> ByteArray?,
    previewLoader: (suspend () -> String?)? = null,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Long-press menu (pin/unpin + leave). Replaced the instant pin toggle once leaving rooms
    // became possible — two destructive-adjacent actions can't share one blind gesture.
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // Last-message snippet, resolved lazily per row (cached in the VM keyed on room.timestamp so
    // it only refetches after new activity). Keyed on the timestamp so a new message refreshes it.
    val preview by produceState<String?>(initialValue = null, room.id, room.timestamp) {
        value = previewLoader?.invoke()
    }
    Box {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (onLeave != null) menuOpen = true else onTogglePin()
                },
            )
            .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
    ) {
        // Long-press the avatar (specifically) to set a room photo.
        Box(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onSetAvatar,
            )
        ) {
            RoomAvatar(room = room, selected = isSelected, avatarLoader = avatarLoader)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = room.name,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isPinned) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            // The most recent message, like a real chat client's room list. Unread rooms read a
            // touch brighter so "something new here" is visible before the count even registers.
            preview?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (room.unreadCount > 0L) 0.95f else 0.65f
                    ),
                    fontSize = 12.sp,
                    fontWeight = if (room.unreadCount > 0L) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
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
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(if (isPinned) "Unpin from Quick Rooms" else "Pin to Quick Rooms") },
            onClick = { menuOpen = false; onTogglePin() },
        )
        DropdownMenuItem(
            text = { Text("Leave room", color = MaterialTheme.colorScheme.error) },
            onClick = { menuOpen = false; confirmLeave = true },
        )
    }
    } // end anchor Box
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${room.name}?", fontSize = 16.sp) },
            text = { Text("You'll stop receiving its messages; rejoining needs a new invite.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onLeave?.invoke() }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            },
        )
    }
}

/** One pending invitation: room name + the accept/decline decision, right in the drawer. */
@Composable
private fun InviteRow(
    invite: RoomInvite,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = invite.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "You've been invited to this room",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDecline) {
                Text("Decline", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            TextButton(onClick = onAccept) {
                Text("Accept", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
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
