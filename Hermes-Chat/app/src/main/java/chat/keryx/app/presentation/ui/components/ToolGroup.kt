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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType

/**
 * A single Hermes tool invocation as a dream-aesthetic "Sandbox Card": the tool's own glyph,
 * monospace name, the (string) argument as a soft wrapping subtitle, and a quiet ✓. Shared by the
 * inline renderer ([MessageContent]) and the collapsible [ToolGroupCard].
 */
@Composable
fun ToolCallCard(call: MessageParser.ToolCall, accent: Color, baseColor: Color) {
    // `skill_manage` is SILAS saving/editing a reusable skill (its closed learning loop) — surface
    // it distinctly so "it just learned something" stands out from ordinary tool noise. This rides
    // genuine, universal Hermes output (the tool call itself) — no config or plugin to set up.
    val isSkill = call.name == "skill_manage"
    val tint = if (isSkill) 0.22f else 0.16f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = tint), accent.copy(alpha = 0.05f)))
            )
            .border(1.dp, accent.copy(alpha = if (isSkill) 0.4f else 0.22f), RoundedCornerShape(10.dp)),
    ) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(accent.copy(alpha = 0.75f)))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text(if (isSkill) "✦" else call.emoji.ifBlank { "⚙" }, fontSize = 15.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSkill) "Skill" else call.name,
                    color = if (isSkill) accent.copy(alpha = 0.95f) else baseColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (isSkill) FontFamily.Default else FontFamily.Monospace,
                )
                if (call.args.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = call.args,
                        color = baseColor.copy(alpha = 0.62f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("✓", color = accent.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A run of consecutive tool-only Hermes messages, collapsed into one compact bubble. While the
 * agent is still working ([active]) it shows a pulsing "Running N tools…"; once done it settles to
 * "Ran N tools". Tap to expand the individual [ToolCallCard]s with a fluid accordion.
 */
@Composable
fun ToolGroupCard(
    run: ChatRenderItem.ToolRun,
    active: Boolean,
    baseColor: Color,
    onToggle: (expanded: Boolean) -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    // Expand state persists as the group grows (keyed on the stable oldest-message id).
    var expanded by remember(run.id) { mutableStateOf(false) }

    // Subtle breathing glow while the agent is actively invoking tools.
    val glow = if (active) {
        val t = rememberInfiniteTransition(label = "toolPulse")
        t.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "toolPulseAlpha",
        ).value
    } else 0.22f

    val distinctEmoji = run.entries.filterIsInstance<ToolRunEntry.Call>()
        .map { it.call.emoji.ifBlank { "⚙" } }.distinct().take(3)
    val n = run.callCount
    val label = if (active) "Running $n ${plural(n)}…" else "Ran $n ${plural(n)}"

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.widthIn(max = 340.dp).animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.14f), accent.copy(alpha = 0.04f)))
                )
                .border(1.dp, accent.copy(alpha = glow), RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded; onToggle(expanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(distinctEmoji.joinToString(" "), fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = baseColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (expanded) "▾" else "▸", color = accent.copy(alpha = 0.8f), fontSize = 12.sp)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(top = 6.dp, start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                run.entries.forEach { entry ->
                    when (entry) {
                        is ToolRunEntry.Call -> ToolCallCard(entry.call, accent, baseColor)
                        is ToolRunEntry.Note -> when (entry.kind) {
                            // Reasoning/status between tools: muted italic, like a scratchpad aside.
                            NoteKind.REASONING -> Text(
                                text = entry.text,
                                color = baseColor.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(start = 3.dp, top = 1.dp, bottom = 1.dp),
                            )
                            // A tool's own output (terminal stdout, vision result): monospace in a
                            // subtle code surface so it reads as machine output, not prose.
                            NoteKind.OUTPUT -> Text(
                                text = entry.text,
                                color = baseColor.copy(alpha = 0.78f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(baseColor.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int) = if (n == 1) "tool" else "tools"

/** Distinguishes a tool's attached output (terminal stdout, vision result → code styling) from
 *  the agent's reasoning/status between tools (→ muted italic). */
enum class NoteKind { REASONING, OUTPUT }

/** One step inside a collapsed tool run: a tool invocation, or text (reasoning or tool output). */
sealed interface ToolRunEntry {
    data class Call(val call: MessageParser.ToolCall) : ToolRunEntry
    data class Note(val text: String, val kind: NoteKind) : ToolRunEntry
}

/** One rendered row in the chat list: either a normal message bubble or a collapsed tool run. */
sealed interface ChatRenderItem {
    val key: String

    data class Single(val message: Message) : ChatRenderItem {
        override val key get() = message.id
    }

    /**
     * A working burst — every step from the agent's first tool call to its last, with the
     * interstitial reasoning/status folded in. [id] is the oldest message id (stable as the run
     * grows). [entries] are chronological (oldest→newest).
     */
    data class ToolRun(val id: String, val entries: List<ToolRunEntry>) : ChatRenderItem {
        override val key get() = "toolrun:$id"
        val callCount: Int get() = entries.count { it is ToolRunEntry.Call }
    }
}

/** Flatten a parsed message into run entries, in order: tool calls become [ToolRunEntry.Call],
 *  any attached text/reasoning (e.g. a terminal command + its output, a vision analysis) becomes
 *  [ToolRunEntry.Note] so it travels with the tool instead of breaking out of the group. */
private fun segmentsToEntries(segs: List<MessageParser.Segment>): List<ToolRunEntry> =
    segs.flatMap { seg ->
        when (seg) {
            is MessageParser.Segment.Tools -> seg.calls.map { ToolRunEntry.Call(it) }
            // Text/tables riding along with a tool message are its output → code styling.
            is MessageParser.Segment.Text ->
                if (seg.text.isNotBlank()) listOf(ToolRunEntry.Note(seg.text, NoteKind.OUTPUT)) else emptyList()
            is MessageParser.Segment.Thinking ->
                if (seg.text.isNotBlank()) listOf(ToolRunEntry.Note(seg.text, NoteKind.REASONING)) else emptyList()
            is MessageParser.Segment.Table -> {
                val text = (listOf(seg.header) + seg.rows).joinToString("\n") { it.joinToString(" | ") }
                if (text.isNotBlank()) listOf(ToolRunEntry.Note(text, NoteKind.OUTPUT)) else emptyList()
            }
            // A diagram emitted inside a tool message: keep its source as output text.
            is MessageParser.Segment.Mermaid ->
                if (seg.code.isNotBlank()) listOf(ToolRunEntry.Note(seg.code, NoteKind.OUTPUT)) else emptyList()
            // Citations / skill signals don't occur inside tool messages; ignore if they ever do.
            is MessageParser.Segment.Citations -> emptyList()
            is MessageParser.Segment.SkillDistilled -> emptyList()
        }
    }

/**
 * If [m] is an agent message that *contains* at least one tool call (not mine, not a media
 * message), return its flattened entries — tool calls plus any attached text. Otherwise null.
 * Messages with tools-plus-text (terminal, vision, …) still count, so they stay in the group.
 */
fun messageEntriesIfTool(m: Message): List<ToolRunEntry>? {
    if (m.sender == SenderType.ME) return null
    if (m.mediaKind != null) return null
    if (m.content.isBlank()) return null
    val segs = MessageParser.parse(m.content)
    if (segs.none { it is MessageParser.Segment.Tools }) return null
    return segmentsToEntries(segs)
}

/**
 * Collapse each agent "working burst" into one [ChatRenderItem.ToolRun]. A burst spans from the
 * first tool call to the *last* tool call within a contiguous block of agent messages, folding in
 * the short reasoning/status text Hermes emits *between* tool calls in multi-step flows. Text before
 * the first tool (an intro) and after the last tool (the final answer) stays a normal bubble, so a
 * write→read→delete flow becomes one clean "Ran 3 tools" group instead of fragmenting.
 *
 * Works in chronological order internally, then returns newest-first to match the reverseLayout list.
 * A burst with only one tool call passes through as a [ChatRenderItem.Single] (renders inline).
 */
fun groupChatItems(orderedNewestFirst: List<Message>): List<ChatRenderItem> {
    val chrono = orderedNewestFirst.asReversed()
    val out = mutableListOf<ChatRenderItem>()
    var i = 0
    while (i < chrono.size) {
        if (messageEntriesIfTool(chrono[i]) == null) {
            out += ChatRenderItem.Single(chrono[i])
            i++
            continue
        }
        // A tool burst starts here. Find the last tool-bearing message within this contiguous agent
        // block (broken by any of: a message from me, or a media message).
        var lastTool = i
        var k = i
        while (k < chrono.size) {
            val m = chrono[k]
            if (m.sender == SenderType.ME || m.mediaKind != null) break
            if (messageEntriesIfTool(m) != null) lastTool = k
            k++
        }
        // Build entries for i..lastTool: each tool message's calls+attached-text, plus any pure
        // interstitial reasoning/status messages between them.
        val entries = mutableListOf<ToolRunEntry>()
        for (p in i..lastTool) {
            val m = chrono[p]
            val te = messageEntriesIfTool(m)
            if (te != null) entries += te
            else if (m.content.isNotBlank()) entries += ToolRunEntry.Note(m.content, NoteKind.REASONING)
        }
        val callCount = entries.count { it is ToolRunEntry.Call }
        if (callCount >= 2) {
            out += ChatRenderItem.ToolRun(id = chrono[i].id, entries = entries)
        } else {
            // Single tool call: render it (and any stray note) as normal bubbles.
            for (p in i..lastTool) out += ChatRenderItem.Single(chrono[p])
        }
        i = lastTool + 1
    }
    return out.asReversed()
}
