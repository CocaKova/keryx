package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The context window as a ring: an arc that travels the circumference as the model's window
 * fills, and *bolds* as it goes — a whisper-thin thread at an empty window, a solid band as
 * compaction nears. Accent while comfortable, amber past three quarters, red past nine tenths.
 * Tap toggles the exact figure ("84k / 128k") beside the ring.
 */
@Composable
fun KeryxContextRing(used: Long, max: Long, modifier: Modifier = Modifier) {
    if (used <= 0L || max <= 0L) return
    val frac = (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(frac * 360f, spring(stiffness = 60f, dampingRatio = 1f), label = "ctxSweep")
    val color = when {
        frac > 0.90f -> KeryxStatus.bad
        frac > 0.75f -> KeryxStatus.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    var showFigure by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The ring is 15dp of drawing; the TAP that reveals "84k / 128k" was 15dp too, wedged in
        // 3dp of padding at the very corner of the screen — the hardest place on a phone to hit
        // accurately. The mark stays the size it was; the target rides the composer footer's
        // own 36dp band.
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .clickable { showFigure = !showFigure }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (showFigure) {
            Text(
                "${used / 1000}k / ${max / 1000}k",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = meta,
            )
            Spacer(Modifier.width(5.dp))
        }
        Canvas(Modifier.size(15.dp)) {
            val trackStroke = 1.2.dp.toPx()
            val fillStroke = (1.2f + 1.9f * frac).dp.toPx()
            drawCircle(track, radius = (size.minDimension - trackStroke) / 2f, style = Stroke(trackStroke))
            val inset = fillStroke / 2f
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                style = Stroke(fillStroke, cap = StrokeCap.Round),
            )
        }
    }
}
