package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.TodoPlan

/**
 * FLIGHT PLAN — the agent's own `todo` list, pinned under the top bar with the other
 * instruments. When the agent lays out a 1-2-3-4 it stays STATIC here and ticks itself off
 * as the work lands — the transcript scrolls, the plan doesn't. Collapsed = one line (count +
 * the step in progress); tap for the full checklist. Appears only while a plan exists.
 *
 * The pulse carries information (a step is in flight), so it keeps moving — and under
 * Battery Saver it holds at the bright end rather than reading as "stopped".
 */
@Composable
fun FlightPlanStrip(plan: TodoPlan) {
    var open by rememberSaveable { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val good = KeryxStatus.good

    val reduced by rememberReducedMotion()
    val pulse = if (reduced) 1f else rememberInfiniteTransition(label = "plan_pulse").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "plan_pulse_alpha",
    ).value

    @Composable
    fun Dot(color: Color, size: Dp, alpha: Float) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color.copy(alpha = color.alpha * alpha)),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(onSurface.copy(alpha = 0.03f))
            .clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (plan.allDone) good else accent, 7.dp, if (plan.allDone) 0.9f else pulse)
            Spacer(Modifier.width(8.dp))
            Text(
                "FLIGHT PLAN",
                color = quiet,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${plan.done}/${plan.total}",
                color = if (plan.allDone) good else quiet,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    plan.allDone -> "all steps landed"
                    else -> plan.active?.content ?: "next: " +
                        (plan.items.firstOrNull { it.status == "pending" }?.content ?: "—")
                },
                color = onSurface.copy(alpha = if (plan.allDone) 0.45f else 0.7f),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) "▾" else "▸", color = quiet.copy(alpha = 0.5f), fontSize = 11.sp)
        }

        if (open) {
            Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                plan.items.forEach { item ->
                    val done = item.status == "completed"
                    val cancelled = item.status == "cancelled"
                    val running = item.status == "in_progress"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.5.dp),
                    ) {
                        when {
                            running -> Dot(accent, 6.dp, pulse)
                            done -> Text("✓", color = good.copy(alpha = 0.75f), fontSize = 10.sp)
                            cancelled -> Text("✕", color = quiet.copy(alpha = 0.5f), fontSize = 10.sp)
                            else -> Text("○", color = quiet.copy(alpha = 0.55f), fontSize = 10.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.content,
                            color = when {
                                running -> onSurface.copy(alpha = 0.9f)
                                done || cancelled -> onSurface.copy(alpha = 0.38f)
                                else -> onSurface.copy(alpha = 0.66f)
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (running) FontWeight.Medium else FontWeight.Normal,
                            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
