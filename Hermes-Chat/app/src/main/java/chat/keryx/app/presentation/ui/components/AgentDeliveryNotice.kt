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
import androidx.compose.ui.draw.alpha
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp).alpha(0.85f),
    ) {
        Text(
            chat.keryx.core.model.Heralds.SIGIL,
            fontSize = 11.sp,
            color = mark.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "relayed · ${delivery.sender}",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = mark.copy(alpha = 0.85f),
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
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(0.8f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            Text(
                chat.keryx.core.model.Heralds.SIGIL,
                fontSize = 12.sp,
                color = mark.copy(alpha = 0.85f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (pending) "Messaging $target…" else "Messaged $target",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = mark.copy(alpha = 0.85f),
            )
        }
        if (!pending && reply.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(KeryxRadius.chip))
                    .clickable { open = !open }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
            ) {
                Text(
                    chat.keryx.core.model.Heralds.SIGIL,
                    fontSize = 12.sp,
                    color = mark.copy(alpha = 0.85f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Message from $target",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = mark.copy(alpha = 0.85f),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (open) "▾" else "▸", fontSize = 9.5.sp, color = quiet.copy(alpha = 0.55f))
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
