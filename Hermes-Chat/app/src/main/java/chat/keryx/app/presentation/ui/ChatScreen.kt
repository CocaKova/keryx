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

/** Where the composer's voice dictation currently is. */
private enum class DictationPhase { IDLE, RECORDING, TRANSCRIBING }

/** An attachment the user has picked but not yet sent. */
private data class PendingAttachment(
    val bytes: ByteArray,
    val name: String,
    val contentType: String,
    val isImage: Boolean,
)

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
            val gen = tts.prepare(message.id)
            val out = java.io.File(context.cacheDir, "tts_reply_${System.currentTimeMillis()}.mp3")
            viewModel.voice.synthesizeSpeech(text, out) { result ->
                result.onSuccess { tts.playFile(message.id, gen, it) }
                    .onFailure { err ->
                        out.delete()
                        // Only reset if this fetch is still the active one — a failure arriving
                        // after the user already started another message must not silence it.
                        if (tts.state.value.messageId == message.id) tts.stop()
                        android.widget.Toast.makeText(context, "Speech failed: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
            }
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
                            onOpenSubagent = { openSubagent = it },
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
                                if (showTelemetry) TelemetryMessageRow(message, textScale = messageTextScale)
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
                            MessageBubble(
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
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
            val agentLive = liveStream != null || typingAgentIds.isNotEmpty() || liveTurnSigns
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
            )
        }
    }

    openSubagent?.let { run ->
        chat.keryx.app.presentation.ui.components.SubagentSessionSheet(
            run = run,
            fetch = { id -> viewModel.hub.sessionMessages(id) },
            onDismiss = { openSubagent = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    textState: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    atBottom: () -> Boolean,
    hasMessages: Boolean,
    onFocusedAtBottom: () -> Unit,
    focusRequester: FocusRequester,
    sttEnabled: Boolean = false,
    dictation: DictationPhase = DictationPhase.IDLE,
    onMicTap: () -> Unit = {},
    // The footer line (2.2, the Talaria treatment): model · reasoning · context ring, docked
    // inside the composer surface. All null/empty when Hermes Link is off — pure-Matrix rooms
    // keep the plain bar.
    caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps? = null,
    contextUsage: ChatViewModel.ContextUsage? = null,
    roomId: String? = null,
    brains: chat.keryx.app.data.remote.HermesStreamClient.Brains? = null,
    catalog: chat.keryx.core.model.ModelCatalog? = null,
    catalogLoading: Boolean = false,
    modelRecents: List<String> = emptyList(),
    onReasoningCommand: (String) -> Unit = {},
    onBrainSelect: (String) -> Unit = {},
    onModelSelect: (chat.keryx.core.model.ModelChoice) -> Unit = {},
    onRefreshCaps: () -> Unit = {},
    onRefreshCatalog: () -> Unit = {},
    // The busy tree: null = normal send; "steer" | "queue" | "stop" while a turn runs.
    busyAction: String? = null,
    onSteer: () -> Unit = {},
    onQueue: () -> Unit = {},
    onStop: () -> Unit = {},
    onStopHint: () -> Unit = {},
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
    // One hairline surface holds everything (the Talaria treatment): near-square, matte,
    // gilt-adjacent border — the input row on top, the status footer beneath.
    val composerShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(composerShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), composerShape)
            .padding(start = 2.dp, end = 4.dp, top = 2.dp, bottom = 0.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                    chat.keryx.app.presentation.ui.components.KeryxGlyphs.Plus,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = addRotation },
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
                    if (focus.isFocused && hasMessages && atBottom()) onFocusedAtBottom()
                },
            placeholder = {
                Text(
                    // Mid-turn the placeholder IS the teacher: the affordance reached for was
                    // /steer because nothing said the composer could do it.
                    when (busyAction) {
                        null -> "Message…"
                        "stop" -> "Type to steer this turn — ■ stops"
                        else -> "Type to steer this turn"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
        if (sttEnabled) {
            val recording = dictation == DictationPhase.RECORDING
            // A slow breathing pulse while the mic is hot — unmistakable "it's listening".
            // The transition used to exist whenever the composer did, animating 1f → 1f while idle:
            // no visible motion, one frame-clock client held open for every second the app was on
            // screen. It now exists only while the mic is actually hot, and not under Battery Saver
            // — where a hot mic reads from the icon's error tint instead of from its scale.
            val reducedMotion by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
            val pulse = if (recording && !reducedMotion) {
                rememberInfiniteTransition(label = "mic_pulse").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
                    label = "mic_pulse_scale",
                ).value
            } else 1f
            IconButton(onClick = onMicTap, modifier = Modifier.size(44.dp)) {
                if (dictation == DictationPhase.TRANSCRIBING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        chat.keryx.app.presentation.ui.components.KeryxGlyphs.Mic,
                        contentDescription = if (recording) "Stop dictation" else "Dictate",
                        tint = if (recording) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        // The send ritual (2.1): the message lifts off with a puff of magic sand and the button
        // itself flicks like a wing — a snap back-and-under, then a spring home with mass — while
        // a haptic tick marks the moment of departure. Only when something real leaves.
        var sendPuffTick by remember { mutableStateOf(0) }
        val sendHaptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
        val wing = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(sendPuffTick) {
            if (sendPuffTick > 0) {
                wing.snapTo(1f)
                wing.animateTo(0f, chat.keryx.app.presentation.ui.components.KeryxMotion.settle)
            }
        }
        // The primary circle is desktop's whole submit tree in one control: send when idle;
        // while a turn runs it becomes steer (text), queue (payload/compacting; also steer's
        // long-press), or stop (empty). One button, states legible by glyph.
        val (glyph, label) = when (busyAction) {
            "steer" -> chat.keryx.app.presentation.ui.components.KeryxGlyphs.Steer to "Steer the running turn"
            "queue" -> chat.keryx.app.presentation.ui.components.KeryxGlyphs.Stack to "Queue for next turn"
            "stop" -> chat.keryx.app.presentation.ui.components.KeryxGlyphs.StopSquare to "Stop the turn"
            // Arrow-up, the desktop's send — never a paper plane among hand-drawn glyphs.
            else -> chat.keryx.app.presentation.ui.components.KeryxGlyphs.ArrowUp to "Send"
        }
        val armed = textState.text.isNotBlank() || busyAction == "stop"
        // Idle, the circle was the accent at 55% ALPHA and the arrow on it was hard white. On
        // parchment that wash composites to #E79961 and white on it measures **2.24:1** — under
        // the 3:1 WCAG asks even of a graphical object, so the send arrow was a ghost sitting on
        // a peach coin. The quiet state now composites to an OPAQUE ground (same colour to the
        // eye, no alpha-on-alpha) and takes the glyph that actually reads on it: 7.4:1 on paper,
        // 7.5:1 on the void. Armed keeps the white arrow on full accent — 3.62:1, past the bar an
        // icon is held to, and the button everyone already knows.
        val sendGround = if (armed) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                .compositeOver(MaterialTheme.colorScheme.surfaceVariant)
        val sendInk = if (armed) Color.White
            else chat.keryx.app.presentation.ui.components.contrastColorFor(sendGround)
        Box {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        val p = wing.value
                        scaleX = 1f + 0.12f * p
                        scaleY = 1f + 0.12f * p
                        rotationZ = -22f * p
                    }
                    .clip(RoundedCornerShape(50))
                    .background(sendGround)
                    .combinedClickable(
                        onClick = {
                            when (busyAction) {
                                "steer" -> { if (textState.text.isNotBlank()) sendHaptics.commit(); onSteer() }
                                "queue" -> { if (textState.text.isNotBlank()) sendHaptics.commit(); onQueue() }
                                "stop" -> { sendHaptics.commit(); onStop() }
                                else -> {
                                    if (textState.text.isNotBlank()) {
                                        sendPuffTick++
                                        sendHaptics.commit()
                                    }
                                    onSend()
                                }
                            }
                        },
                        // Desktop's ⌘⏎, translated: long-press while steerable queues instead.
                        // Long-press on STOP teaches (it was the first thing tried, and silence
                        // read as broken).
                        onLongClick = when (busyAction) {
                            "steer" -> onQueue
                            "stop" -> onStopHint
                            else -> null
                        },
                    ),
            ) {
                Icon(glyph, contentDescription = label, tint = sendInk, modifier = Modifier.size(24.dp))
            }
            chat.keryx.app.presentation.ui.components.KeryxPuffBurst(
                tick = sendPuffTick,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
    ComposerFooter(
        caps = caps,
        contextUsage = contextUsage,
        roomId = roomId,
        brains = brains,
        catalog = catalog,
        catalogLoading = catalogLoading,
        modelRecents = modelRecents,
        onReasoningCommand = onReasoningCommand,
        onBrainSelect = onBrainSelect,
        onModelSelect = onModelSelect,
        onRefreshCaps = onRefreshCaps,
        onRefreshCatalog = onRefreshCatalog,
        busyAction = busyAction,
    )
    } // end composer surface Column
    } // end Column (attach bloom + composer row)
}

/**
 * The composer's own status line: model pill (tap = pick from the gateway's routes), reasoning
 * pill (the full effort menu, gated by what the active brain declares), and the context ring.
 * Mono and tiny on purpose — it's an instrument readout, not chrome. Renders nothing when the
 * gateway link is absent.
 */
@Composable
private fun ComposerFooter(
    caps: chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?,
    contextUsage: ChatViewModel.ContextUsage?,
    roomId: String?,
    brains: chat.keryx.app.data.remote.HermesStreamClient.Brains?,
    catalog: chat.keryx.core.model.ModelCatalog?,
    catalogLoading: Boolean,
    modelRecents: List<String>,
    onReasoningCommand: (String) -> Unit,
    onBrainSelect: (String) -> Unit,
    onModelSelect: (chat.keryx.core.model.ModelChoice) -> Unit,
    onRefreshCaps: () -> Unit,
    onRefreshCatalog: () -> Unit,
    busyAction: String?,
) {
    val usage = contextUsage?.takeIf { roomId != null && it.roomId == roomId }
    if (caps == null && usage == null) return
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    // The footer is an instrument readout, but two of its three readouts are the app's most-used
    // controls after Send — the model picker and the reasoning dial. At `padding(vertical = 2.dp)`
    // round a 10.5sp line they were **~17dp** tall: a target you aim at rather than hit, on the
    // control that decides which brain answers. The row is a 40dp band now and the pills fill it,
    // so the readouts sit exactly where they sat and the taps land. The composer's own bottom
    // padding is given back to the band so the growth is ~17dp, not 21.
    val pillShape = RoundedCornerShape(
        chat.keryx.app.presentation.ui.components.KeryxRadius.chip
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp, end = 4.dp),
    ) {
        // Model pill — the live brain by name, ▾ inside the same hit target.
        var modelMenu by remember { mutableStateOf(false) }
        val modelName = (usage?.model ?: "").ifBlank { catalog?.model.orEmpty() }
            .ifBlank { caps?.model.orEmpty() }.ifBlank { "model" }.substringAfter('/')
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(pillShape)
                    .clickable { modelMenu = true; onRefreshCaps(); onRefreshCatalog() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                // The name rises into place when the brain changes — the readout answers the pick.
                AnimatedContent(
                    targetState = modelName,
                    transitionSpec = {
                        (fadeIn(KeryxMotion.settle) + slideInVertically(KeryxMotion.settleInt) { it / 2 })
                            .togetherWith(fadeOut(KeryxMotion.leave) + slideOutVertically(KeryxMotion.leaveInt) { -it / 2 })
                    },
                    label = "modelPill",
                ) { name ->
                    Text(
                        name,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
                Text(" ▾", fontSize = 9.sp, color = meta.copy(alpha = 0.7f))
            }
            if (modelMenu) chat.keryx.app.presentation.ui.components.ModelPickerSheet(
                catalog = catalog,
                loading = catalogLoading,
                recents = modelRecents,
                brains = brains,
                onDismiss = { modelMenu = false },
                onPick = onModelSelect,
                onBrainPick = onBrainSelect,
                onRefresh = { onRefreshCaps(); onRefreshCatalog() },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        // Reasoning pill — the relocated top-bar menu, now living where the thinking happens.
        var reasoningMenu by remember { mutableStateOf(false) }
        val levelLabel = caps?.let { c -> (c.labels[c.current] ?: c.current).ifBlank { "reasoning" } } ?: "reasoning"
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(pillShape)
                    .clickable { reasoningMenu = true; onRefreshCaps() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    levelLabel,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = meta,
                    maxLines = 1,
                )
                Text(" ▾", fontSize = 9.sp, color = meta.copy(alpha = 0.7f))
            }
            chat.keryx.app.presentation.ui.ReasoningMenu(
                expanded = reasoningMenu,
                caps = caps,
                onDismiss = { reasoningMenu = false },
                onCommand = { arg -> reasoningMenu = false; onReasoningCommand(arg) },
            )
        }
        if (busyAction == "steer") {
            Spacer(Modifier.width(10.dp))
            Text(
                "↪ steers the turn · hold to queue",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Yields first when the line is tight — the pills are controls, this is a hint.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        usage?.let { chat.keryx.app.presentation.ui.components.KeryxContextRing(it.used, it.max) }
    }
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
            Icon(chat.keryx.app.presentation.ui.components.KeryxGlyphs.Close, contentDescription = "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            chat.keryx.app.presentation.ui.components.decodeSampled(att.bytes, targetPx = 128, longEdge = false)
        } else null
        if (thumb != null) {
            androidx.compose.foundation.Image(
                bitmap = thumb,
                contentDescription = att.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                if (att.isImage) chat.keryx.app.presentation.ui.components.KeryxGlyphs.Image
                else chat.keryx.app.presentation.ui.components.KeryxGlyphs.FileClip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
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
            Icon(chat.keryx.app.presentation.ui.components.KeryxGlyphs.Close, contentDescription = "Remove attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
            DreamPill("Photo", chat.keryx.app.presentation.ui.components.KeryxGlyphs.Image, accent, delayMs = 0) { onPhoto() }
            DreamPill("File", chat.keryx.app.presentation.ui.components.KeryxGlyphs.FileClip, accent, delayMs = 55) { onFile() }
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
 * A full-screen dream-field of braille glyphs that condenses out of the blur mid-transition and
 * scatters again — dots switching patterns as they drift, like thoughts crossing between rooms.
 * Peak visibility at the middle of [progress]; fully gone at both ends, so it never obstructs.
 */
@Composable
private fun BrailleWake(
    progress: Float,
    color: Color,
    color2: Color,
    modifier: Modifier = Modifier,
) {
    // The room-switch beat, rebuilt for 2.5.
    //
    // What was here (2026-07-02, seven weeks before the gilded void gave the app a visual
    // language) was 72 hash-scattered glyphs at random sizes mutating on a counter. Every other
    // living thing in Keryx is *ordered* — the snake traces a path, the sand obeys physics, the
    // sweep is one specular pass — so a field of noise read as a different app's idea, and light
    // mode exposed it because noise had no darkness left to hide in.
    //
    // So: a wake, not a scatter. Braille streams in lanes from the edge you came from, a bright
    // leading edge with a fading tail, in the DESTINATION room's own light. That is 2.2's
    // endorsed-but-never-built vision — "room-to-room navigation as light traveling with you" —
    // and it says something true: this room has its own life, and you have just walked into it.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 128)
    // sin envelope: nothing at either end, fullest mid-flight.
    val envelope = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat())
    if (envelope <= 0.01f) return

    androidx.compose.foundation.Canvas(modifier = modifier) {
        // The front runs past the right edge by the tail's length so the last lane empties out
        // instead of being cut off mid-stream when progress lands.
        val front = progress * (1f + WAKE_TAIL)
        val laneH = size.height / WAKE_LANES
        val stepPx = size.width * WAKE_STEP

        for (lane in 0 until WAKE_LANES) {
            val laneF = lane / (WAKE_LANES - 1f)
            // A deterministic per-lane lead so the front is a soft diagonal rather than a wall —
            // the difference between a curtain and something moving through the room.
            val lead = ((lane * 37) % 11) / 11f * WAKE_SKEW
            val laneFront = (front - lead) * size.width
            if (laneFront <= 0f) continue

            // Lanes breathe apart slightly as they travel, so the stream has depth.
            val drift = kotlin.math.sin((progress * Math.PI.toFloat()) + lane) * laneH * 0.18f
            val y = lane * laneH + laneH * 0.5f + drift
            val laneColor = androidx.compose.ui.graphics.lerp(color, color2, laneF)

            var i = 0
            while (true) {
                val gx = laneFront - i * stepPx
                if (gx < -stepPx) break
                // Distance behind the leading edge, 0 at the front -> 1 at the tail's end.
                val behind = (i * stepPx) / (size.width * WAKE_TAIL)
                if (behind > 1f) break
                i++
                if (gx > size.width) continue

                // An ordered ring of dot patterns, advanced along the lane: consecutive glyphs
                // read as one thing streaming past, which is the conga line the snake walks —
                // not the per-glyph randomness this replaced.
                val glyph = (0x2800 + WAKE_DOTS[(i + lane) % WAKE_DOTS.size]).toChar()
                // Bright at the edge, fading back. Squared so the head stays crisp and the tail
                // gives up quickly rather than smearing halfway across the screen.
                val fade = (1f - behind) * (1f - behind)
                val a = (fade * envelope * 0.85f).coerceIn(0f, 1f)
                if (a < 0.02f) continue

                val layout = measurer.measure(
                    text = glyph.toString(),
                    style = androidx.compose.ui.text.TextStyle(fontSize = WAKE_GLYPH_SP.sp),
                )
                drawText(
                    textLayoutResult = layout,
                    color = laneColor.copy(alpha = a),
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = gx - layout.size.width / 2f,
                        y = y - layout.size.height / 2f,
                    ),
                )
            }
        }
    }
}

/** Lanes the wake streams along. Odd, so one runs through the middle of the screen. */
private const val WAKE_LANES = 9

/** Tail length as a fraction of screen width — how far the stream trails its leading edge. */
private const val WAKE_TAIL = 0.42f

/** Gap between glyphs along a lane, as a fraction of width. */
private const val WAKE_STEP = 0.052f

/** How far lanes lead or lag each other, as a fraction of the crossing. Enough to read as a
 *  diagonal, little enough that it never reads as ragged. */
private const val WAKE_SKEW = 0.22f

private const val WAKE_GLYPH_SP = 15f

/**
 * One ordered turn of the braille dot ring — the same six-step conga the spinner walks, so the
 * wake and the emblem are visibly the same alphabet.
 */
private val WAKE_DOTS = intArrayOf(0x19, 0x38, 0x34, 0x26, 0x07, 0x0B)

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

private data class SlashCommand(
    val cmd: String,
    val desc: String,
    val takesArgs: Boolean,
    val aliases: List<String> = emptyList(),
)

/** Offline fallback: the palette before the gateway's live registry has been fetched. */
private val PRESET_COMMANDS = listOf(
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

@Composable
fun CommandPaletteMenu(
    filter: String,
    recents: List<String>,
    onCommandSelected: (String, Boolean) -> Unit,
    live: List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand> = emptyList(),
) {
    // The live registry (what's actually installed on the connected gateway — core commands
    // plus plugin-registered ones) replaces the preset guess once fetched. An args_hint means
    // the command takes arguments: fill the composer instead of auto-sending.
    val all = remember(live) {
        if (live.isEmpty()) PRESET_COMMANDS
        else live.map {
            SlashCommand(
                cmd = it.cmd,
                desc = it.description,
                takesArgs = it.argsHint.isNotBlank(),
                aliases = it.aliases,
            )
        }
    }
    val q = filter.trim().lowercase()
    val matches = all.filter {
        q.isBlank() || it.cmd.removePrefix("/").startsWith(q) ||
            it.aliases.any { a -> a.removePrefix("/").startsWith(q) }
    }
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
