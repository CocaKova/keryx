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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
    val liveStream by viewModel.liveStream.collectAsState()
    val pendingSend by viewModel.pendingSend.collectAsState()
    val showTelemetry by viewModel.showTelemetry.collectAsState()
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

    // Restore this room's unsent draft when it opens (and swap drafts when switching rooms) so
    // half-typed thoughts survive room hops and app restarts.
    LaunchedEffect(currentSession?.id) {
        val roomId = currentSession?.id ?: return@LaunchedEffect
        val draft = viewModel.draftFor(roomId)
        textState = TextFieldValue(draft, selection = TextRange(draft.length))
        viewModel.onComposerTextChanged(draft)
    }

    // Dream dissolve on room switch: the timeline re-materializes through a soft blur+fade
    // while a wisp of braille glyphs drifts across and scatters — the app's signature
    // "crossing rooms in a dream" beat. Skipped on first open (no jarring boot blur).
    var lastRoomForDissolve by remember { mutableStateOf<String?>(null) }
    val dissolve = remember { Animatable(1f) }
    LaunchedEffect(currentSession?.id) {
        val id = currentSession?.id
        if (lastRoomForDissolve != null && id != null && id != lastRoomForDissolve) {
            dissolve.snapTo(0f)
            // Hold while the drawer clears the stage — the old 560ms version played almost
            // entirely BEHIND the closing drawer, which is why it read as "a slight blur".
            kotlinx.coroutines.delay(230)
            dissolve.animateTo(1f, tween(1050, easing = LinearOutSlowInEasing))
        }
        lastRoomForDissolve = id
    }

    // Drop a Steer (or other) prefill into the composer and focus it.
    LaunchedEffect(composerPrefill) {
        composerPrefill?.let { prefill ->
            textState = TextFieldValue(prefill, selection = TextRange(prefill.length))
            viewModel.onComposerTextChanged(prefill)
            runCatching { focusRequester.requestFocus() }
            viewModel.consumeComposerPrefill()
        }
    }

    // Follow new messages / edits ONLY while the user is actually at the bottom. Two past bugs
    // live here: (1) "at bottom" must be index 0 with a small pixel offset — `index <= 1` stayed
    // true a full screen up inside the tall growing stream bubble; (2) the old "or the last
    // message is mine" clause locked scrolling for entire streamed turns, because while the agent
    // streams the newest COMMITTED message is your own command — every 100ms token dispatch
    // yanked the list back down. Own sends get their own effect below instead.
    val bottomThresholdPx = with(density) { 56.dp.toPx() }
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= bottomThresholdPx
        }
    }
    // Signature changes on streamed edits AND live side-channel tokens (content length / tool
    // state / stream length) so the view keeps following the stream while pinned.
    val lastSignature = (messages.lastOrNull()?.let { "${it.id}:${it.content.length}:${it.toolActivity?.status?.name ?: ""}" } ?: "") +
        ":${liveStream?.text?.length ?: 0}"
    LaunchedEffect(lastSignature, awaitingReply) {
        // Never fight an active user drag/fling — that's what made scroll-up impossible mid-stream.
        if (atBottom && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    // A message I just sent always snaps to the newest, wherever I was scrolled.
    val lastMineId = messages.lastOrNull()?.takeIf { it.sender == SenderType.ME }?.id
    LaunchedEffect(pendingSend?.sentAt, lastMineId) {
        if (pendingSend != null || lastMineId != null) listState.animateScrollToItem(0)
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

    // Tap a reply-quote → sail to the original message and flash it briefly.
    var flashMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flashMessageId) {
        if (flashMessageId != null) { kotlinx.coroutines.delay(1500); flashMessageId = null }
    }
    fun jumpToMessage(id: String) {
        val idx = renderItems.indexOfFirst { it is ChatRenderItem.Single && it.message.id == id }
        if (idx >= 0) {
            flashMessageId = id
            scope.launch { listState.animateScrollToItem(idx) }
        }
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
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 0.12f + 0.88f * dissolve.value
                    translationY = (1f - dissolve.value) * 26.dp.toPx()
                    val sc = 0.985f + 0.015f * dissolve.value
                    scaleX = sc; scaleY = sc
                }
                .then(
                    if (dissolve.value < 1f)
                        Modifier.blur(((1f - dissolve.value) * 12f).dp)
                    else Modifier
                ),
            reverseLayout = true,
            contentPadding = PaddingValues(top = 16.dp, bottom = bottomReserve, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // reverseLayout: this first item sits at the very bottom, below the newest message.
            // While side-channel tokens are visible the streaming bubble takes the slot; the
            // quips indicator covers the silent phases (connecting, reasoning, tools).
            val stream = liveStream
            val streamVisible = stream != null && stream.roomId == currentSession?.id &&
                (stream.text.isNotBlank() || stream.status == chat.keryx.app.presentation.LiveStreamStatus.INTERRUPTED)
            if (streamVisible && stream != null) {
                item(key = "livestream") {
                    // animateItem so the handoff reads as a soft cross-fade: this bubble fades out
                    // the same beat the committed Matrix bubble animates in — no pop, no jump.
                    Box(modifier = Modifier.animateItem()) {
                        StreamingBubble(
                            stream = stream,
                            bubbleStyle = bubbleStyle,
                            textScale = messageTextScale,
                        )
                    }
                }
            } else if (awaitingReply) {
                item(key = "waiting") { Box(modifier = Modifier.animateItem()) { WaitingIndicator() } }
            }
            // Optimistic send: my message blooms into the chat the instant Send is tapped, instead
            // of waiting for the homeserver echo. Hidden the frame the real event is in the list.
            val pending = pendingSend
            val echoLanded = pending != null && messages.lastOrNull()?.let {
                it.sender == SenderType.ME && ChatViewModel.pendingEchoMatches(it.content, pending.text)
            } == true
            if (pending != null && pending.roomId == currentSession?.id && !echoLanded) {
                item(key = "pendingsend") {
                    Box(modifier = Modifier.animateItem()) {
                        PendingSendBubble(
                            text = pending.text,
                            bubbleStyle = bubbleStyle,
                            textScale = messageTextScale,
                        )
                    }
                }
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
                            // Automated telemetry never gets a chat bubble: it renders as a quiet,
                            // low-contrast block (or nothing at all when telemetry is hidden).
                            val isTelem = message.sender == SenderType.HERMES &&
                                chat.keryx.app.presentation.ui.components.isTelemetryMessage(message)
                            if (isTelem) {
                                if (showTelemetry) TelemetryMessageRow(message, textScale = messageTextScale)
                                return@Box
                            }
                            // Live reactions: updates the moment anyone adds/removes one — no manual refresh.
                            val reactionsFlow = remember(message.id) {
                                viewModel.reactionsFlow(message.sessionId, message.id)
                            }
                            // Flash halo when this message is the target of a quote-jump.
                            val flashed = flashMessageId == message.id
                            val flashColor by animateColorAsState(
                                targetValue = if (flashed) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                                animationSpec = tween(if (flashed) 220 else 900),
                                label = "quoteFlash",
                            )
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
                                onQuoteClick = message.replyToId?.let { target -> { jumpToMessage(target) } },
                                modifier = Modifier.background(flashColor, RoundedCornerShape(18.dp)),
                            )
                        }
                    }
                }
            }
        }

        // Jump-to-now: while scrolled up into history, a frosted chip floats above the composer;
        // it counts agent messages that landed meanwhile and sails back to the newest on tap.
        var missedWhileAway by remember { mutableStateOf(0) }
        LaunchedEffect(messages.lastOrNull()?.id) {
            val last = messages.lastOrNull() ?: return@LaunchedEffect
            if (!atBottom && last.sender != SenderType.ME) missedWhileAway++
        }
        LaunchedEffect(atBottom) { if (atBottom) missedWhileAway = 0 }
        val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
        AnimatedVisibility(
            visible = showJump,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = bottomReserve + 10.dp, end = 18.dp),
        ) {
            val accent = MaterialTheme.colorScheme.primary
            val accent2 = MaterialTheme.colorScheme.tertiary
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 8.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent2.copy(alpha = 0.22f))),
                    shape = RoundedCornerShape(18.dp),
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { scope.launch { listState.animateScrollToItem(0) } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (missedWhileAway > 0) {
                        Text(
                            text = if (missedWhileAway > 9) "9+" else "$missedWhileAway",
                            color = accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to newest",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
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

        // The braille wisp riding the room-switch dissolve.
        if (dissolve.value < 1f) {
            BrailleWisp(
                progress = dissolve.value,
                color = MaterialTheme.colorScheme.primary,
                color2 = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.Center),
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
        // The banner itself is the cloud: bumpy orbiting edges + a gentle bob, with the label inside.
        chat.keryx.app.presentation.ui.components.CloudBanner(
            // Opaque fill so the scalloped edge stays crisp (translucency made the bumps ghost
            // through each other, which is what read as "circles" in light mode).
            fill = MaterialTheme.colorScheme.surfaceVariant,
            border = accent.copy(alpha = 0.85f),
            border2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
        ) {
            Text(
                text = "$label · $clock",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
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
        // A real thumbnail of the staged image (downsampled), not a stand-in emoji.
        val thumb = if (att.isImage) remember(att.bytes) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(att.bytes, 0, att.bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeByteArray(att.bytes, 0, att.bytes.size, opts)
                    ?.asImageBitmap()
            }.getOrNull()
        } else null
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = att.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Text(if (att.isImage) "🖼" else "📎", fontSize = 18.sp)
        }
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
    onQuoteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isMine = message.sender == SenderType.ME
    val isAgent = message.sender == SenderType.HERMES
    var showReactionPicker by remember { mutableStateOf(false) }

    val reactions by reactionsFlow.collectAsState(initial = emptyList())

    // Swipe-to-reply: pull the message toward the middle and let go — a reply arrow condenses
    // behind it as you pull, haptic ticks at the commit point, then the bubble springs home.
    val dragX = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val replyThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // The arrow that materializes as you pull.
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(
                alpha = (dragX.value / replyThresholdPx).coerceIn(0f, 0.9f)
            ),
            modifier = Modifier
                .align(if (isMine) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 6.dp)
                .graphicsLayer {
                    val p = (dragX.value / replyThresholdPx).coerceIn(0f, 1f)
                    scaleX = 0.5f + 0.5f * p; scaleY = 0.5f + 0.5f * p
                },
        )
    Column(
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = if (isMine) -dragX.value else dragX.value }
            .pointerInput(message.id) {
                var fired = false
                detectHorizontalDragGestures(
                    onDragStart = { fired = false },
                    onDragEnd = {
                        if (dragX.value >= replyThresholdPx) onReply()
                        dragScope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                    },
                    onDragCancel = {
                        dragScope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                    },
                ) { change, amount ->
                    // Mine pull left, others pull right — always toward the center line.
                    val toward = if (isMine) -amount else amount
                    val next = (dragX.value + toward * 0.62f).coerceIn(0f, replyThresholdPx * 1.5f)
                    if (!fired && next >= replyThresholdPx) {
                        fired = true
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    if (next > 0f) change.consume()
                    dragScope.launch { dragX.snapTo(next) }
                }
            }
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

        // Double-tap-to-❤️ bloom: a heart swells out of the tap and exhales away.
        var heartBloomTick by remember { mutableStateOf(0) }

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
            Box {
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
                        onDoubleClick = { heartBloomTick++; onReact("❤️") },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (replyTo != null) ReplyQuote(replyTo, appearance.textColor, onClick = onQuoteClick)
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
            if (heartBloomTick > 0) {
                val bloom = remember(heartBloomTick) { Animatable(0f) }
                LaunchedEffect(heartBloomTick) { bloom.animateTo(1f, tween(650, easing = LinearOutSlowInEasing)) }
                if (bloom.value < 1f) {
                    Text(
                        "❤️",
                        fontSize = 34.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                val p = bloom.value
                                val sc = 0.5f + 1.1f * p
                                scaleX = sc; scaleY = sc
                                alpha = (1f - p) * 0.95f
                                translationY = -p * 26.dp.toPx()
                            },
                    )
                }
            }
            } // end bloom wrapper
        }

        if (showReactionPicker) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val copyContext = androidx.compose.ui.platform.LocalContext.current
            ReactionPickerRow(
                onPick = { emoji -> showReactionPicker = false; onReact(emoji) },
                onReply = { showReactionPicker = false; onReply() },
                onCopy = {
                    showReactionPicker = false
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.content))
                    android.widget.Toast.makeText(copyContext, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                },
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
    } // end swipe wrapper Box
}

