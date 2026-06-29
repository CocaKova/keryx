package chat.keryx.app.presentation.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.MessageReaction
import chat.keryx.app.domain.model.RoomType
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.HermesThinkingAnimation
import chat.keryx.app.presentation.ui.components.MessageContent
import chat.keryx.app.presentation.ui.components.MessageMedia
import chat.keryx.app.presentation.ui.components.ToolActivityCard
import chat.keryx.app.presentation.ui.components.ToolGroupCard
import chat.keryx.app.presentation.ui.components.bubbleAppearance
import chat.keryx.app.presentation.ui.components.groupChatItems
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** An attachment the user has picked but not yet sent. */
private data class PendingAttachment(
    val bytes: ByteArray,
    val name: String,
    val contentType: String,
    val isImage: Boolean,
)

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🙏", "🔥", "👀", "✅")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val rooms by viewModel.rooms.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val bubbleStyle by viewModel.bubbleStyle.collectAsState()
    val messageTextScale by viewModel.messageTextScale.collectAsState()
    val awaitingReply by viewModel.awaitingReply.collectAsState()
    val workStartedAt by viewModel.workStartedAt.collectAsState()
    val workLabel by viewModel.workLabel.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val composerPrefill by viewModel.composerPrefill.collectAsState()

    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var composerHeightPx by remember { mutableStateOf(0) }

    fun stageFromUri(uri: android.net.Uri?, forceImage: Boolean) {
        if (uri == null) return
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return
        val type = context.contentResolver.getType(uri) ?: if (forceImage) "image/jpeg" else "application/octet-stream"
        val name = queryDisplayName(context, uri)
        pendingAttachment = PendingAttachment(bytes, name, type, isImage = forceImage || type.startsWith("image"))
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        stageFromUri(uri, forceImage = true)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        stageFromUri(uri, forceImage = false)
    }

    // Composer state
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val commandMenuVisible by viewModel.commandMenuVisible.collectAsState()
    val recentCommands by viewModel.recentCommands.collectAsState()
    val commandFilter by viewModel.commandFilter.collectAsState()

    val isGroupRoom = rooms.firstOrNull { it.id == currentSession?.id }?.type == RoomType.SHARED_GROUP
    // reverseLayout: index 0 is the newest message, pinned to the bottom.
    val ordered = messages.asReversed()
    val byId = remember(messages) { messages.associateBy { it.id } }
    // Collapse runs of consecutive tool-only messages into one expandable "Ran N tools" group.
    val renderItems = remember(messages) { groupChatItems(ordered) }

    // Drop a Steer (or other) prefill into the composer and focus it.
    LaunchedEffect(composerPrefill) {
        composerPrefill?.let { prefill ->
            textState = TextFieldValue(prefill, selection = TextRange(prefill.length))
            viewModel.onComposerTextChanged(prefill)
            runCatching { focusRequester.requestFocus() }
            viewModel.consumeComposerPrefill()
        }
    }

    // Follow new messages / edits when the user is at (or near) the bottom, or the new message is
    // our own. Keyed on a signature that also changes on streamed edits (content length / tool state)
    // so long "working" updates still pull the view down.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val lastSignature = messages.lastOrNull()?.let { "${it.id}:${it.content.length}:${it.toolActivity?.status?.name ?: ""}" }
    LaunchedEffect(lastSignature, awaitingReply) {
        val mine = messages.lastOrNull()?.sender == SenderType.ME
        if (atBottom || mine) listState.animateScrollToItem(0)
    }

    // Pagination: when the oldest loaded item scrolls into view, request more history.
    LaunchedEffect(listState, renderItems.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (renderItems.isNotEmpty() && lastVisible >= renderItems.size - 3) {
                    viewModel.loadOlderMessages()
                }
            }
    }

    // Send a read receipt for the latest message while viewing this room (clears unread).
    LaunchedEffect(currentSession?.id, messages.lastOrNull()?.id) {
        val roomId = currentSession?.id
        val lastId = messages.lastOrNull()?.id
        if (roomId != null && lastId != null) viewModel.markRoomRead(roomId, lastId)
    }

    fun doSend() {
        val attachment = pendingAttachment
        val text = textState.text
        if (attachment != null) {
            viewModel.sendAttachment(attachment.bytes, attachment.name, attachment.contentType)
            pendingAttachment = null
        }
        if (text.isNotBlank()) {
            if (text.startsWith("/")) viewModel.recordCommandUse(text)
            viewModel.sendMessage(text)
            textState = TextFieldValue("")
        }
    }

    // Background is supplied app-wide (gradient lives in HermesApp); keep this surface transparent.
    Box(modifier = modifier.fillMaxSize().imePadding()) {
        if (currentSession == null) {
            EmptyChat(modifier = Modifier.align(Alignment.Center))
        }
        // Reserve space at the bottom equal to the (growing) composer height so messages never
        // slide underneath it as the user types a multi-line message.
        val bottomReserve = with(density) { composerHeightPx.toDp() } + 28.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(top = 16.dp, bottom = bottomReserve, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // reverseLayout: this first item sits at the very bottom, below the newest message.
            if (awaitingReply) {
                item(key = "waiting") { WaitingIndicator() }
            }
            itemsIndexed(
                items = renderItems,
                key = { _, item -> item.key }
            ) { index, item ->
                Box(modifier = Modifier.animateItem()) {
                    when (item) {
                        is ChatRenderItem.ToolRun -> ToolGroupCard(
                            run = item,
                            // The newest item (index 0 under reverseLayout) is "running" while we
                            // still await Hermes' reply; older runs are settled ("Ran N tools").
                            active = index == 0 && awaitingReply,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            // Predictable anchor: when opened, pin the group to a known spot so the
                            // viewport never jumps unpredictably as the accordion grows.
                            onToggle = { isExpanded ->
                                if (isExpanded) scope.launch { listState.animateScrollToItem(index) }
                            },
                        )
                        is ChatRenderItem.Single -> {
                            val message = item.message
                            // Live reactions: updates the moment anyone adds/removes one — no manual refresh.
                            val reactionsFlow = remember(message.id) {
                                viewModel.reactionsFlow(message.sessionId, message.id)
                            }
                            MessageBubble(
                                message = message,
                                replyTo = message.replyToId?.let { byId[it] },
                                bubbleStyle = bubbleStyle,
                                textScale = messageTextScale,
                                showSender = isGroupRoom,
                                reactionsFlow = reactionsFlow,
                                // Resolve media by event id in the repo, which handles both plaintext
                                // (mxc) and E2EE-encrypted files and falls back to the thumbnail.
                                mediaLoader = { viewModel.loadMessageMedia(message.sessionId, message.id) },
                                onReply = { viewModel.setReplyTarget(message) },
                                onReact = { emoji -> viewModel.sendReaction(message.id, emoji) },
                            )
                        }
                    }
                }
            }
        }

        // Floating Command Palette
        AnimatedVisibility(
            visible = commandMenuVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
        ) {
            CommandPaletteMenu(
                filter = commandFilter,
                recents = recentCommands,
                onCommandSelected = { command, takesArgs ->
                    if (takesArgs) {
                        // Fill the composer and let the user type arguments (palette hides on the space).
                        val withSpace = "$command "
                        textState = TextFieldValue(withSpace, selection = TextRange(withSpace.length))
                        viewModel.onComposerTextChanged(withSpace)
                        runCatching { focusRequester.requestFocus() }
                    } else {
                        // No arguments -> send immediately and clear.
                        viewModel.recordCommandUse(command)
                        viewModel.sendMessage(command)
                        textState = TextFieldValue("")
                        viewModel.onComposerTextChanged("")
                    }
                }
            )
        }

        // Composer column: optional reply bar + attachment preview, then the pill.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .fillMaxWidth()
                .onSizeChanged { composerHeightPx = it.height },
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = replyTarget != null) {
                replyTarget?.let { target ->
                    Column {
                        ReplyBar(target = target, onDismiss = { viewModel.clearReplyTarget() })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = pendingAttachment != null) {
                pendingAttachment?.let { att ->
                    Column {
                        AttachmentPreview(att, onRemove = { pendingAttachment = null })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            Composer(
                textState = textState,
                onTextChange = { textState = it; viewModel.onComposerTextChanged(it.text) },
                onSend = ::doSend,
                onPickGallery = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickFile = { filePicker.launch("*/*") },
                atBottom = atBottom,
                hasMessages = messages.isNotEmpty(),
                onFocusedAtBottom = { scope.launch { listState.animateScrollToItem(0) } },
                focusRequester = focusRequester,
            )
        }

        // Compact top "working" counter: a small spinner + what the agent is doing + elapsed clock.
        // Pinned at the top so it stays put for the whole run, unlike the per-message tool labels.
        WorkingStatusBar(
            visible = awaitingReply,
            label = workLabel,
            startedAt = workStartedAt,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        )
    }
}

@Composable
private fun WorkingStatusBar(
    visible: Boolean,
    label: String,
    startedAt: Long?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier,
    ) {
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(startedAt) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
        }
        val elapsed = startedAt?.let { ((now - it).coerceAtLeast(0L)) / 1000 } ?: 0L
        val clock = "${elapsed / 60}:${"%02d".format(elapsed % 60)}"
        val accent = MaterialTheme.colorScheme.primary
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shadowElevation = 6.dp,
            modifier = Modifier.border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(50)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$label · $clock",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    textState: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    atBottom: Boolean,
    hasMessages: Boolean,
    onFocusedAtBottom: () -> Unit,
    focusRequester: FocusRequester,
) {
    var attachMenu by remember { mutableStateOf(false) }
    // The dream attach options bloom in just above the composer pill (rendered inline rather than in
    // a Popup — Popup positioning at the screen edge was unreliable and hid the menu entirely).
    Column {
        DreamAttachBloom(
            visible = attachMenu,
            onPhoto = { attachMenu = false; onPickGallery() },
            onFile = { attachMenu = false; onPickFile() },
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box {
            // The + gently rotates to an × while the dream menu is open.
            val addRotation by animateFloatAsState(
                targetValue = if (attachMenu) 135f else 0f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
                label = "add_rotate",
            )
            IconButton(onClick = { attachMenu = !attachMenu }, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = addRotation },
                )
            }
        }
        OutlinedTextField(
            value = textState,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (focus.isFocused && hasMessages && atBottom) onFocusedAtBottom()
                },
            placeholder = { Text("Message…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.width(6.dp))
        FloatingActionButton(
            onClick = onSend,
            containerColor = MaterialTheme.colorScheme.primary,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(50)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
        }
    }
    } // end Column (attach bloom + composer row)
}

@Composable
private fun ReplyBar(target: Message, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .height(IntrinsicSize.Min)
            .padding(end = 4.dp),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Replying to ${shortSender(target.senderName)}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = replyPreviewText(target),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachmentPreview(att: PendingAttachment, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(if (att.isImage) "🖼" else "📎", fontSize = 18.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = att.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    replyTo: Message?,
    bubbleStyle: String,
    textScale: Float,
    showSender: Boolean,
    reactionsFlow: kotlinx.coroutines.flow.Flow<List<MessageReaction>>,
    mediaLoader: suspend () -> ByteArray?,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
) {
    val isMine = message.sender == SenderType.ME
    val isAgent = message.sender == SenderType.HERMES
    var showReactionPicker by remember { mutableStateOf(false) }

    val reactions by reactionsFlow.collectAsState(initial = emptyList())

    Column(
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showSender && !isMine && message.senderName.isNotBlank()) {
            Text(
                text = shortSender(message.senderName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }

        if (isAgent && message.toolActivity != null) {
            ToolActivityCard(toolActivity = message.toolActivity)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (message.isStreaming && message.content.isEmpty() && message.mediaKind == null) {
            HermesThinkingAnimation(style = "Braille", modifier = Modifier.padding(8.dp))
        } else if (message.content.isNotEmpty() || message.mediaKind != null) {
            val appearance = bubbleAppearance(isMine, bubbleStyle)
            val shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            )
            val baseDensity = LocalDensity.current
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(shape)
                    .background(appearance.brush)
                    .then(
                        if (appearance.border != null) Modifier.border(1.dp, appearance.border, shape)
                        else Modifier
                    )
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showReactionPicker = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (replyTo != null) ReplyQuote(replyTo, appearance.textColor)
                    val mediaKind = message.mediaKind
                    if (mediaKind != null) {
                        MessageMedia(
                            loadKey = message.id,
                            kind = mediaKind,
                            fileName = message.fileName,
                            textColor = appearance.textColor,
                            loader = mediaLoader,
                        )
                    } else {
                        CompositionLocalProvider(
                            LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
                        ) {
                            MessageContent(
                                content = message.content,
                                textColor = appearance.textColor,
                                isStreaming = message.isStreaming,
                            )
                        }
                    }
                }
            }
        }

        if (showReactionPicker) {
            ReactionPickerRow(
                onPick = { emoji -> showReactionPicker = false; onReact(emoji) },
                onReply = { showReactionPicker = false; onReply() },
                onDismiss = { showReactionPicker = false },
            )
        }

        if (reactions.isNotEmpty()) {
            ReactionChips(reactions, isMine, onReact)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)) {
            if (message.timestamp > 0L) {
                Text(
                    text = formatClock(message.timestamp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
            }
            if (isMine) {
                Spacer(modifier = Modifier.width(4.dp))
                // Sent indicator (Element-style). The message is a real timeline event, so it's delivered.
                Text("✓", color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ReplyQuote(replyTo: Message, textColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .height(IntrinsicSize.Min),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accent.copy(alpha = 0.7f)))
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(shortSender(replyTo.senderName), color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = replyPreviewText(replyTo),
                color = textColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReactionChips(reactions: List<MessageReaction>, isMine: Boolean, onReact: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        reactions.forEach { r ->
            val bg = if (r.mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceVariant
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onReact(r.emoji) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(r.emoji, fontSize = 13.sp)
                if (r.count > 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        r.count.toString(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerRow(onPick: (String) -> Unit, onReply: () -> Unit, onDismiss: () -> Unit) {
    // A focusable Popup so a tap anywhere outside (or the back gesture) reliably dismisses it —
    // the inline version was hard to get rid of once it was up.
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        // Dream reveal: the bar blooms up from its lower edge with a soft spring + fade, rather than
        // snapping in like a stock menu. Each emoji then settles in with a gentle staggered scale.
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        val accent = MaterialTheme.colorScheme.primary
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(animationSpec = tween(180)) +
                scaleIn(
                    initialScale = 0.82f,
                    transformOrigin = TransformOrigin(0.15f, 1f),
                    animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
                ),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                // Translucent, faintly accent-tinted "frosted" fill for the dream aesthetic.
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.10f)),
                        ),
                        shape = RoundedCornerShape(22.dp),
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    QUICK_REACTIONS.forEachIndexed { i, emoji ->
                        var shown by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { kotlinx.coroutines.delay(40L * i); shown = true }
                        val scale by animateFloatAsState(
                            targetValue = if (shown) 1f else 0.4f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                            label = "emoji_pop",
                        )
                        Text(
                            emoji,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0f, 1f) }
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(emoji) }
                                .padding(4.dp),
                        )
                    }
                    Box(modifier = Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
                    IconButton(onClick = onReply, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DreamAttachBloom(visible: Boolean, onPhoto: () -> Unit, onFile: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    AnimatedVisibility(
        visible = visible,
        // Rises and blooms up out of the composer rather than dropping down like a stock menu.
        enter = fadeIn(tween(160)) + expandVertically(
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            expandFrom = Alignment.Bottom,
        ) + scaleIn(initialScale = 0.85f, transformOrigin = TransformOrigin(0.12f, 1f)),
        exit = fadeOut(tween(120)) + shrinkVertically(shrinkTowards = Alignment.Bottom) + scaleOut(targetScale = 0.9f),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
        ) {
            DreamPill("Photo", Icons.Default.Image, accent, delayMs = 0) { onPhoto() }
            DreamPill("File", Icons.Default.AttachFile, accent, delayMs = 55) { onFile() }
        }
    }
}

@Composable
private fun DreamPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    delayMs: Long,
    onClick: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(delayMs); shown = true }
    val t by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pill",
    )
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
        modifier = Modifier
            .graphicsLayer {
                alpha = t
                translationY = (1f - t) * 24f
                scaleX = 0.85f + 0.15f * t
                scaleY = 0.85f + 0.15f * t
            }
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.10f))), shape)
            .clip(shape)
            .clickable { onClick() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun replyPreviewText(m: Message): String = when {
    m.content.isNotBlank() -> m.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: m.content.trim()
    m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "attachment" }}"
    else -> "message"
}

private fun shortSender(id: String): String = id.trimStart('@').substringBefore(':')

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "attachment"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { name = it }
            }
        }
    }
    return name
}

