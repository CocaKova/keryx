package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Find in chat (2.16): a field pinned in the instrument rail, on the flight plan's floor (the
 * contrast test measures that floor), with "3 of 12" and older / newer. It searches what is
 * loaded; scrolling further back loads more for it to find.
 */
@Composable
fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    index: Int,
    total: Int,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = FlightPlanFloor.ALPHA))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Find in chat", fontSize = KeryxType.body) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onOlder() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (query.isBlank()) "" else chat.keryx.core.model.FindInChat.label(index, total),
            fontSize = KeryxType.caption,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onOlder, enabled = total > 1 || (total == 1 && index != 0)) { Text("▲", fontSize = KeryxType.body) }
        TextButton(onClick = onNewer, enabled = total > 1) { Text("▼", fontSize = KeryxType.body) }
        TextButton(onClick = onClose) { Text("Done", fontSize = KeryxType.caption) }
    }
}
