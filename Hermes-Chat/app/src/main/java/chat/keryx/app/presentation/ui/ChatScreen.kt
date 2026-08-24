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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.HermesThinkingAnimation
import chat.keryx.app.presentation.ui.components.MessageContent
import chat.keryx.app.presentation.ui.components.MessageMedia
import chat.keryx.app.presentation.ui.components.ToolTheaterRun
import chat.keryx.app.presentation.ui.components.AgentDeliveryNotice
import chat.keryx.app.presentation.ui.components.HeraldSigil
import chat.keryx.app.presentation.ui.components.LocalHeraldConfig
import chat.keryx.app.presentation.ui.components.bubbleAppearance
import chat.keryx.app.presentation.ui.components.heraldLightFor
import chat.keryx.app.presentation.ui.components.keryxLightSweep
import chat.keryx.app.presentation.ui.components.rememberSweepProgress
import androidx.compose.ui.text.font.FontFamily
import chat.keryx.app.presentation.ui.components.keryxMagicDust
import chat.keryx.app.presentation.ui.components.GroupedTimeline
import chat.keryx.app.presentation.ui.components.groupChatItemsIncremental
import chat.keryx.app.presentation.ui.components.withLiveTheater
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

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🙏", "🔥", "👀", "✅")

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
    val typingHumans by viewModel.typingHumans.collectAsState()
    val typingAgentIds by viewModel.typingAgentIds.collectAsState()
    val liveStream by viewModel.liveStream.collectAsState()
    val pendingSend by viewModel.pendingSend.collectAsState()
    val showTelemetry by viewModel.showTelemetry.collectAsState()
    val workStartedAt by viewModel.workStartedAt.collectAsState()
    val workLabel by viewModel.workLabel.collectAsState()
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
            EmptyChat(modifier = Modifier.align(Alignment.Center))
        }
        // FLIGHT PLAN: pinned with the instruments — the transcript scrolls, the plan doesn't.
        flightPlan?.takeIf { it.total > 0 }?.let { plan ->
            Box(Modifier.align(Alignment.TopCenter).zIndex(1f)) {
                chat.keryx.app.presentation.ui.components.FlightPlanStrip(plan)
            }
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
            } else if (awaitingReply) {
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
                            // Structured reasoning (the direct producer fills Message.reasoning;
                            // the Matrix parser gathers its own into the run instead): the quiet
                            // "Thought for Ns" disclosure, above whatever the turn said. A
                            // reasoning-ONLY row (a turn that was all thought before its tools)
                            // is just the disclosure — no empty bubble under it.
                            val thought = message.reasoning?.takeIf { it.isNotBlank() }
                            if (thought != null && message.content.isBlank()) {
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
                        Icons.Default.KeyboardArrowDown,
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
            // The agent is STOPPED waiting on a human — loudest thing on screen, right above
            // the composer where the answer happens (merge dowry, plan §5).
            androidx.compose.animation.AnimatedVisibility(visible = pendingApproval != null) {
                pendingApproval?.let { approval ->
                    Column {
                        chat.keryx.app.presentation.ui.components.ApprovalCard(approval) {
                            viewModel.respondApproval(it)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = pendingBlocking != null) {
                pendingBlocking?.let { request ->
                    Column {
                        chat.keryx.app.presentation.ui.components.BlockingRequestCard(request) {
                            viewModel.respondBlocking(request, it)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
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
            val composerCaps by viewModel.hub.reasoningCaps.collectAsState()
            val composerUsage by viewModel.contextUsage.collectAsState()
            val hubBrainsPanel by viewModel.hub.brains.collectAsState()
            Composer(
                textState = textState,
                onTextChange = { textState = it; viewModel.onComposerTextChanged(it.text) },
                onSend = ::doSend,
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
                onReasoningCommand = { viewModel.sendReasoningCommand(it) },
                onBrainSelect = { viewModel.hub.brainSelect(it) },
                onRefreshCaps = { viewModel.hub.refreshReasoningCaps(); viewModel.hub.refreshBrains() },
                onSteer = { viewModel.prefillComposer("/steer ") },
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

        // Compact top "working" counter: a small spinner + what the agent is doing + elapsed clock,
        // plus a live ≈tok/s readout while side-channel tokens are flowing.
        // Pinned at the top so it stays put for the whole run, unlike the per-message tool labels.
        val topTokPerSec = liveStream?.takeIf {
            it.roomId == currentRoom?.id &&
                it.status == chat.keryx.app.presentation.LiveStreamStatus.STREAMING
        }?.charsPerSec?.div(4f) ?: 0f
        WorkingStatusBar(
            visible = awaitingReply || topTokPerSec > 0f,
            label = workLabel,
            startedAt = workStartedAt,
            tokPerSec = topTokPerSec,
            typingAgentIds = typingAgentIds,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        )
    }

    openSubagent?.let { run ->
        chat.keryx.app.presentation.ui.components.SubagentSessionSheet(
            run = run,
            fetch = { id -> viewModel.hub.sessionMessages(id) },
            onDismiss = { openSubagent = null },
        )
    }
}

@Composable
private fun WorkingStatusBar(
    visible: Boolean,
    label: String,
    startedAt: Long?,
    tokPerSec: Float = 0f,
    /** Heralds typing right now — in a council room the bar wears one sigil each, so you can see
     *  *who* is working without waiting for the bubble (2.3 §1). */
    typingAgentIds: List<String> = emptyList(),
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
        val council = LocalHeraldConfig.current.council
        // The banner itself is the cloud: bumpy orbiting edges + a gentle bob, with the label inside.
        chat.keryx.app.presentation.ui.components.CloudBanner(
            // Opaque fill so the scalloped edge stays crisp (translucency made the bumps ghost
            // through each other, which is what read as "circles" in light mode).
            fill = MaterialTheme.colorScheme.surfaceVariant,
            border = accent.copy(alpha = 0.85f),
            border2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (council && typingAgentIds.isNotEmpty()) {
                    typingAgentIds.forEach { id ->
                        HeraldSigil(heraldLightFor(id, ""), fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = buildString {
                        append("$label · $clock")
                        // Live generation speed while tokens stream over the side-channel.
                        if (tokPerSec > 2f) append(" · ≈${tokPerSec.toInt()} tok/s")
                    },
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
    onReasoningCommand: (String) -> Unit = {},
    onBrainSelect: (String) -> Unit = {},
    onRefreshCaps: () -> Unit = {},
    onSteer: () -> Unit = {},
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
            .padding(start = 2.dp, end = 6.dp, top = 2.dp, bottom = 3.dp),
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
                    if (focus.isFocused && hasMessages && atBottom()) onFocusedAtBottom()
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
                        Icons.Default.Mic,
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
        Box {
            FloatingActionButton(
                onClick = {
                    if (textState.text.isNotBlank()) {
                        sendPuffTick++
                        sendHaptics.commit()
                    }
                    onSend()
                },
                containerColor = MaterialTheme.colorScheme.primary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                modifier = Modifier.size(48.dp).graphicsLayer {
                    val p = wing.value
                    scaleX = 1f + 0.12f * p
                    scaleY = 1f + 0.12f * p
                    rotationZ = -22f * p
                },
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
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
        onReasoningCommand = onReasoningCommand,
        onBrainSelect = onBrainSelect,
        onRefreshCaps = onRefreshCaps,
        onSteer = onSteer,
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
    onReasoningCommand: (String) -> Unit,
    onBrainSelect: (String) -> Unit,
    onRefreshCaps: () -> Unit,
    onSteer: () -> Unit,
) {
    val usage = contextUsage?.takeIf { roomId != null && it.roomId == roomId }
    if (caps == null && usage == null) return
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 1.dp),
    ) {
        // Model pill — the live brain by name, ▾ inside the same hit target.
        var modelMenu by remember { mutableStateOf(false) }
        val modelName = (usage?.model ?: "").ifBlank { caps?.model.orEmpty() }.ifBlank { "model" }
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { modelMenu = true; onRefreshCaps() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    modelName,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Text(" ▾", fontSize = 9.sp, color = meta.copy(alpha = 0.7f))
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                // The Spire brains roster — the machines that can actually answer here. The
                // gateway's /v1/models only knows its own name ("hermes-agent"), so it was never
                // the right source. Picking a brain starts a real swap (systemd + cooldown).
                val roster = brains?.brains.orEmpty()
                if (roster.isEmpty()) DropdownMenuItem(
                    text = { Text("No brains roster from the gateway", fontSize = 13.sp, color = meta) },
                    onClick = { modelMenu = false },
                )
                roster.forEach { b ->
                    val isActive = b.name == brains?.active
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isActive) "● " else "  ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Column {
                                    Text(
                                        b.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    )
                                    if (b.description.isNotBlank()) Text(
                                        b.description, fontSize = 10.sp, color = meta,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        onClick = { modelMenu = false; if (!isActive) onBrainSelect(b.name) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        // Reasoning pill — the relocated top-bar menu, now living where the thinking happens.
        var reasoningMenu by remember { mutableStateOf(false) }
        val levelLabel = caps?.let { c -> (c.labels[c.current] ?: c.current).ifBlank { "reasoning" } } ?: "reasoning"
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { reasoningMenu = true; onRefreshCaps() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
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
                onSteer = { reasoningMenu = false; onSteer() },
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

/**
 * The arrival mark (2.3 §3): a hairline in the herald's own light, its sigil, and the plain fact
 * that nobody asked. Sits above the bubble it announces.
 *
 * Deliberately quiet — the *bubble* below carries the one focal effect (a single light sweep), and
 * two competing attention-grabs in the same beat would spend the room's whole attention budget on
 * one message.
 */
@Composable
private fun ArrivalMark(message: Message) {
    val light = heraldLightFor(message.senderId, message.senderName)
    val clock = remember(message.timestamp) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(message.timestamp))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
    ) {
        HeraldSigil(light, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${light.name} · unprompted · $clock",
            color = light.accent.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(light.accent.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    replyTo: Message?,
    bubbleStyle: String,
    animationStyle: String = "Caduceus",
    textScale: Float,
    showSender: Boolean,
    /** This bubble was announced by an [ChatRenderItem.Arrival] — it gets one light sweep as it
     *  first composes, the focal beat that says a herald just walked in (2.3 §3). */
    arrival: Boolean = false,
    reactionsFlow: kotlinx.coroutines.flow.Flow<List<MessageReaction>>,
    mediaLoader: suspend () -> ByteArray?,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onQuoteClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** True while this message is being read aloud (or its speech is being fetched). */
    speaking: Boolean = false,
    /** Read this message aloud / stop reading it. Null hides the affordance (non-agent senders). */
    onSpeak: (() -> Unit)? = null,
    /** Whether this message is kept in the Archive's Saved list; null hides the affordance. */
    kept: Boolean? = null,
    onToggleKeep: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isMine = message.sender == SenderType.ME
    val isAgent = message.sender == SenderType.HERMES
    // 2.3 §1: an agent bubble carries its herald's light. Humans and I keep the theme's own
    // accents, and so does the primary herald — a 1:1 room looks exactly like 2.2.
    val herald = if (isAgent) heraldLightFor(message.senderId, message.senderName) else null
    val heraldRim = herald != null && !herald.primary
    var showReactionPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val reactions by reactionsFlow.collectAsState(initial = emptyList())

    // Swipe-to-reply: pull the message LEFT and let go — a reply arrow condenses behind it on the
    // right as you pull, haptic ticks at the commit point, then the bubble springs home. Reply is
    // leftward for every message (mine and agent) so a rightward swipe is always free to open the
    // left-edge navigation drawer — agent bubbles used to pull right and swallowed that gesture.
    val dragX = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    val haptics = chat.keryx.app.presentation.ui.components.LocalKeryxHaptics.current
    val replyThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // The arrow that materializes as you pull.
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(
                alpha = (dragX.value / replyThresholdPx).coerceIn(0f, 0.9f)
            ),
            modifier = Modifier
                .align(Alignment.CenterEnd)
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
            .graphicsLayer { translationX = -dragX.value }
            .pointerInput(message.id) {
                // Claim the gesture ONLY once the drag proves LEFTWARD at touch slop. The old
                // detectHorizontalDragGestures consumed the slop-crossing event for either
                // direction, which cancelled the drawer's own drag detector — that's why the
                // right-swipe-to-open-drawer only landed when the finger happened to start on
                // the sliver of screen not covered by a bubble. A rightward slop is now left
                // completely unconsumed, so the drawer sees a virgin gesture and opens.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    fun apply(amount: Float): Float {
                        val next = (dragX.value + -amount * 0.62f).coerceIn(0f, replyThresholdPx * 1.5f)
                        if (!fired && next >= replyThresholdPx) {
                            fired = true
                            haptics.commit()
                        }
                        dragScope.launch { dragX.snapTo(next) }
                        return next
                    }
                    val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        if (overSlop < 0f) {
                            change.consume()
                            apply(overSlop)
                        }
                        // Rightward: never consume — the drawer takes it. (If the finger later
                        // reverses past slop leftward, this callback re-fires and we claim it.)
                    }
                    if (first != null) {
                        horizontalDrag(first.id) { change ->
                            val next = apply(change.positionChange().x)
                            if (next > 0f) change.consume()
                        }
                        if (dragX.value >= replyThresholdPx) onReply()
                        dragScope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                    } else if (dragX.value > 0f) {
                        dragScope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                    }
                }
            }
    ) {
        // 2.3 §2: this account is relaying another agent — say so before the words, so a delivery
        // is never mistaken for the courier speaking.
        message.agentDelivery?.let { delivery ->
            AgentDeliveryNotice(
                delivery = delivery,
                accent = herald?.accent,
            )
        }

        if (showSender && !isMine && message.senderName.isNotBlank()) {
            if (herald != null && LocalHeraldConfig.current.council) {
                // In a council room the name is the only thing that says *which* agent spoke, so
                // it gets the sigil and the herald's own colour.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                ) {
                    HeraldSigil(herald, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = shortSender(message.senderName),
                        color = herald.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    text = shortSender(message.senderName),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
        }

        // Double-tap-to-❤️ bloom: a heart swells out of the tap and exhales away.
        var heartBloomTick by remember { mutableStateOf(0) }

        if (message.isStreaming && message.content.isEmpty() && message.mediaKind == null) {
            HermesThinkingAnimation(
                style = animationStyle,
                modifier = Modifier.padding(8.dp),
                accent = herald?.accent,
                accent2 = herald?.accent2,
            )
        } else if (message.content.isNotEmpty() || message.mediaKind != null) {
            val appearance = bubbleAppearance(
                isMine = isMine,
                style = bubbleStyle,
                accent = herald?.accent ?: MaterialTheme.colorScheme.primary,
                accent2 = herald?.accent2 ?: MaterialTheme.colorScheme.tertiary,
                heraldRim = heraldRim,
            )
            val shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            )
            val baseDensity = LocalDensity.current
            // The arrival's one focal beat: a single pass of light across the bubble as it first
            // composes. One-shot by construction (the Animatable never resets), and skipped
            // outright under reduced motion — an arrival still reads from the mark above it.
            val reducedMotion by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
            val arrivalSweep = remember(message.id) { Animatable(0f) }
            LaunchedEffect(message.id, arrival, reducedMotion) {
                if (arrival && !reducedMotion && arrivalSweep.value == 0f) {
                    arrivalSweep.animateTo(1f, tween(1100, easing = LinearEasing))
                }
            }
            Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .keryxLightSweep(
                        herald?.accent ?: MaterialTheme.colorScheme.primary,
                        herald?.accent2 ?: MaterialTheme.colorScheme.tertiary,
                        core = chat.keryx.app.presentation.ui.components.keryxSweepCore(),
                    ) { arrivalSweep.value }
                    // While the agent's reply is still growing, magic sand rises off the bubble's
                    // edge and sifts back down — the dreaming made visible, in the user's own
                    // accents. Sits BEFORE clip() so the dust lives outside the shape; the last
                    // grains finish falling after the words land (2.0, Jonny's call: real sand
                    // over a border gleam).
                    .keryxMagicDust(active = isAgent && message.isStreaming, shape = shape)
                    .clip(shape)
                    .background(appearance.brush)
                    .then(
                        when {
                            appearance.edgeBrush != null -> Modifier.border(1.5.dp, appearance.edgeBrush, shape)
                            appearance.border != null -> Modifier.border(1.dp, appearance.border, shape)
                            else -> Modifier
                        }
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
                        // MSC2530 caption: the body carries the sender's words (a bare filename
                        // body is just the upload name — not worth a text block).
                        val caption = message.content.takeIf { it.isNotBlank() && it != message.fileName }
                        if (caption != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            CompositionLocalProvider(
                                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
                            ) {
                                MessageContent(
                                    content = caption,
                                    textColor = appearance.textColor,
                                    isStreaming = message.isStreaming,
                                    isAgent = message.sender == SenderType.HERMES,
                                )
                            }
                        }
                    } else {
                        CompositionLocalProvider(
                            LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * textScale)
                        ) {
                            MessageContent(
                                content = message.content,
                                textColor = appearance.textColor,
                                isStreaming = message.isStreaming,
                                isAgent = message.sender == SenderType.HERMES,
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
                onDelete = onDelete?.let { { showReactionPicker = false; confirmDelete = true } },
                onSpeak = onSpeak?.let { speak -> { showReactionPicker = false; speak() } },
                speaking = speaking,
                kept = kept,
                onToggleKeep = onToggleKeep?.let { toggle -> { showReactionPicker = false; toggle() } },
                onDismiss = { showReactionPicker = false },
            )
        }

        if (confirmDelete) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete message?", fontSize = 16.sp) },
                text = { Text("It's removed for everyone — this can't be undone.", fontSize = 13.sp) },
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
            if (speaking && onSpeak != null) {
                Spacer(modifier = Modifier.width(6.dp))
                // Breathing speaker while the voice is live (dimmer while speech is being fetched);
                // tapping it stops playback without reopening the long-press bar.
                val reducedMotion by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
                val pulse = if (!reducedMotion) {
                    rememberInfiniteTransition(label = "ttsPulse").animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "ttsPulseAlpha",
                    ).value
                } else 1f
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Stop speaking",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                    modifier = Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSpeak() },
                )
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(reactions: List<MessageReaction>, isMine: Boolean, onReact: (String) -> Unit) {
    // FlowRow, not Row: once SILAS starts reacting too, >5 distinct emoji overflow a bubble width.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
private fun ReactionPickerRow(
    onPick: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    speaking: Boolean = false,
    kept: Boolean? = null,
    onToggleKeep: (() -> Unit)? = null,
) {
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
                    if (onToggleKeep != null) {
                        IconButton(onClick = onToggleKeep, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (kept == true) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (kept == true) "Remove from Saved" else "Keep in Archive",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (onSpeak != null) {
                        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (speaking) "Stop speaking" else "Read aloud",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Delete message",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            )
                        }
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

private fun replyPreviewText(m: Message): String = when {
    m.content.isNotBlank() -> m.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: m.content.trim()
    m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "attachment" }}"
    else -> "message"
}

/** MXIDs compact to their localpart; a resolved display name passes through untouched
 *  ("Anna K." must not truncate at some incidental colon). */
private fun shortSender(id: String): String =
    if (id.startsWith("@") && ':' in id) id.trimStart('@').substringBefore(':') else id

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

    // The live reply wears a quiet, steady border — the life is in the magic sand rising off
    // its edge while tokens flow (2.0, Jonny's call: real sand over a breathing gleam). This is
    // THE streaming bubble users actually see (the side-channel path); MessageBubble's
    // isStreaming dust is the Matrix-sync fallback twin.
    val glow = 0.3f

    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    val baseDensity = LocalDensity.current
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .keryxMagicDust(active = streaming, shape = shape)
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
                    // Live reasoning, thinking rendered AS it happens: auto-expanded while the
                    // model is still purely thinking, folding to the "💭 Reasoning" chip the
                    // moment answer tokens start (same canvas the committed message uses, so the
                    // handoff swap keeps the identical visual).
                    if (stream.reasoning.isNotBlank()) {
                        chat.keryx.app.presentation.ui.components.ReasoningCanvas(
                            text = stream.reasoning,
                            baseColor = appearance.textColor,
                            active = streaming && stream.text.isBlank(),
                        )
                    }
                    // No tool theater in here (3.1 §A2). What the agent is DOING belongs to the
                    // transcript, where the run already is — this bubble is what it is SAYING.
                    // The stage used to draw the same calls a second time, a few dp under the run
                    // that was drawing them properly, in a different vocabulary.
                    if (stream.text.isNotBlank()) chat.keryx.app.presentation.ui.components.MessageContent(
                        content = stream.text,
                        textColor = appearance.textColor,
                        isStreaming = streaming,
                    )
                    if (streaming) {
                        // A quiet blinking caret marks "still writing" without a layout-shifting
                        // spinner; its blink crossfades accent 1 → accent 2. Beside it, a live
                        // ≈tok/s readout — practical telemetry that also just looks alive.
                        // Stilled, the caret holds solid rather than blinking — the text growing
                        // above it is the liveness signal, and the ≈tok/s readout beside it moves
                        // on its own without a frame clock.
                        val reducedMotion by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
                        val a = if (!reducedMotion) {
                            rememberInfiniteTransition(label = "caret").animateFloat(
                                initialValue = 0.15f, targetValue = 0.9f,
                                animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
                                label = "caretAlpha",
                            ).value
                        } else 0.9f
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
    val reducedMotion by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    val tickAlpha = if (!reducedMotion) {
        rememberInfiniteTransition(label = "sendBreathe").animateFloat(
            initialValue = 0.25f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "sendTickAlpha",
        ).value
    } else 0.9f

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
                progress = true,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        val quipAccent = MaterialTheme.colorScheme.primary
        val quipAccent2 = MaterialTheme.colorScheme.tertiary
        androidx.compose.animation.AnimatedContent(targetState = idx, label = "quip") { i ->
            Text(
                text = quips[i],
                style = androidx.compose.ui.text.TextStyle(
                    // Same accent-1 → accent-2 sweep as the braille snake beside it.
                    brush = Brush.linearGradient(
                        listOf(quipAccent.copy(alpha = 0.9f), quipAccent2.copy(alpha = 0.9f)),
                    ),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontSize = 13.sp,
                ),
            )
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
