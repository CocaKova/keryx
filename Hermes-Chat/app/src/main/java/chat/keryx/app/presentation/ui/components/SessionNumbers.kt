package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.keryx.core.model.SessionMeta
import chat.keryx.core.model.SessionUsage

/**
 * The session's run numbers under the window breakdown (2.16): "prefix cache 94% · 41 tok/s avg ·
 * 2 compactions · 1.2 s/call", then the totals. Only what the gateway actually said — a number it
 * has no data for is left out, never shown as zero. Draws nothing when nothing is known.
 */
@Composable
internal fun SessionNumbers(meta: SessionMeta?, modifier: Modifier = Modifier) {
    val head = meta?.let(SessionUsage::headline)
    val totals = meta?.let(SessionUsage::ledger)
    if (head == null && totals == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "This session",
            fontSize = KeryxType.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (head != null) Text(
            head,
            fontSize = KeryxType.body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (totals != null) Text(
            totals,
            fontSize = KeryxType.caption,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
