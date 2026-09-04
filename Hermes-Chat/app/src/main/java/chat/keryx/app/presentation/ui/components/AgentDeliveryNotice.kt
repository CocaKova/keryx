package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.AgentDelivery

/**
 * An inter-agent delivery as a quiet attribution line (2.3 §2): the relay is an EVENT in this
 * room, and the account it arrived through is a courier, not the author.
 *
 * Divergence from Talaria, which hides the body behind a tap expander because a bot transcript has
 * no bubbles: Keryx is a chat client, so the delivered words keep a real bubble and this line sits
 * above it saying who actually said them. (The repository has already cut the `Message from …:`
 * prefix off the content, so the two never say the same thing twice.)
 *
 * When the relayed sender is a herald Keryx knows, the line wears that herald's light — a relayed
 * message and a direct one from the same agent read as the same life.
 */
@Composable
fun AgentDeliveryNotice(
    delivery: AgentDelivery,
    modifier: Modifier = Modifier,
    /** The relayed sender's light, when it is a herald Keryx knows. */
    accent: Color? = null,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = accent ?: quiet
    // ⚠️ ALPHA ON ALPHA. A layer alpha of 0.85 wrapping text already set at 0.85 is 0.7225 —
    // and the paper herald palette is tuned to clear AA at FULL strength, so 10.5sp of a
    // herald's own light landed at 2.79:1 on parchment (3.01:1 for the plain quiet ink). Two
    // separate decisions to "make it quiet", each reasonable alone, multiplying. One of them
    // now, and it is the one at full strength: the line is small, mono-weight and short — it is
    // already quiet without being faint.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp),
    ) {
        Text(
            chat.keryx.core.model.Heralds.SIGIL,
            fontSize = 11.sp,
            color = mark,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "relayed · ${delivery.sender}",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = mark,
        )
    }
}

/**
 * The SENT half of the same exchange (2.3 §2): this agent messaged another one. Wherever a
 * delivery command would have drawn a terminal row, it draws this instead — because "ran a shell
 * command" is a true description of the mechanism and a false description of the event.
 *
 * "Messaging X…" while the call is out, "Messaged X" once it lands, and when the run carried a
 * reply back, a second line naming it with the body behind a tap. Deliberately the same glyph,
 * weight and alpha as [AgentDeliveryNotice] — the two halves of one exchange should not look like
 * two different features.
 */
@Composable
fun AgentDeliverySentNotice(
    target: String,
    pending: Boolean,
    reply: String,
    modifier: Modifier = Modifier,
    stateKey: String = "",
    accent: Color? = null,
) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = accent ?: quiet
    var open by rememberSaveable(stateKey) { mutableStateOf(false) }
    // Same stacking as [AgentDeliveryNotice], one step worse: 0.8 × 0.85 = 0.68, and the
    // disclosure caret below compounded to 0.44 — a 1.86:1 affordance marker on parchment.
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            Text(
                chat.keryx.core.model.Heralds.SIGIL,
                fontSize = 12.sp,
                color = mark,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (pending) "Messaging $target…" else "Messaged $target",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = mark,
            )
        }
        if (!pending && reply.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 4dp round a 12sp line is a ~22dp target for the one thing here you can open.
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .clickable { open = !open }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    chat.keryx.core.model.Heralds.SIGIL,
                    fontSize = 12.sp,
                    color = mark,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Message from $target",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = mark,
                )
                Spacer(Modifier.width(6.dp))
                // The caret is the affordance — the one glyph that says this row opens.
                Text(if (open) "▾" else "▸", fontSize = 9.5.sp, color = quiet)
            }
            AnimatedVisibility(
                visible = open,
                enter = keryxReveal(),
                exit = keryxConceal(),
            ) {
                Text(
                    reply,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}
