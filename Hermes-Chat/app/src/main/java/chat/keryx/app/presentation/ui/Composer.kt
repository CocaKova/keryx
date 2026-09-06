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

// The composer and what hangs off it — the reply bar, attachments, the attach bloom, the
// wake glyph. Split out of ChatScreen.kt in 2.10 (Phase E): a move, zero behaviour change.

/** Where the composer's voice dictation currently is. */
internal enum class DictationPhase { IDLE, RECORDING, TRANSCRIBING }

/** An attachment the user has picked but not yet sent. */
internal data class PendingAttachment(
    val bytes: ByteArray,
    val name: String,
    val contentType: String,
    val isImage: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
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
    /** The picker's own refresh button: past the catalog's TTL, straight to the wire. */
    onRefreshCatalogHard: () -> Unit = onRefreshCatalog,
    // The busy tree: null = normal send; "steer" | "queue" | "stop" while a turn runs.
    busyAction: String? = null,
    onContextTap: (() -> Unit)? = null,
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
        onRefreshCatalogHard = onRefreshCatalogHard,
        busyAction = busyAction,
        onContextTap = onContextTap,
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
internal fun ComposerFooter(
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
    onRefreshCatalogHard: () -> Unit = onRefreshCatalog,
    busyAction: String?,
    /** Tap the ring to see what fills it (2.10). Null where the door cannot itemise. */
    onContextTap: (() -> Unit)? = null,
) {
    val usage = contextUsage?.takeIf { roomId != null && it.roomId == roomId }
    // The pill reads this room's route on arrival (2.10) — within the catalog's TTL this is
    // free, and a new session's sticky model shows without a tap on the pill.
    LaunchedEffect(roomId) { if (roomId != null) onRefreshCatalog() }
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
                onRefresh = { onRefreshCaps(); onRefreshCatalogHard() },
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
        usage?.let {
            chat.keryx.app.presentation.ui.components.KeryxContextRing(
                it.used, it.max,
                modifier = if (onContextTap != null) Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClickLabel = "What is in the context window", onClick = onContextTap)
                else Modifier,
            )
        }
    }
}

@Composable
internal fun ReplyBar(target: Message, onDismiss: () -> Unit) {
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
internal fun AttachmentPreview(att: PendingAttachment, onRemove: () -> Unit) {
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
internal fun DreamAttachBloom(visible: Boolean, onPhoto: () -> Unit, onFile: () -> Unit) {
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
internal fun DreamPill(
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
internal fun BrailleWake(
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
internal val WAKE_DOTS = intArrayOf(0x19, 0x38, 0x34, 0x26, 0x07, 0x0B)
