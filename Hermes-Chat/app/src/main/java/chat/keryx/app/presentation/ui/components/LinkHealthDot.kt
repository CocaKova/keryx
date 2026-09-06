package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Hermes Link health, whispered: a tiny dot that breathes while tokens flow, dims when idle,
 * warms red when the gateway is unreachable. Lived in HermesApp until 2.10; the drawer's status
 * strip wears the same dot now, so it is one composable in one place.
 */
@Composable
internal fun LinkHealthDot(
    health: chat.keryx.app.presentation.LinkHealth,
    onClick: (() -> Unit)? = null,
    /** The dot alone, no tap box — for a row that is itself the control (the drawer's status
     *  strip). Off-link it still draws, idle-grey, because the strip has words beside it. */
    compact: Boolean = false,
) {
    if (health == chat.keryx.app.presentation.LinkHealth.OFF && !compact) return
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    // Stilled, LIVE holds the top of its breath — full-strength accent, still a step clear of OK's
    // 75% — so "tokens are flowing" survives Battery Saver as a state you can read at a glance.
    val reduced by chat.keryx.app.presentation.ui.components.rememberReducedMotion()
    val alpha = if (health == chat.keryx.app.presentation.LinkHealth.LIVE && !reduced) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "linkBreath")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "linkBreathAlpha",
        ).value
    } else 1f
    val color = when (health) {
        // Tokens flowing: the dot breathes BETWEEN the two accents, not just in alpha.
        chat.keryx.app.presentation.LinkHealth.LIVE ->
            androidx.compose.ui.graphics.lerp(accent2, accent, alpha).copy(alpha = 0.5f + 0.5f * alpha)
        chat.keryx.app.presentation.LinkHealth.OK -> accent.copy(alpha = 0.75f)
        chat.keryx.app.presentation.LinkHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> chat.keryx.app.presentation.ui.components.KeryxStatus.bad.copy(alpha = 0.85f)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = when (health) {
        chat.keryx.app.presentation.LinkHealth.LIVE -> "Hermes Link: streaming live"
        chat.keryx.app.presentation.LinkHealth.OK -> "Hermes Link: connected"
        chat.keryx.app.presentation.LinkHealth.UNKNOWN -> "Hermes Link: not tested yet"
        else -> "Hermes Link: unreachable — replies fall back to Matrix sync"
    }
    // The dot is 7dp because it is a whisper. The TAP is not: the click used to sit on the dot
    // itself — a 7dp target, under 3mm, in a top bar whose neighbours are 48dp icon buttons, so
    // opening the Gateway from here was a coin toss. The target is the 44dp box; the dot inside
    // it stays exactly the size it was.
    if (compact) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (health == chat.keryx.app.presentation.LinkHealth.OFF)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    else color
                ),
        )
        return
    }
    Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                // The dot draws no glyph and carries no text, so without this the link's whole
                // state was invisible to TalkBack — a control that announced nothing at all.
                onClickLabel = label,
                role = androidx.compose.ui.semantics.Role.Button,
            ) {
                onClick?.invoke()
                    ?: android.widget.Toast.makeText(context, label, android.widget.Toast.LENGTH_SHORT).show()
            },
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
