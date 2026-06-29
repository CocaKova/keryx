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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 *
 * The expanded body opens with all of the run's reasoning gathered into ONE collapsible block (so a
 * mid-run "let me correct course" thought no longer fragments the chain), then the tool steps in a
 * height-bounded scroller that always starts at the top — the first (oldest) tool, not the last.
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
            Column(modifier = Modifier.padding(top = 6.dp, start = 8.dp)) {
                // One consolidated reasoning block for the whole run, above the steps.
                run.reasoning?.let { RunReasoning(it, baseColor, accent) }
                // Tool steps: bounded height + own scroll, so a long run opens at the FIRST tool
                // (the scroll state starts at the top) instead of jumping to the newest one.
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    run.entries.forEach { entry ->
                        when (entry) {
                            is ToolRunEntry.Call -> ToolCallCard(entry.call, accent, baseColor)
                            // A tool's own output (terminal stdout, vision result): monospace in a
                            // subtle code surface so it reads as machine output, not prose.
                            is ToolRunEntry.Note -> Text(
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

/**
 * The run's gathered reasoning as one muted, collapsible aside (collapsed by default — it's history).
 * This is where every "💭"/course-correction thought from the burst lands, so it reads as a single
 * inner monologue instead of being scattered between the tool steps.
 */
@Composable
private fun RunReasoning(text: String, baseColor: Color, accent: Color) {
    var open by remember(text) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text("💭", fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Reasoning",
                color = accent.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "▾" else "▸", color = baseColor.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                text = text,
                color = baseColor.copy(alpha = 0.62f),
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 9.dp),
            )
        }
    }
}

private fun plural(n: Int) = if (n == 1) "tool" else "tools"

/** One step inside a collapsed tool run: a tool invocation, or a tool's own output (stdout, a
 *  vision result, a table, …). Reasoning is NOT an entry — it's gathered into [ChatRenderItem.ToolRun.reasoning]. */
sealed interface ToolRunEntry {
    data class Call(val call: MessageParser.ToolCall) : ToolRunEntry
    data class Note(val text: String) : ToolRunEntry
}

/** One rendered row in the chat list: either a normal message bubble or a collapsed tool run. */
sealed interface ChatRenderItem {
    val key: String

    data class Single(val message: Message) : ChatRenderItem {
        override val key get() = message.id
    }

    /**
     * A working burst — every step from the agent's first tool call to its last. [id] is the oldest
     * message id (stable as the run grows). [entries] are the tool calls + their outputs, in order;
     * [reasoning] is ALL of the burst's reasoning gathered into one block.
     */
    data class ToolRun(
        val id: String,
        val entries: List<ToolRunEntry>,
        val reasoning: String?,
    ) : ChatRenderItem {
        override val key get() = "toolrun:$id"
        val callCount: Int get() = entries.count { it is ToolRunEntry.Call }
    }
}

/** A message's contribution to a run: its tool calls/outputs and any reasoning, kept separate so
 *  reasoning can be consolidated and never interleaved with the steps. */
private class MsgParts(val entries: List<ToolRunEntry>, val reasoning: String?)

/** Split a parsed message into (entries, reasoning). Tool calls + tool output → entries; 💭/<think>
 *  reasoning → reasoning text. */
private fun segmentsToParts(segs: List<MessageParser.Segment>): MsgParts {
    val entries = mutableListOf<ToolRunEntry>()
    val reasoning = StringBuilder()
    segs.forEach { seg ->
        when (seg) {
            is MessageParser.Segment.Tools -> seg.calls.forEach { entries += ToolRunEntry.Call(it) }
            is MessageParser.Segment.Text -> if (seg.text.isNotBlank()) entries += ToolRunEntry.Note(seg.text)
            is MessageParser.Segment.Thinking -> if (seg.text.isNotBlank()) {
                if (reasoning.isNotEmpty()) reasoning.append("\n\n"); reasoning.append(seg.text.trim())
            }
            is MessageParser.Segment.Table -> {
                val t = (listOf(seg.header) + seg.rows).joinToString("\n") { it.joinToString(" | ") }
                if (t.isNotBlank()) entries += ToolRunEntry.Note(t)
            }
            is MessageParser.Segment.Mermaid -> if (seg.code.isNotBlank()) entries += ToolRunEntry.Note(seg.code)
            is MessageParser.Segment.Citations -> Unit
            is MessageParser.Segment.SkillDistilled -> Unit
        }
    }
    return MsgParts(entries, reasoning.toString().ifBlank { null })
}

/** True for an agent message that contains at least one tool call (not mine, not a media message). */
fun isToolMessage(m: Message): Boolean {
    if (m.sender == SenderType.ME) return false
    if (m.mediaKind != null) return false
    if (m.content.isBlank()) return false
    return MessageParser.parse(m.content).any { it is MessageParser.Segment.Tools }
}

/** Drop a tool call that is identical (same name + args, glyph ignored) to the previous step — the
 *  doubled calls that show up when Hermes re-emits a step or sync replays an edit while you switch
 *  apps. Conservative: only strictly-adjacent duplicates are collapsed, so a genuine re-run that is
 *  separated by its output survives. */
private fun dedupAdjacent(entries: List<ToolRunEntry>): List<ToolRunEntry> {
    val out = mutableListOf<ToolRunEntry>()
    for (e in entries) {
        val prev = out.lastOrNull()
        if (e is ToolRunEntry.Call && prev is ToolRunEntry.Call &&
            e.call.name == prev.call.name && e.call.args == prev.call.args
        ) continue
        out += e
    }
    return out
}

/**
 * Collapse each agent turn's "working" into one [ChatRenderItem.ToolRun]. We take a contiguous block
 * of agent messages (broken only by one of my messages or a media message); if it contains AT LEAST
 * ONE tool call, everything from the block's start up to its LAST tool call becomes the run — every
 * tool call + its output as steps, and ALL the surrounding prose (the intro, Hermes' "working…"
 * status, and internal reasoning, whether before or between the tools) folded into the run's single
 * reasoning block. Only the message(s) AFTER the last tool — the final answer — stay normal bubbles.
 *
 * This is what keeps a single long `terminal` call from fragmenting: previously a one-tool turn
 * wasn't grouped, so its reasoning + status + tool card "broke free" as separate bubbles. Now even a
 * one-tool turn is one tidy "Ran 1 tool" group.
 *
 * Works in chronological order internally, then returns newest-first to match the reverseLayout list.
 */
fun groupChatItems(orderedNewestFirst: List<Message>): List<ChatRenderItem> {
    val chrono = orderedNewestFirst.asReversed()
    val out = mutableListOf<ChatRenderItem>()
    var i = 0
    while (i < chrono.size) {
        val start = chrono[i]
        if (start.sender == SenderType.ME || start.mediaKind != null) {
            out += ChatRenderItem.Single(start); i++; continue
        }
        // Extent of this contiguous agent block (broken by one of my messages or a media message).
        var blockEnd = i
        while (blockEnd < chrono.size) {
            val m = chrono[blockEnd]
            if (m.sender == SenderType.ME || m.mediaKind != null) break
            blockEnd++
        }
        // Last tool-bearing message in the block (exclusive blockEnd).
        var lastTool = -1
        for (p in i until blockEnd) if (isToolMessage(chrono[p])) lastTool = p
        if (lastTool < 0) {
            // A plain agent reply (no tools): normal bubbles.
            for (p in i until blockEnd) out += ChatRenderItem.Single(chrono[p])
            i = blockEnd; continue
        }
        // Build steps + gather ALL surrounding prose as reasoning for [i .. lastTool].
        val entries = mutableListOf<ToolRunEntry>()
        val reasoning = StringBuilder()
        fun addReasoning(t: String) { if (t.isNotBlank()) { if (reasoning.isNotEmpty()) reasoning.append("\n\n"); reasoning.append(t.trim()) } }
        for (p in i..lastTool) {
            val m = chrono[p]
            val parts = segmentsToParts(MessageParser.parse(m.content))
            if (isToolMessage(m)) {
                entries += parts.entries
                parts.reasoning?.let { addReasoning(it) }
            } else {
                // Intro / "working…" status / standalone reasoning → into the reasoning block, not a
                // loose bubble. (Use parsed reasoning if present, else the whole body.)
                addReasoning(parts.reasoning ?: m.content)
            }
        }
        out += ChatRenderItem.ToolRun(
            id = chrono[i].id,
            entries = dedupAdjacent(entries),
            reasoning = reasoning.toString().ifBlank { null },
        )
        // The final answer (anything after the last tool) stays a normal bubble.
        for (p in (lastTool + 1) until blockEnd) out += ChatRenderItem.Single(chrono[p])
        i = blockEnd
    }
    return out.asReversed()
}
