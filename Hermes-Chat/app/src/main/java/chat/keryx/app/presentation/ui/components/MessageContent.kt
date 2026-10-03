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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.em
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import chat.keryx.core.protocol.MessageParser

/**
 * Renders a message. [MessageParser] splits the body into segments: prose goes through the
 * markdown renderer, and everything Keryx draws itself takes its own path — tables (content-sized
 * columns, alignment, sorting), display math (typeset), rich fences (charts, diffs, timelines…),
 * mermaid, markers and tool output.
 */
@Composable
fun MessageContent(
    content: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    /** Agent messages get the full chrome parse (tools/reasoning/telemetry); a human sender's
     *  text must render as plain markdown even when it pattern-matches agent output. */
    isAgent: Boolean = true,
    /**
     * Draw parsed tool calls inline, as rows inside this text (3.1 §A4).
     *
     * **Off by default, and a chat bubble must never turn it on.** In the transcript a
     * tool-bearing message is lifted out of the bubble entirely and rendered as a run
     * ([ToolTheaterRun]) — leaving the inline path armed meant the same calls could draw twice,
     * once in the run and once inside whatever bubble the parser also found them in.
     *
     * The exception is a surface with no run to lift into: the Archive reads one stored message
     * at a time, so its tool rows have nowhere else to go and it opts in.
     */
    inlineTools: Boolean = false,
    /**
     * Draw parsed reasoning inline, as the settled disclosure (3.1 §B2).
     *
     * **Off by default, and a chat bubble must never turn it on.** In the transcript the thought
     * lives on [chat.keryx.core.model.Message.reasoning] (both producers fill it — §B1) and the
     * bubble's caller renders the one disclosure above the bubble; leaving the inline path armed
     * would draw the same thought twice. The Archive opts in for the same reason it opts into
     * [inlineTools]: it reads a raw stored body with no message-level field to ride.
     */
    inlineReasoning: Boolean = false,
) {
    // The gateway splits turns at tool-call boundaries and can leave several blank lines at the
    // edges of each piece (e.g. a step header stranded after 3 empty lines) — trim so bubbles
    // never open with dead space.
    // Streaming bodies change on every dispatch tick — parse them cache-free so the LRU keeps
    // serving the committed messages around them, and TAIL-WINDOWED so the per-tick re-parse
    // stays O(window) no matter how long the turn grows. Without the window, a tier-2
    // (m.replace-edited) marathon answer re-parsed its full body every sync tick — the same
    // freeze class the tier-1 overlay was cured of in 1.18.3. The full body renders the moment
    // isStreaming flips false.
    val segments = remember(content, isAgent, isStreaming) {
        val body = content.trim('\n')
        val bounded =
            if (isStreaming) MessageParser.streamTailWindow(body, MessageParser.STREAM_RENDER_WINDOW)
            else body
        MessageParser.parse(bounded, agentChrome = isAgent, cacheable = !isStreaming)
    }
    // Render **strong** spans heavier than the library's default (FontWeight.Bold looked too light).
    val inlineCodeBg = textColor.copy(alpha = 0.10f)
    val annotator = remember(inlineCodeBg) { markdownAnnotator { source, node ->
        // `this` is the AnnotatedString.Builder.
        when (node.type) {
            // Inline code, padded with narrow NO-BREAK spaces. The library pads with plain
            // spaces, so a line could wrap right after the padding and the rounded pill painter
            // drew a sliver around the orphaned space at the line's end (device, 2026-10-03).
            MarkdownElementTypes.CODE_SPAN -> {
                val raw = node.getTextInNode(source).toString()
                val ticks = raw.takeWhile { it == '`' }.length
                var code = raw.drop(ticks).dropLast(ticks.coerceAtMost(raw.length - ticks))
                // CommonMark: one space stripped from each side when both are there.
                if (code.length >= 2 && code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) code = code.substring(1, code.length - 1)
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.92.em, background = inlineCodeBg))
                append('\u202F'); append(code.replace('\n', ' ')); append('\u202F')
                pop()
                true
            }
            // A bold span with a link, code span or emphasis inside goes back to the library:
            // flattening it to text is what made every `**https://…**` untappable (2.11.4).
            MarkdownElementTypes.STRONG -> if (node.hasInlineStructure()) false else {
                val inner = node.getTextInNode(source).toString().trim('*', '_')
                pushStyle(SpanStyle(fontWeight = FontWeight.Black))
                append(inner)
                pop()
                true
            }
            // GFM ~~strikethrough~~: the flavour parses it, the 0.35 renderer has no element
            // for it and printed the tildes (2.6.2 renderer parity).
            org.intellij.markdown.flavours.gfm.GFMElementTypes.STRIKETHROUGH -> if (node.hasInlineStructure()) false else {
                val inner = node.getTextInNode(source).toString().removePrefix("~~").removeSuffix("~~")
                pushStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                append(inner)
                pop()
                true
            }
            else -> false
        }
    } }
    // Render code blocks/fences in a horizontally-scrollable monospace surface so long lines
    // (e.g. ASCII-art diagrams some brains emit) can be panned instead of overflowing off-screen.
    // CRITICAL: MarkdownComponentModel.content is the WHOLE markdown source, not this node's
    // text — passing it directly put the entire message inside every code block (the "identical
    // message inside a copy-paste block" bug). The node's own range must be extracted.
    val components = remember(textColor, isStreaming) {
        markdownComponents(
            codeBlock = { ScrollableCodeBlock(indentedCodeText(it.node.getTextInNode(it.content).toString()), textColor) },
            codeFence = {
                val raw = it.node.getTextInNode(it.content).toString()
                ScrollableCodeBlock(fencedCodeText(raw), textColor, language = fenceLanguage(raw), streaming = isStreaming)
            },
        )
    }
    // An inline image is the only thing in a bubble with no text to select: 2.9.1 turned a
    // bare image URL into the picture itself, which took the address off the screen. A tap
    // hands it back — copy, share, save, open — through the transformer's Modifier, so the
    // target exists wherever the parser put the image.
    var tappedImage by remember { mutableStateOf<String?>(null) }
    val imageTransformer = remember { TappableImageTransformer { tappedImage = it } }
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
                    if (head.isNotBlank()) {
                        // The pre-render chain (TeX → Unicode, dangling fences, autolinks) is
                        // three regex passes over the whole body. Keyed on the head so a
                        // recomposition that changes nothing about the text (a reaction
                        // landing, the TTS pulse) does not run them again (2.8).
                        val source = remember(head) {
                            MessageParser.linkifyAutolinks(
                                MessageParser.closeDanglingFences(
                                    // LaTeX → Unicode (2.6.2): `$E=mc^2$` reads as E = mc²
                                    // instead of raw TeX; fences and code spans are skipped.
                                    // runCatching at the CALL SITE: the object's own guard
                                    // cannot catch its class-init (a regex the phone's ICU
                                    // engine rejects surfaces as ExceptionInInitializerError
                                    // here, and killed the app on 09-01). Raw TeX beats a crash.
                                    runCatching { chat.keryx.core.protocol.MathUnicode.render(head) }
                                        .getOrDefault(head),
                                ),
                            )
                        }
                        // immediate (sync) parse, NOT the String overload: 0.35 parses async
                        // (Loading → Success), and the Loading frame renders as an EMPTY box —
                        // so any content change collapsed the rendered prose to zero height for
                        // a frame and the bubble yo-yoed ("very very glitchy" streaming,
                        // device-caught 2026-09-01; 0.26 parsed in composition and never
                        // flashed). The streaming head is tail-windowed, so its blocking parse
                        // is cheap. A SETTLED body of any length goes through MarkdownCache:
                        // parsed once (ahead of the scroll when the warmer got there first),
                        // then a lookup every time the row scrolls back in (2.8).
                        val mdState = if (!isStreaming && source.length >= MarkdownCache.MIN_CHARS) {
                            remember(source) { ParsedMarkdownState(MarkdownCache.parse(source)) }
                        } else {
                            com.mikepenz.markdown.model.rememberMarkdownState(
                                content = source,
                                flavour = GFMFlavourDescriptor(),
                                immediate = true,
                            )
                        }
                        Markdown(
                            markdownState = mdState,
                            colors = chatMarkdownColors(textColor),
                            typography = chatMarkdownTypography(textColor),
                            extendedSpans = chatExtendedSpans(),
                            annotator = annotator,
                            components = components,
                            // Inline `![alt](url)` images load through coil3 (2.6.2); the
                            // library's default transformer is a no-op that drew nothing.
                            imageTransformer = imageTransformer,
                        )
                    }
                    if (fadeTail && tail.isNotEmpty()) FadingStreamText(
                        text = tail,
                        textColor = textColor,
                        formatted = true,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                is MessageParser.Segment.Table -> MarkdownTable(segment.header, segment.rows, segment.align, textColor)
                is MessageParser.Segment.Math -> chat.keryx.app.presentation.ui.components.math.MathBlock(segment.tex, textColor)
                is MessageParser.Segment.Svg -> SvgBlock(segment.code, textColor)
                is MessageParser.Segment.Rich -> RichBlockView(
                    block = segment.block,
                    textColor = textColor,
                    renderMarkdown = { body -> MessageContent(content = body, textColor = textColor, isAgent = false) },
                )
                // 3.1 §B2 — one reasoning grammar, two states. Live (still streaming): the canvas,
                // thinking rendered as it happens. Settled: the thought is on Message.reasoning
                // (§B1) and the bubble's CALLER draws the one disclosure — rendering it here too
                // would double it, so the branch draws nothing unless this surface opted in
                // (the Archive, reading raw stored bodies with no field to ride).
                is MessageParser.Segment.Thinking -> when {
                    isStreaming -> ReasoningCanvas(segment.text, textColor, active = true)
                    inlineReasoning -> ReasoningDisclosure(
                        reasoning = segment.text,
                        seconds = null,
                        streaming = false,
                    )
                    else -> Unit
                }
                is MessageParser.Segment.Tools -> if (inlineTools) ToolCalls(segment.calls, textColor)
                is MessageParser.Segment.Mermaid -> MermaidDiagram(segment.code, textColor)
                is MessageParser.Segment.Citations -> CitationsBar(segment.items, textColor)
                is MessageParser.Segment.QuickActions -> QuickActionTiles(segment.options, textColor)
                is MessageParser.Segment.Hands -> HandsTiles(segment.actions, textColor)
                is MessageParser.Segment.SkillDistilled -> SkillDistilledPill(segment, textColor)
                is MessageParser.Segment.Telemetry -> TelemetryBlock(segment, textColor)
                is MessageParser.Segment.ActionOutput ->
                    ActionOutputCard(segment, MaterialTheme.colorScheme.primary, textColor)
            }
        }
        tappedImage?.let { url ->
            InlineImageSheet(url = url, onDismiss = { tappedImage = null })
        }
    }
}