@Composable
private fun ReplyQuote(replyTo: Message, textColor: Color, onClick: (() -> Unit)? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
private fun ReactionPickerRow(onPick: (String) -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
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
        val accent2 = MaterialTheme.colorScheme.tertiary
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
                            listOf(accent.copy(alpha = 0.45f), accent2.copy(alpha = 0.22f)),
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
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy text", tint = MaterialTheme.colorScheme.primary)
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
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.45f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)),
                ),
                shape,
            )
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

/**
 * A fleeting line of braille glyphs that condenses out of nothing mid-transition and scatters
 * again — dots switching patterns as they drift, like a thought crossing between rooms. Peak
 * visibility at the middle of [progress]; fully gone at both ends, so it never obstructs.
 */
@Composable
private fun BrailleWisp(
    progress: Float,
    color: Color,
    color2: Color,
    modifier: Modifier = Modifier,
) {
    val n = 12
    // sin envelope: invisible at 0 and 1, fullest mid-flight.
    val envelope = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat())
    val step = (progress * 24).toInt() // glyph mutation clock
    Row(modifier = modifier.graphicsLayer {
        alpha = envelope * 0.9f
        val sc = 0.9f + 0.25f * progress
        scaleX = sc; scaleY = sc
    }) {
        for (i in 0 until n) {
            // Deterministic per-(cell, step) dot pattern in the braille block U+2800–U+28FF.
            val h = (i * 31 + step * 17 + i * step * 7) and 0xFF
            val drift = ((i * 13 + step * 3) % 7 - 3) * (1f - progress)
            Text(
                text = (0x2800 + h).toChar().toString(),
                color = lerp(color, color2, i / (n - 1f)),
                fontSize = 18.sp,
                modifier = Modifier.graphicsLayer {
                    translationY = drift * 2.dp.toPx()
                    alpha = 0.35f + 0.65f * (((i * 7 + step) % 5) / 4f)
                },
            )
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
                color2 = MaterialTheme.colorScheme.tertiary,
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

/**
 * The tier-1 live response: tokens streaming over the Hermes side-channel, rendered as an agent
 * bubble with a softly pulsing accent border. On `stop` it holds perfectly still (AWAITING_SYNC)
 * until the identical committed Matrix event replaces it — same text, same layout, so the swap is
 * invisible. A mid-stream drop keeps the partial text and shows a quiet recovery alert instead of
 * losing what was already read.
 */
@Composable
private fun StreamingBubble(
    stream: chat.keryx.app.presentation.LiveStream,
    bubbleStyle: String,
    textScale: Float,
) {
    val appearance = bubbleAppearance(isMine = false, style = bubbleStyle)
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val interrupted = stream.status == chat.keryx.app.presentation.LiveStreamStatus.INTERRUPTED
    val streaming = stream.status == chat.keryx.app.presentation.LiveStreamStatus.STREAMING

    // Dreamy breathing border while tokens flow; settles once generation stops.
    val glow = if (streaming) {
        val t = rememberInfiniteTransition(label = "streamPulse")
        t.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "streamPulseAlpha",
        ).value
    } else 0.3f

    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    val baseDensity = LocalDensity.current
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(appearance.brush)
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(accent.copy(alpha = glow), accent2.copy(alpha = glow * 0.7f))),
                    shape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
            ) {
                Column {
                    chat.keryx.app.presentation.ui.components.MessageContent(
                        content = stream.text,
                        textColor = appearance.textColor,
                        isStreaming = streaming,
                    )
                    if (streaming) {
                        // A quiet blinking caret marks "still writing" without a layout-shifting
                        // spinner; its blink crossfades accent 1 → accent 2. Beside it, a live
                        // ≈tok/s readout — practical telemetry that also just looks alive.
                        val caret = rememberInfiniteTransition(label = "caret")
                        val a by caret.animateFloat(
                            initialValue = 0.15f, targetValue = 0.9f,
                            animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
                            label = "caretAlpha",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("▍", color = lerp(accent2, accent, a).copy(alpha = a), fontSize = 13.sp)
                            if (stream.charsPerSec > 8f) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "≈${(stream.charsPerSec / 4f).toInt()} tok/s",
                                    color = appearance.textColor.copy(alpha = 0.40f),
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (interrupted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("⚡", fontSize = 11.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Stream dropped — recovering via Matrix sync…",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * The optimistic own-message bubble: appears the instant Send is tapped and blooms into place —
 * rising from the composer with a soft spring, unfurling from 92% scale, while an accent glow
 * flares on its border and exhales away as it settles. A faint breathing "sending" tick sits where
 * the ✓ will be until the homeserver echo replaces this bubble with the real event (same frame).
 */
@Composable
private fun PendingSendBubble(text: String, bubbleStyle: String, textScale: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val appearance = bubbleAppearance(isMine = true, style = bubbleStyle)
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)

    // The bloom: one-shot entrance driven by a single progress animatable (0 → 1).
    val bloom = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        bloom.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow))
    }
    // The glow flare rides the same progress but fades back out near the end of the settle.
    val glowAlpha = (1f - bloom.value) * 0.55f + 0.12f
    // Breathing "sending" indicator, alive until the echo swap retires this bubble.
    val breathe = rememberInfiniteTransition(label = "sendBreathe")
    val tickAlpha by breathe.animateFloat(
        initialValue = 0.25f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sendTickAlpha",
    )

    val baseDensity = LocalDensity.current
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val p = bloom.value
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * 34.dp.toPx()
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                    transformOrigin = TransformOrigin(0.9f, 1f)
                }
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(appearance.brush)
                .border(1.dp, accent.copy(alpha = glowAlpha), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
            ) {
                chat.keryx.app.presentation.ui.components.MessageContent(
                    content = text,
                    textColor = appearance.textColor,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp, end = 4.dp)) {
            // The pending tick breathes; the real bubble's steady ✓ takes over after the swap.
            Text("✓", color = accent.copy(alpha = tickAlpha), fontSize = 11.sp)
        }
    }
}

/** A pure-telemetry agent message: no chat bubble, just the low-contrast machine-voice block. */
@Composable
private fun TelemetryMessageRow(message: Message, textScale: Float) {
    val baseDensity = LocalDensity.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        CompositionLocalProvider(
            LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
        ) {
            chat.keryx.app.presentation.ui.components.MessageContent(
                content = message.content,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.timestamp > 0L) {
            Text(
                text = formatClock(message.timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 4.dp, top = 1.dp),
            )
        }
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
                color2 = MaterialTheme.colorScheme.tertiary,
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
