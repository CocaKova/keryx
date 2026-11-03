package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.keryx.app.R
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRoster
import chat.keryx.core.model.GroupRoom

/**
 * The bot lights (3.0): every bot across the top of the drawer, each in its own light, so the
 * next chat is one tap from wherever you are, never a trip into a place and back out of it.
 * Tap a light for that bot's forever-chat; long-press it to manage the bot in the Bots place.
 * A working bot's ring breathes, the bot whose chat is on screen wears a full ring, and the
 * group chats follow the bots as lights of their members' colours. The whole roster stays the
 * Bots door at the drawer's foot. Direct door only: bots are profiles, and only the direct door sees them.
 *
 * The order is the roster's own (working first, then most recent), so the row and the Bots
 * place never disagree about who is where.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotLights(
    viewModel: ChatViewModel,
    /** The drawer can be seen: the breathing only runs then (the drawer composes while closed). */
    visible: Boolean,
    onOpenBot: (BotProfile) -> Unit,
    onManageBot: (BotProfile) -> Unit,
    onOpenGroup: (GroupRoom) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bots = viewModel.bots
    val panel by bots.roster.collectAsState()
    val busy by bots.busyNames.collectAsState()
    val groupsPanel by viewModel.groups.rooms.collectAsState()
    val current by viewModel.currentRoom.collectAsState()
    // Fresh whenever the drawer comes into view: the roster's own slow pulse may be minutes old.
    LaunchedEffect(visible) {
        if (visible) {
            bots.refresh()
            viewModel.groups.refresh()
        }
    }
    val all = panel.data?.bots.orEmpty()
    if (all.isEmpty()) return
    val now = remember(all, busy) { System.currentTimeMillis() }
    val ordered = remember(all, busy, now) { BotRoster.order(all, now, busy) }
    val groups = groupsPanel.data.orEmpty().map { it.room }
    val here = bots.botForSession(current?.id)?.name

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(ordered, key = { "bot:" + it.name }) { bot ->
            val manage = stringResource(R.string.chat_bot_light_manage, bot.label)
            LightCell(
                label = bot.label,
                onClick = { onOpenBot(bot) },
                onLongClick = { onManageBot(bot) },
                longClickLabel = manage,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BotFace(bot = bot, viewModel = viewModel, size = 40.dp, working = visible && bot.name in busy)
                    if (bot.name == here) {
                        val light = botLightFor(bot.name, bot.label, bot.isDefault)
                        Box(Modifier.size(46.dp).border(2.dp, light.accent, CircleShape))
                    }
                }
            }
        }
        items(groups, key = { "group:" + it.roomId }) { room ->
            LightCell(label = room.name, onClick = { onOpenGroup(room) }) { GroupLight(room) }
        }
    }
}

/** One light and its name under it: a 48dp-wide tap target at least (3.0 accessibility pass). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LightCell(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    face: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            )
            .padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { face() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = KeryxType.micro,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

/** A group chat's light: its first two members' colours as one disc, their sigils on it. */
@Composable
private fun GroupLight(room: GroupRoom) {
    val members = room.members.take(3)
    val lights = members.map { botLightFor(it.profile, it.label, it.profile == "default") }
    val a = lights.getOrNull(0)?.accent ?: MaterialTheme.colorScheme.primary
    val b = lights.getOrNull(1)?.accent ?: a
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(a.copy(alpha = 0.30f), b.copy(alpha = 0.30f))))
            .border(1.dp, a.copy(alpha = 0.55f), CircleShape),
    ) {
        Row { lights.forEach { HeraldSigil(it, fontSize = KeryxType.micro) } }
    }
}
