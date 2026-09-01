package chat.keryx.app.presentation.ui.components

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.SenderType
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🙏", "🔥", "👀", "✅")

/**
 * The arrival mark (2.3 §3): a hairline in the herald's own light, its sigil, and the plain fact
 * that nobody asked. Sits above the bubble it announces.
 *
 * Deliberately quiet — the *bubble* below carries the one focal effect (a single light sweep), and
 * two competing attention-grabs in the same beat would spend the room's whole attention budget on
 * one message.
 */
@Composable
internal fun ArrivalMark(message: Message) {
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
            KeryxGlyphs.Reply,
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
                        Icon(KeryxGlyphs.Reply, contentDescription = "Reply", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(KeryxGlyphs.Copy, contentDescription = "Copy text", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    if (onToggleKeep != null) {
                        IconButton(onClick = onToggleKeep, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (kept == true) KeryxGlyphs.BookmarkFilled else KeryxGlyphs.Bookmark,
                                contentDescription = if (kept == true) "Remove from Saved" else "Keep in Archive",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (onSpeak != null) {
                        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (speaking) KeryxGlyphs.StopSquare else KeryxGlyphs.Volume,
                                contentDescription = if (speaking) "Stop speaking" else "Read aloud",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                KeryxGlyphs.Trash,
                                contentDescription = "Delete message",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun replyPreviewText(m: Message): String = when {
    m.content.isNotBlank() -> m.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: m.content.trim()
    m.mediaKind != null -> "📎 ${m.fileName.ifBlank { "attachment" }}"
    else -> "message"
}

/** MXIDs compact to their localpart; a resolved display name passes through untouched
 *  ("Anna K." must not truncate at some incidental colon). */
internal fun shortSender(id: String): String =
    if (id.startsWith("@") && ':' in id) id.trimStart('@').substringBefore(':') else id

internal fun formatClock(ts: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))
