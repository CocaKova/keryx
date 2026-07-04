package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

/**
 * Renders message markdown. GFM **tables** are split out and drawn as real Compose grids
 * (the markdown renderer has no table component), everything else goes through the CommonMark
 * renderer. Designed to be extended with more rich message mechanics later.
 */
@Composable
fun MessageContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
) {
    val segments = remember(content) { MessageParser.parse(content) }
    // Render **strong** spans heavier than the library's default (FontWeight.Bold looked too light).
    val annotator = markdownAnnotator { source, node ->
        // `this` is the AnnotatedString.Builder.
        if (node.type == MarkdownElementTypes.STRONG) {
            val inner = node.getTextInNode(source).toString().trim('*', '_')
            pushStyle(SpanStyle(fontWeight = FontWeight.Black))
            append(inner)
            pop()
            true
        } else {
            false
        }
    }
    // Render code blocks/fences in a horizontally-scrollable monospace surface so long lines
    // (e.g. ASCII-art diagrams some brains emit) can be panned instead of overflowing off-screen.
    val components = remember(textColor) {
        markdownComponents(
            codeBlock = { ScrollableCodeBlock(it.content, textColor) },
            codeFence = { ScrollableCodeBlock(it.content, textColor) },
        )
    }
    Column(modifier = modifier) {
        val lastIndex = segments.lastIndex
        segments.forEachIndexed { index, segment ->
            when (segment) {
                // closeDanglingFences: an unclosed ``` (mid-stream, or a sloppy brain) renders as
                // a code block instead of visually swallowing the rest of the message.
                is MessageParser.Segment.Text -> {
                    // While streaming, the trailing (still-growing) paragraph is drawn by
                    // FadingStreamText so new tokens fade in instead of typewriter-popping.
                    // Completed paragraphs stay full markdown; a paragraph "graduates" the moment
                    // the next one starts. Inside an unclosed code fence the split would tear the
                    // fence apart, so the whole segment stays on the markdown path there.
                    val fadeTail = isStreaming && index == lastIndex && !hasOpenFence(segment.text)
                    val splitAt = if (fadeTail) segment.text.lastIndexOf("\n\n") else -1
                    val head = when {
                        !fadeTail -> segment.text
                        splitAt >= 0 -> segment.text.substring(0, splitAt + 2)
                        else -> ""
                    }
                    val tail = when {
                        !fadeTail -> ""
                        splitAt >= 0 -> segment.text.substring(splitAt + 2)
                        else -> segment.text
                    }
                    if (head.isNotBlank()) Markdown(
                        content = MessageParser.closeDanglingFences(head),
                        colors = markdownColor(text = textColor),
                        typography = chatMarkdownTypography(),
                        flavour = GFMFlavourDescriptor(),
                        annotator = annotator,
                        components = components,
                    )
                    if (fadeTail && tail.isNotEmpty()) FadingStreamText(
                        text = tail,
                        textColor = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MessageParser.Segment.Table -> MarkdownTable(segment.header, segment.rows, textColor)
                is MessageParser.Segment.Thinking -> ReasoningCanvas(segment.text, textColor, active = isStreaming)
                is MessageParser.Segment.Tools -> ToolCalls(segment.calls, textColor)
                is MessageParser.Segment.Mermaid -> MermaidDiagram(segment.code, textColor)
                is MessageParser.Segment.Citations -> CitationsBar(segment.items, textColor)
                is MessageParser.Segment.SkillDistilled -> SkillDistilledPill(segment, textColor)
                is MessageParser.Segment.Telemetry -> TelemetryBlock(segment, textColor)
                is MessageParser.Segment.ActionOutput ->
                    ActionOutputCard(segment, MaterialTheme.colorScheme.primary, textColor)
            }
        }
    }
}

// Fence-line parity: an odd count of ```-starting lines means a code fence is still open.
private val FENCE_LINE = Regex("(?m)^\\s{0,3}`{3,}")
private fun hasOpenFence(text: String): Boolean = FENCE_LINE.findAll(text).count() % 2 != 0

/** How long a freshly-arrived run of streamed characters takes to fade to full opacity. */
private const val STREAM_FADE_MS = 320f

/**
 * Streamed text that materializes like settling ink: each newly arrived run of characters fades
 * from transparent to full opacity over [STREAM_FADE_MS], while everything already settled stays
 * solid. Replaces the typewriter chunk-pop for live streams. Runs are tracked by append boundary;
 * if the tail is ever rewritten rather than appended (the sanitizer trimming a partial marker, or
 * a new message), everything settles instantly rather than re-fading.
 */
@Composable
fun FadingStreamText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    // (startIndex, bornAtMs) per not-yet-settled run, chronological — so a settled prefix can be
    // pruned front-first and everything before the first entry renders fully opaque.
    val fading = remember { mutableStateListOf<Pair<Int, Long>>() }
    var prev by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(0L) }
    remember(text) {
        val t = System.currentTimeMillis()
        if (text.startsWith(prev)) {
            if (text.length > prev.length) fading.add(prev.length to t)
        } else {
            fading.clear()
        }
        prev = text
        now = t
        text
    }
    // Tick with the frame clock only while something is mid-fade, then go quiet.
    LaunchedEffect(text) {
        while (fading.isNotEmpty()) {
            withFrameMillis { }
            now = System.currentTimeMillis()
            fading.removeAll { now - it.second >= STREAM_FADE_MS }
        }
    }
    val annotated = buildAnnotatedString {
        val runs = fading.toList()
        val settledEnd = (runs.firstOrNull()?.first ?: text.length).coerceAtMost(text.length)
        append(text.substring(0, settledEnd))
        runs.forEachIndexed { i, (start, born) ->
            if (start >= text.length) return@forEachIndexed
            val end = (runs.getOrNull(i + 1)?.first ?: text.length).coerceAtMost(text.length)
            if (end <= start) return@forEachIndexed
            val alpha = ((now - born) / STREAM_FADE_MS).coerceIn(0f, 1f)
            pushStyle(SpanStyle(color = textColor.copy(alpha = textColor.alpha * alpha)))
            append(text.substring(start, end))
            pop()
        }
    }
    Text(text = annotated, color = textColor, style = style, modifier = modifier)
}

