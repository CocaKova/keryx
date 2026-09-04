package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.Alignment
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
                ApprovalButton(label, color) { haptics.commit(); onChoice(choice) }
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
            // The only explicit accent left on a section header in the app; the default is
            // already the ink form, so this was the one that stayed at 3.26:1 on paper.
            color = keryxAccentInk(accent),
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
                    ApprovalButton(
                        label = choice,
                        color = onSurface,
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    ) { haptics.commit(); onAnswer(choice) }
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
            // Send was painted live whatever the field held — full accent, full border — while
            // `clickable(enabled = typed.isNotBlank())` quietly swallowed the tap. A button that
            // looks pressable and does nothing is worse than no button: it reads as the app
            // being broken. It now dims with its own state.
            ApprovalButton(
                label = "Send",
                color = keryxAccentInk(accent),
                enabled = typed.isNotBlank(),
            ) { haptics.commit(); onAnswer(typed) }
            Spacer(Modifier.width(8.dp))
            // Blank IS the answer the gateway reads as "skipped" — it releases the tool
            // with an empty result rather than leaving the turn parked on the timeout.
            ApprovalButton(label = "Skip", color = onSurface, weight = FontWeight.Normal) {
                onAnswer("")
            }
        }
    }
}

/**
 * The card's one button.
 *
 * Four families of these lived inline — approval choices, clarify choices, Send, Skip — each
 * with its own copy of a 6dp corner and `padding(vertical = 7.dp)`, which round a 12.5sp cap is
 * a **28.6dp** target. This card is the loudest thing on the screen precisely because the agent
 * has stopped and is waiting on a person, and "Always" sat 8dp from "Deny" at 28dp tall. One
 * button, 44dp, on the [KeryxRadius.chip] corner the rest of the app's chips use, with the
 * press-scale 2.8.1 gave every other pressable surface.
 */
@Composable
private fun ApprovalButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    weight: FontWeight = FontWeight.SemiBold,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(KeryxRadius.chip)
    val ink = if (enabled) color else color.copy(alpha = 0.38f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .border(1.dp, ink.copy(alpha = if (enabled) 0.35f else 0.18f), shape)
            .keryxPressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = weight, color = ink)
    }
}
