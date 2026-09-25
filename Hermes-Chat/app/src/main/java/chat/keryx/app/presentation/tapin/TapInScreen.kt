package chat.keryx.app.presentation.tapin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ui.components.KeryxBreathingDot
import chat.keryx.app.presentation.ui.components.KeryxRunCard
import chat.keryx.app.presentation.ui.components.KeryxContextRing
import chat.keryx.app.presentation.ui.components.KeryxMotion
import chat.keryx.app.presentation.ui.components.KeryxRadius
import chat.keryx.app.presentation.ui.components.KeryxSectionHeader
import chat.keryx.app.presentation.ui.components.KeryxSpace
import chat.keryx.app.presentation.ui.components.KeryxStatus
import chat.keryx.app.presentation.ui.components.KeryxToolTint
import chat.keryx.app.presentation.ui.components.ToolTheaterRow
import chat.keryx.app.presentation.ui.components.rememberReducedMotion
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.protocol.MessageParser
import kotlinx.coroutines.delay

/**
 * Tap-In (2.12): the agent, going — full screen.
 *
 * The transcript says what the agent is doing quietly, one row per call, because the answer is
 * the point and the work is scaffolding. This is the other register, for the moments you want
 * to watch the work: the same facts, the same grammar, the same colours, drawn with room. Four
 * regions and an instrument row — headline, mind, crew, rail, instruments — every one a
 * projection of [TapInState], which is itself a pure function of what the chat already holds.
 *
 * A standalone [KeryxSpace], so it owns its window and the back gesture closes it: nothing
 * floats over the transcript and the floor+contrast rule (2.11.8) does not apply. It does not
 * close itself when the turn lands; the instruments freeze, the headline says "Landed", and
 * the reader steps out when done.
 */
@Composable
fun TapInScreen(
    state: TapInState,
    onClose: () -> Unit,
    /** Open a helper's window — the existing subagent sheet — when it has a record to show. */
    onOpenHelper: ((Delegation) -> Unit)?,
    /** The hand on the turn (2.13): steer / hold-to-queue / stop, the chat composer's busy
     *  grammar. Null where the door can't (Matrix) — the bar is then simply not drawn. */
    steer: TapInSteer? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val reduced by rememberReducedMotion()

    // One clock for the whole screen, second resolution, only while something is live.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running) {
        while (state.running) { now = System.currentTimeMillis(); delay(1_000) }
    }

    KeryxSpace(
        title = "Tap-In",
        onClose = onClose,
        standalone = true,
        liveSlot = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                KeryxBreathingDot(
                    color = if (state.running) accent else if (state.failedCount > 0) KeryxStatus.bad else KeryxStatus.good,
                    alive = state.running,
                    size = 6.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    state.subline.ifBlank { if (state.running) "just started" else "nothing to show yet" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item("headline") { Headline(state, ink, accent, reduced) }
            if (state.mind.isNotBlank()) item("mind") { Mind(state.mind, ink, reduced) }
            if (state.hasCrew) item("crew") { CrewDeck(state.crew, state.running, now, ink, accent2, onOpenHelper) }
            if (state.rail.isNotEmpty()) item("rail-head") {
                KeryxSectionHeader("Rail", count = state.rail.size)
            }
            items(state.rail.size, key = { "rail-$it" }) { i ->
                RailRow(state.rail[i], last = i == state.rail.size - 1, ink = ink, accent = accent, live = state.running)
            }
            if (state.answer.isNotBlank()) item("answer") { Saying(state.answer, ink) }
            if (state.isEmpty) item("empty") {
                Text(
                    if (state.running) "The turn has not shown anything yet — the first tool or thought lands here."
                    else "Nothing was watched on this turn.",
                    color = ink.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        // The bar sits between the space and its instruments while the turn runs: a word to
        // the agent is a thing you do while watching, and the instruments are what you read
        // after. When the turn lands the bar goes — a stop with nothing to stop, or a steer
        // into a finished turn, is the chat composer's job, not this screen's.
        Column(Modifier.fillMaxWidth().imePadding()) {
            if (steer != null && state.running) {
                SteerBar(
                    placeholder = "Type to steer this turn · hold to queue",
                    onSteer = steer.onSteer,
                    onStop = steer.onStop,
                    onQueue = steer.onQueue,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            Instruments(state, ink)
        }
    }
}

/** What the Tap-In bar can do to the turn it is watching. */
class TapInSteer(
    val onSteer: (String) -> Unit,
    val onQueue: (String) -> Unit,
    val onStop: () -> Unit,
)

// --- 1. Headline -------------------------------------------------------------------------------

@Composable
private fun Headline(state: TapInState, ink: Color, accent: Color, reduced: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = state.headline,
            transitionSpec = {
                if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(KeryxMotion.settle) + slideInVertically(KeryxMotion.settleInt) { it / 3 }) togetherWith
                    (fadeOut(KeryxMotion.leave) + slideOutVertically(KeryxMotion.leaveInt) { -it / 3 })
            },
            label = "tapInHeadline",
        ) { line ->
            Text(
                line,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TapIn.clock(state.elapsedMs),
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                color = if (state.running) accent else ink.copy(alpha = 0.6f),
            )
            if (state.charsPerSec > 8f) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "≈${(state.charsPerSec / 4f).toInt()} tok/s",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ink.copy(alpha = 0.45f),
                )
            }
        }
    }
}

