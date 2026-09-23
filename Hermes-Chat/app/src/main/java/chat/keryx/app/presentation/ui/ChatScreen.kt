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
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.ToolTheaterRun
import chat.keryx.app.presentation.ui.components.keryxLightSweep
import chat.keryx.app.presentation.ui.components.KeryxMotion
import chat.keryx.app.presentation.ui.components.keryxReveal
import chat.keryx.app.presentation.ui.components.keryxConceal
import chat.keryx.app.presentation.ui.components.rememberSweepProgress
import androidx.compose.ui.text.font.FontFamily
import chat.keryx.app.presentation.ui.components.GroupedTimeline
import chat.keryx.app.presentation.ui.components.groupChatItemsIncremental
import chat.keryx.app.presentation.ui.components.withLiveTheater
import chat.keryx.app.presentation.ui.components.ArrivalMark
import chat.keryx.app.presentation.ui.components.MessageBubble
import chat.keryx.app.presentation.ui.components.PendingSendBubble
import chat.keryx.app.presentation.ui.components.StreamingBubble
import chat.keryx.app.presentation.ui.components.TelemetryMessageRow
import chat.keryx.app.presentation.ui.components.WaitingIndicator
import chat.keryx.app.presentation.ui.components.WorkingStatusBar
import chat.keryx.app.presentation.ui.components.replyPreviewText
import chat.keryx.app.presentation.ui.components.shortSender
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Qwen3-VL patches images on a 32px grid (patch_size 16 * merge_size 2). Images whose
 * dimensions aren't a multiple of 32 hit a rounding mismatch between vLLM's placeholder-token
 * estimate and the HF processor's actual patch count, which vLLM rejects with a 400. Rounding
 * down to the nearest 32px avoids the mismatch at the source.
 */
private fun normalizeImageBytes(bytes: ByteArray, contentType: String): Pair<ByteArray, String> {
    val bitmap = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        ?: return bytes to contentType
    val gridSize = 32
    val targetWidth = (bitmap.width / gridSize) * gridSize
    val targetHeight = (bitmap.height / gridSize) * gridSize
    if (targetWidth <= 0 || targetHeight <= 0 ||
        (targetWidth == bitmap.width && targetHeight == bitmap.height)
    ) {
        return bytes to contentType
    }
    return runCatching {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        out.toByteArray() to "image/jpeg"
    }.getOrDefault(bytes to contentType)
}

