package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message

@Composable
internal fun WorkingStatusBar(
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

/**
 * The tier-1 live response: tokens streaming over the Hermes side-channel, rendered as an agent
 * bubble with a softly pulsing accent border. On `stop` it holds perfectly still (AWAITING_SYNC)
 * until the identical committed Matrix event replaces it — same text, same layout, so the swap is
 * invisible. A mid-stream drop keeps the partial text and shows a quiet recovery alert instead of
 * losing what was already read.
 */
@Composable
internal fun StreamingBubble(
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
internal fun PendingSendBubble(text: String, bubbleStyle: String, textScale: Float) {
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
internal fun TelemetryMessageRow(message: Message, textScale: Float) {
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
internal fun WaitingIndicator() {
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