// --- 2. Mind -----------------------------------------------------------------------------------

@Composable
private fun Mind(mind: String, ink: Color, reduced: Boolean) {
    Column {
        KeryxSectionHeader("Mind")
        Spacer(Modifier.height(6.dp))
        Text(
            mind,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontStyle = FontStyle.Italic,
            color = ink.copy(alpha = 0.72f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp)
                // Older thought fades upward — the window is a tail, and it should look like
                // one. The text's own alpha is masked, not a band painted over it: the space
                // sits on the dusk sky, whose drifting pools no flat colour matches, so a
                // `surface` gradient read as a bar laid across the cloud.
                .then(if (reduced) Modifier else Modifier.tailFade(22.dp)),
        )
    }
}

/** Fades this content's top [height] to transparent, over whatever ground is behind it. */
private fun Modifier.tailFade(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height / 2f)
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, fade to Color.Black, startY = 0f, endY = fade),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }

// --- 3. Crew -----------------------------------------------------------------------------------

/**
 * The helpers, as cards on a deck: flying first. Each card is the wing's facts with room —
 * glyph and role, the model, tools so far, elapsed, tokens; the live line while it flies, the
 * summary once it lands. A card with a record behind it is a door onto the existing sheet.
 */
@Composable
internal fun CrewDeck(
    crew: List<CrewMember>,
    live: Boolean,
    now: Long,
    ink: Color,
    accent2: Color,
    onOpen: ((Delegation) -> Unit)?,
) {
    val landed = crew.count { !it.run.running }
    Column {
        KeryxSectionHeader(
            "Crew",
            count = crew.size,
            dotColor = if (live && landed < crew.size) accent2 else null,
        )
        if (crew.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$landed of ${crew.size} landed",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ink.copy(alpha = 0.45f),
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(crew, key = { it.run.key }) { member ->
                CrewCard(member, live, now, ink, onOpen)
            }
        }
    }
}

