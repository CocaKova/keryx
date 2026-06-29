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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
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
        segments.forEach { segment ->
            when (segment) {
                is MessageParser.Segment.Text -> Markdown(
                    content = segment.text,
                    colors = markdownColor(text = textColor),
                    typography = chatMarkdownTypography(),
                    flavour = GFMFlavourDescriptor(),
                    annotator = annotator,
                    components = components,
                )
                is MessageParser.Segment.Table -> MarkdownTable(segment.header, segment.rows, textColor)
                is MessageParser.Segment.Thinking -> ReasoningCanvas(segment.text, textColor, active = isStreaming)
                is MessageParser.Segment.Tools -> ToolCalls(segment.calls, textColor)
                is MessageParser.Segment.Mermaid -> MermaidDiagram(segment.code, textColor)
                is MessageParser.Segment.Citations -> CitationsBar(segment.items, textColor)
                is MessageParser.Segment.SkillDistilled -> SkillDistilledPill(segment, textColor)
            }
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
            Text(
                text = text,
                color = muted,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
            )
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

/** A code block/fence on a subtle surface that scrolls horizontally (long lines pan, not clip). */
@Composable
private fun ScrollableCodeBlock(code: String, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(textColor.copy(alpha = 0.06f)),
    ) {
        Text(
            text = code.trim('\n'),
            color = textColor.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
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

    Column(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
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

