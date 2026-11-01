package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.LinkHealth
import chat.keryx.app.transport.direct.McpServer

// The agent page (makeover): who the agent is up top, what it is doing in three numbers, then
// every room of it in labelled cards with a count each — the Hermes Mobile bot page, in Keryx's
// own voice. The landing is a place you read at a glance; the details are one tap down.

/** The agent's own name: the SOUL's first heading ("# SILAS"), else the profile, else the gateway. */
@Composable
internal fun agentDisplayName(viewModel: ChatViewModel): String {
    val soul by viewModel.agent.soul.collectAsState()
    val profile by viewModel.agent.profile.collectAsState()
    val fleet by viewModel.doors.fleet.collectAsState()
    return soulHeading(soul.data?.content)
        ?: profile?.takeIf { it.isNotBlank() && it != "default" }?.replaceFirstChar { it.uppercase() }
        ?: fleet.active?.name?.takeIf { it.isNotBlank() }
        ?: "Your agent"
}

internal fun soulHeading(content: String?): String? =
    content?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 }

/** The SOUL's first line of prose — the Soul row's subtitle. */
internal fun soulGist(content: String?): String? =
    content?.lineSequence()?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("---") }
        ?.take(90)

@Composable
internal fun AgentHero(viewModel: ChatViewModel, health: LinkHealth) {
    val petInfo by viewModel.pet.petInfo.collectAsState()
    val caps by viewModel.hub.reasoningCaps.collectAsState()
    val panel by viewModel.hub.health.collectAsState()
    val name = agentDisplayName(viewModel)
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val reduced by rememberReducedMotion()

    // Arrival: the tile lands with a little give — the one bounce on the page, so it reads as
    // someone arriving rather than a screen loading.
    val land = remember { Animatable(if (reduced) 1f else 0.82f) }
    LaunchedEffect(Unit) { land.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 420f)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.graphicsLayer {
            scaleX = land.value; scaleY = land.value; alpha = (land.value - 0.6f) / 0.4f
        }) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), accent2.copy(alpha = 0.75f))))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp)),
            ) {
                val pet = petInfo
                if (pet != null) {
                    PetSprite(
                        info = pet,
                        pose = if (panel.data?.busy == true) PetPose.RUN else PetPose.IDLE,
                        running = true,
                        modifier = Modifier.size(width = 60.dp, height = 65.dp),
                    )
                } else {
                    Text(
                        name.take(1).uppercase(),
                        fontFamily = chat.keryx.app.theme.CinzelFamily,
                        fontSize = KeryxType.hero,
                        color = contrastColorFor(accent),
                    )
                }
            }
            // The status dot rides the tile's corner, like a presence light.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                KeryxBreathingDot(
                    color = linkHealthColor(health),
                    alive = health == LinkHealth.LIVE || health == LinkHealth.OK,
                    size = 11.dp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            fontFamily = chat.keryx.app.theme.CinzelFamily,
            fontSize = KeryxType.display,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        val line = listOfNotNull(
            caps?.model?.takeIf { it.isNotBlank() },
            panel.data?.version?.takeIf { it.isNotBlank() }?.let { "hermes $it" },
        ).joinToString(" · ").ifBlank { linkHealthLabel(health) }
        Text(
            line,
            fontSize = KeryxType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
    }
}