/**
 * Automated agent output (runtime footer, cron check-in, subtext aside) rendered as a low-contrast
 * telemetry block — clearly machine-voice, never competing with dialogue. Footers get a single
 * hairline-topped caption; check-ins a whisper-quiet mono block.
 */
@Composable
fun TelemetryBlock(segment: MessageParser.Segment.Telemetry, baseColor: Color) {
    val muted = baseColor.copy(alpha = 0.42f)
    when (segment.kind) {
        MessageParser.TelemetryKind.FOOTER -> Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            HorizontalDivider(color = baseColor.copy(alpha = 0.10f))
            Text(
                text = segment.text,
                color = muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        else -> Text(
            text = segment.text,
            color = muted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(baseColor.copy(alpha = 0.045f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/**
 * A structured tool payload as a stylized "Action Output" card instead of a raw JSON wall:
 * tool name + status chip, the key parameters as quiet rows, a clean result summary, and the raw
 * JSON tucked behind a tap.
 */
@Composable
fun ActionOutputCard(
    action: MessageParser.Segment.ActionOutput,
    accent: Color,
    baseColor: Color,
) {
    var showRaw by remember(action.raw) { mutableStateOf(false) }
    val statusColor = when (action.success) {
        true -> Color(0xFF4CAF7D)
        false -> Color(0xFFE0524D)
        null -> baseColor.copy(alpha = 0.5f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.12f), accent.copy(alpha = 0.03f))))
            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .clickable { showRaw = !showRaw }
            .animateContentSize()
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▣", color = accent, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = action.tool,
                color = baseColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when (action.success) { true -> "SUCCESS"; false -> "FAILED"; null -> "ACTION" },
                color = statusColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
        }
        action.params.take(6).forEach { (k, v) ->
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text("$k ", color = accent.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    v,
                    color = baseColor.copy(alpha = 0.68f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action.result != null) {
            Text(
                text = action.result,
                color = baseColor.copy(alpha = 0.78f),
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(baseColor.copy(alpha = 0.05f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        AnimatedVisibility(visible = showRaw, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val rawScroll = rememberScrollState()
            Text(
                text = action.raw,
                color = baseColor.copy(alpha = 0.55f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rawScroll, enabled = rawScroll.maxValue > 0),
                softWrap = false,
            )
        }
    }
}

/**
 * The dream-state Reasoning Canvas: Hermes' model reasoning rendered as a frosted, inset stratum of
 * the message. While the agent is still thinking ([active]) it auto-expands and emits a soft pulsing
 * purple glow; once the answer lands it settles into a minimal "💭 Reasoning" indicator. Tap to
 * expand/collapse at will (a manual choice overrides the auto behaviour). Italic/muted body text
 * keeps it reading as an inner monologue distinct from the answer.
 */
@Composable
private fun ReasoningCanvas(text: String, baseColor: Color, active: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = baseColor.copy(alpha = 0.7f)
    // Default: follow the agent (open while thinking, collapse when done) until the user decides.
    var userOverride by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userOverride ?: active

    // Breathing glow while reasoning is actively streaming.
    val glow = if (active) {
        val t = rememberInfiniteTransition(label = "reasonPulse")
        t.animateFloat(
            initialValue = 0.16f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "reasonPulseAlpha",
        ).value
    } else 0.18f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            // Frosted: a soft translucent accent wash + hairline border that brightens with the pulse.
            .background(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.13f), accent.copy(alpha = 0.04f)))
            )
            .border(1.dp, accent.copy(alpha = glow), RoundedCornerShape(12.dp))
            .clickable { userOverride = !expanded }
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text("💭", fontSize = 13.sp)
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (active) "Reasoning…" else "Reasoning",
                color = accent.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "▸", color = muted, fontSize = 12.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val reasonStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
            )
            if (active) {
                // Live reasoning settles in like ink, same as the answer stream.
                FadingStreamText(
                    text = text,
                    textColor = muted,
                    style = reasonStyle,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                )
            } else {
                Text(
                    text = text,
                    color = muted,
                    style = reasonStyle,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                )
            }
        }
    }
}

/**
 * Hermes tool invocations as dream-aesthetic "Sandbox Cards". Each tool carries its own glyph
 * (⚙️ graphiti, 👁️ vision, 🔍 search, 🔧 patch …); the card shows that glyph, the tool name in
 * monospace, the (single string) argument as a soft wrapping subtitle, and a quiet ✓ since these
 * are completed actions in history. Frosted accent gradient keeps them on-brand and uncluttered.
 */
@Composable
private fun ToolCalls(calls: List<MessageParser.ToolCall>, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        calls.forEach { call -> ToolCallCard(call, accent, baseColor) }
    }
}

/** A code block/fence on a subtle surface that scrolls horizontally (long lines pan, not clip),
 *  with a quiet copy affordance floating in the corner: tap → clipboard, glyph melts ❐ → ✓ for a
 *  beat as confirmation. Kept low-alpha so it never competes with the code itself. */
@Composable
private fun ScrollableCodeBlock(code: String, textColor: Color) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val trimmed = code.trim('\n')
    var copied by remember(trimmed) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1600); copied = false }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = 0.06f)),
    ) {
        // enabled only when the code actually overflows: a scrollable that CAN'T scroll still
        // claims every horizontal drag over it, which is what made drawer/reply swipes dead
        // over short code blocks ("you can only swipe in some areas").
        val codeScroll = rememberScrollState()
        Text(
            text = trimmed,
            color = textColor.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(codeScroll, enabled = codeScroll.maxValue > 0)
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 34.dp),
        )
        Text(
            text = if (copied) "✓" else "❐",
            color = if (copied) Color(0xFF4CAF7D) else textColor.copy(alpha = 0.45f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(trimmed))
                    copied = true
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Chat-tuned markdown typography: headings are only slightly larger than body so a stray
 *  `#` line never blows up into a giant title inside a small message bubble. */
@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.4f, fontWeight = FontWeight.Bold),
    h2 = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.25f, fontWeight = FontWeight.Bold),
    h3 = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.15f, fontWeight = FontWeight.Bold),
    h4 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
    h5 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
    h6 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
    text = MaterialTheme.typography.bodyLarge,
)

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, textColor: Color) {
    val border = textColor.copy(alpha = 0.30f)
    val headerBg = textColor.copy(alpha = 0.08f)
    val colCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val colWidth = 130.dp

    // Same swipe-friendliness rule as ScrollableCodeBlock: only eat horizontal drags when
    // the table is actually wider than the bubble.
    val tableScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .horizontalScroll(tableScroll, enabled = tableScroll.maxValue > 0)
    ) {
        TableRow(header, colCount, colWidth, textColor, border, isHeader = true, rowBg = headerBg)
        rows.forEach { row ->
            HorizontalDivider(color = border)
            TableRow(row, colCount, colWidth, textColor, border, isHeader = false, rowBg = Color.Transparent)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    colCount: Int,
    colWidth: androidx.compose.ui.unit.Dp,
    textColor: Color,
    border: Color,
    isHeader: Boolean,
    rowBg: Color,
) {
    Row(modifier = Modifier.height(IntrinsicSize.Min).background(rowBg)) {
        for (c in 0 until colCount) {
            if (c > 0) VerticalDivider(color = border, modifier = Modifier.fillMaxHeight())
            Text(
                text = cells.getOrElse(c) { "" },
                color = textColor,
                fontSize = 13.sp,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(colWidth).padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