private fun formatClock(ts: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(120.dp)) {
            chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Select a room to begin",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun WaitingIndicator() {
    val quips = remember {
        listOf(
            "Dispatching the herald…",
            "Winging your reply…",
            "Crossing the aether…",
            "Consulting the oracle…",
            "Tracing the threads…",
            "Summoning a thought…",
            "Lacing the sandals…",
            "Reading the entrails…",
            "Bribing the muses…",
            "Negotiating with the tokens…",
            "Untangling the timeline…",
            "Polishing the prophecy…",
            "Chasing a stray idea…",
            "Asking the rubber duck…",
            "Aligning the constellations…",
            "Warming up the wings…",
            "Sifting the context…",
            "Whispering to the weights…",
            // — expanded —
            "Folding the probability space…",
            "Tuning the inner monologue…",
            "Wandering the latent space…",
            "Courting a better metaphor…",
            "Auditing the assumptions…",
            "Stitching the argument together…",
            "Letting the idea steep…",
            "Listening for the signal…",
            "Sketching it in the margins…",
            "Counting the right syllables…",
            "Threading the needle…",
            "Coaxing the tokens out…",
            "Reconciling the contradictions…",
            "Pacing the reasoning floor…",
            "Decanting the nuance…",
            "Cross-checking the lore…",
            "Drafting, then redrafting…",
            "Easing past the tangents…",
            "Composing in the quiet…",
            "Catching the dropped thread…",
            "Sanding down the rough edges…",
            "Reaching for the precise word…",
        )
    }
    var idx by remember { mutableStateOf(kotlin.random.Random.nextInt(quips.size)) }
    LaunchedEffect(Unit) {
        while (true) {
            // Slow, contemplative rotation — long enough to actually read each one.
            kotlinx.coroutines.delay(6800)
            idx = (idx + 1) % quips.size
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(40.dp)) {
            chat.keryx.app.presentation.ui.components.BrailleSnakeAnimation(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
                snakeLength = 10,
                periodMillis = 1500,
                glyphSize = 7f,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        androidx.compose.animation.AnimatedContent(targetState = idx, label = "quip") { i ->
            Text(
                text = quips[i],
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 13.sp,
            )
        }
    }
}

private data class SlashCommand(val cmd: String, val desc: String, val takesArgs: Boolean)

@Composable
fun CommandPaletteMenu(
    filter: String,
    recents: List<String>,
    onCommandSelected: (String, Boolean) -> Unit,
) {
    // Common Hermes slash commands; `takesArgs` commands fill the composer, the rest auto-send.
    val all = remember {
        listOf(
            SlashCommand("/new", "Start a fresh conversation", false),
            SlashCommand("/compress", "Compress / summarize this thread", false),
            SlashCommand("/handoff", "Hand off context to a new session", false),
            SlashCommand("/steer", "Steer the agent mid-task", true),
            SlashCommand("/think", "Ask for deeper reasoning", true),
            SlashCommand("/model", "Switch the active model", true),
            SlashCommand("/reset", "Reset the agent's working state", false),
            SlashCommand("/help", "List what this agent can do", false),
            SlashCommand("/status", "Show agent + system status", false),
            SlashCommand("/memory", "Recall or edit long-term memory", true),
            SlashCommand("/tools", "List available tools", false),
        )
    }
    val q = filter.trim().lowercase()
    val matches = all.filter { q.isBlank() || it.cmd.removePrefix("/").startsWith(q) }
    // Surface recently-used commands first.
    val ordered = matches.sortedByDescending { recents.indexOf(it.cmd).let { i -> if (i < 0) -1 else recents.size - i } }

    if (ordered.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            items(ordered, key = { it.cmd }) { sc ->
                val isRecent = sc.cmd in recents
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommandSelected(sc.cmd, sc.takesArgs) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sc.cmd, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (sc.takesArgs) {
                                Spacer(Modifier.width(6.dp))
                                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                            }
                        }
                        Text(sc.desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (isRecent) Text("recent", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
        }
    }
}