/** Three numbers: how full the context is, what is running, what is scheduled. */
@Composable
internal fun AgentStatStrip(viewModel: ChatViewModel, open: (String) -> Unit) {
    val footer by viewModel.runtimeFooter.collectAsState()
    val ring by viewModel.contextUsage.collectAsState()
    val panel by viewModel.hub.health.collectAsState()
    val jobs by viewModel.hub.jobs.collectAsState()
    // The last reply's footer when there is one, else the open chat's ring reading — measured
    // the way the ring measures it, toward the summary point when the gateway names one.
    val pct = footer?.contextPct ?: ring?.let { u ->
        val to = u.compactAt.takeIf { it in 1..u.max } ?: u.max
        if (to > 0L) (u.used * 100 / to).toInt().coerceIn(0, 100) else null
    }
    val turns = panel.data?.activeAgents ?: 0
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        StatTile(
            value = pct?.let { "$it%" } ?: "—",
            label = "context",
            tint = when {
                pct == null -> null
                pct >= 80 -> KeryxStatus.bad
                pct >= 50 -> KeryxStatus.warn
                else -> KeryxStatus.good
            },
            fill = pct?.let { it.coerceIn(0, 100) / 100f },
            onClick = { open("health") },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = if (turns > 0) turns.toString() else "idle",
            label = if (turns == 1) "turn running" else if (turns > 1) "turns running" else "right now",
            tint = if (turns > 0) MaterialTheme.colorScheme.primary else null,
            onClick = { open("sessions") },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = jobs.data?.size?.toString() ?: "—",
            label = "scheduled",
            tint = null,
            onClick = { open("jobs") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    tint: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Float? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KeryxRadius.sheet)
    Column(
        modifier = modifier
            .keryxPressScale(source)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            value,
            fontSize = KeryxType.display,
            fontWeight = FontWeight.SemiBold,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(label, fontSize = KeryxType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (fill != null) {
            Spacer(Modifier.height(8.dp))
            val animated by androidx.compose.animation.core.animateFloatAsState(fill, KeryxMotion.settle, label = "statFill")
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(animated).height(4.dp).clip(CircleShape).background(tint ?: MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/** The slash commands people reach for, as chips — one tap sends them into the open chat. */
@Composable
internal fun AgentQuickActions(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val currentRoom by viewModel.currentRoom.collectAsState()
    val haveRoom = currentRoom != null
    val actions = listOf(
        "/new" to "Fresh session",
        "/compress" to "Compress",
        "/handoff" to "Handoff",
        "/status" to "Status",
    )
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        KeryxSectionHeader("In this chat", modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(actions, key = { it.first }) { (cmd, title) ->
                val source = remember { MutableInteractionSource() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .keryxPressScale(source)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (haveRoom) 0.14f else 0.06f))
                        .clickable(enabled = haveRoom, interactionSource = source, indication = androidx.compose.material3.ripple()) {
                            viewModel.composer.recordCommandUse(cmd)
                            viewModel.sendMessage(cmd)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        title,
                        fontSize = KeryxType.body,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (haveRoom) 1f else 0.45f),
                    )
                }
            }
        }
        if (!haveRoom) Text(
            viewModel.lexicon.openOneFirst,
            fontSize = KeryxType.micro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
        )
    }
}

// --- MCP ---------------------------------------------------------------------------------------

@Composable
internal fun McpTab(viewModel: ChatViewModel) {
    val panel by viewModel.agent.mcp.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        val servers = panel.data
        when {
            servers == null -> if (panel.error == null) PanelLoading()
            servers.isEmpty() -> Text(
                "No MCP servers yet. Add one on the gateway with `hermes mcp add`, and it shows up here.",
                fontSize = KeryxType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                item(key = "mcp-group") {
                    val on = servers.count { it.enabled }
                    KeryxHubGroup("Servers · $on of ${servers.size} on", modifier = Modifier.animateContentSize(KeryxMotion.settleSize)) {
                        servers.forEachIndexed { i, s ->
                            if (i > 0) KeryxHubDivider()
                            McpRow(s) { viewModel.agent.setMcpEnabled(s.name, it) }
                        }
                    }
                }
                item(key = "mcp-note") {
                    Text(
                        "A switch takes effect from the agent's next session. Servers stay in the gateway's config either way.",
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun McpRow(s: McpServer, onToggle: (Boolean) -> Unit) {
    val dim by androidx.compose.animation.core.animateFloatAsState(
        if (s.enabled) 1f else 0.55f, KeryxMotion.glide, label = "mcpDim",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).graphicsLayer { alpha = dim }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name, fontSize = KeryxType.title, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TransportBadge(s.transport)
            }
            if (s.target.isNotBlank()) Text(
                middleEllipsis(s.target, 44),
                fontSize = KeryxType.caption,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
            val extras = listOfNotNull(
                s.envCount.takeIf { it > 0 }?.let { if (it == 1) "1 env var" else "$it env vars" },
                s.auth?.let { "auth: $it" },
                s.plugin?.let { "from plugin $it" },
            )
            if (extras.isNotEmpty()) Text(
                extras.joinToString(" · "),
                fontSize = KeryxType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = s.enabled, onCheckedChange = onToggle, enabled = s.plugin == null)
    }
}

@Composable
private fun TransportBadge(transport: String) {
    val color = when (transport) {
        "http" -> MaterialTheme.colorScheme.primary
        "stdio" -> KeryxStatus.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        transport.uppercase(),
        fontSize = KeryxType.micro,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = color,
        modifier = Modifier
            .border(0.75.dp, color.copy(alpha = 0.6f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Keep the start and the end of a long path — the end is where the name is. */
internal fun middleEllipsis(s: String, max: Int): String {
    if (s.length <= max) return s
    val head = max / 3
    val tail = max - head - 1
    return s.take(head) + "…" + s.takeLast(tail)
}

// --- Soul --------------------------------------------------------------------------------------

@Composable
internal fun SoulTab(viewModel: ChatViewModel) {
    val panel by viewModel.agent.soul.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val soul = panel.data

    Column(Modifier.fillMaxSize()) {
        PanelErrorLine(panel.error)
        when {
            soul == null -> if (panel.error == null) PanelLoading()
            editing -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = KeryxType.body, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(KeryxRadius.sheet),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                ) {
                    Text(
                        "${draft.length} chars · new sessions start with it",
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editing = false }, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            saving = true
                            viewModel.agent.saveSoul(draft) { ok -> saving = false; if (ok) editing = false }
                        },
                        enabled = !saving && draft != soul.content,
                    ) { Text(if (saving) "Saving…" else "Save") }
                }
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text(
                        if (soul.exists) "SOUL.md · profile ${soul.profile} · ${soul.content.length} chars"
                        else "No SOUL.md on profile ${soul.profile} — Hermes uses its stock persona.",
                        fontSize = KeryxType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    OutlinedButton(onClick = { draft = soul.content; editing = true }) {
                        Text(if (soul.exists) "Edit" else "Write one", fontSize = KeryxType.body)
                    }
                }
                if (soul.exists) {
                    val shape = RoundedCornerShape(KeryxRadius.sheet)
                    Text(
                        soul.content,
                        fontSize = KeryxType.body,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), shape)
                            .padding(16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// --- The drawer's door -------------------------------------------------------------------------

/**
 * The way in (makeover): the drawer's first card is the agent itself — its tile, its name, what
 * it is running and whether the line is up — and a tap opens its page. Until now the page sat
 * behind the model strip at the drawer's foot, a door nobody knew was one.
 */
@Composable
internal fun AgentDoorCard(viewModel: ChatViewModel, onOpen: () -> Unit) {
    val health by viewModel.linkHealth.collectAsState()
    val caps by viewModel.hub.reasoningCaps.collectAsState()
    val soul by viewModel.agent.soul.collectAsState()
    // The name lives in the SOUL; one read the first time the drawer shows this card.
    LaunchedEffect(Unit) { if (soul.data == null) viewModel.agent.refreshSoul() }
    val name = agentDisplayName(viewModel)
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KeryxRadius.sheet)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .keryxPressScale(source)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), shape)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), accent2.copy(alpha = 0.75f)))),
            ) {
                Text(
                    name.take(1).uppercase(),
                    fontFamily = chat.keryx.app.theme.CinzelFamily,
                    fontSize = KeryxType.display,
                    color = contrastColorFor(accent),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                KeryxBreathingDot(
                    color = linkHealthColor(health),
                    alive = health == LinkHealth.LIVE || health == LinkHealth.OK,
                    size = 8.dp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, fontSize = KeryxType.title, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(caps?.model?.takeIf { it.isNotBlank() }, linkHealthLabel(health)).joinToString(" · "),
                fontSize = KeryxType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Icon(
            KeryxGlyphs.ChevronRight, contentDescription = "Open the agent's page",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}
