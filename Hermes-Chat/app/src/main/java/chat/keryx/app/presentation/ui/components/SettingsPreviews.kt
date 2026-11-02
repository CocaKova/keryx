package chat.keryx.app.presentation.ui.components

import chat.keryx.app.R

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
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

/**
 * The room transition, playing (makeover): two little rooms crossing back and forth in the
 * chosen style at the chosen speed. Not a lookalike: the four drawn styles go through the same
 * [roomCrossing] the transcript wears (the room on screen is photographed, as the drawer does
 * before a real switch), and Dissolve plays ChatScreen's own blur-and-rise. Under Battery Saver
 * it holds still, as the chat does.
 */
@Composable
internal fun RoomTransitionPreview(style: RoomTransition, speed: RoomTransitionSpeed, modifier: Modifier = Modifier) {
    val reduced by rememberReducedMotion()
    val crossing = rememberRoomCrossing()
    val dissolve = remember { androidx.compose.animation.core.Animatable(1f) }
    var room by remember { mutableIntStateOf(0) }
    LaunchedEffect(crossing, style, speed, reduced) {
        crossing.pending = false
        crossing.progress.snapTo(1f)
        crossing.release()
        dissolve.snapTo(1f)
        if (reduced) return@LaunchedEffect
        val ms = speed.scaled(durationOf(style))
        while (true) {
            kotlinx.coroutines.delay(PREVIEW_REST_MS)
            if (style == RoomTransition.DISSOLVE) {
                room = 1 - room
                dissolve.snapTo(0f)
                dissolve.animateTo(1f, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                continue
            }
            if (crossing.live.size == IntSize.Zero) continue
            val photo = runCatching { crossing.live.toImageBitmap() }.getOrNull() ?: continue
            crossing.oldFrame = photo
            crossing.oldFrameKey = room.toString()
            crossing.style = style
            crossing.origin = null
            // Drawn as progress 0 from the frame the new room composes in, never one frame of 1.
            crossing.pending = true
            room = 1 - room
            crossing.progress.snapTo(0f)
            crossing.pending = false
            crossing.progress.animateTo(1f, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
            crossing.release()
        }
    }
    val shape = RoundedCornerShape(KeryxRadius.sheet)
    val described = stringResource(R.string.mk_transition_preview, style.label, speed.label)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(shape)
            .background(duskBrush())
            .border(0.75.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f), shape)
            .semantics { contentDescription = described },
    ) {
        val stage = if (style == RoomTransition.DISSOLVE) {
            Modifier
                .graphicsLayer {
                    val d = dissolve.value
                    alpha = 0.12f + 0.88f * d
                    translationY = (1f - d) * 12.dp.toPx()
                    val sc = 0.985f + 0.015f * d
                    scaleX = sc; scaleY = sc
                }
                .let { if (dissolve.value < 1f) it.blur(((1f - dissolve.value) * 8f).dp) else it }
        } else {
            Modifier.roomCrossing(
                crossing, style,
                amber = MaterialTheme.colorScheme.primary,
                violet = MaterialTheme.colorScheme.tertiary,
            )
        }
        Box(Modifier.fillMaxWidth().height(132.dp).then(stage)) { MiniRoom(room) }
    }
}

/** How long each little room stays put between crossings. */
private const val PREVIEW_REST_MS = 1_100L

/** A room in miniature: its light, a title, three bubbles. The two rooms differ in every line,
 *  so the crossing has something to carry. */
@Composable
private fun MiniRoom(which: Int) {
    val light = if (which == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val ink = MaterialTheme.colorScheme.onSurface
    val lines = if (which == 0) listOf(false to 0.62f, true to 0.44f, false to 0.70f)
    else listOf(true to 0.52f, false to 0.66f, true to 0.34f)
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(light))
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier.fillMaxWidth(if (which == 0) 0.32f else 0.46f).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(ink.copy(alpha = 0.22f)),
            )
        }
        lines.forEach { (mine, w) ->
            Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(
                    Modifier.fillMaxWidth(w).height(17.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (mine) light.copy(alpha = 0.55f) else ink.copy(alpha = 0.10f)),
                )
            }
        }
    }
}
