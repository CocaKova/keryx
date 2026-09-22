package chat.keryx.app.presentation.tapin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ui.components.KeryxGlyphs
import chat.keryx.app.presentation.ui.components.contrastColorFor

/**
 * The one-line steer composer (2.13), for the places the chat composer is not: the foot of
 * Tap-In, and the foot of a helper's sheet.
 *
 * Same grammar as the chat composer's busy state so nothing new has to be learned: text →
 * steer (the wheel), empty → stop (the square), and, where the caller offers it, hold on
 * steer → queue (the stack). The chat composer itself is not reused because it carries the
 * attachment tray, the slash palette and the model pill — a steer bar wants none of those,
 * and a running turn inside a full-screen view wants a bar that reads at a glance.
 *
 * @param placeholder the hint that teaches the bar's verb, e.g. "Type to steer this turn".
 * @param onSteer called with the trimmed text; the field clears on the call.
 * @param onStop the square. `null` hides the stop state entirely (the bar then only steers).
 * @param onQueue long-press on steer, when the caller has a queue; `null` = no long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SteerBar(
    placeholder: String,
    onSteer: (String) -> Unit,
    onStop: (() -> Unit)?,
    onQueue: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val armed = text.isNotBlank()
    val stopping = !armed && onStop != null
    val ink = MaterialTheme.colorScheme.onSurface
    val ground = if (armed || stopping) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f).compositeOver(MaterialTheme.colorScheme.surfaceVariant)
    val glyphInk = if (armed || stopping) Color.White else contrastColorFor(ground)
    val commit = {
        val t = text.trim()
        if (t.isNotEmpty()) { onSteer(t); text = "" }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ink.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, fontSize = 14.sp, color = ink.copy(alpha = 0.45f), maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 14.sp, color = ink, lineHeight = 19.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { commit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(10.dp))
        val glyph = when {
            armed -> KeryxGlyphs.Steer
            stopping -> KeryxGlyphs.StopSquare
            else -> KeryxGlyphs.Steer
        }
        val label = when {
            armed -> "Steer"
            stopping -> "Stop"
            else -> "Type to steer"
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(ground)
                .combinedClickable(
                    onClick = {
                        when {
                            armed -> commit()
                            stopping -> onStop?.invoke()
                        }
                    },
                    onLongClick = if (armed && onQueue != null) ({
                        val t = text.trim()
                        if (t.isNotEmpty()) { onQueue(t); text = "" }
                    }) else null,
                ),
        ) {
            Icon(glyph, contentDescription = label, tint = glyphInk, modifier = Modifier.size(22.dp))
        }
    }
}
