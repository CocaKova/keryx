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
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.model.SenderType
import chat.keryx.core.protocol.MessageParser

/**
 * A single Hermes tool invocation as a dream-aesthetic "Sandbox Card": the tool's own glyph,
 * monospace name, the (string) argument as a soft wrapping subtitle, and a quiet ✓. Shared by the
 * inline renderer ([MessageContent]) and the collapsible [ToolTheaterRun].
 */
@Composable
fun ToolTheaterRow(
    call: ToolCall,
    accent: Color,
    baseColor: Color,
    /** The recipient's answer, when this call turned out to be an inter-agent delivery and the
     *  run carried a reply back (2.3 §2). */
    deliveryReply: String? = null,
    /** The same call as the side-channel saw it, when the turn was watched live (2.4) — this is
     *  where a duration, a real verdict and a diff come from; the message text has none. */
    beat: ToolCall? = null,
) {
    // An inter-agent delivery is a `terminal` call by mechanism and a conversation by meaning.
    // A FAILED one keeps the terminal row on purpose: when the mechanism breaks, the mechanism is
    // exactly what you need to see.
    val deliveryTarget = if (call.failed) null else deliveryTargetOf(call)
    if (deliveryTarget != null) {
        AgentDeliverySentNotice(
            target = deliveryTarget,
            pending = call.verdictOk == null,
            reply = deliveryReply.orEmpty(),
            stateKey = "delivery:$deliveryTarget:${call.context.hashCode()}",
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
    val target = ToolGrammar.targetOf(call.name, call.context)
    val ok = beat?.verdictOk ?: call.verdictOk
    val durS = beat?.durationS
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
            if (durS != null && durS > 0.0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (durS >= 1.0) "${durS.toInt()}s" else "${(durS * 1000).toInt()}ms",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = baseColor.copy(alpha = 0.35f),
                )
            }
        }
        // What the tool handed back (2.5.7). Both producers carry it now — the direct door always
        // did (`tool.complete.result`), the side-channel since every `end` frame started carrying
        // its result — and neither was drawn: a failure showed a ✕ and kept its reason to itself,
        // and a `write_file` result with the syntax oracle's verdict appended had no window at all
        // (Jonny: "I don't see the tool payloads or failures"). A failure's reason is always on
        // show; the full payload is a fold, because a run of twelve calls is a wall otherwise.
        val output = beat?.result?.ifBlank { null } ?: call.result
        if (ok == false && output.isNotBlank()) {
            Text(
                text = Theater.reason(output),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 19.dp, top = 1.dp),
            )
        }
        if (output.isNotBlank()) {
            var showOutput by androidx.compose.runtime.saveable.rememberSaveable(
                "out:" + call.name + call.context,
            ) { mutableStateOf(false) }
            Text(
                if (showOutput) "▾ output" else "▸ output",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.42f),
                modifier = Modifier
                    .padding(start = 19.dp, top = 1.dp)
                    .clickable { showOutput = !showOutput },
            )
            if (showOutput) {
                Text(
                    text = output,
                    color = baseColor.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(start = 19.dp, top = 2.dp, bottom = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(baseColor.copy(alpha = 0.06f))
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        if (beat != null && beat.hasDiff) {
            var open by androidx.compose.runtime.saveable.rememberSaveable(call.name + call.context) {
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
                    diff = beat.inlineDiff,
                    truncated = beat.diffTruncated,
                    baseColor = baseColor,
                    modifier = Modifier.padding(start = 19.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** The profile an inter-agent delivery is addressed to, or null for an ordinary tool call. */
internal fun deliveryTargetOf(call: ToolCall): String? =
    chat.keryx.core.model.AgentDeliveryCommand.targetOfCall(call.name, call.context)

/**
 * A run of consecutive tool-only Hermes messages, collapsed into one compact bubble. While the
 * agent is still working ([active]) it shows a pulsing "Running N tools…"; once done it settles to
 * "Ran N tools". Tap to expand the individual [ToolTheaterRow]s with a fluid accordion.
 *
 * The expanded body opens with all of the run's reasoning gathered into ONE collapsible block (so a
 * mid-run "let me correct course" thought no longer fragments the chain), then the tool steps in a
 * height-bounded scroller that always starts at the top — the first (oldest) tool, not the last.
 */
@Composable
fun ToolTheaterRun(
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
    structured: List<ToolCall> = emptyList(),
    /** Open a landed subagent's own session. Rode the live stage until 3.1 §A2 retired it; the
     *  wings live in the run now, and so does the way into them. */
    onOpenSubagent: ((chat.keryx.core.model.Delegation) -> Unit)? = null,
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
    val wings = run.entries.filterIsInstance<ToolRunEntry.Delegated>().map { it.run }
    // The theater's glyphs, not the emoji the gateway happened to print into the message text:
    // the live view and this record are the same run at two ages and must not look like two
    // different features (Jonny, on device: "the tool call log and the new tool call kind of
    // fight"). Same source of truth for the summary sentence, too.
    val distinctGlyphs = calls.map { ToolGrammar.glyphOf(it.name) }.distinct().take(3)
        .ifEmpty { listOf("⚙") }
    val n = run.callCount
    val failed = calls.count { it.failed }
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
                            target = ToolGrammar.targetOf(it.name, it.context),
                            running = active && it.verdictOk == null,
                        )
                    },
                    live = active,
                )
            )
        } else if (n > 0) {
            append(if (active) "Running $n ${plural(n)}…" else "Ran $n ${plural(n)}")
        } else if (wings.isNotEmpty()) {
            // A wings-only run (a background fan-out's dispatch or its landed report): the
            // rail says what it is; the header just counts.
            val flying = wings.count { it.running }
            append(
                when {
                    flying > 0 && wings.size > 1 -> "$flying of ${wings.size} subagents running"
                    flying > 0 -> "Subagent running"
                    wings.size > 1 -> "Delegated ${wings.size} tasks"
                    else -> "Delegated a task"
                },
            )
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
                // One consolidated reasoning block for the whole run, above the steps — the same
                // disclosure every settled thought renders as (3.1 §B3: one renderer, one voice).
                // Not keyed on the text: the block grows while the run is live, and re-keying
                // collapsed it mid-read; the stable run id keeps the user's toggle.
                run.reasoning?.let {
                    ReasoningDisclosure(
                        reasoning = it,
                        seconds = null,
                        streaming = false,
                        stateKey = "runthink-${run.id}",
                    )
                }
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
                            call != null && !call.call.failed && deliveryTargetOf(call.call) != null &&
                                run.entries.getOrNull(i + 1) is ToolRunEntry.Note
                        }.mapTo(HashSet()) { it + 1 }
                    }
                    // Consecutive wings are one dispatch — one rail, not one header per wing.
                    var wingGroupStart = -1
                    run.entries.forEachIndexed { i, entry ->
                        if (i in consumed) return@forEachIndexed
                        if (entry is ToolRunEntry.Delegated) {
                            if (wingGroupStart < 0) {
                                wingGroupStart = i
                                val group = run.entries.drop(i)
                                    .takeWhile { it is ToolRunEntry.Delegated }
                                    .map { (it as ToolRunEntry.Delegated).run }
                                DelegationWings(group, live = active, baseColor = baseColor, onOpen = onOpenSubagent)
                            }
                            return@forEachIndexed
                        }
                        wingGroupStart = -1
                        when (entry) {
                            is ToolRunEntry.Call -> ToolTheaterRow(
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
                            is ToolRunEntry.Delegated -> Unit // drawn as a wing group above
                        }
                    }
                }
            }
        }
    }
}

private fun plural(n: Int) = if (n == 1) "tool" else "tools"
