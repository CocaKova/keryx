package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One run of a worker, as a card: glyph and role, a monospace meta line, then either the live
 * line breathing while it works or what it came back with once it lands. Tap-In's crew deck
 * (2.12) drew this first; Missions (2.14) draws a card's run history with the same card, so a
 * worker reads the same wherever it shows up.
 *
 * [activity] non-null = still running: the newest line it sent, behind a breathing dot that
 * only moves while [alive]. Null = landed: [summary] gets the room, in [summaryColor].
 */
@Composable
fun KeryxRunCard(
    glyph: String,
    title: String,
    ink: Color,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    breathing: Boolean = false,
    meta: List<String> = emptyList(),
    activity: String? = null,
    alive: Boolean = false,
    summary: String = "",
    summaryColor: Color = ink.copy(alpha = 0.78f),
    summaryLines: Int = 5,
    onOpen: (() -> Unit)? = null,
) {
    KeryxCard(modifier = modifier, onClick = onOpen, tint = tint, breathing = breathing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, fontSize = 16.sp, color = tint ?: ink.copy(alpha = 0.7f))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onOpen != null) Text("↗", fontSize = 11.sp, color = ink.copy(alpha = 0.45f))
        }
        Spacer(Modifier.height(6.dp))
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ink.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (activity != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxBreathingDot(color = tint ?: ink, alive = alive, size = 5.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    activity,
                    fontSize = 11.5.sp,
                    color = ink.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (summary.isNotBlank()) {
            Text(
                summary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = summaryColor,
                maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
