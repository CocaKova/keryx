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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.RoomInvite
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomSigil
import chat.keryx.core.model.RoomSigils
import chat.keryx.core.model.RoomType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.RoomSigilAvatar
import chat.keryx.app.theme.*

@Composable
fun NavigationDrawerContent(
    viewModel: ChatViewModel,
    onRoomSelected: (RoomProfile) -> Unit,
    // Full-screen places (Missions, Archive) open on the nav stack owned by the host — the
    // drawer only asks; it never composes a space itself (2.0 Phase 1).
    onOpenSpace: (chat.keryx.app.presentation.ui.nav.KeryxDest) -> Unit,
    // ModalNavigationDrawer composes its drawer content even while closed (just translated
    // offscreen), so anything permanently animated in here would burn frames invisibly. The host
    // passes the drawer's real visibility so ornament only runs while it can be seen.
    drawerVisible: Boolean = true,
) {
    val rooms by viewModel.rooms.collectAsState()
    val pinnedRoomIds by viewModel.pinnedRoomIds.collectAsState()
    // "Move to project…" — explicit projects with a folder (membership is cwd).
    val moveTargets by viewModel.projects.projectMoveTargets.collectAsState()
    val moveCtx = LocalContext.current
    val currentUserId by viewModel.currentUserId.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()

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
    
    // Settings is a nav destination now (2.0 Phase 4) — see SettingsPlace.
    
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
                    val petInfo by viewModel.pet.petInfo.collectAsState()
                    val awaitingReply by viewModel.awaitingReply.collectAsState()
                    var petGreeting by remember { mutableStateOf(false) }
                    LaunchedEffect(drawerVisible) {
                        if (drawerVisible) {
                            viewModel.pet.refreshPet()
                            // The Projects door's probe (no-op on Matrix).
                            viewModel.projects.refreshProjects()
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
                                    .clickable { viewModel.pet.refreshPetGallery(); showPetPicker = true },
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
                Spacer(modifier = Modifier.weight(1f))
                // The drawer's ONE new-conversation entry point: DM / create / join, in a sheet.
                var showNewChat by remember { mutableStateOf(false) }
                IconButton(onClick = { showNewChat = true }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (showNewChat) {
                    chat.keryx.app.presentation.ui.components.NewChatSheet(
                        viewModel = viewModel,
                        onDismiss = { showNewChat = false },
                    )
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

            // Deep search rides the same field on the direct door: after a typing pause the
            // gateway's server-side FTS answers over message CONTENT, which the local title
            // filter can never see. Debounced so scrolling through a query doesn't spray
            // requests; cleared with the query so stale hits never linger.
            var deepHits by remember { mutableStateOf<List<chat.keryx.core.transport.SessionSearchHit>>(emptyList()) }
            LaunchedEffect(query) {
                if (viewModel.transportIsDirect && query.trim().length >= 2) {
                    kotlinx.coroutines.delay(350)
                    deepHits = viewModel.searchGatewaySessions(query.trim())
                } else {
                    deepHits = emptyList()
                }
            }

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
                            selectedRoomId = currentRoom?.id,
                            onRoomClick = { onRoomSelected(it) },
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
                val direct = viewModel.transportIsDirect
                items(listRooms, key = { it.id }) { room ->
                    RoomRow(
                        room = room,
                        isSelected = currentRoom?.id == room.id,
                        isPinned = room.id in pinnedRoomIds,
                        onClick = { onRoomSelected(room) },
                        onTogglePin = { viewModel.togglePin(room.id) },
                        // Each transport brings its own verbs to the long-press menu: Matrix
                        // rooms have membership and a server-side avatar; gateway sessions
                        // rename and delete (there is nothing to "leave").
                        onSetAvatar = if (direct) null else {
                            {
                                pendingAvatarRoomId = room.id
                                avatarPicker.launch("image/*")
                            }
                        },
                        onLeave = if (direct) null else {
                            { viewModel.leaveRoom(room.id) }
                        },
                        onInvite = if (direct) null else {
                            { userId -> viewModel.inviteUser(room.id, userId) }
                        },
                        onRename = if (direct) {
                            { title -> viewModel.renameSession(room.id, title) }
                        } else null,
                        onDelete = if (direct) {
                            { viewModel.deleteSession(room.id) }
                        } else null,
                        moveTargets = if (direct) moveTargets else emptyList(),
                        onMoveToProject = if (direct) {
                            { target ->
                                viewModel.projects.moveSessionToProject(room.id, target) { err ->
                                    android.widget.Toast.makeText(
                                        moveCtx, err ?: "Moved to ${target.name}", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } else null,
                        avatarLoader = { viewModel.loadAvatar(it) },
                        previewLoader = { viewModel.roomPreview(room.id, room.timestamp) },
                    )
                }

                // Deep search (direct door): the gateway's FTS over transcript CONTENT — the
                // thing the title filter above can never answer. Only sessions the local list
                // didn't already match, so the two sections never show the same row twice.
                if (deepHits.isNotEmpty()) {
                    val shown = filtered.mapTo(HashSet()) { it.id }
                    val extras = deepHits.filterNot { it.sessionId in shown }
                    if (extras.isNotEmpty()) {
                        item { DrawerSectionHeader("In transcripts") }
                        items(extras, key = { "hit-${it.sessionId}" }) { hit ->
                            SearchHitRow(hit = hit, onClick = {
                                val room = rooms.firstOrNull { it.id == hit.sessionId }
                                    ?: RoomProfile(
                                        id = hit.sessionId,
                                        name = hit.title,
                                        type = RoomType.DIRECT_MESSAGE,
                                        timestamp = hit.lastActive,
                                    )
                                onRoomSelected(room)
                            })
                        }
                    }
                }
            }

            // Bottom bar — theme toggle and settings side by side (was two
            // full-width stacked rows; this halves the footer's height).
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

            // The doors. Six of these now share the footer, and as one Row of equal weights the
            // labels were already stepping their font down to fit at four ("Mission" losing its
            // s). A wrapping row of thirds keeps every door the same size no matter how many
            // there are — the next one costs a list entry, not a re-layout.
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 3,
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
                DrawerDoor(themeIcon, themeText, Modifier.weight(1f)) {
                    viewModel.toggleTheme(
                        when (isDarkTheme) {
                            null -> true
                            true -> false
                            false -> null
                        }
                    )
                }
                DrawerDoor(Icons.Default.ViewKanban, "Missions", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Missions)
                }
                DrawerDoor(Icons.Default.AutoStories, "Archive", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Archive)
                }
                // Only where the gateway serves projects.* — the probe is the overview fetch.
                val hasProjects by viewModel.projects.hasProjects.collectAsState()
                if (hasProjects) DrawerDoor(Icons.Default.Folder, "Projects", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Projects)
                }
                DrawerDoor(Icons.Default.Dns, "Gateway", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Gateway)
                }
                DrawerDoor(Icons.Default.Handyman, "Workshop", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Workshop)
                }
                DrawerDoor(Icons.Default.Settings, "Settings", Modifier.weight(1f)) {
                    onOpenSpace(chat.keryx.app.presentation.ui.nav.KeryxDest.Settings)
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
    // Matrix-only affordances (m.room.avatar / membership) — null on the direct door.
    onSetAvatar: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
    onInvite: ((String) -> Unit)? = null,
    // Gateway-only affordances (the row IS a session) — null on Matrix.
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Projects that can claim this session (they have a folder); empty = no menu entry. */
    moveTargets: List<chat.keryx.core.model.ProjectInfo> = emptyList(),
    onMoveToProject: ((chat.keryx.core.model.ProjectInfo) -> Unit)? = null,
    avatarLoader: suspend (String) -> ByteArray?,
    previewLoader: (suspend () -> String?)? = null,
) {
    val haptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
    // Long-press menu (pin/unpin + the transport's own verbs). Replaced the instant pin toggle
    // once leaving rooms became possible — two destructive-adjacent actions can't share one
    // blind gesture.
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    val hasMenu = onLeave != null || onInvite != null || onRename != null || onDelete != null
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
                    haptics.press()
                    if (hasMenu) menuOpen = true else onTogglePin()
                },
            )
            .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)
    ) {
        // Long-press the avatar (specifically) to set a room photo — Matrix only.
        Box(
            modifier = if (onSetAvatar != null) Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = onSetAvatar,
            ) else Modifier
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
        if (onInvite != null) {
            DropdownMenuItem(
                text = { Text("Invite user…") },
                onClick = { menuOpen = false; inviteOpen = true },
            )
        }
        if (onRename != null) {
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = { menuOpen = false; renameOpen = true },
            )
        }
        if (onMoveToProject != null && moveTargets.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text("Move to project…") },
                onClick = { menuOpen = false; moveOpen = true },
            )
        }
        if (onLeave != null) {
            DropdownMenuItem(
                text = { Text("Leave room", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmLeave = true },
            )
        }
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text("Delete session…", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmDelete = true },
            )
        }
    }
    // The project roster, as a second menu on the same anchor (a submenu would be a
    // nested popup; this reads as "which one?" and dismisses like the first).
    DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
        moveTargets.forEach { target ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(target.name)
                        target.anchorPath?.let {
                            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                },
                onClick = { moveOpen = false; onMoveToProject?.invoke(target) },
            )
        }
    }
    } // end anchor Box
    if (inviteOpen && onInvite != null) {
        var inviteId by remember { mutableStateOf("") }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { inviteOpen = false },
            title = { Text("Invite to ${room.name}", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = inviteId,
                    onValueChange = { inviteId = it },
                    placeholder = { Text("@user:server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = inviteId.isNotBlank(),
                    onClick = { inviteOpen = false; onInvite(inviteId.trim()) },
                ) { Text("Invite") }
            },
            dismissButton = {
                TextButton(onClick = { inviteOpen = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
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
    if (renameOpen && onRename != null) {
        var newTitle by remember { mutableStateOf(room.name) }
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename session", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank() && newTitle.trim() != room.name,
                    onClick = { renameOpen = false; onRename(newTitle.trim()) },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(KeryxRadius.sheet),
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${room.name}?", fontSize = 16.sp) },
            text = { Text("Deletes the session and its whole transcript from the gateway. This can't be undone.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
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
 * One gateway deep-search hit: the session title over the transcript line that matched, with
 * the server's `>>>…<<<` match markers rendered as emphasis instead of shown raw.
 */
@Composable
private fun SearchHitRow(
    hit: chat.keryx.core.transport.SessionSearchHit,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val snippet = remember(hit.snippet, accent) {
        androidx.compose.ui.text.buildAnnotatedString {
            var rest = hit.snippet.replace('\n', ' ')
            while (true) {
                val s = rest.indexOf(">>>")
                val e = if (s >= 0) rest.indexOf("<<<", s + 3) else -1
                if (s < 0 || e < 0) { append(rest); break }
                append(rest.substring(0, s))
                withStyle(
                    androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)
                ) { append(rest.substring(s + 3, e)) }
                rest = rest.substring(e + 3)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hit.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hit.lastActive > 0L) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatRelativeTime(hit.lastActive),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            text = snippet,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
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
        val sigil = RoomSigils.of(room.heraldIds)
        if (sigil != RoomSigil.None) {
            RoomSigilAvatar(sigil = sigil, base = base, size = 34.dp, highlighted = selected)
        } else {
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
}

// The palette lives in RoomLight.kt — it was duplicated here and in the Quick Rooms deck.
private fun roomAvatarColor(name: String): Color =
    chat.keryx.app.presentation.ui.components.roomLightRaw(name)

/** Compact relative timestamp: now, 5m, 3h, 2d, 1w. */
internal fun formatRelativeTime(ts: Long): String {
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

/**
 * One door in the drawer's footer. Was four near-identical 18-line blocks; the auto-sizing label
 * is the part worth keeping — a fixed size clips ("Mission" lost its s at large font scales) and
 * wrapping makes the cells different heights.
 */
@Composable
private fun DrawerDoor(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(
            icon, contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label, color = MaterialTheme.colorScheme.onSurface,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp, stepSize = 0.25.sp),
            maxLines = 1, softWrap = false,
        )
    }
}