// Fence-line parity: an odd count of ```-starting lines means a code fence is still open.
private val FENCE_LINE = Regex("(?m)^\\s{0,3}`{3,}")
private fun hasOpenFence(text: String): Boolean = FENCE_LINE.findAll(text).count() % 2 != 0

/** The code INSIDE a ```fence``` node: drop the opening ```lang line and the closing ``` line
 *  (which may be missing mid-stream / after closeDanglingFences ate a sloppy brain's tail). */
internal fun fencedCodeText(raw: String): String {
    val lines = raw.trim('\n').lines()
    if (lines.isEmpty() || !lines.first().trimStart().startsWith("```")) return raw.trim('\n')
    val end = if (lines.size > 1 && lines.last().trim().startsWith("```")) lines.size - 1 else lines.size
    return lines.subList(1, end).joinToString("\n")
}

/** The info string after the opening fence (```kotlin → "kotlin"), or null when bare. */
internal fun fenceLanguage(raw: String): String? {
    val first = raw.trim('\n').lines().firstOrNull()?.trimStart() ?: return null
    if (!first.startsWith("```") && !first.startsWith("~~~")) return null
    return first.drop(3).trim().substringBefore(' ').substringBefore('{').ifBlank { null }
}

/** The code inside an indented (4-space) code block node: strip the indent prefix per line. */
internal fun indentedCodeText(raw: String): String =
    raw.trim('\n').lines().joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }

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
    /** Style markdown as it types (the chat's live paragraph): bold, code, links and headings
     *  show formatted with their markers hidden, instead of raw until the paragraph settles. */
    formatted: Boolean = false,
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
    val styled = remember(text, formatted) {
        if (formatted) chat.keryx.core.protocol.StreamTailStyle.style(text) else null
    }
    val shownText = styled?.text ?: text
    val linkColor = if (formatted) chatLinkColor(textColor) else textColor
    val annotated = buildAnnotatedString {
        val text = shownText
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
        styled?.spans?.forEach { span ->
            val end = span.end.coerceAtMost(text.length)
            if (span.start >= end) return@forEach
            val spanStyle = streamSpanStyle(span, style.fontSize, textColor, linkColor)
            if (spanStyle.background != Color.Unspecified) {
                // A code tint drawn under characters still fading in showed as an empty grey
                // box ahead of its text: the tint covers only what has settled.
                addStyle(spanStyle.copy(background = Color.Unspecified), span.start, end)
                val tintEnd = minOf(end, settledEnd)
                if (span.start < tintEnd) addStyle(SpanStyle(background = spanStyle.background), span.start, tintEnd)
            } else {
                addStyle(spanStyle, span.start, end)
            }
        }
    }
    Text(text = annotated, color = textColor, style = style, modifier = modifier)
}

