package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.BlockingKind
import chat.keryx.core.model.BlockingRequest

/**
 * A pending tool approval, surfaced above the composer: the agent is stopped mid-turn
 * waiting for a human verdict, so this is the loudest thing on screen — a warn border,
 * the (pre-redacted) command in a mono box, one button per gateway-offered choice.
 * Deny reads destructive; everything else stays quiet.
 *
 * Merge dowry (absorption plan §5): it exists because the direct wire carries the event,
 * not because The Gate came back — no movement is built around this card.
 */
@Composable
fun ApprovalCard(
    approval: ApprovalRequest,
    onChoice: (String) -> Unit,
) {
    val warn = KeryxStatus.warn
    val haptics = LocalKeryxHaptics.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(warn.copy(alpha = 0.07f))
            .border(1.dp, warn.copy(alpha = 0.45f), RoundedCornerShape(KeryxRadius.chip))
            .padding(12.dp),
    ) {
        KeryxSectionHeader("Approval needed", color = warn)
        if (approval.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                approval.description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (approval.command.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                approval.command,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row {
            approval.choices.forEach { choice ->
                val (label, color) = when (choice) {
                    "once" -> "Allow once" to MaterialTheme.colorScheme.onSurface
                    "session" -> "Session" to MaterialTheme.colorScheme.onSurface
                    "always" -> "Always" to MaterialTheme.colorScheme.onSurface
                    "deny" -> "Deny" to MaterialTheme.colorScheme.error
                    else -> choice to MaterialTheme.colorScheme.onSurface
                }
                Text(
                    label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .clickable { haptics.commit(); onChoice(choice) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/**
 * The agent has stopped mid-turn and is waiting on a person: a clarify question, the host's
 * sudo password, or a credential a skill wants stored. Sibling of [ApprovalCard] and just as
 * loud, because ignoring it doesn't cancel anything — it just runs the gateway's clock down.
 *
 * The three kinds differ only in shape. Clarify offers the gateway's choices as taps plus a
 * free-text field; sudo and secret are single masked fields whose value is sent and then
 * dropped — never logged, never toasted, never kept in a draft.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockingRequestCard(
    request: BlockingRequest,
    onAnswer: (String) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = LocalKeryxHaptics.current
    // Keyed on the request so a second question never inherits the first one's typing.
    var typed by remember(request.requestId) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.40f), RoundedCornerShape(KeryxRadius.chip))
            .padding(12.dp),
    ) {
        KeryxSectionHeader(
            when (request.kind) {
                BlockingKind.CLARIFY -> "Hermes is asking"
                BlockingKind.SUDO -> "Sudo password needed"
                BlockingKind.SECRET -> "Credential needed"
            },
            color = accent,
        )
        if (request.prompt.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(request.prompt, fontSize = 13.sp, lineHeight = 18.sp, color = onSurface)
        }
        if (request.envVar.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                // Say where it lands: this is stored on the gateway, not just used once.
                "Stored on the gateway as ${request.envVar}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = onSurface.copy(alpha = 0.6f),
            )
        }

        if (request.kind == BlockingKind.CLARIFY && request.choices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            // Wrapped rather than a Row: gateway choices are whole phrases, not chips.
            FlowRow {
                request.choices.forEach { choice ->
                    Text(
                        choice,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface,
                        modifier = Modifier
                            .padding(end = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, onSurface.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .clickable { haptics.commit(); onAnswer(choice) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            if (request.multiSelect) {
                Text(
                    "Several answers are allowed — type them comma-separated below.",
                    fontSize = 11.sp,
                    color = onSurface.copy(alpha = 0.55f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp),
            shape = RoundedCornerShape(KeryxRadius.field),
            placeholder = {
                Text(
                    when (request.kind) {
                        BlockingKind.CLARIFY -> "Your answer"
                        BlockingKind.SUDO -> "Password"
                        BlockingKind.SECRET -> "Value"
                    },
                    fontSize = 13.sp,
                )
            },
            visualTransformation =
                if (request.kind.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (request.kind.isSecret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Send,
                // A password field must never feed the keyboard's learning dictionary.
                autoCorrectEnabled = !request.kind.isSecret,
            ),
            keyboardActions = KeyboardActions(onSend = { onAnswer(typed) }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "Send",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .clickable(enabled = typed.isNotBlank()) { haptics.commit(); onAnswer(typed) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // Blank IS the answer the gateway reads as "skipped" — it releases the tool
                // with an empty result rather than leaving the turn parked on the timeout.
                "Skip",
                fontSize = 12.5.sp,
                color = onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, onSurface.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .clickable { onAnswer("") }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
