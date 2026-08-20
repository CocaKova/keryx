package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.domain.model.Delegation
import chat.keryx.app.domain.model.DelegationState
import chat.keryx.app.domain.model.Theater
import chat.keryx.app.domain.model.TheaterState
import chat.keryx.app.domain.model.ToolBeat
import chat.keryx.app.domain.model.ToolGrammar

/**
 * The tool theater (2.4), inside the live reply: what the agent is *doing* while it works —
 * its own calls, and the subagents it sent out.
 *
 * Deliberately scaffold voice — monospace, low alpha, one line per beat — because this is
 * telemetry that expires. The committed message renders the same calls properly a moment
 * later ([ToolGroupCard]); if the theater competed with the answer for attention it would be
 * shouting about the means while the end arrives.
 *
 * The exception is a delegation, which gets Talaria's fuller treatment: a delegated child is
 * not a session you can open and its relay is never persisted, so this live view is the only
 * window onto it. A tail of what it is doing beats a silent spinner, and the token rollup is
 * the one number that makes delegation legible as a decision.
 */
private const val VISIBLE_BEATS = 6

@Composable
fun TheaterStage(
    state: TheaterState,
    live: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier,
    /** Open a landed subagent's own session — null when this surface has no way to. */
    onOpenSubagent: ((Delegation) -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = !state.isEmpty,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val hidden = (state.beats.size - VISIBLE_BEATS).coerceAtLeast(0)
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (hidden > 0) {
                Text(
                    "· $hidden earlier",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = baseColor.copy(alpha = 0.35f),
                )
            }
            Theater.batches(state.beats.takeLast(VISIBLE_BEATS)).forEach { batch ->
                if (batch.size > 1) ParallelBatch(batch, live, baseColor)
                else TheaterRow(batch[0], live, baseColor)
            }
            if (state.delegations.isNotEmpty()) {
                DelegationWings(state.delegations, live, baseColor, onOpenSubagent)
            }
        }
    }
}

/**
 * One dispatch: the calls the model fired in a single breath, joined by a hairline rail with a
 * count at its head. No box, no fill — the rail is the only new mark, because the fact being
 * communicated ("these ran together") is structural, not decorative.
 */
@Composable
private fun ParallelBatch(calls: List<ToolBeat>, live: Boolean, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.tertiary
    val running = calls.any { it.running }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(active = running && live, baseColor = baseColor, accent = accent)
        Column {
            Text(
                // Say only what is known. This channel reports when calls were ANNOUNCED, not
                // how the runtime ran them — it may well have gone one at a time (barriers,
                // path conflicts). "In one turn" is the claim that survives.
                "${calls.size} in one turn · " + ToolGrammar.summarize(
                    calls.map {
                        ToolGrammar.Mention(it.name, ToolGrammar.targetOf(it.name, it.preview), it.running)
                    },
                    live = running,
                ),
                color = baseColor.copy(alpha = 0.45f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            calls.forEach { TheaterRow(it, live, baseColor) }
        }
    }
}

/** The subagents this turn sent out, under the same rail a parallel batch uses — because that
 *  is exactly what this is: work happening somewhere else, at the same time. */
@Composable
private fun DelegationWings(
    runs: List<Delegation>,
    live: Boolean,
    baseColor: Color,
    onOpen: ((Delegation) -> Unit)?,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val flying = runs.count { it.running }
    Row(Modifier.height(IntrinsicSize.Min).padding(top = 2.dp)) {
        Rail(active = flying > 0 && live, baseColor = baseColor, accent = accent)
        Column {
            Text(
                when {
                    // The fan-out size comes from the gateway's own task_count, so a single
                    // delegation never claims to be a squadron.
                    flying > 0 && runs.size > 1 -> "$flying of ${runs.size} subagents running"
                    flying > 0 -> "subagent running"
                    runs.size > 1 -> "${runs.size} subagents"
                    else -> "subagent"
                },
                color = baseColor.copy(alpha = 0.45f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
            )
            runs.forEach { DelegationWing(it, live, baseColor, onOpen) }
        }
    }
}

@Composable
private fun DelegationWing(
    run: Delegation,
    live: Boolean,
    baseColor: Color,
    onOpen: ((Delegation) -> Unit)?,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val failed = run.state == DelegationState.FAILED
    val interrupted = run.state == DelegationState.INTERRUPTED
    Column(Modifier.padding(vertical = 1.dp).alpha(if (run.running) 1f else 0.75f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 12.dp)) {
                // A flying wing breathes; a settled one keeps the delegate glyph. Success stays
                // silent — only failure earns a mark.
                if (run.running && live) Pulse(accent)
                else Text(
                    if (failed) "!" else "⑂",
                    color = if (failed) error else baseColor.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(4.dp))
            // A landed wing with a session behind it is a door, and should look like one.
            val canOpen = onOpen != null && run.openable && !run.running
            Text(
                buildString {
                    // A fan-out's wings are told apart by their 1-based index, matching the
                    // gateway's own "[2] " prefixes in the CLI tree.
                    if (run.taskCount > 1) append("[${run.taskIndex + 1}] ")
                    append(run.goal.ifBlank { "delegated task" })
                },
                color = baseColor.copy(alpha = 0.85f),
                fontSize = 10.sp,
                textDecoration = if (canOpen) TextDecoration.Underline else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (canOpen) Modifier.clickable { onOpen!!(run) } else Modifier),
            )
            if (canOpen) {
                Spacer(Modifier.width(5.dp))
                Text("↗", fontSize = 9.sp, color = baseColor.copy(alpha = 0.45f))
            }
        }
        val meta = buildList {
            if (run.model.isNotBlank()) add(run.model)
            if (run.toolCount > 0) add("${run.toolCount} tool${if (run.toolCount == 1) "" else "s"}")
            durationLabel(run.durationSeconds)?.let { add(it) }
            if (run.totalTokens > 0) add("${run.totalTokens / 1000}k tok")
            if (run.filesWritten > 0) add("${run.filesWritten} written")
            if (interrupted) add("interrupted")
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                color = baseColor.copy(alpha = 0.4f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        // While it flies: the newest line it sent. Once it lands: the summary it came back
        // with — for a fan-out reporting back, that summary IS the work, and the only place
        // the child's result exists on this screen. Two lines at rest, the rest one tap away
        // rather than gone.
        val tail = if (run.running) run.activity else run.summary
        var expanded by rememberSaveable(run.key) { mutableStateOf(false) }
        val expandable = !run.running && tail.isNotBlank()
        if (tail.isNotBlank()) {
            AnimatedContent(
                targetState = tail,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn(tween(180))) togetherWith
                        (slideOutVertically { -it } + fadeOut(tween(120)))
                },
                label = "wingTail",
            ) { line ->
                Text(
                    line,
                    color = if (failed) error.copy(alpha = 0.8f) else baseColor.copy(alpha = 0.5f),
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp,
                    maxLines = when {
                        run.running -> 1
                        expanded -> Int.MAX_VALUE
                        else -> 2
                    },
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
                )
            }
        }
    }
}