/** How a live-paragraph mark looks. Hidden markers keep their place in the string (the fade
 *  tracks runs by index) but take no visible room. */
private fun streamSpanStyle(
    span: chat.keryx.core.protocol.StreamTailStyle.Span,
    base: androidx.compose.ui.unit.TextUnit,
    textColor: Color,
    linkColor: Color,
): SpanStyle = when (span.kind) {
    chat.keryx.core.protocol.StreamTailStyle.Kind.HIDE -> SpanStyle(color = Color.Transparent, fontSize = KeryxType.vanish, letterSpacing = 0.sp)
    chat.keryx.core.protocol.StreamTailStyle.Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Black)
    chat.keryx.core.protocol.StreamTailStyle.Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    chat.keryx.core.protocol.StreamTailStyle.Kind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = textColor.copy(alpha = 0.10f))
    chat.keryx.core.protocol.StreamTailStyle.Kind.STRIKE -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
    chat.keryx.core.protocol.StreamTailStyle.Kind.LINK -> SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
    chat.keryx.core.protocol.StreamTailStyle.Kind.HEADING -> SpanStyle(
        fontWeight = FontWeight.Bold,
        fontSize = if (base.isSpecified) base * when (span.level) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.15f; else -> 1f } else androidx.compose.ui.unit.TextUnit.Unspecified,
    )
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
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        else -> Text(
            text = segment.text,
            color = muted,
            fontSize = KeryxType.caption,
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
        false -> KeryxStatus.bad
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
            Text("▣", color = accent, fontSize = KeryxType.body)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = action.tool,
                color = baseColor,
                fontSize = KeryxType.body,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when (action.success) { true -> "SUCCESS"; false -> "FAILED"; null -> "ACTION" },
                color = statusColor,
                fontSize = KeryxType.micro,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
        }
        action.params.take(6).forEach { (k, v) ->
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text("$k ", color = accent.copy(alpha = 0.75f), fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace)
                Text(
                    v,
                    color = baseColor.copy(alpha = 0.68f),
                    fontSize = KeryxType.micro,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Bound to a local: `result` is a public property of :core now, and Kotlin will not
        // smart-cast across a module boundary.
        val actionResult = action.result
        if (actionResult != null) {
            Text(
                text = actionResult,
                color = baseColor.copy(alpha = 0.78f),
                fontSize = KeryxType.caption,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(baseColor.copy(alpha = 0.05f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        AnimatedVisibility(visible = showRaw, enter = keryxReveal(), exit = fadeOut() + shrinkVertically()) {
            val rawScroll = rememberScrollState()
            Text(
                text = action.raw,
                color = baseColor.copy(alpha = 0.55f),
                fontSize = KeryxType.micro,
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
internal fun ReasoningCanvas(text: String, baseColor: Color, active: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = baseColor.copy(alpha = 0.7f)
    // Default: follow the agent (open while thinking, collapse when done) until the user decides.
    var userOverride by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userOverride ?: active

    // Breathing glow while reasoning is actively streaming. Battery Saver holds it at the bright
    // end rather than the resting value, so a stream still reads as streaming.
    val reduced by rememberReducedMotion()
    val glow = if (active && !reduced) {
        val t = rememberInfiniteTransition(label = "reasonPulse")
        t.animateFloat(
            initialValue = 0.16f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "reasonPulseAlpha",
        ).value
    } else if (active) 0.5f else 0.18f

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
            Text("💭", fontSize = KeryxType.body)
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (active) "Reasoning…" else "Reasoning",
                color = accent.copy(alpha = 0.92f),
                fontSize = KeryxType.caption,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▾" else "▸", color = muted, fontSize = KeryxType.caption)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = keryxReveal(),
            exit = keryxConceal(),
        ) {
            val reasonStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = KeryxType.body,
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
 * Tool calls parsed out of one message's own text, for a surface that reads messages one at a
 * time and has no run to lift them into (the Archive). Same [ToolTheaterRow] the transcript's
 * runs use — one renderer, wherever a call is shown.
 */
@Composable
private fun ToolCalls(calls: List<chat.keryx.core.model.ToolCall>, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        calls.forEach { call -> ToolTheaterRow(call, accent, baseColor) }
    }
}

/** A code block/fence on a subtle surface that scrolls horizontally (long lines pan, not clip),
 *  with a quiet copy affordance floating in the corner: tap → clipboard, glyph melts ❐ → ✓ for a
 *  beat as confirmation. Kept low-alpha so it never competes with the code itself. */
@Composable
internal fun ScrollableCodeBlock(code: String, textColor: Color, language: String? = null, streaming: Boolean = false) {
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
        // The web chats' code block: a language tag in the corner and the tokens coloured.
        // The colour is OUR text with the tokenizer's spans laid over it (CodeHighlighting) —
        // the renderer's own highlighted-code composable brings a second horizontalScroll,
        // and a scroller inside a scroller is a measure-time crash, not a style choice.
        // A language the tokenizer doesn't know is plain mono, same as before.
        val onVoid = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val lang = language?.trim()?.lowercase().orEmpty()
        // Settled: tokenize in place (cached by content, so a re-scroll is a lookup). Streaming:
        // the block grows every tick, and tokenizing each new length on the UI thread churned the
        // cache and dropped frames — so the spans come from a background pass and the last good
        // set stays on screen meanwhile (text only grows, so they still line up).
        var liveSpans by remember(lang, onVoid) { mutableStateOf(emptyList<CodeHighlighting.Span>()) }
        if (streaming) {
            LaunchedEffect(trimmed, lang, onVoid) {
                liveSpans = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    CodeHighlighting.spans(trimmed, lang, darkMode = onVoid, cache = false)
                }
            }
        }
        val coloured = remember(trimmed, lang, onVoid, streaming, liveSpans) {
            val spans = if (streaming) liveSpans else CodeHighlighting.spans(trimmed, lang, darkMode = onVoid)
            androidx.compose.ui.text.buildAnnotatedString {
                append(trimmed)
                for (span in spans) {
                    if (span.end > trimmed.length) continue
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = span.rgb?.let { Color(0xFF000000.toInt() or it) } ?: Color.Unspecified,
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                        ),
                        span.start, span.end,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .horizontalScroll(codeScroll, enabled = codeScroll.maxValue > 0)
                .padding(start = 10.dp, top = if (lang.isNotBlank()) 22.dp else 10.dp, bottom = 10.dp, end = 34.dp),
        ) {
            Text(
                text = coloured,
                color = textColor.copy(alpha = 0.85f),
                fontSize = KeryxType.caption,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
        if (lang.isNotBlank()) {
            Text(
                text = lang,
                color = textColor.copy(alpha = 0.45f),
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 6.dp),
            )
        }
        Text(
            text = if (copied) "✓" else "❐",
            color = if (copied) Color(0xFF4CAF7D) else textColor.copy(alpha = 0.45f),
            fontSize = KeryxType.body,
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

/**
 * Chat-tuned markdown typography. Headings are only slightly larger than body so a stray `#` line
 * never blows up into a giant title inside a small bubble. Every slot the bubble shows is set:
 * left at the library's defaults, quotes, lists, links and inline code were stock Material
 * inside Keryx's own bubbles (2.17).
 */
@Composable
internal fun chatMarkdownTypography(textColor: Color = Color.Unspecified): com.mikepenz.markdown.model.MarkdownTypography {
    val body = MaterialTheme.typography.bodyLarge
    val heading = body.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp)
    return markdownTypography(
        h1 = heading.copy(fontSize = body.fontSize * 1.4f, lineHeight = body.lineHeight * 1.3f),
        h2 = heading.copy(fontSize = body.fontSize * 1.25f, lineHeight = body.lineHeight * 1.2f),
        h3 = heading.copy(fontSize = body.fontSize * 1.15f),
        h4 = heading,
        h5 = heading,
        h6 = heading.copy(color = textColor.takeOrElse { body.color }.copy(alpha = 0.75f)),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        code = body.copy(fontFamily = FontFamily.Monospace, fontSize = KeryxType.caption),
        inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = body.fontSize * 0.9f),
        quote = body.copy(
            fontStyle = FontStyle.Italic,
            color = textColor.takeOrElse { body.color }.copy(alpha = 0.78f),
        ),
        textLink = androidx.compose.ui.text.TextLinkStyles(
            style = SpanStyle(
                color = chatLinkColor(textColor),
                fontWeight = FontWeight.Medium,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            ),
            pressedStyle = SpanStyle(background = chatLinkColor(textColor).copy(alpha = 0.16f)),
        ),
        table = body,
        alertTitle = body.copy(fontWeight = FontWeight.Bold, fontSize = KeryxType.body),
    )
}

/** Links lean toward the theme's accent but stay legible on the bubble's own ink: half primary,
 *  half text colour, so a link on a primary-tinted bubble never vanishes into it. */
@Composable
internal fun chatLinkColor(textColor: Color): Color {
    if (textColor == Color.Unspecified) return MaterialTheme.colorScheme.primary
    return androidx.compose.ui.graphics.lerp(textColor, MaterialTheme.colorScheme.primary, 0.55f)
}

/** The bubble's colours for every markdown slot: code and quote tints come from its own ink, and
 *  GitHub alerts pick their light or dark palette from that ink rather than the app theme
 *  (a dark bubble can sit in a light app). */
@Composable
internal fun chatMarkdownColors(textColor: Color): com.mikepenz.markdown.model.MarkdownColors {
    val darkBubble = textColor.luminance() > 0.5f
    return markdownColor(
        text = textColor,
        codeBackground = textColor.copy(alpha = 0.06f),
        inlineCodeBackground = textColor.copy(alpha = 0.10f),
        dividerColor = textColor.copy(alpha = 0.14f),
        tableBackground = Color.Transparent,
        darkTheme = darkBubble,
        alert = com.mikepenz.markdown.model.markdownAlertColors(darkTheme = darkBubble),
    )
}

/** Inline code gets a rounded pill behind it instead of a hard rectangle. */
@Composable
internal fun chatExtendedSpans(): com.mikepenz.markdown.model.MarkdownExtendedSpans =
    com.mikepenz.markdown.model.markdownExtendedSpans {
        remember {
            com.mikepenz.markdown.compose.extendedspans.ExtendedSpans(
                com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter(cornerRadius = 5.sp),
            )
        }
    }

private fun Color.takeOrElse(other: () -> Color): Color = if (this == Color.Unspecified) other() else this

/**
 * A table drawn as a real grid. Columns are as wide as their widest cell (clamped, so one long
 * cell wraps instead of pushing the rest off-screen), honour the separator row's alignment, and
 * cells carry inline markdown — bold, italic, code, links, strike and inline math. A table with a
 * few rows sorts by tapping a header (numbers as numbers), tap again to reverse.
 */
@Composable
private fun MarkdownTable(
    header: List<String>,
    rows: List<List<String>>,
    align: List<MessageParser.ColumnAlign>,
    textColor: Color,
) {
    val border = textColor.copy(alpha = 0.16f)
    val headerBg = textColor.copy(alpha = 0.07f)
    val stripe = textColor.copy(alpha = 0.025f)
    val colCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val linkColor = chatLinkColor(textColor)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = KeryxType.body)
    val headStyle = bodyStyle.copy(fontWeight = FontWeight.SemiBold)

    var sortCol by remember(header, rows) { mutableStateOf(-1) }
    var descending by remember(header, rows) { mutableStateOf(false) }
    val sortable = rows.size >= 3
    val shown = remember(rows, sortCol, descending) {
        if (sortCol < 0) rows else {
            val key: (List<String>) -> String = { it.getOrElse(sortCol) { "" } }
            val numeric = rows.all { key(it).isBlank() || tableNumber(key(it)) != null }
            val sorted = if (numeric) rows.sortedBy { tableNumber(key(it)) ?: Double.NEGATIVE_INFINITY }
            else rows.sortedBy { key(it).lowercase() }
            if (descending) sorted.reversed() else sorted
        }
    }

    // Column widths from the content itself, measured once per table.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widths = remember(header, rows, colCount) {
        (0 until colCount).map { c ->
            val cells = listOf(header.getOrElse(c) { "" } to headStyle) + rows.map { it.getOrElse(c) { "" } to bodyStyle }
            val px = cells.maxOf { (text, style) ->
                measurer.measure(inlineMarkdownAnnotated(text, linkColor), style, softWrap = false, maxLines = 1).size.width
            }
            with(density) { (px.toDp() + 22.dp + if (sortable) 12.dp else 0.dp).coerceIn(56.dp, 240.dp) }
        }
    }

    // Same swipe-friendliness rule as ScrollableCodeBlock: only eat horizontal drags when
    // the table is actually wider than the bubble.
    val tableScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .horizontalScroll(tableScroll, enabled = tableScroll.maxValue > 0)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min).background(headerBg)) {
            for (c in 0 until colCount) {
                if (c > 0) VerticalDivider(color = border, modifier = Modifier.fillMaxHeight())
                val marker = when {
                    sortCol != c -> ""
                    descending -> " ▾"
                    else -> " ▴"
                }
                TableCell(
                    text = header.getOrElse(c) { "" },
                    suffix = marker,
                    width = widths[c],
                    align = align.getOrElse(c) { MessageParser.ColumnAlign.START },
                    style = headStyle,
                    textColor = textColor,
                    linkColor = linkColor,
                    modifier = if (sortable) Modifier.clickable {
                        if (sortCol == c) { if (descending) { sortCol = -1; descending = false } else descending = true }
                        else { sortCol = c; descending = false }
                    } else Modifier,
                )
            }
        }
        shown.forEachIndexed { r, row ->
            HorizontalDivider(color = border)
            Row(modifier = Modifier.height(IntrinsicSize.Min).background(if (r % 2 == 1) stripe else Color.Transparent)) {
                for (c in 0 until colCount) {
                    if (c > 0) VerticalDivider(color = border, modifier = Modifier.fillMaxHeight())
                    TableCell(
                        text = row.getOrElse(c) { "" },
                        width = widths[c],
                        align = align.getOrElse(c) { MessageParser.ColumnAlign.START },
                        style = bodyStyle,
                        textColor = textColor,
                        linkColor = linkColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    align: MessageParser.ColumnAlign,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    val annotated = remember(text, suffix, linkColor) {
        val base = inlineMarkdownAnnotated(text, linkColor)
        if (suffix.isEmpty()) base else androidx.compose.ui.text.AnnotatedString.Builder(base).apply {
            pushStyle(SpanStyle(color = textColor.copy(alpha = 0.55f))); append(suffix); pop()
        }.toAnnotatedString()
    }
    Text(
        text = annotated,
        color = textColor,
        style = style,
        textAlign = when (align) {
            MessageParser.ColumnAlign.START -> androidx.compose.ui.text.style.TextAlign.Start
            MessageParser.ColumnAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
            MessageParser.ColumnAlign.END -> androidx.compose.ui.text.style.TextAlign.End
        },
        modifier = modifier.width(width).padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

/** A cell as a number for sorting: "1,204", "$3.50", "42%", "-7 ms" all count. */
internal fun tableNumber(cell: String): Double? {
    val t = cell.trim().replace(",", "").replace("**", "").replace("`", "")
    val m = Regex("""^[^\d\-+.]{0,2}([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)""").find(t) ?: return null
    return m.groupValues[1].toDoubleOrNull()
}

/**
 * Inline markdown for text the markdown renderer never sees (table cells, rich-block labels):
 * **bold**, *italic* or _italic_, `code`, ~~strike~~, [label](https://…) as a tappable link, bare
 * https URLs, and inline TeX through the Unicode transform. Unmatched markers stay literal.
 */
internal fun inlineMarkdownAnnotated(text: String, linkColor: Color): androidx.compose.ui.text.AnnotatedString {
    val src = runCatching { chat.keryx.core.protocol.MathUnicode.render(text) }.getOrDefault(text)
    return buildAnnotatedString {
        var i = 0
        fun plainUntil(end: Int) { append(src.substring(i, end)); i = end }
        while (i < src.length) {
            val c = src[i]
            when {
                c == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end < 0) { plainUntil(src.length); break }
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.10f)))
                    append(src.substring(i + 1, end)); pop(); i = end + 1
                }
                src.startsWith("**", i) || src.startsWith("__", i) -> {
                    val mark = src.substring(i, i + 2)
                    val end = src.indexOf(mark, i + 2)
                    if (end < 0) { append(mark); i += 2; continue }
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(inlineMarkdownAnnotated(src.substring(i + 2, end), linkColor)); pop(); i = end + 2
                }
                src.startsWith("~~", i) -> {
                    val end = src.indexOf("~~", i + 2)
                    if (end < 0) { append("~~"); i += 2; continue }
                    pushStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                    append(src.substring(i + 2, end)); pop(); i = end + 2
                }
                (c == '*' || c == '_') && i + 1 < src.length && !src[i + 1].isWhitespace() -> {
                    val end = src.indexOf(c, i + 1)
                    val wordy = c == '_' && i > 0 && src[i - 1].isLetterOrDigit()
                    if (end < 0 || wordy || src[end - 1].isWhitespace()) { append(c); i++; continue }
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(src.substring(i + 1, end)); pop(); i = end + 1
                }
                c == '[' -> {
                    val close = src.indexOf("](", i)
                    val end = if (close > 0) src.indexOf(')', close) else -1
                    val url = if (end > 0) src.substring(close + 2, end) else ""
                    if (close < 0 || end < 0 || !(url.startsWith("http://") || url.startsWith("https://"))) { append(c); i++; continue }
                    withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) {
                        append(src.substring(i + 1, close))
                    }
                    i = end + 1
                }
                src.startsWith("https://", i) || src.startsWith("http://", i) -> {
                    var end = i
                    while (end < src.length && !src[end].isWhitespace()) end++
                    while (end > i && src[end - 1] in ".,;:!?)") end--
                    val url = src.substring(i, end)
                    withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, androidx.compose.ui.text.TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)))) {
                        append(url)
                    }
                    i = end
                }
                else -> { append(c); i++ }
            }
        }
    }
}

/**
 * Line-level markdown for quiet text (a settled thought): headings turn bold, list markers turn
 * into bullets, and each line gets [inlineMarkdownAnnotated]. Fences pass through untouched.
 */
internal fun quietMarkdownAnnotated(text: String, linkColor: Color): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        var inFence = false
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inFence = !inFence; append(line); return@forEachIndexed }
            if (inFence) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(line); pop(); return@forEachIndexed
            }
            val t = line.trimStart()
            val indent = line.length - t.length
            when {
                t.startsWith("#") && t.trimStart('#').startsWith(" ") -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(inlineMarkdownAnnotated(t.trimStart('#').trim(), linkColor)); pop()
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                    append(" ".repeat(indent)); append("• ")
                    append(inlineMarkdownAnnotated(t.substring(2), linkColor))
                }
                else -> append(inlineMarkdownAnnotated(line, linkColor))
            }
        }
    }

/**
 * A ```svg fence drawn as the picture it describes. AndroidSVG (through coil) renders it: no
 * scripts, no fetches. Sized to the bubble's width, capped in height; tap shows the source.
 */
@Composable
private fun SvgBlock(code: String, textColor: Color) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSource by remember(code) { mutableStateOf(false) }
    var failed by remember(code) { mutableStateOf(false) }
    if (failed || showSource) {
        ScrollableCodeBlock(code, textColor, language = "svg")
        if (!failed) Text(
            "show drawing",
            color = textColor.copy(alpha = 0.55f),
            fontSize = KeryxType.micro,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showSource = false }.padding(4.dp),
        )
        return
    }
    val request = remember(code) {
        coil3.request.ImageRequest.Builder(context)
            .data(code.encodeToByteArray())
            .decoderFactory(coil3.svg.SvgDecoder.Factory())
            .memoryCacheKey("svg:" + code.hashCode())
            .build()
    }
    coil3.compose.AsyncImage(
        model = request,
        contentDescription = "Drawing",
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        onError = { failed = true },
        modifier = Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 24.dp, max = 360.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { showSource = true },
    )
}

