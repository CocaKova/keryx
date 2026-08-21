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
import chat.keryx.core.model.Message
import chat.keryx.core.model.Theater
import chat.keryx.core.model.ToolBeat
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.model.SenderType

/**
 * A single Hermes tool invocation as a dream-aesthetic "Sandbox Card": the tool's own glyph,
 * monospace name, the (string) argument as a soft wrapping subtitle, and a quiet ✓. Shared by the
 * inline renderer ([MessageContent]) and the collapsible [ToolGroupCard].
 */
@Composable
fun ToolCallCard(
    call: MessageParser.ToolCall,
    accent: Color,
    baseColor: Color,
    /** The recipient's answer, when this call turned out to be an inter-agent delivery and the
     *  run carried a reply back (2.3 §2). */
    deliveryReply: String? = null,
    /** The same call as the side-channel saw it, when the turn was watched live (2.4) — this is
     *  where a duration, a real verdict and a diff come from; the message text has none. */
    beat: ToolBeat? = null,
) {
    // An inter-agent delivery is a `terminal` call by mechanism and a conversation by meaning.
    // A FAILED one keeps the terminal row on purpose: when the mechanism breaks, the mechanism is
    // exactly what you need to see.
    val deliveryTarget = if (call.ok == false) null else deliveryTargetOf(call)
    if (deliveryTarget != null) {
        AgentDeliverySentNotice(
            target = deliveryTarget,
            pending = call.ok == null,
            reply = deliveryReply.orEmpty(),
            stateKey = "delivery:$deliveryTarget:${call.args.hashCode()}",
            accent = accent,
        )
        return
    }
    // `skill_manage` is SILAS saving/editing a reusable skill (its closed learning loop) — surface
    // it distinctly so "it just learned something" stands out from ordinary tool noise. This rides
    // genuine, universal Hermes output (the tool call itself) — no config or plugin to set up.
    val isSkill = call.name == "skill_manage"
    // The theater's row, at rest. Same glyph, same verb grammar, same quiet — this is the same
    // call the live view already showed, and a boxed gradient card beside a hairline row made
    // one run look like two features.
    val target = ToolGrammar.targetOf(call.name, call.args)
    val ok = beat?.ok ?: call.ok
    val ms = beat?.ms ?: 0L
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Three verdicts, not two: a ✓ has to mean the call was SEEN to succeed. Most tool
            // lines carry no verdict in the text, and printing ✓ for those told a turn's one
            // failure apart from its successes only by luck. Unknown gets its own faint mark; a
            // watched turn fills it in for real from the beat.
            Text(
                when (ok) { true -> "✓"; false -> "✕"; null -> "·" },
                fontSize = 9.sp,
                color = when (ok) {
                    false -> MaterialTheme.colorScheme.error
                    true -> baseColor.copy(alpha = 0.45f)
                    null -> baseColor.copy(alpha = 0.28f)
                },
                modifier = Modifier.width(6.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                if (isSkill) "✦" else ToolGrammar.glyphOf(call.name),
                fontSize = 10.sp,
                color = if (isSkill) accent.copy(alpha = 0.9f) else baseColor.copy(alpha = 0.55f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = ToolGrammar.title(call.name, "", running = false),
                color = baseColor.copy(alpha = 0.72f),
                fontSize = 11.sp,
                maxLines = 1,
            )
            if (target.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = target,
                    color = baseColor.copy(alpha = 0.5f),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (beat != null && (beat.added > 0 || beat.removed > 0)) {
                Spacer(modifier = Modifier.width(6.dp))
                DiffStat(added = beat.added, removed = beat.removed)
            }
            if (ms > 0L) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (ms >= 1000L) "${ms / 1000}s" else "${ms}ms",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = baseColor.copy(alpha = 0.35f),
                )
            }
        }
        if (beat != null && beat.hasDiff) {
            var open by androidx.compose.runtime.saveable.rememberSaveable(call.name + call.args) {
                mutableStateOf(false)
            }
            Text(
                if (open) "▾ diff" else "▸ diff",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.42f),
                modifier = Modifier
                    .padding(start = 19.dp, top = 1.dp)
                    .clickable { open = !open },
            )
            if (open) {
                DiffPanel(
                    diff = beat.diff,
                    truncated = beat.diffTruncated,
                    baseColor = baseColor,
                    modifier = Modifier.padding(start = 19.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** The profile an inter-agent delivery is addressed to, or null for an ordinary tool call. */
internal fun deliveryTargetOf(call: MessageParser.ToolCall): String? =
    chat.keryx.core.model.AgentDeliveryCommand.targetOfCall(call.name, call.args)

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
    /**
     * The structured record of this run, when the turn was watched live (2.4).
     *
     * The committed message only ever carried tool NAMES and a display argument, so a written
     * file could never say what it changed. The side-channel knows — durations, verdicts, real
     * diffs — and while the app is up it keeps that record keyed to the message the turn
     * committed to. Empty for history loaded from Matrix after a restart, which is simply the
     * pre-2.4 card: same grammar, fewer facts.
     */
    structured: List<ToolBeat> = emptyList(),
) {
    val accent = MaterialTheme.colorScheme.primary
    // Expand state persists as the group grows (keyed on the stable oldest-message id).
    // rememberSaveable, not remember: the LazyColumn disposes items that scroll off-screen (a tall
    // expanded run + one auto-follow was enough), and plain remember state died with the item —
    // the log "closed on its own" while being watched.
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(run.id) { mutableStateOf(false) }

    // Subtle breathing glow while the agent is actively invoking tools. Battery Saver holds it at
    // the bright end rather than the resting value, so an active run still reads as active.
    val reduced by rememberReducedMotion()
    val glow = if (active && !reduced) {
        val t = rememberInfiniteTransition(label = "toolPulse")
        t.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "toolPulseAlpha",
        ).value
    } else if (active) 0.42f else 0.22f

    val calls = run.entries.filterIsInstance<ToolRunEntry.Call>().map { it.call }
    // The theater's glyphs, not the emoji the gateway happened to print into the message text:
    // the live view and this record are the same run at two ages and must not look like two
    // different features (Jonny, on device: "the tool call log and the new tool call kind of
    // fight"). Same source of truth for the summary sentence, too.
    val distinctGlyphs = calls.map { ToolGrammar.glyphOf(it.name) }.distinct().take(3)
        .ifEmpty { listOf("⚙") }
    val n = run.callCount
    val failed = calls.count { it.ok == false }
    // A continuation run (header-less fence output only — no headered Call survived Hermes'
    // progress grouping) counts its output steps instead of reading "Ran 0 tools".
    val steps = run.entries.count { it is ToolRunEntry.Note }
    val label = buildString {
        if (calls.isNotEmpty()) {
            append(
                ToolGrammar.summarize(
                    calls.map {
                        ToolGrammar.Mention(
                            name = it.name,
                            target = ToolGrammar.targetOf(it.name, it.args),
                            running = active && it.ok == null,
                        )
                    },
                    live = active,
                )
            )
        } else if (n > 0) {
            append(if (active) "Running $n ${plural(n)}…" else "Ran $n ${plural(n)}")
        } else {
            append(if (active) "Working…" else "$steps output ${if (steps == 1) "step" else "steps"}")
        }
        if (failed > 0) append(" · $failed failed")
    }
    val enriched = remember(calls, structured) { Theater.align(calls.map { it.name }, structured) }
    val added = enriched.values.sumOf { it.added }
    val removed = enriched.values.sumOf { it.removed }

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.widthIn(max = 340.dp).animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                // Hairline, not a filled gradient card. This is scaffolding around the answer,
                // and at rest it should read as quietly as the live theater does; only the
                // border carries the "still working" breath.
                .border(
                    1.dp,
                    if (active) accent.copy(alpha = glow) else baseColor.copy(alpha = 0.16f),
                    RoundedCornerShape(10.dp),
                )
                .clickable { expanded = !expanded; onToggle(expanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(distinctGlyphs.joinToString(" "), fontSize = 12.sp, color = baseColor.copy(alpha = 0.65f))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = baseColor.copy(alpha = 0.8f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // What a run of edits actually did, on the collapsed header — the question you have
            // before deciding whether to open it at all.
            if (added > 0 || removed > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                DiffStat(added = added, removed = removed)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (expanded) "▾" else "▸", color = baseColor.copy(alpha = 0.5f), fontSize = 11.sp)
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
                    // Indexed, not forEach: a delivery call swallows the note that follows it —
                    // that note is the recipient's answer, not a blob of stdout to stack below.
                    // Keryx has no `call.result` the way Talaria does, so the adjacent Note IS the
                    // result, cut at its documented `session_id:` boundary.
                    val consumed = remember(run.entries) {
                        run.entries.indices.filterTo(HashSet()) { i ->
                            val call = run.entries[i] as? ToolRunEntry.Call
                            call != null && call.call.ok != false && deliveryTargetOf(call.call) != null &&
                                run.entries.getOrNull(i + 1) is ToolRunEntry.Note
                        }.mapTo(HashSet()) { it + 1 }
                    }
                    run.entries.forEachIndexed { i, entry ->
                        if (i in consumed) return@forEachIndexed
                        when (entry) {
                            is ToolRunEntry.Call -> ToolCallCard(
                                entry.call,
                                accent,
                                baseColor,
                                deliveryReply = (run.entries.getOrNull(i + 1) as? ToolRunEntry.Note)
                                    ?.takeIf { i + 1 in consumed }
                                    ?.let { chat.keryx.core.model.AgentDeliveryCommand.replyText(it.text) },
                                beat = enriched[calls.indexOfFirst { c -> c === entry.call }],
                            )
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

    /** [suppressQuote]: the gateway reply-threads EVERY prose chunk of a turn back to the
     *  triggering message, so a multi-part turn re-quoted the user's own last message over and
     *  over. A quote that merely points at the user's most recent message adds nothing — it's
     *  suppressed; a quote reaching further back (a genuine reference) still renders. */
    data class Single(val message: Message, val suppressQuote: Boolean = false) : ChatRenderItem {
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
        /** Timestamp of the run's first message — used for day-boundary placement. */
        val ts: Long = 0L,
    ) : ChatRenderItem {
        override val key get() = "toolrun:$id"
        val callCount: Int get() = entries.count { it is ToolRunEntry.Call }
    }

    /** A quiet centered chip marking a local-calendar day boundary in the timeline. */
    data class DayHeader(val epochMillis: Long, val dayKey: String) : ChatRenderItem {
        override val key get() = "day:$dayKey"
    }

    /**
     * An agent turn nobody asked for (2.3 §3). Sits immediately above the bubble it announces, so
     * a herald that speaks on its own initiative reads as an *arrival* rather than as an answer to
     * something you have long since forgotten saying.
     */
    data class Arrival(val message: Message) : ChatRenderItem {
        override val key get() = "arrival:${message.id}"
    }
}

/** How long the room must have been quiet before an agent turn counts as unprompted. */
const val ARRIVAL_QUIET_MS: Long = 20L * 60L * 1000L

/**
 * True when [m] is a herald arriving of its own accord: an agent message whose predecessor is not
 * mine and is at least [ARRIVAL_QUIET_MS] old — nobody asked, and nothing was already in flight.
 *
 * Telemetry (cron check-ins, runtime footers) never arrives: those are already quiet low-contrast
 * rows and marking each one would turn the banner into wallpaper. A null [prev] is NOT an arrival
 * either — at the top of a loaded window "nothing before this" means "not paged in yet", and a
 * false banner on every scrollback boundary is worse than a missed one.
 */
fun isArrival(m: Message, prev: Message?): Boolean {
    if (m.sender != SenderType.HERMES) return false
    if (isTelemetryMessage(m)) return false
    if (prev == null) return false
    if (prev.sender == SenderType.ME) return false
    return m.timestamp - prev.timestamp >= ARRIVAL_QUIET_MS
}

/** Local-calendar day of a timestamp, as a stable key (year * 1000 + day-of-year). */
internal fun dayKeyOf(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
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
            // Decision chips belong to the dialogue bubble, never to a tool-run accordion.
            is MessageParser.Segment.QuickActions -> Unit
        }
    }
    return MsgParts(entries, reasoning.toString().ifBlank { null })
}

/** True for an agent message that contains at least one tool call (not a human's, not mine, not a
 *  media message). The HERMES gate matters: a human whose text merely pattern-matches a tool line
 *  must never be dragged into a tool-run accordion. */
fun isToolMessage(m: Message): Boolean {
    if (m.sender != SenderType.HERMES) return false
    if (m.mediaKind != null) return false
    if (m.content.isBlank()) return false
    return MessageParser.parse(m.content).any {
        it is MessageParser.Segment.Tools || it is MessageParser.Segment.ActionOutput
    }
}

/** True for an agent message that is pure automated telemetry (runtime footer, cron check-in…). */
fun isTelemetryMessage(m: Message): Boolean {
    if (m.sender != SenderType.HERMES || m.mediaKind != null || m.content.isBlank()) return false
    return MessageParser.isTelemetryMessage(m.content)
}

private fun isRuntimeFooterMessage(m: Message): Boolean {
    if (m.sender != SenderType.HERMES || m.mediaKind != null || m.content.isBlank()) return false
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
            // Deliveries are exempt: messaging the same agent twice in a turn is two conversations,
            // not one step re-announced, and collapsing them loses a message the user was sent.
            if (deliveryTargetOf(e.call) != null) { out += e; continue }
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
    val walk = walkRange(orderedNewestFirst.asReversed(), lastMineIdInit = null)
    insertDayHeaders(walk.items, lastDayInit = null)
    return walk.items.asReversed()
}

/** The chronological grouping walk over one message range, with the carried state injected so a
 *  range can be resumed mid-timeline: [lastMineIdInit] is the id of the user's most recent
 *  message BEFORE this range (quote suppression must see across the split). Returns the items in
 *  chronological order plus the carried state at the range's end. */
private class RangeWalk(val items: MutableList<ChatRenderItem>, val lastMineId: String?)

private fun walkRange(
    chronoRange: List<Message>,
    lastMineIdInit: String?,
    /** The message immediately before this range, when the timeline was split — [isArrival] needs
     *  it or the first turn of every resumed suffix would lose its banner. */
    prevBeforeRange: Message? = null,
): RangeWalk {
    val chrono = mergeRuntimeFooters(chronoRange)
    val out = mutableListOf<ChatRenderItem>()
    // The user's most recent message so far in the walk. An agent quote pointing at it is the
    // gateway's per-chunk reply-threading, not a meaningful reference — those quotes are hidden.
    var lastMineId: String? = lastMineIdInit
    fun agentSingle(m: Message) = ChatRenderItem.Single(
        m,
        suppressQuote = m.replyToId != null && m.replyToId == lastMineId,
    )
    var i = 0
    while (i < chrono.size) {
        val start = chrono[i]
        // OTHER (human) senders are never part of an agent block: like media, they render as
        // plain bubbles and break the run — a human interjection must not fold into a tool run's
        // reasoning or get counted as a step.
        if (start.sender != SenderType.HERMES || start.mediaKind != null) {
            if (start.sender == SenderType.ME) lastMineId = start.id
            out += ChatRenderItem.Single(start); i++; continue
        }
        // Extent of this contiguous agent block. My slash commands don't break it — the agent's
        // run continues right through a /steer.
        var blockEnd = i
        while (blockEnd < chrono.size) {
            val m = chrono[blockEnd]
            if (m.mediaKind != null) break
            if (m.sender == SenderType.OTHER) break
            if (m.sender == SenderType.ME && !isCommandMessage(m)) break
            blockEnd++
        }
        if ((i until blockEnd).none { isToolMessage(chrono[it]) }) {
            // A plain agent reply (no tools anywhere): normal bubbles.
            for (p in i until blockEnd) {
                val m = chrono[p]
                if (m.sender == SenderType.ME) { lastMineId = m.id; out += ChatRenderItem.Single(m) }
                else out += agentSingle(m)
            }
            i = blockEnd; continue
        }

        // Sequential walk: build runs, splitting at answer-prose boundaries.
        var runInsertAt = -1 // where in [out] the open run's card belongs (its chronological spot)
        val entries = mutableListOf<ToolRunEntry>()
        val reasoning = StringBuilder()
        val seenCalls = mutableSetOf<Pair<String, String>>()
        var runStartId: String? = null
        var runStartTs = 0L
        // Once a real tool has run in this block, a message that is nothing but ``` fences is that
        // tool's continued output (Hermes drops the glyph header on follow-up progress sends) —
        // NOT an answer. Without this, mid-run fences broke the run and floated as code bubbles.
        // The one exception is the block's LAST substantive agent message (see
        // moreAgentContentAhead below): a turn ends on its answer, never on raw stdout.
        var toolSeen = false
        fun fenceOnly(m: Message) = m.content.trimStart().startsWith("```")
        fun addReasoning(t: String) {
            if (t.isNotBlank()) { if (reasoning.isNotEmpty()) reasoning.append("\n\n"); reasoning.append(t.trim()) }
        }
        fun openRun(at: Message) {
            if (runStartId == null) { runStartId = at.id; runStartTs = at.timestamp; runInsertAt = out.size }
        }
        fun closeRun() {
            val id = runStartId ?: return
            // A run holding ONLY header-less fence Notes is still real tool output: Hermes puts
            // the 💻 header on just the FIRST progress send of its group, so when an interim
            // answer (a steered turn's narration) splits the block mid-group, the continuation
            // run has Notes but no Call. Dropping those runs vanished the output AND every
            // aside folded into their reasoning the moment the room reloaded.
            if (entries.any { it is ToolRunEntry.Call || it is ToolRunEntry.Note }) {
                out.add(
                    runInsertAt,
                    ChatRenderItem.ToolRun(id, entries.toList(), reasoning.toString().ifBlank { null }, runStartTs),
                )
            }
            runStartId = null; runInsertAt = -1
            entries.clear(); reasoning.setLength(0); seenCalls.clear()
        }
        for (p in i until blockEnd) {
            val m = chrono[p]
            if (m.sender == SenderType.ME) { // an embedded slash command: keep it visible in place
                lastMineId = m.id
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
            // A fence-starting message is only a stdout CONTINUATION while more of the turn is
            // still coming — a real turn always ends on its answer, so stdout is never the last
            // substantive agent message of the block (telemetry heartbeats and embedded slash
            // commands don't count). An answer the user asked for "in a code block" also starts
            // with ``` and used to be swallowed into the run as a Note step — live-caught
            // 2026-07-24 when an X-post draft rendered inside its turn's ✍️ write_file run.
            val moreAgentContentAhead = (p + 1 until blockEnd).any { q ->
                val n = chrono[q]
                n.sender == SenderType.HERMES && !isTelemetryMessage(n)
            }
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
                // Requires more of the turn ahead: a fence-starting FINAL message is the answer.
                toolSeen && fenceOnly(m) && moreAgentContentAhead -> {
                    openRun(m)
                    entries += ToolRunEntry.Note(m.content.trim())
                }
                runStartId != null && !toolAhead -> {
                    // First prose after the run's last tool: the answer. Close the run; bubble.
                    closeRun()
                    out += agentSingle(m)
                }
                (runStartId != null || toolAhead) && proseLength(segs) >= ANSWER_PROSE_MIN -> {
                    // A substantial INTERIM answer mid-run (steer continued the turn): visible
                    // bubble, and whatever tools follow start a fresh run.
                    closeRun()
                    out += agentSingle(m)
                }
                runStartId != null -> {
                    // A short aside BETWEEN tools folds into the run.
                    if (m.content.trimStart().startsWith("```")) {
                        // Tool output that lost its header (a bare fenced block): a machine-output
                        // step, not inner monologue.
                        entries += ToolRunEntry.Note(m.content.trim())
                    } else {
                        // "working…" status / mid-run course-correction → the reasoning block.
                        addReasoning(parts.reasoning ?: m.content)
                    }
                }
                toolAhead -> {
                    // Turn-OPENING prose before the first tool ("No worries — let's do it again.
                    // **1. Create file**") is dialogue, not inner monologue: folding it into the
                    // collapsed run made turns appear to start with a bare tool chip and the
                    // intro text silently vanished. Keep it a visible bubble; the run opens at
                    // its first actual tool call.
                    out += agentSingle(m)
                }
                else -> out += agentSingle(m)
            }
        }
        closeRun()
        i = blockEnd
    }
    insertArrivals(out, chrono, prevBeforeRange)
    return RangeWalk(out, lastMineId)
}

/**
 * Put an [ChatRenderItem.Arrival] above every bubble that turns out to be unprompted. Done as a
 * pass over the finished items rather than inside the block walk: only messages that survived as
 * their own bubble can arrive (anything folded into a tool run is mid-turn by definition), and the
 * walk is intricate enough without another branch in it.
 */
private fun insertArrivals(
    items: MutableList<ChatRenderItem>,
    chrono: List<Message>,
    prevBeforeRange: Message?,
) {
    if (items.isEmpty()) return
    val prevOf = HashMap<String, Message?>(chrono.size)
    for ((idx, m) in chrono.withIndex()) {
        prevOf[m.id] = if (idx == 0) prevBeforeRange else chrono[idx - 1]
    }
    var at = 0
    while (at < items.size) {
        val item = items[at]
        if (item is ChatRenderItem.Single && isArrival(item.message, prevOf[item.message.id])) {
            items.add(at, ChatRenderItem.Arrival(item.message))
            at++ // skip the bubble we just announced
        }
        at++
    }
}

/** Day boundaries: [items] is chronological here, so a single walk inserts one quiet header
 *  before the first item of each new local-calendar day. Items without a real timestamp
 *  (synthetic ts=0 placeholders) never trigger a boundary. [lastDayInit] carries the last day
 *  seen before this range so a resumed suffix doesn't repeat its prefix's header; returns the
 *  last day seen, for the next resume. */
private fun insertDayHeaders(items: MutableList<ChatRenderItem>, lastDayInit: String?): String? {
    var lastDay: String? = lastDayInit
    var j = 0
    while (j < items.size) {
        val ts = when (val item = items[j]) {
            is ChatRenderItem.Single -> item.message.timestamp
            is ChatRenderItem.ToolRun -> item.ts
            is ChatRenderItem.DayHeader -> 0L
            // The arrival carries its bubble's own timestamp, so a day boundary lands *above* the
            // mark instead of between the mark and the message it announces.
            is ChatRenderItem.Arrival -> item.message.timestamp
        }
        if (ts > 0L) {
            val day = dayKeyOf(ts)
            if (day != lastDay) {
                items.add(j, ChatRenderItem.DayHeader(ts, day))
                j++
            }
            lastDay = day
        }
        j++
    }
    return lastDay
}

/**
 * A grouped timeline plus the state needed to regroup the next emission incrementally: the
 * rendered [items] (newest-first), and internally the input fingerprint, the prefix/suffix split
 * point, and the carried walk state at the split.
 */
class GroupedTimeline internal constructor(
    /** The rendered items, newest-first — what the LazyColumn consumes. */
    val items: List<ChatRenderItem>,
    /** (id, content-length) fingerprint of the CHRONOLOGICAL input this grouping was built from. */
    internal val ids: Array<String>,
    internal val lens: IntArray,
    /** Chrono index where the trailing (mutable) region begins — just past the last hard boundary. */
    internal val suffixStart: Int,
    /** Grouped items for chrono[0, suffixStart), chronological, day headers included. */
    internal val prefixItems: List<ChatRenderItem>,
    internal val prefixLastMineId: String?,
    internal val prefixLastDay: String?,
)

/** A message no run, footer-merge, or agent block can span: the safe splice point. Mirrors the
 *  block-extent scan in [walkRange] — a human message (not a slash command), any OTHER sender, or
 *  any media message breaks the walk there, so everything before it groups independently of
 *  everything after. */
private fun isHardBoundary(m: Message): Boolean =
    m.mediaKind != null || m.sender == SenderType.OTHER ||
        (m.sender == SenderType.ME && !isCommandMessage(m))

/**
 * [groupChatItems], but O(changed block) per emission instead of O(timeline): during a streamed
 * or multi-message agent turn only the trailing agent block changes, so the grouped prefix up to
 * the last hard boundary is spliced from [previous] and only the tail is re-walked. Any other
 * change (older history loaded, room switch, edits behind the boundary, the boundary itself
 * moving) falls back to the full walk — the fast path is an optimization, never a semantic:
 * `groupChatItemsIncremental(msgs, anything).items == groupChatItems(msgs)` always.
 */
fun groupChatItemsIncremental(
    orderedNewestFirst: List<Message>,
    previous: GroupedTimeline?,
): GroupedTimeline {
    val chrono = orderedNewestFirst.asReversed()
    val n = chrono.size
    var suffixStart = 0
    for (i in n - 1 downTo 0) {
        if (isHardBoundary(chrono[i])) { suffixStart = i + 1; break }
    }
    // The cached prefix is reusable only when it was split at the SAME boundary and every
    // message before it is unchanged (id + length; edits behind the boundary are rare and a
    // length change is how m.replace edits manifest).
    val prefixReusable = previous != null &&
        previous.suffixStart == suffixStart &&
        previous.ids.size >= suffixStart &&
        (0 until suffixStart).all { i ->
            previous.ids[i] == chrono[i].id && previous.lens[i] == chrono[i].content.length
        }
    val prefixItems: List<ChatRenderItem>
    val prefixLastMineId: String?
    val prefixLastDay: String?
    if (prefixReusable) {
        prefixItems = previous!!.prefixItems
        prefixLastMineId = previous.prefixLastMineId
        prefixLastDay = previous.prefixLastDay
    } else {
        val walk = walkRange(chrono.subList(0, suffixStart), lastMineIdInit = null)
        prefixLastDay = insertDayHeaders(walk.items, lastDayInit = null)
        prefixItems = walk.items
        prefixLastMineId = walk.lastMineId
    }
    val suffix = walkRange(
        chrono.subList(suffixStart, n),
        lastMineIdInit = prefixLastMineId,
        prevBeforeRange = chrono.getOrNull(suffixStart - 1),
    )
    insertDayHeaders(suffix.items, lastDayInit = prefixLastDay)
    val combined = ArrayList<ChatRenderItem>(prefixItems.size + suffix.items.size)
    combined.addAll(prefixItems)
    combined.addAll(suffix.items)
    return GroupedTimeline(
        items = combined.asReversed(),
        ids = Array(n) { chrono[it].id },
        lens = IntArray(n) { chrono[it].content.length },
        suffixStart = suffixStart,
        prefixItems = prefixItems,
        prefixLastMineId = prefixLastMineId,
        prefixLastDay = prefixLastDay,
    )
}
