package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

// Choices you can see (makeover): a bubble style is picked by looking at bubbles, a loading
// animation by watching it run. The Hermes Mobile chat-style page, in Keryx's own pieces — the
// previews draw with the real bubbleAppearance and the real spinners, so what you pick is what
// the chat shows.

/** The card every preview sits in: a ring when picked, a soft press either way. */
@Composable
private fun PreviewCard(
    picked: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KeryxRadius.sheet)
    val ring by androidx.compose.animation.animateColorAsState(
        if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
        label = "previewRing",
    )
    Column(
        modifier = modifier
            .keryxPressScale(source)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .border(if (picked) 2.dp else 0.75.dp, ring, shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(12.dp),
    ) {
        content()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = KeryxType.body,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, ring, CircleShape),
            ) {
                androidx.compose.animation.AnimatedVisibility(picked, enter = keryxPop(), exit = keryxVanish()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** Two cards to a row; a short last row keeps its cards the same width. */
@Composable
private fun <T> PreviewGrid(items: List<T>, columns: Int = 2, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MiniBubble(text: String, mine: Boolean, style: String) {
    val look = bubbleAppearance(isMine = mine, style = style)
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text,
            fontSize = KeryxType.micro,
            color = look.textColor,
            maxLines = 1,
            modifier = Modifier
                .widthIn(max = 130.dp)
                .clip(shape)
                .background(look.brush)
                .let { m ->
                    when {
                        look.edgeBrush != null -> m.border(1.dp, look.edgeBrush, shape)
                        look.border != null -> m.border(1.dp, look.border, shape)
                        else -> m
                    }
                }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

/** Bubble style, as four little conversations. */
@Composable
internal fun BubbleStylePicker(selected: String, onSelect: (String) -> Unit) {
    PreviewGrid(BubbleStyles.ALL) { style, mod ->
        PreviewCard(picked = selected == style, label = style, onClick = { onSelect(style) }, modifier = mod) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.heightIn(min = 70.dp)) {
                MiniBubble("All planned.", mine = false, style = style)
                MiniBubble("Sounds good!", mine = true, style = style)
                MiniBubble("On it.", mine = false, style = style)
            }
        }
    }
}

/** Loading animation, each one running in its own card. [options] are (stored name, label). */
@Composable
internal fun LoadingStylePicker(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    PreviewGrid(options) { (key, label), mod ->
        PreviewCard(picked = selected == key, label = label, onClick = { onSelect(key) }, modifier = mod) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                HermesThinkingAnimation(style = key, label = "")
            }
        }
    }
}
