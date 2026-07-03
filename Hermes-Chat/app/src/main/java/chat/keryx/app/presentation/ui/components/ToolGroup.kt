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
            // Verdict glyph: explicit failure reads loud, success stays quiet.
            when (call.ok) {
                false -> Text("✗", color = Color(0xFFE0524D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                else -> Text("✓", color = accent.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
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
    // rememberSaveable, not remember: the LazyColumn disposes items that scroll off-screen (a tall
    // expanded run + one auto-follow was enough), and plain remember state died with the item —
    // the log "closed on its own" while being watched.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(run.id) { mutableStateOf(false) }

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
    val failed = run.entries.count { it is ToolRunEntry.Call && it.call.ok == false }
    val label = buildString {
        append(if (active) "Running $n ${plural(n)}…" else "Ran $n ${plural(n)}")
        if (failed > 0) append(" · $failed failed")
    }

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
                            is ToolRunEntry.Action -> ActionOutputCard(entry.action, accent, baseColor)
                            // A mid-run automated check-in: quieter than everything else in the run.
                            is ToolRunEntry.Telemetry -> Text(
                                text = entry.text,
                                color = baseColor.copy(alpha = 0.45f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
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
    // NOT keyed on [text]: the reasoning grows while the run is live, and re-keying collapsed the
    // block mid-read every time a new thought landed. Saveable so scrolling away keeps it too.
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
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

/** One step inside a collapsed tool run: a tool invocation, a tool's own output (stdout, a
 *  vision result, a table, …), a structured action payload, or a telemetry aside. Reasoning is NOT
 *  an entry — it's gathered into [ChatRenderItem.ToolRun.reasoning]. */
sealed interface ToolRunEntry {
    data class Call(val call: MessageParser.ToolCall) : ToolRunEntry
    data class Note(val text: String) : ToolRunEntry
    data class Action(val action: MessageParser.Segment.ActionOutput) : ToolRunEntry
    data class Telemetry(val text: String) : ToolRunEntry
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
            is MessageParser.Segment.ActionOutput -> entries += ToolRunEntry.Action(seg)
            is MessageParser.Segment.Telemetry -> if (seg.text.isNotBlank()) entries += ToolRunEntry.Telemetry(seg.text)
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
    return MessageParser.parse(m.content).any {
        it is MessageParser.Segment.Tools || it is MessageParser.Segment.ActionOutput
    }
}

/** True for an agent message that is pure automated telemetry (runtime footer, cron check-in…). */
fun isTelemetryMessage(m: Message): Boolean {
    if (m.sender == SenderType.ME || m.mediaKind != null || m.content.isBlank()) return false
    return MessageParser.isTelemetryMessage(m.content)
}

private fun isRuntimeFooterMessage(m: Message): Boolean {
    if (m.sender == SenderType.ME || m.mediaKind != null || m.content.isBlank()) return false
    return MessageParser.isRuntimeFooterMessage(m.content)
}

private fun mergeRuntimeFooters(chrono: List<Message>): List<Message> {
    val out = mutableListOf<Message>()
    for (m in chrono) {
        if (isRuntimeFooterMessage(m)) {
            val prev = out.lastOrNull()
            if (prev != null && prev.sender == m.sender && prev.mediaKind == null && !isTelemetryMessage(prev)) {
                out[out.lastIndex] = prev.copy(
                    content = prev.content.trimEnd() + "\n\n" + m.content.trim()
                )
                continue
            }
        }
        out += m
    }
    return out
}

/**
 * Collapse the visually-doubled tool calls. Two duplication modes exist in the wild:
 *  1. adjacent re-emission of the same step (an edit replay while you switch apps);
 *  2. Hermes' `tool_progress_grouping: accumulate` — each new progress message repeats every
 *     PREVIOUS call of the turn before appending the new one, so a run built from N messages
 *     shows call #1 N times.
 * We therefore drop a Call when the same (name, args) pair was already contributed by an EARLIER
 * message of the run ([seenBefore]) or is strictly adjacent within this one. A genuine same-message
 * re-run (same command retried after its output) survives, because within one message only the
 * adjacent rule applies.
 */
private fun dedupCalls(entries: List<ToolRunEntry>, seenBefore: Set<Pair<String, String>>): List<ToolRunEntry> {
    val out = mutableListOf<ToolRunEntry>()
    for (e in entries) {
        if (e is ToolRunEntry.Call) {
            val key = e.call.name to e.call.args
            if (key in seenBefore) continue
            val prev = out.lastOrNull()
            if (prev is ToolRunEntry.Call && prev.call.name == e.call.name && prev.call.args == e.call.args) {
                // Same step re-announced adjacently: keep the one carrying a verdict if either does.
                if (e.call.ok != null && prev.call.ok == null) { out[out.size - 1] = e }
                continue
            }
        }
        out += e
    }
    return out
}

/** One of MY messages that is a slash command (`/steer …`, `/think …`): a control input to the
 *  running agent, not conversation — it must not split the surrounding tool run in two. */
private fun isCommandMessage(m: Message): Boolean =
    m.sender == SenderType.ME && m.mediaKind == null && m.content.trimStart().startsWith("/")

/** Total prose (Text-segment) length of an agent message. Drives the answer-vs-aside call below. */
private fun proseLength(segs: List<MessageParser.Segment>): Int =
    segs.filterIsInstance<MessageParser.Segment.Text>().sumOf { it.text.length }

/** A short course-correction ("Let me check the orphan logic…") folds into the run's reasoning; a
 *  substantial prose message is a REAL (interim or final) answer and must stay a visible bubble.
 *  Folding those in is what made steered turns swallow whole answers into the 💭 block. */
private const val ANSWER_PROSE_MIN = 240

/**
 * Collapse each agent turn's "working" into [ChatRenderItem.ToolRun]s. We take a contiguous block
 * of agent messages — broken by one of my real messages or a media message, but NOT by one of my
 * slash commands (`/steer` mid-run used to cut every run in two) — and walk it chronologically:
 *
 *  - tool-bearing messages open/extend a run (deduped against accumulate-mode re-emission);
 *  - telemetry check-ins inside a run become quiet [ToolRunEntry.Telemetry] steps;
 *  - short asides between tools fold into the run's single reasoning block; a bare code-fence
 *    message (tool stdout that lost its header) becomes a [ToolRunEntry.Note] step instead;
 *  - substantial prose is an ANSWER: it closes the current run and stays a normal bubble, and any
 *    tools after it (a steered turn continuing) start a NEW run rather than dragging the answer in.
 *
 * This is what keeps a single long `terminal` call from fragmenting: even a one-tool turn is one
 * tidy "Ran 1 tool" group. Works chronologically, returns newest-first for the reverseLayout list.
 */
fun groupChatItems(orderedNewestFirst: List<Message>): List<ChatRenderItem> {
    val chrono = mergeRuntimeFooters(orderedNewestFirst.asReversed())
    val out = mutableListOf<ChatRenderItem>()
    var i = 0
    while (i < chrono.size) {
        val start = chrono[i]
        if (start.sender == SenderType.ME || start.mediaKind != null) {
            out += ChatRenderItem.Single(start); i++; continue
        }
        // Extent of this contiguous agent block. My slash commands don't break it — the agent's
        // run continues right through a /steer.
        var blockEnd = i
        while (blockEnd < chrono.size) {
            val m = chrono[blockEnd]
            if (m.mediaKind != null) break
            if (m.sender == SenderType.ME && !isCommandMessage(m)) break
            blockEnd++
        }
        if ((i until blockEnd).none { isToolMessage(chrono[it]) }) {
            // A plain agent reply (no tools anywhere): normal bubbles.
            for (p in i until blockEnd) out += ChatRenderItem.Single(chrono[p])
            i = blockEnd; continue
        }

        // Sequential walk: build runs, splitting at answer-prose boundaries.
        var runInsertAt = -1 // where in [out] the open run's card belongs (its chronological spot)
        val entries = mutableListOf<ToolRunEntry>()
        val reasoning = StringBuilder()
        val seenCalls = mutableSetOf<Pair<String, String>>()
        var runStartId: String? = null
        // Once a real tool has run in this block, a message that is nothing but ``` fences is that
        // tool's continued output (Hermes drops the glyph header on follow-up progress sends) —
        // NOT an answer. Without this, mid-run fences broke the run and floated as code bubbles.
        var toolSeen = false
        fun fenceOnly(m: Message) = m.content.trimStart().startsWith("```")
        fun addReasoning(t: String) {
            if (t.isNotBlank()) { if (reasoning.isNotEmpty()) reasoning.append("\n\n"); reasoning.append(t.trim()) }
        }
        fun openRun(at: Message) {
            if (runStartId == null) { runStartId = at.id; runInsertAt = out.size }
        }
        fun closeRun() {
            val id = runStartId ?: return
            if (entries.any { it is ToolRunEntry.Call }) {
                out.add(
                    runInsertAt,
                    ChatRenderItem.ToolRun(id, entries.toList(), reasoning.toString().ifBlank { null }),
                )
            }
            runStartId = null; runInsertAt = -1
            entries.clear(); reasoning.setLength(0); seenCalls.clear()
        }
        for (p in i until blockEnd) {
            val m = chrono[p]
            if (m.sender == SenderType.ME) { // an embedded slash command: keep it visible in place
                out += ChatRenderItem.Single(m)
                continue
            }
            val segs = MessageParser.parse(m.content)
            val parts = segmentsToParts(segs)
            // Is there more tool activity before the next answer boundary? Decides whether
            // trailing prose/telemetry still belongs to this run or the run is over. Header-less
            // fence messages count as tool activity (they're tool output continuations).
            val toolAhead = (p + 1 until blockEnd).asSequence()
                .takeWhile { q ->
                    val n = chrono[q]
                    n.sender == SenderType.ME || isToolMessage(n) || isTelemetryMessage(n) ||
                        (toolSeen && fenceOnly(n)) ||
                        proseLength(MessageParser.parse(n.content)) < ANSWER_PROSE_MIN
                }
                .any { q -> isToolMessage(chrono[q]) || (toolSeen && fenceOnly(chrono[q])) }
            when {
                // Telemetry FIRST: a "⏳ Working…" heartbeat can also match the tool-line shapes,
                // and it must stay a quiet aside, not inflate the "Ran N tools" count.
                isTelemetryMessage(m) -> {
                    if (runStartId != null) entries += ToolRunEntry.Telemetry(m.content.trim())
                    else out += ChatRenderItem.Single(m) // renders as the quiet telemetry row
                }
                isToolMessage(m) -> {
                    toolSeen = true
                    openRun(m)
                    val deduped = dedupCalls(parts.entries, seenCalls)
                    entries += deduped
                    deduped.forEach { if (it is ToolRunEntry.Call) seenCalls += it.call.name to it.call.args }
                    parts.reasoning?.let { addReasoning(it) }
                }
                // Header-less tool output mid-run: a machine-output step inside the run, however
                // long the fences are — never an answer boundary, never a loose code bubble.
                toolSeen && fenceOnly(m) -> {
                    openRun(m)
                    entries += ToolRunEntry.Note(m.content.trim())
                }
                runStartId != null && !toolAhead -> {
                    // First prose after the run's last tool: the answer. Close the run; bubble.
                    closeRun()
                    out += ChatRenderItem.Single(m)
                }
                (runStartId != null || toolAhead) && proseLength(segs) >= ANSWER_PROSE_MIN -> {
                    // A substantial INTERIM answer mid-run (steer continued the turn): visible
                    // bubble, and whatever tools follow start a fresh run.
                    closeRun()
                    out += ChatRenderItem.Single(m)
                }
                runStartId != null || toolAhead -> {
                    openRun(m)
                    if (m.content.trimStart().startsWith("```")) {
                        // Tool output that lost its header (a bare fenced block): a machine-output
                        // step, not inner monologue.
                        entries += ToolRunEntry.Note(m.content.trim())
                    } else {
                        // Intro / "working…" status / standalone reasoning → the reasoning block.
                        addReasoning(parts.reasoning ?: m.content)
                    }
                }
                else -> out += ChatRenderItem.Single(m)
            }
        }
        closeRun()
        i = blockEnd
    }
    return out.asReversed()
}