/** A turn shorter than this ends without the completion tick — you never looked away. */
private const val COMPLETION_TICK_MIN_MS = 1_500L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    // The newest reply — the one exchange the gateway can take back (2.10).
    val lastAgentId = remember(messages) { messages.lastOrNull { it.sender == SenderType.HERMES }?.id }
    // The ring, itemised (2.10): a sheet over the chat, opened from the composer's ring.
    var showContext by remember { mutableStateOf(false) }
    if (showContext) chat.keryx.app.presentation.ui.components.ContextBreakdownSheet(
        viewModel = viewModel, onDismiss = { showContext = false },
    )
    val rooms by viewModel.rooms.collectAsState()
    val currentRoom by viewModel.currentRoom.collectAsState()
    val bubbleStyle by viewModel.bubbleStyle.collectAsState()
    val animationStyle by viewModel.animationStyle.collectAsState()
    val messageTextScale by viewModel.messageTextScale.collectAsState()
    val awaitingReply by viewModel.awaitingReply.collectAsState()
    val liveTurnSigns by viewModel.liveTurnSigns.collectAsState()
    // The agent finished (2.8.1): the completion tick — defined with the vocabulary in 2.0 and
    // never fired — lands when a turn you waited on ends. Waited-on means the wait was long
    // enough to have looked away (an instant echo gets no ceremony), and only for the room on
    // screen: a turn ending elsewhere is that room's news, not a buzz in your hand.
    val turnHaptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
    var awaitingSince by remember { mutableStateOf(0L) }
    var awaitingRoom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(awaitingReply, currentRoom?.id) {
        val now = System.currentTimeMillis()
        val room = currentRoom?.id
        when {
            // A room switch mid-wait is not a completion: forget the wait, no tick.
            room != awaitingRoom -> { awaitingRoom = room; awaitingSince = if (awaitingReply) now else 0L }
            awaitingReply -> if (awaitingSince == 0L) awaitingSince = now
            else -> {
                if (awaitingSince != 0L && now - awaitingSince >= COMPLETION_TICK_MIN_MS) turnHaptics.completion()
                awaitingSince = 0L
            }
        }
    }
    val typingHumans by viewModel.typingHumans.collectAsState()
    val typingAgentIds by viewModel.typingAgentIds.collectAsState()
    val liveStream by viewModel.liveStream.collectAsState()
    val pendingSend by viewModel.pendingSend.collectAsState()
    val showTelemetry by viewModel.showTelemetry.collectAsState()
    val workStartedAt by viewModel.workStartedAt.collectAsState()
    val workLabel by viewModel.workLabel.collectAsState()
    val sessionStatus by viewModel.sessionStatus.collectAsState()
    val replyTarget by viewModel.replyTarget.collectAsState()
    val savedIds by viewModel.archive.savedIds.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val composerPrefill by viewModel.composerPrefill.collectAsState()
    // The harvest's instruments (plan §5) — quiet nulls on the Matrix path.
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val pendingBlocking by viewModel.pendingBlocking.collectAsState()
    val flightPlan by viewModel.flightPlan.collectAsState()

    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    var composerHeightPx by remember { mutableStateOf(0) }

    fun stageFromUri(uri: android.net.Uri?, fallbackType: String) {
        if (uri == null) return
        val rawBytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return
        // Trust the resolver's mime: the gallery hands out videos too now, and a video forced
        // through the image normalizer would come out corrupted.
        val rawType = context.contentResolver.getType(uri) ?: fallbackType
        val isImage = rawType.startsWith("image")
        val (bytes, type) = if (isImage) normalizeImageBytes(rawBytes, rawType) else rawBytes to rawType
        val name = queryDisplayName(context, uri)
        pendingAttachment = PendingAttachment(bytes, name, type, isImage = isImage)
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        stageFromUri(uri, fallbackType = "image/jpeg")
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        stageFromUri(uri, fallbackType = "application/octet-stream")
    }

    // Composer state
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val commandMenuVisible by viewModel.commandMenuVisible.collectAsState()
    val recentCommands by viewModel.recentCommands.collectAsState()
    val commandFilter by viewModel.commandFilter.collectAsState()

    val isGroupRoom = rooms.firstOrNull { it.id == currentRoom?.id }?.type == RoomType.SHARED_GROUP
    // reverseLayout: index 0 is the newest message, pinned to the bottom.
    val ordered = messages.asReversed()
    // Quote lookups only: the old full id→message map was rebuilt on every emission and held
    // every message for the rare quoted-reply render; only actual reply targets are needed.
    val byId = remember(messages) {
        val targets = messages.mapNotNullTo(HashSet()) { it.replyToId }
        if (targets.isEmpty()) emptyMap()
        else messages.filter { it.id in targets }.associateBy { it.id }
    }
    // Collapse runs of consecutive tool-only messages into one expandable "Ran N tools" group.
    // Incremental: during a streamed turn every sync tick re-emits the list with only the tail
    // changed, so the grouped prefix is spliced from the previous pass and only the trailing
    // agent block is re-walked (O(changed block), not O(timeline)). Plain holder, not snapshot
    // state — renderItems is already keyed on [messages].
    val groupCache = remember { arrayOfNulls<GroupedTimeline>(1) }
    val groupedItems = remember(messages) {
        val grouped = groupChatItemsIncremental(ordered, groupCache[0])
        groupCache[0] = grouped
        grouped.items
    }
    // The turn's theater: the live one while it runs, then the record of the one just watched.
    //
    // Keyed on the TheaterState itself, NOT on `liveStream` — the stream object is rebuilt ~10×/s
    // by the token dispatch and carries the same TheaterState instance between tool frames, so
    // this re-runs a handful of times per turn instead of ten times a second.
    val lastTurn by viewModel.lastTurnTheater.collectAsState()
    val liveTheater = liveStream?.takeIf { it.roomId == currentRoom?.id }?.theater?.takeUnless { it.isEmpty }
    val turnTheater = liveTheater ?: lastTurn?.takeIf { it.first == currentRoom?.id }?.second
    // 3.1 §A1: the side-channel's tool frames are a PRODUCER, not a second renderer. Calls the
    // committed transcript already carries get enriched below (`structured`); calls it doesn't
    // carry yet land as live rows in the same run. One grammar, live and settled.
    val renderItems = remember(groupedItems, turnTheater, liveTheater != null) {
        withLiveTheater(
            groupedItems,
            beats = turnTheater?.beats.orEmpty(),
            delegations = turnTheater?.delegations.orEmpty(),
            live = liveTheater != null,
        )
    }
    // Which bubbles an arrival announced — they get the single light sweep as they first compose.
    val arrivalIds = remember(renderItems) {
        renderItems.filterIsInstance<ChatRenderItem.Arrival>().mapTo(HashSet()) { it.message.id }
    }
    // The run the theater's record belongs to (2.4). renderItems is newest-first under
    // reverseLayout, so the first ToolRun in it IS the newest one.
    val newestToolRunKey = remember(renderItems) {
        renderItems.firstOrNull { it is ChatRenderItem.ToolRun }?.key
    }
    val lastTurnBeats = turnTheater?.beats.orEmpty()
    // A landed subagent the reader asked to see inside (2.4).
    var openSubagent by remember { mutableStateOf<chat.keryx.core.model.Delegation?>(null) }
    // The room the open helper belongs to. A room switch under the sheet (a notice tap) closes
    // it: every run it could re-resolve against, and every verb it sends, belong to that room.
    var openSubagentRoom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentRoom?.id) {
        if (openSubagentRoom != null && openSubagentRoom != currentRoom?.id) openSubagent = null
    }
    // Tap-In (2.12): the turn in flight, full screen. Opened from the working banner, a long
    // press on the newest run, or the run notice in the shade; closed by the back gesture. It
    // reads the same render items the transcript draws, so both doors are already reconciled.
    var tapInOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val tapInRequested by viewModel.tapInRequested.collectAsState()
    LaunchedEffect(tapInRequested) {
        if (tapInRequested) { tapInOpen = true; viewModel.consumeTapIn() }
    }
    if (tapInOpen) chat.keryx.app.presentation.tapin.TapInHost(
        viewModel = viewModel,
        itemsNewestFirst = renderItems,
        structured = lastTurnBeats,
        onClose = { tapInOpen = false },
    )

    // Restore this room's unsent draft when it opens (and swap drafts when switching rooms) so
    // half-typed thoughts survive room hops and app restarts.
    LaunchedEffect(currentRoom?.id) {
        val roomId = currentRoom?.id ?: return@LaunchedEffect
        val draft = viewModel.draftFor(roomId)
        textState = TextFieldValue(draft, selection = TextRange(draft.length))
        viewModel.onComposerTextChanged(draft)
    }

    // Dream dissolve on room switch: the timeline re-materializes through a soft blur+fade while
    // the arriving room's light streams across it as a braille wake — the app's signature
    // "crossing rooms" beat. Skipped on first open (no jarring boot blur), and skipped entirely
    // under Battery Saver, which is where KeryxSpaceBody's arrival already stands.
    var lastRoomForDissolve by remember { mutableStateOf<String?>(null) }
    val dissolve = remember { Animatable(1f) }
    val dissolveReduced by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    LaunchedEffect(currentRoom?.id) {
        val id = currentRoom?.id
        if (dissolveReduced) {
            dissolve.snapTo(1f)
            lastRoomForDissolve = id
            return@LaunchedEffect
        }
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

    // The assist gesture lands here: composer focused, draft untouched (2.0 Phase 5).
    val assistSummon by viewModel.assistSummon.collectAsState()
    LaunchedEffect(assistSummon) {
        if (assistSummon > viewModel.assistConsumed) {
            viewModel.assistConsumed = assistSummon
            runCatching { focusRequester.requestFocus() }
        }
    }

    // Voice dictation: mic tap → record m4a → POST to the configured STT endpoint → transcript
    // appends to whatever's already typed. The mic only appears once an endpoint is configured.
    val sttUrl by viewModel.voice.sttUrl.collectAsState()
    val voiceRecorder = remember { chat.keryx.app.audio.VoiceRecorder(context) }
    var dictation by remember { mutableStateOf(DictationPhase.IDLE) }
    DisposableEffect(Unit) { onDispose { voiceRecorder.cancel() } }

    fun insertTranscript(transcript: String) {
        val t = transcript.trim()
        if (t.isEmpty()) return
        val base = textState.text
        val sep = if (base.isEmpty() || base.endsWith(" ") || base.endsWith("\n")) "" else " "
        val merged = base + sep + t
        textState = TextFieldValue(merged, selection = TextRange(merged.length))
        viewModel.onComposerTextChanged(merged)
    }

    fun startDictation() {
        runCatching { voiceRecorder.start() }
            .onSuccess { dictation = DictationPhase.RECORDING }
            .onFailure {
                dictation = DictationPhase.IDLE
                android.widget.Toast.makeText(context, "Mic unavailable", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    fun finishDictation() {
        val take = voiceRecorder.stop()
        if (take == null) {
            dictation = DictationPhase.IDLE
            return
        }
        dictation = DictationPhase.TRANSCRIBING
        viewModel.voice.transcribe(take) { result ->
            dictation = DictationPhase.IDLE
            result.onSuccess(::insertTranscript).onFailure {
                android.widget.Toast.makeText(context, "Transcription failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation()
        else android.widget.Toast.makeText(context, "Keryx needs mic access to dictate", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun onMicTap() {
        when (dictation) {
            DictationPhase.RECORDING -> finishDictation()
            DictationPhase.TRANSCRIBING -> {} // hands off; the callback resets us
            DictationPhase.IDLE -> {
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) startDictation()
                else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Voice replies: agent messages read aloud — the device's built-in voice when no TTS server
    // is configured, otherwise the user's /v1/audio/speech endpoint. One voice at a time; leaving
    // the screen, switching rooms, or sending all silence it.
    val ttsUrl by viewModel.voice.ttsUrl.collectAsState()
    val tts = remember {
        chat.keryx.app.audio.TtsController(context) { error ->
            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val ttsState by tts.state.collectAsState()
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) tts.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(currentRoom?.id) { tts.stop() }

    fun speakMessage(message: Message) {
        val text = chat.keryx.app.presentation.TtsText.speakable(message.content)
        if (text.isBlank()) {
            android.widget.Toast.makeText(context, "Nothing to read aloud", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (ttsUrl.isBlank()) {
            tts.speakSystem(message.id, text)
        } else {
            // Streams PCM and starts talking on the first bytes; falls back to a whole mp3 for
            // servers that cannot stream. Failures surface through the controller's onError.
            tts.speakRemote(
                message.id,
                context.cacheDir,
                openStream = { viewModel.voice.openSpeechStream(text) },
                synthesizeFile = { file -> viewModel.voice.synthesizeBlocking(text, file) },
            )
        }
    }

    // Auto-speak: the ViewModel emits each settled agent reply in the open room (opt-in setting).
    LaunchedEffect(Unit) { viewModel.speakRequests.collect { speakMessage(it) } }

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
    // snapshotFlow, NOT composition-scope reads: per-token signature changes (and atBottom
    // flips) must nudge this effect without recomposing the whole screen — the old plain-val
    // form re-ran ChatScreen top to bottom on every streamed token.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val sig = (messages.lastOrNull()?.let { "${it.id}:${it.content.length}:${it.toolCalls.size}" } ?: "") +
                ":${liveStream?.text?.length ?: 0}"
            sig to awaitingReply
        }.collect {
            // Never fight an active user drag/fling — that's what made scroll-up impossible mid-stream.
            if (atBottom && !listState.isScrollInProgress) listState.scrollToItem(0)
        }
    }
    // A message I just sent always snaps to the newest, wherever I was scrolled.
    val lastMineId = messages.lastOrNull()?.takeIf { it.sender == SenderType.ME }?.id
    LaunchedEffect(pendingSend?.sentAt, lastMineId) {
        if (pendingSend != null || lastMineId != null) listState.animateScrollToItem(0)
    }

    // Timeline-window decay: tell the ViewModel when the viewport is (and stays) at the bottom so
    // a deep-scrolled history window can shrink back after a dwell instead of pinning hundreds of
    // resolved events for the rest of the session. (snapshotFlow: an atBottom flip as an effect
    // KEY is still a composition-scope read — it restarted the whole screen twice per swipe.)
    LaunchedEffect(Unit) { snapshotFlow { atBottom }.collect { viewModel.onViewportAtBottom(it) } }

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

    // 2.8 — parse ahead of the scroll. Every settled, long agent body in the loaded window
    // gets its markdown tree built off the main thread as soon as it is known, so when the
    // user swipes up into it the bubble composes from a cache hit instead of a blocking
    // parse. Bounded by what is loaded; the cache itself is bounded and content-keyed.
    LaunchedEffect(messages) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            for (m in messages) {
                if (m.isStreaming || m.sender != SenderType.HERMES) continue
                if (m.content.length < chat.keryx.app.presentation.ui.components.MarkdownCache.MIN_CHARS) continue
                chat.keryx.app.presentation.ui.components.MarkdownWarmer.warm(m.content)
            }
        }
    }

    // Send a read receipt for the latest message while viewing this room (clears unread).
    LaunchedEffect(currentRoom?.id, messages.lastOrNull()?.id) {
        val roomId = currentRoom?.id
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
        tts.stop()
        val attachment = pendingAttachment
        val text = textState.text
        // Text sent alongside an attachment rides in the same event as its caption (one Matrix
        // event, one agent turn) — except slash commands, which must reach the gateway as text.
        val caption = text.takeIf { attachment != null && it.isNotBlank() && !it.startsWith("/") }
        if (attachment != null) {
            viewModel.sendAttachment(attachment.bytes, attachment.name, attachment.contentType, caption)
            pendingAttachment = null
        }
        if (text.isNotBlank()) {
            if (caption == null) {
                if (text.startsWith("/")) viewModel.recordCommandUse(text)
                // 2.3 §4: the herald carries news back. Senses appends its marker here, on the way
                // out and inside the message body, so it is E2EE-wrapped like everything else and
                // the gateway needs no change. Self-guards: off by default, never on a slash
                // command, at most once per room per half hour unless something actually changed.
                val outgoing = currentRoom
                    ?.let { chat.keryx.app.senses.KeryxSenses.decorateOutgoing(context, it.id, text) }
                    ?: text
                viewModel.sendMessage(outgoing)
            }
            textState = TextFieldValue("")
            viewModel.onComposerTextChanged("")
        }
    }

    // Background is supplied app-wide (gradient lives in HermesApp); keep this surface transparent.
    Box(modifier = modifier.fillMaxSize().imePadding()) {
        if (currentRoom == null) {
            EmptyChat(viewModel = viewModel, modifier = Modifier.align(Alignment.Center))
        }
        // The instrument rail (flight plan + working banner) is composed at the END of this Box —
        // see "TOP INSTRUMENTS" below. Both are pinned to the top edge and both float over the
        // transcript, so drawing them as two independently-aligned children put them in the same
        // 28dp of screen.
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
            // While side-channel tokens are visible the streaming bubble takes the slot. The
            // quips indicator fires only BEFORE the turn's first sign of life (3.1 §C3): once
            // tool beats exist, the live run row in the transcript already names the tool, and
            // quips beneath it were a fourth "working" signal saying less than the row above.
            val stream = liveStream
            val streamVisible = stream != null && stream.roomId == currentRoom?.id &&
                (stream.text.isNotBlank() || stream.reasoning.isNotBlank() ||
                    stream.status == chat.keryx.app.presentation.LiveStreamStatus.INTERRUPTED)
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
            } else if (awaitingReply && liveTheater == null && !liveTurnSigns) {
                // liveTheater covers the Matrix side-channel; liveTurnSigns covers the direct
                // door, whose live overlay and tool rows render inside the transcript itself —
                // without it the quips ran the whole turn there (3.1 §C3, device-caught).
                item(key = "waiting") { Box(modifier = Modifier.animateItem()) { WaitingIndicator() } }
            } else if (typingHumans.isNotEmpty()) {
                // Humans typing get a plain low-contrast line in the same slot — never the
                // agent's working cloud.
                item(key = "humantyping") {
                    Box(modifier = Modifier.animateItem()) {
                        Text(
                            text = when (typingHumans.size) {
                                1 -> "${typingHumans[0]} is typing…"
                                2 -> "${typingHumans[0]} and ${typingHumans[1]} are typing…"
                                else -> "Several people are typing…"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            // Optimistic send: my message blooms into the chat the instant Send is tapped, instead
            // of waiting for the homeserver echo. Hidden the frame the real event is in the list.
            val pending = pendingSend
            val echoLanded = pending != null && messages.lastOrNull()?.let {
                it.sender == SenderType.ME && ChatViewModel.pendingEchoMatches(it.content, pending.text)
            } == true
            if (pending != null && pending.roomId == currentRoom?.id && !echoLanded) {
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
                key = { _, item -> item.key },
                // Heterogeneous rows: telling Lazy layout which kind each item is lets it reuse
                // compositions within a kind during scroll instead of across unrelated shapes.
                contentType = { _, item ->
                    when (item) {
                        is ChatRenderItem.DayHeader -> "day"
                        is ChatRenderItem.ToolRun -> "run"
                        is ChatRenderItem.Single -> "single"
                        is ChatRenderItem.Arrival -> "arrival"
                    }
                },
            ) { index, item ->
                Box(modifier = Modifier.animateItem()) {
                    when (item) {
                        is ChatRenderItem.DayHeader -> DaySeparator(item.epochMillis)
                        is ChatRenderItem.Arrival -> ArrivalMark(item.message)
                        is ChatRenderItem.ToolRun -> ToolTheaterRun(
                            run = item,
                            // Only the newest run, and only in the room it was watched in: the
                            // record is of one turn, and putting it on an older run would be
                            // attaching one turn's diffs to another's calls.
                            structured = if (item.key == newestToolRunKey) lastTurnBeats else emptyList(),
                            onOpenSubagent = { openSubagent = it; openSubagentRoom = currentRoom?.id },
                            onTapIn = if (item.key == newestToolRunKey) ({ tapInOpen = true }) else null,
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
                            // Structured reasoning (both producers fill Message.reasoning — 3.1
                            // §B1): the quiet "Thought for Ns" disclosure, above whatever the
                            // turn said. A reasoning-ONLY row (a turn that was all thought before
                            // its tools) is just the disclosure — no empty bubble under it. On
                            // the Matrix door content keeps its <think> lines (the parse owns
                            // what counts as thought), so "only" is judged by the parse, not by
                            // blankness.
                            val thought = message.reasoning?.takeIf { it.isNotBlank() }
                            if (thought != null && (message.content.isBlank() ||
                                    chat.keryx.core.protocol.MessageParser.isReasoningOnly(message.content))) {
                                chat.keryx.app.presentation.ui.components.ReasoningDisclosure(
                                    reasoning = thought,
                                    seconds = message.reasoningSeconds,
                                    streaming = message.isStreaming,
                                    stateKey = "think-${message.id}",
                                )
                                return@Box
                            }
                            // Automated telemetry never gets a chat bubble: it renders as a quiet,
                            // low-contrast block (or nothing at all when telemetry is hidden).
                            val isTelem = message.sender == SenderType.HERMES &&
                                chat.keryx.app.presentation.ui.components.isTelemetryMessage(message)
                            if (isTelem) {
                                if (chat.keryx.app.presentation.ui.components.showsTelemetryRow(message, showTelemetry)) {
                                    TelemetryMessageRow(message, textScale = messageTextScale)
                                }
                                return@Box
                            }
                            // Live reactions: updates the moment anyone adds/removes one — no manual refresh.
                            val reactionsFlow = remember(message.id) {
                                viewModel.reactionsFlow(message.roomId, message.id)
                            }
                            // Flash halo when this message is the target of a quote-jump.
                            val flashed = flashMessageId == message.id
                            val flashColor by animateColorAsState(
                                targetValue = if (flashed) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else Color.Transparent,
                                animationSpec = tween(if (flashed) 220 else 900),
                                label = "quoteFlash",
                            )
                            // The gateway reply-threads every chunk of a turn to the triggering
                            // message; a quote of the user's own last message is noise (grouping
                            // marks those suppressed). Genuine references further back still show.
                            val quotedId = message.replyToId.takeUnless { item.suppressQuote }
                            Column {
                            if (thought != null) {
                                chat.keryx.app.presentation.ui.components.ReasoningDisclosure(
                                    reasoning = thought,
                                    seconds = message.reasoningSeconds,
                                    streaming = false,
                                    stateKey = "think-${message.id}",
                                )
                            }
                            val failure = message.failure
                            if (failure != null) {
                                // A failed turn is a card, not a bubble that says "Error:" (2.10).
                                chat.keryx.app.presentation.ui.components.TurnFailureCard(
                                    message = message,
                                    failure = failure,
                                    viewModel = viewModel,
                                    textScale = messageTextScale,
                                )
                            } else MessageBubble(
                                message = message,
                                replyTo = quotedId?.let { byId[it] },
                                bubbleStyle = bubbleStyle,
                                animationStyle = animationStyle,
                                textScale = messageTextScale,
                                showSender = isGroupRoom,
                                arrival = message.id in arrivalIds,
                                reactionsFlow = reactionsFlow,
                                // Resolve media by event id in the repo, which handles both plaintext
                                // (mxc) and E2EE-encrypted files and falls back to the thumbnail.
                                mediaLoader = { viewModel.loadMessageMedia(message.roomId, message.id) },
                                onReply = { viewModel.setReplyTarget(message) },
                                onReact = { emoji -> viewModel.sendReaction(message.id, emoji) },
                                onQuoteClick = quotedId?.let { target -> { jumpToMessage(target) } },
                                // Redaction: own messages only — the agent's power level owns the
                                // rest. A Matrix power; the gateway keeps its transcript, so the
                                // direct door offers no dead affordance.
                                onDelete = if (message.sender == SenderType.ME && viewModel.canDeleteMessages) {
                                    { viewModel.deleteMessage(message.roomId, message.id) }
                                } else null,
                                kept = savedIds.contains(message.id),
                                onToggleKeep = if (viewModel.archive.available) {
                                    { viewModel.archive.toggleSaved(message) }
                                } else null,
                                speaking = ttsState.messageId == message.id &&
                                    ttsState.phase != chat.keryx.app.audio.TtsController.Phase.IDLE,
                                onSpeak = if (message.sender == SenderType.HERMES) {
                                    {
                                        val active = ttsState.messageId == message.id &&
                                            ttsState.phase != chat.keryx.app.audio.TtsController.Phase.IDLE
                                        if (active) tts.stop() else speakMessage(message)
                                    }
                                } else null,
                                // Take back the last exchange (2.10): only the newest reply, only
                                // on the direct door, never while a turn runs — the gateway
                                // refuses that anyway, and a button that would be refused is
                                // not a button.
                                onUndoTurn = if (viewModel.transportIsDirect && message.id == lastAgentId && !awaitingReply) {
                                    { viewModel.undoLastTurn() }
                                } else null,
                                // Retry rides the same gate as the undo (2.11.9): it IS an undo
                                // with the prompt re-sent, so a refused undo is a refused retry.
                                onRetry = if (viewModel.transportIsDirect && message.id == lastAgentId && !awaitingReply &&
                                    ChatViewModel.retryPromptOf(messages) != null
                                ) {
                                    { viewModel.retryExchange() }
                                } else null,
                                modifier = Modifier.background(flashColor, RoundedCornerShape(18.dp)),
                            )
                            }
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
        LaunchedEffect(Unit) { snapshotFlow { atBottom }.collect { if (it) missedWhileAway = 0 } }
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
                        chat.keryx.app.presentation.ui.components.KeryxGlyphs.ChevronDown,
                        contentDescription = "Jump to newest",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Floating Command Palette
        val gatewayCommands by viewModel.hub.gatewayCommands.collectAsState()
        LaunchedEffect(commandMenuVisible) {
            // Opening "/" refreshes the live registry (throttled in the VM); the preset list
            // covers the gap until the first successful fetch.
            if (commandMenuVisible) viewModel.hub.refreshGatewayCommands()
        }
        AnimatedVisibility(
            visible = commandMenuVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            // Sits on top of the composer as MEASURED, not a guessed 90dp: the composer grows
            // (the Matrix door's rows, a reply target, a multi-line draft) and a fixed offset
            // left the palette's lower rows under it, down by the keyboard.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) { composerHeightPx.toDp() } + 8.dp)
        ) {
            CommandPaletteMenu(
                filter = commandFilter,
                recents = recentCommands,
                live = gatewayCommands,
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
            // Bot Mode (2.8): typing `@` in a bot's chat offers the roster. A tap completes
            // the handle; on send the ViewModel appends the note that tells the agent whom the
            // tag means, so it can hand off with message_agent. Only where the tool exists.
            val mentionRoster = viewModel.bots.roster.collectAsState().value.data
            val mentionable = remember(currentRoom?.id, mentionRoster) {
                if (currentRoom != null && mentionRoster != null && mentionRoster.messagingArmed &&
                    viewModel.bots.isCanonicalChat(currentRoom?.id)
                ) mentionRoster.bots.filter { !it.hidden } else emptyList()
            }
            if (mentionable.isNotEmpty()) {
                chat.keryx.app.presentation.ui.components.MentionChips(
                    text = textState.text,
                    cursor = textState.selection.end,
                    bots = mentionable,
                    self = viewModel.bots.botForSession(currentRoom?.id)?.name,
                    onPick = { replaced, caret ->
                        textState = TextFieldValue(replaced, selection = TextRange(caret))
                        viewModel.onComposerTextChanged(replaced)
                    },
                )
            }
            // The agent is STOPPED waiting on a human — loudest thing on screen, right above
            // the composer where the answer happens (merge dowry, plan §5).
            androidx.compose.animation.AnimatedVisibility(visible = pendingApproval != null, enter = keryxReveal(), exit = keryxConceal()) {
                pendingApproval?.let { approval ->
                    Column {
                        chat.keryx.app.presentation.ui.components.ApprovalCard(approval) {
                            viewModel.respondApproval(it)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = pendingBlocking != null, enter = keryxReveal(), exit = keryxConceal()) {
                pendingBlocking?.let { request ->
                    Column {
                        chat.keryx.app.presentation.ui.components.BlockingRequestCard(request) {
                            viewModel.respondBlocking(request, it)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = replyTarget != null, enter = keryxReveal(), exit = keryxConceal()) {
                replyTarget?.let { target ->
                    Column {
                        ReplyBar(target = target, onDismiss = { viewModel.clearReplyTarget() })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = pendingAttachment != null, enter = keryxReveal(), exit = keryxConceal()) {
                pendingAttachment?.let { att ->
                    Column {
                        AttachmentPreview(att, onRemove = { pendingAttachment = null })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            val composerCaps by viewModel.hub.reasoningCaps.collectAsState()
            val composerUsage by viewModel.contextUsage.collectAsState()
            val hubBrainsPanel by viewModel.hub.brains.collectAsState()
            val modelCatalog by viewModel.models.catalog.collectAsState()
            val modelCatalogLoading by viewModel.models.loading.collectAsState()
            val modelRecents by viewModel.models.recents.collectAsState()
            // Desktop's busy-state submit tree, the Talaria way: text typed mid-turn STEERS
            // the live turn (no interrupt), payloads/compacting/blocked QUEUE for the next
            // turn, an empty composer stops. Slash commands keep their console path.
            // Every live-turn sign counts, not just our own awaiting flag: a turn steered from
            // the desktop, entered mid-flight, or running across a relaunch never set ours —
            // and a plain send against it would interrupt the work on the direct door.
            val compactingNow = sessionStatus?.isCompacting == true
            // Only THIS room's stream counts: the overlay is app-wide and a turn running in the
            // session you just left made a fresh session read "steer" until you visited an ended
            // room and came back (device-caught 2026-09-06, the fleet walk).
            val agentLive = (liveStream?.roomId != null && liveStream?.roomId == currentRoom?.id) ||
                typingAgentIds.isNotEmpty() || liveTurnSigns
            val busyNow = awaitingReply || agentLive || compactingNow
            val slashTyped = textState.text.trimStart().startsWith("/")
            val steerable = busyNow && !compactingNow && pendingApproval == null &&
                pendingBlocking == null && pendingAttachment == null && !slashTyped
            val busyAction = when {
                !busyNow -> null
                slashTyped -> null // slash runs inline even mid-turn
                textState.text.isBlank() && pendingAttachment == null ->
                    if (viewModel.canInterruptTurn) "stop" else null
                steerable -> "steer"
                else -> "queue"
            }
            fun takeComposerText(): String {
                val t = textState.text.trim()
                textState = TextFieldValue("")
                viewModel.onComposerTextChanged("")
                return t
            }
            Composer(
                onContextTap = if (viewModel.transportIsDirect) ({ showContext = true }) else null,
                textState = textState,
                onTextChange = { textState = it; viewModel.onComposerTextChanged(it.text) },
                onSend = ::doSend,
                busyAction = busyAction,
                onSteer = {
                    if (textState.text.isNotBlank()) viewModel.steerTurn(takeComposerText())
                },
                onQueue = {
                    if (pendingAttachment != null) {
                        viewModel.toast("Attachments can't queue — send after this turn finishes")
                    } else if (textState.text.isNotBlank()) {
                        viewModel.queueMessage(takeComposerText())
                    }
                },
                onStop = { viewModel.interruptTurn() },
                onStopHint = {
                    viewModel.toast("Type your correction first — then tap steers the turn, hold queues it")
                },
                onPickGallery = { galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onPickFile = { filePicker.launch("*/*") },
                // Deferred read: Composer only consults this inside its focus callback, and a
                // plain Boolean param recomposed the composer (and this whole screen) on every
                // bottom-threshold crossing during scroll.
                atBottom = { atBottom },
                hasMessages = messages.isNotEmpty(),
                onFocusedAtBottom = { scope.launch { listState.animateScrollToItem(0) } },
                focusRequester = focusRequester,
                sttEnabled = sttUrl.isNotBlank(),
                dictation = dictation,
                onMicTap = ::onMicTap,
                caps = composerCaps,
                contextUsage = composerUsage,
                roomId = currentRoom?.id,
                brains = hubBrainsPanel.data,
                catalog = modelCatalog,
                catalogLoading = modelCatalogLoading,
                modelRecents = modelRecents,
                onReasoningCommand = { viewModel.sendReasoningCommand(it) },
                onBrainSelect = { viewModel.hub.brainSelect(it) },
                onModelSelect = { viewModel.models.select(it) },
                onRefreshCaps = { viewModel.refreshReasoningCaps(); viewModel.hub.refreshBrains() },
                onRefreshCatalog = { viewModel.models.refresh() },
                onRefreshCatalogHard = { viewModel.models.refresh(force = true) },
            )
        }

        // Light travel (2.2): the accent band crosses the room with the dissolve — arriving
        // somewhere reads as light moving with you. Under the braille wisps, over the content.
        // Mounted unconditionally: the sweep now runs on its own clock, which outlives a fast
        // dissolve, and gating the node on `dissolve < 1f` would tear the light down mid-cross.
        // Idle costs nothing — the draw returns immediately at rest, and the node takes no input.
        val sweepCore = chat.keryx.app.presentation.ui.components.keryxSweepCore()
        // One light for the whole beat: the gleam and the wake below it are the same hue, the one
        // the arriving room wears in the drawer. Two different accents crossing the same 1.2s was
        // most of why this read as assembled rather than composed.
        val arrivingRoom = currentRoom?.name.orEmpty()
        val arrivingLight =
            if (arrivingRoom.isNotBlank()) chat.keryx.app.presentation.ui.components.roomLight(arrivingRoom)
            else MaterialTheme.colorScheme.primary
        Box(
            Modifier.fillMaxSize().then(
                Modifier.keryxLightSweep(
                    arrivingLight,
                    MaterialTheme.colorScheme.tertiary,
                    core = sweepCore,
                    progress = rememberSweepProgress { dissolve.value },
                )
            )
        )
        // The wake rides the room-switch dissolve, carrying the light of the room you are
        // arriving in — the same hue its avatar wears in the drawer, so the light you tapped is
        // the light that travels. Falls back to the theme accents before a room is known.
        if (dissolve.value < 1f) {
            BrailleWake(
                progress = dissolve.value,
                color = arrivingLight,
                color2 = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // TOP INSTRUMENTS — the flight plan and the working banner, stacked.
        //
        // They were two separate TopCenter children of this Box and they occupied the SAME
        // pixels: the plan strip is ~28dp tall with a 94%-opaque floor and rode a zIndex(1f)
        // above everything, while the cloud banner sits 6dp from the same top edge and stands
        // ~46dp tall. So exactly when both were live — and a flight plan exists precisely
        // *because* a turn is running — the plan ate the banner's crown, and an opened plan ate
        // the banner whole. One column, so the rail reads top-down: the pinned instrument flush
        // to the edge, the transient cloud beneath it.
        //
        // Compact top "working" counter: a small spinner + what the agent is doing + elapsed clock,
        // plus a live ≈tok/s readout while side-channel tokens are flowing.
        // Pinned at the top so it stays put for the whole run, unlike the per-message tool labels.
        val topTokPerSec = liveStream?.takeIf {
            it.roomId == currentRoom?.id &&
                it.status == chat.keryx.app.presentation.LiveStreamStatus.STREAMING
        }?.charsPerSec?.div(4f) ?: 0f
        // Compaction takes the banner over while it runs: the gateway's own count of what it is
        // summarizing, in place of a verb it is not doing (2.5.7). Everything else it says stays
        // where it was — the clock keeps counting, the cloud keeps its shape.
        val compacting = sessionStatus?.takeIf { it.isCompacting }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(1f),
        ) {
            // FLIGHT PLAN: pinned with the instruments — the transcript scrolls, the plan doesn't.
            // Lifted above its sibling inside the column so the banner slides out from UNDER the
            // rail rather than across it.
            flightPlan?.takeIf { it.total > 0 }?.let { plan ->
                Box(Modifier.zIndex(1f)) {
                    chat.keryx.app.presentation.ui.components.FlightPlanStrip(plan)
                }
            }
            WorkingStatusBar(
                visible = awaitingReply || topTokPerSec > 0f || compacting != null,
                label = compacting?.headline ?: workLabel,
                compacting = compacting != null,
                startedAt = workStartedAt,
                tokPerSec = topTokPerSec,
                typingAgentIds = typingAgentIds,
                modifier = Modifier.padding(top = 6.dp),
                onTapIn = { tapInOpen = true },
            )
        }
    }

    openSubagent?.let { picked ->
        // Re-resolved by key on every recomposition rather than shown as the snapshot that was
        // tapped: a sheet opened over a FLYING wing has to keep growing as that wing's frames
        // land, and has to notice the moment it settles so it can fetch the real transcript.
        // Falls back to the snapshot when the run is no longer in the list (scrolled out of the
        // window, or a new turn replaced it) — a frozen record beats a sheet that empties.
        val live = renderItems.asSequence()
            .filterIsInstance<ChatRenderItem.ToolRun>()
            .flatMap { it.entries.asSequence() }
            .filterIsInstance<chat.keryx.app.presentation.ui.components.ToolRunEntry.Delegated>()
            .map { it.run }
            .firstOrNull { it.key == picked.key }
            ?: picked
        chat.keryx.app.presentation.ui.components.SubagentSessionSheet(
            run = live,
            fetch = { id -> viewModel.hub.sessionMessages(id) },
            onDismiss = { openSubagent = null },
            crew = viewModel.crewControls(openSubagentRoom),
        )
    }
}

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

@Composable
private fun EmptyChat(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
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
            text = viewModel.lexicon.emptyChat,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
        )
        // Direct door: the empty pane is also the front door for a fresh install — nothing to
        // select yet is the common case, so the way to make one is right here, not two taps
        // away behind the drawer.
        if (viewModel.transportIsDirect) {
            var showNewSession by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = { showNewSession = true }) {
                Icon(
                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("New session")
            }
            if (showNewSession) {
                chat.keryx.app.presentation.ui.components.NewChatSheet(
                    viewModel = viewModel,
                    onDismiss = { showNewSession = false },
                )
            }
        }
    }
}

/** A quiet centered chip marking a day boundary ("Today", "Yesterday", "Wednesday, Jul 2"). */
@Composable
fun DaySeparator(epochMillis: Long) {
    val label = remember(epochMillis) {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val sameDay = { a: java.util.Calendar, b: java.util.Calendar ->
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        }
        val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        when {
            sameDay(now, then) -> "Today"
            sameDay(yesterday, then) -> "Yesterday"
            now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) ->
                java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(then.time)
            else ->
                java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(then.time)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}
