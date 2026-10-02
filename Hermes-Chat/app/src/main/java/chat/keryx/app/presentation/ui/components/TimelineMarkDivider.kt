package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.TimelineMark

/**
 * A system row as a divider (2.16): "── model → qwen3.8-flash-next ──", drawn exactly like the
 * compaction divider beside it. These rows used to take a full agent bubble each, timestamped,
 * so a model switch read as the agent saying "model changed" — twice, and never which model.
 * When the row said more than its label (a skill's instructions, a cron job's brief, a
 * background process's output) the rule is a tap target and the text opens beneath it.
 */
@Composable
fun TimelineMarkDivider(mark: TimelineMark, stateKey: String) {
    var open by rememberSaveable(stateKey) { mutableStateOf(false) }
    val meta = MaterialTheme.colorScheme.onSurfaceVariant
    val detail = mark.detail
    // A skill body can run to many kilobytes; the fold shows the head of it, which is the part
    // that says what it is. The agent still has the whole thing.
    val shown = remember(detail) {
        detail?.let { if (it.length <= DETAIL_CAP) it else it.take(DETAIL_CAP).trimEnd() + "\n…" }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                // The whole band is the target when there is something to open: a one-line
                // micro label alone is too small to aim at.
                .heightIn(min = if (detail != null) 36.dp else 24.dp)
                .clip(RoundedCornerShape(KeryxRadius.chip))
                .then(
                    if (detail != null) Modifier.clickable(
                        onClickLabel = if (open) "Hide what it said" else "Read what it said",
                    ) { open = !open } else Modifier
                ),
        ) {
            val line = meta.copy(alpha = 0.18f)
            Box(Modifier.weight(1f).height(1.dp).background(line))
            Text(
                text = when {
                    detail == null -> mark.label
                    open -> mark.label + "  ▾"
                    else -> mark.label + "  ▸"
                },
                color = meta.copy(alpha = 0.75f),
                fontSize = KeryxType.micro,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Read as what it is, without the fold's triangle.
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .widthIn(max = 300.dp)
                    .semantics { contentDescription = "System: ${mark.label}" },
            )
            Box(Modifier.weight(1f).height(1.dp).background(line))
        }
        if (shown != null) {
            AnimatedVisibility(visible = open) {
                Text(
                    text = shown,
                    color = meta,
                    fontSize = KeryxType.caption,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(KeryxRadius.chip))
                        .background(meta.copy(alpha = 0.06f))
                        .padding(12.dp),
                )
            }
        }
    }
}

/** How much of a mark's text the fold renders — enough to know what it was. */
private const val DETAIL_CAP = 4_000
