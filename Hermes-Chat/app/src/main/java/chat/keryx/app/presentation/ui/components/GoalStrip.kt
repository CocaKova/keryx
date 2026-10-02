package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.core.model.GoalControl
import chat.keryx.core.model.SessionControl
import chat.keryx.core.model.SessionControls

/**
 * The goal strip (2.16): the session's standing goal, pinned with the flight plan — turns used
 * of the max, the gates going green one by one, the verdict stamped when the judge rules. Tap
 * opens the subgoals, the gates with their last exit code, and pause / resume / clear.
 *
 * It floats over the transcript exactly as the flight plan does, so it stands on the same floor
 * ([FlightPlanFloor]: [GoalStripFloor] pins the two together and PaperContrastTest measures the
 * text on it). A loop or heartbeat with no goal reads as one quiet line.
 */
@Composable
fun GoalStrip(control: SessionControl, onAction: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val floor = MaterialTheme.colorScheme.surface.copy(alpha = GoalStripFloor.ALPHA)
    val good = KeryxStatus.good
    val bad = MaterialTheme.colorScheme.error
    val goal = control.goal
    val line = goal?.let(SessionControls::headline) ?: buildList {
        control.loop?.let { add("↻ loop · ${it.ticksFired}${it.maxTicks?.let { m -> "/$m" } ?: ""} · ${it.status}") }
        control.heartbeat?.let { add("♥ heartbeat · ${it.fireCount} · ${it.status}") }
    }.joinToString("  ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(floor)
            .drawBehind {
                drawLine(
                    color = onSurface.copy(alpha = FlightPlanFloor.EDGE_ALPHA),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .clickable(enabled = goal != null, onClickLabel = if (open) "Close the goal" else "Open the goal") { open = !open }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line,
                color = when {
                    goal?.done == true -> good
                    goal?.lastVerdict == "blocked" -> bad
                    else -> onSurface.copy(alpha = GoalStripFloor.LINE_ALPHA)
                },
                fontSize = KeryxType.caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    // The verdict and the gates changing are worth hearing once each.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (goal != null) Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.5f), fontSize = KeryxType.micro)
        }
        if (open && goal != null) GoalDetail(goal, onSurface, quiet, good, bad, onAction)
    }
}

@Composable
private fun GoalDetail(
    goal: GoalControl,
    onSurface: androidx.compose.ui.graphics.Color,
    quiet: androidx.compose.ui.graphics.Color,
    good: androidx.compose.ui.graphics.Color,
    bad: androidx.compose.ui.graphics.Color,
    onAction: (String) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        (goal.waitReason ?: goal.pausedReason ?: goal.lastReason)?.let {
            Text(it, color = quiet, fontSize = KeryxType.caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        goal.subgoals.forEach { sub ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("·", color = quiet, fontSize = KeryxType.caption)
                Spacer(Modifier.width(8.dp))
                Text(sub, color = onSurface.copy(alpha = 0.8f), fontSize = KeryxType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        goal.gates.forEach { gate ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    when { gate.passed -> "✓"; gate.failed -> "✕"; else -> "○" },
                    color = when { gate.passed -> good; gate.failed -> bad; else -> quiet },
                    fontSize = KeryxType.caption,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    gate.command,
                    color = onSurface.copy(alpha = 0.8f),
                    fontSize = KeryxType.caption,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                gate.lastExitCode?.let {
                    Text("exit $it", color = if (it == 0) good else bad, fontSize = KeryxType.micro, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row {
            SessionControls.actions(goal).forEach { (action, label) ->
                TextButton(onClick = { onAction(action) }) {
                    Text(label, fontSize = KeryxType.caption, color = if (action == "goal.clear") bad else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * The goal strip's floor: the flight plan's, held equal so one contrast measurement covers both
 * instruments (PaperContrastTest).
 */
object GoalStripFloor {
    const val ALPHA = FlightPlanFloor.ALPHA
    const val LINE_ALPHA = FlightPlanFloor.LINE_ALPHA
}