@Composable
private fun CrewCard(member: CrewMember, live: Boolean, now: Long, ink: Color, onOpen: ((Delegation) -> Unit)?) {
    val run = member.run
    val flying = run.running && live
    val tint = when {
        member.failed -> KeryxStatus.bad
        member.interrupted -> KeryxStatus.warn
        flying -> KeryxToolTint.of(ToolGrammar.Family.PEOPLE)
        else -> null
    }
    val canOpen = onOpen != null && run.hasRecord
    val meta = buildList {
        if (run.model.isNotBlank()) add(run.model)
        if (run.toolCount > 0) add("${run.toolCount} tool${if (run.toolCount == 1) "" else "s"}")
        run.elapsedSeconds(if (flying) now else 0L)?.takeIf { it > 0.0 }?.let { add(TapIn.clock((it * 1000).toLong())) }
        if (run.totalTokens > 0) add("${TapIn.compact(run.totalTokens.toLong())} tok")
        if (run.filesWrittenN > 0) add("${run.filesWrittenN} written")
        if (member.interrupted) add("interrupted")
    }
    // While it flies: the newest line it sent, breathing. Once it lands: what it came back
    // with — the summary IS the deliverable, so it gets the room the wing never had.
    KeryxRunCard(
        glyph = member.glyph,
        title = buildString {
            if (member.ordinal > 0) append("[${member.ordinal}] ")
            append(member.role)
        },
        ink = ink,
        modifier = Modifier.width(236.dp).alpha(if (run.running || live.not()) 1f else 0.82f),
        tint = tint,
        breathing = flying,
        meta = meta,
        activity = if (run.running) {
            run.activity.ifBlank { if (run.state == chat.keryx.core.model.DelegationState.SPAWNING) "spawning…" else "working…" }
        } else null,
        alive = flying,
        summary = if (run.running) "" else
            run.summary.ifBlank { run.trail.lastOrNull()?.line.orEmpty() }
                .takeIf { it.isNotBlank() }
                ?.let { MessageParser.extractKeryx(it).text.trim() }.orEmpty(),
        summaryColor = if (member.failed) KeryxStatus.bad else ink.copy(alpha = 0.78f),
        onOpen = if (canOpen) ({ onOpen!!(run) }) else null,
    )
}

// --- 4. Rail -----------------------------------------------------------------------------------

/**
 * The tool timeline. The row is the transcript's own [ToolTheaterRow] — same glyph, same verb,
 * same verdict, same folds for output and diff — hung from a hairline with a bead per call.
 * The bead breathes on the open call; nothing else on the rail moves.
 */
@Composable
private fun RailRow(call: ToolCall, last: Boolean, ink: Color, accent: Color, live: Boolean) {
    Row(Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
        Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) Box(
                Modifier.padding(top = 10.dp).width(1.dp).fillMaxHeight()
                    .background(ink.copy(alpha = 0.18f)),
            )
            val bead = when {
                call.running && live -> accent
                call.failed -> KeryxStatus.bad
                call.verdictOk == true -> KeryxToolTint.forTool(call.name)
                else -> ink.copy(alpha = 0.35f)
            }
            Box(Modifier.padding(top = 4.dp)) {
                if (call.running && live) KeryxBreathingDot(color = accent, alive = true, size = 7.dp)
                else Box(Modifier.size(7.dp).clip(CircleShape).background(bead))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 8.dp)) {
            ToolTheaterRow(call = call, accent = accent, baseColor = ink, beat = call)
        }
    }
}

// --- 5. Saying ---------------------------------------------------------------------------------

@Composable
private fun Saying(answer: String, ink: Color) {
    Column {
        KeryxSectionHeader("Saying")
        Spacer(Modifier.height(6.dp))
        Text(
            MessageParser.extractKeryx(answer).text.trim(),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = ink.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KeryxRadius.card))
                .background(ink.copy(alpha = 0.05f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

// --- Instruments -------------------------------------------------------------------------------

/** One row, same numbers the composer footer shows, drawn with room. */
@Composable
private fun Instruments(state: TapInState, ink: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        val used = state.usedTokens
        val max = state.maxTokens
        if (used != null && max != null && max > 0L) {
            KeryxContextRing(used, max)
            Spacer(Modifier.width(10.dp))
            Instrument(TapIn.compact(used), "of ${TapIn.compact(max)}", ink)
            Spacer(Modifier.width(16.dp))
        }
        Instrument(state.toolCount.toString(), if (state.toolCount == 1) "tool" else "tools", ink)
        if (state.failedCount > 0) {
            Spacer(Modifier.width(16.dp))
            Instrument(state.failedCount.toString(), "failed", ink, KeryxStatus.bad)
        }
        if (state.hasCrew) {
            Spacer(Modifier.width(16.dp))
            Instrument("${state.crewLanded}/${state.crew.size}", "crew", ink)
        }
        Spacer(Modifier.weight(1f))
        if (state.model.isNotBlank()) {
            Text(
                state.model,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ink.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
    }
}

@Composable
private fun Instrument(value: String, label: String, ink: Color, color: Color = ink) {
    Column {
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = color)
        Text(label, fontSize = 9.sp, letterSpacing = 0.6.sp, color = ink.copy(alpha = 0.45f))
    }
}
