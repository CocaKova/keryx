package chat.keryx.app.presentation.ui.components

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 *
 * With [compactAt] (the gateway's auto-compaction trigger, 2.13.11) the ring measures the way
 * to THAT, not to the model's window: full means "the next call compacts". Against the window
 * the ring stood at ~60% the moment a session compacted, and the compaction looked like it came
 * from nowhere.
 *
 * 3.0 (chat memory): quiet until it matters. Below half way to the summary point it is not drawn
 * at all — at a low fill the hairline arc read as a loading spinner. From [ContextOutlook.SHOW_LEFT]
 * it carries a short label ("~12 left", from [repliesLeft]), and one tap ([onTap]) opens the
 * chat-memory sheet that explains it in a sentence. The old tap that toggled "84k · 216k to
 * compaction" and the second tap target laid over it are gone.
 */
@Composable
fun KeryxContextRing(
    used: Long,
    max: Long,
    modifier: Modifier = Modifier,
    compactAt: Long = 0L,
    /** The prefix cache is warm (2.16, `cache_hit_pct` ≥ 80): a gilded inner edge. */
    cacheWarm: Boolean = false,
    /** A compaction running since this phone-clock instant (2.16): the ring drains toward empty
     *  over [drainSeconds] (the typical compaction this app has measured), and snaps to the new
     *  reading with one tick when it lands. Null = not compacting. */
    drainSince: Long? = null,
    drainSeconds: Int? = null,
    /** About how many replies fit before the summary point ([ContextOutlook.repliesLeft]). */
    repliesLeft: Int? = null,
    /** Opens the chat-memory sheet. */
    onTap: (() -> Unit)? = null,
) {
    if (used <= 0L || max <= 0L) return
    val toCompaction = chat.keryx.core.model.CompactionGauge.of(used, compactAt.takeIf { it in 1..max })
    val reading = toCompaction?.fraction ?: (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    if (!chat.keryx.core.model.ContextOutlook.showRing(reading, compacting = drainSince != null)) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(drainSince) {
        // On screen only, and only while a compaction runs: four reads a second is plenty.
        while (drainSince != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(250) }
    }
    val haptics = LocalKeryxHaptics.current
    var wasDraining by remember { mutableStateOf(drainSince != null) }
    androidx.compose.runtime.LaunchedEffect(drainSince != null) {
        if (wasDraining && drainSince == null) haptics.commit()
        wasDraining = drainSince != null
    }
    val frac = chat.keryx.core.model.CompactionGauge.drained(reading, drainSince, drainSeconds, now)
    val sweep by animateFloatAsState(frac * 360f, spring(stiffness = 60f, dampingRatio = 1f), label = "ctxSweep")
    val color = when {
        frac > 0.90f -> KeryxStatus.bad
        frac > 0.75f -> KeryxStatus.warn
        else -> MaterialTheme.colorScheme.primary
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    val left = toCompaction?.left ?: (max - used).coerceAtLeast(0L)
    val label = if (drainSince != null) null
        else chat.keryx.core.model.ContextOutlook.shortLabel(reading, repliesLeft, left)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The ring is 15dp of drawing; the target rides the composer footer's own 36dp band
        // (a 15dp target in the corner of the screen is the hardest place on a phone to hit).
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .let { m -> if (onTap != null) m.clickable(onClickLabel = "Chat memory", onClick = onTap) else m }
            // The ring is a drawing, so it says in words what it shows (3.0 accessibility pass).
            .semantics { contentDescription = contextRingLabel(used, max, toCompaction?.left, repliesLeft) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (label != null) {
            Text(
                label,
                fontSize = KeryxType.micro,
                fontFamily = FontFamily.Monospace,
                color = if (frac > 0.75f) color else meta,
                // One line: wrapped, it made the composer grow a row (QA 2.19.1).
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(5.dp))
        }
        Canvas(Modifier.size(15.dp)) {
            val trackStroke = 1.2.dp.toPx()
            val fillStroke = (1.2f + 1.9f * frac).dp.toPx()
            drawCircle(track, radius = (size.minDimension - trackStroke) / 2f, style = Stroke(trackStroke))
            if (cacheWarm) {
                // Gilt on the inside of the band: what the window holds is mostly already paid for.
                val gild = 0.9.dp.toPx()
                drawCircle(Gilt, radius = size.minDimension / 2f - fillStroke - gild, style = Stroke(gild))
            }
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

/** The cache-warm edge's gold (2.16). Decorative: it carries no text. */
private val Gilt = androidx.compose.ui.graphics.Color(0xFFD4A537)

/** What the context ring says aloud: how full the chat is, and how far the summary is. */
internal fun contextRingLabel(used: Long, max: Long, toCompaction: Long?, repliesLeft: Int? = null): String {
    val pct = if (max > 0) (used * 100 / max).coerceIn(0, 100) else 0
    val base = "Chat memory ${used / 1000}k of ${max / 1000}k tokens, $pct percent"
    return when {
        toCompaction == null -> base
        toCompaction > 0L && repliesLeft != null -> "$base, about $repliesLeft replies before the summary"
        toCompaction > 0L -> "$base, ${(toCompaction + 999) / 1000}k until the summary"
        else -> "$base, the next reply starts a summary"
    }
}