@Composable
private fun TheaterRow(beat: ToolBeat, live: Boolean, baseColor: Color) {
    val accent = MaterialTheme.colorScheme.tertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 1.dp),
    ) {
        // A running call breathes; a landed one is a fixed mark. When the turn itself has
        // stopped, a still-open call is neither — it never reported back, and a pulse that
        // outlives the turn reads as "still working" when nothing is.
        if (beat.running && live) {
            Pulse(accent)
        } else {
            Text(
                when {
                    beat.running -> "·"
                    beat.ok == false -> "✕"
                    else -> "✓"
                },
                fontSize = 9.sp,
                color = if (beat.ok == false) MaterialTheme.colorScheme.error
                        else baseColor.copy(alpha = 0.45f),
                modifier = Modifier.width(5.dp),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            ToolGrammar.glyphOf(beat.name),
            fontSize = 9.5.sp,
            color = baseColor.copy(alpha = 0.55f),
        )
        Spacer(Modifier.width(5.dp))
        // One grammar, live and committed alike: "Reading SOUL.md" now, "Read SOUL.md" in the
        // transcript afterwards — not `read_file` in one place and a sentence in the other.
        val target = ToolGrammar.targetOf(beat.name, beat.preview)
        Text(
            ToolGrammar.title(beat.name, "", beat.running),
            fontSize = 10.sp,
            color = baseColor.copy(alpha = if (beat.running) 0.85f else 0.6f),
            maxLines = 1,
        )
        if (target.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                target,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (beat.added > 0 || beat.removed > 0) {
            Spacer(Modifier.width(6.dp))
            DiffStat(added = beat.added, removed = beat.removed)
        }
        if (!beat.running && beat.ms > 0L) {
            Spacer(Modifier.width(6.dp))
            Text(
                if (beat.ms >= 1000L) "${beat.ms / 1000}s" else "${beat.ms}ms",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = baseColor.copy(alpha = 0.35f),
            )
        }
    }
    // What the edit actually did. Behind a tap, because the stat beside the row already answers
    // "how big was it" and the body is only wanted when the answer is "bigger than I expected".
    if (beat.hasDiff) {
        var open by rememberSaveable(beat.name + beat.added + beat.removed) { mutableStateOf(false) }
        Text(
            if (open) "▾ diff" else "▸ diff",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = baseColor.copy(alpha = 0.4f),
            modifier = Modifier
                .padding(start = 17.dp, bottom = 1.dp)
                .clickable { open = !open },
        )
        if (open) {
            DiffPanel(
                diff = beat.diff,
                truncated = beat.diffTruncated,
                baseColor = baseColor,
                maxHeight = 150.dp,
                modifier = Modifier.padding(start = 17.dp, bottom = 3.dp),
            )
        }
    }
    // Only a failure carries its result this far (the gateway sends it for nothing else), and
    // when a tool breaks mid-turn the reason is the one thing worth the extra line.
    if (beat.ok == false && beat.result.isNotBlank()) {
        Text(
            Theater.reason(beat.result),
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 17.dp, bottom = 2.dp),
        )
    }
}

/** The hairline a grouped run hangs from — one mark, no box, no fill. */
@Composable
private fun Rail(active: Boolean, baseColor: Color, accent: Color) {
    Box(
        Modifier
            .padding(end = 7.dp, top = 2.dp, bottom = 2.dp)
            .width(1.dp)
            .fillMaxHeight()
            .background(if (active) accent.copy(alpha = 0.5f) else baseColor.copy(alpha = 0.25f)),
    )
}

@Composable
private fun Pulse(color: Color) {
    // The transition only exists while it actually animates: a turn that runs for minutes under
    // Battery Saver must not hold a frame-clock client open for a 5dp dot. Stilled, the dot keeps
    // the bright end of its range — it is marking something genuinely live, so it reads as lit
    // rather than as a beat that stopped.
    val reduced by rememberReducedMotion()
    val a = if (!reduced) {
        val transition = rememberInfiniteTransition(label = "beat")
        transition.animateFloat(
            initialValue = 0.25f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
            label = "beatAlpha",
        ).value
    } else 0.9f
    Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

private fun durationLabel(seconds: Double?): String? {
    val s = seconds ?: return null
    if (s <= 0.0) return null
    return if (s < 60.0) "${s.toInt()}s" else "${(s / 60).toInt()}m${(s % 60).toInt()}s"
}
