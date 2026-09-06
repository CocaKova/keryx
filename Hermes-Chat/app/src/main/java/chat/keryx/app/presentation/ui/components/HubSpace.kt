package chat.keryx.app.presentation.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.LinkHealth

/**
 * The Gateway: one place for the machine Keryx is pointed at.
 *
 * Until 2.5 this was a single "Agent Hub" carrying six tabs, and the tab's *index* was the thing
 * the code reasoned about. 2.5 made a panel a value ([HubPanel]) and split the six into two
 * doors — Gateway (status, controls, jobs) and Workshop (sessions, skills, tools) — because six
 * tabs in one scrollable row made you read all of them to answer either question.
 *
 * 2.10 puts them back under one door without bringing the row back. The landing is what the
 * machine is doing — status, brain, the last turn — and the rest are **spokes**: a row each,
 * with one line of what is behind it, opened one at a time with a way back. It is the shape
 * Settings already has, and the shape the Hermes Desktop gives its own management overlays
 * (a master list, one detail at a time). Nothing is read that was not asked for; nothing is a
 * tab you have to know exists.
 *
 * A panel is still a value: [HubPanel] carries its label, its glyph, its ONE definition of
 * "get current" (first visit, the poll and the header button all call exactly that) and its
 * body. The registry below is the only place the app says which spokes exist.
 */
class HubPanelScope(
    val viewModel: ChatViewModel,
    val health: LinkHealth,
    /** Leave the space entirely — a panel that hands you back to a room uses this. */
    val closeSpace: () -> Unit,
)

/**
 * One spoke of the Gateway.
 *
 * @param id stable across releases: it names the panel in saved state and is what a deep link
 *   would address. Never rename casually — the label is what you change to re-word the UI.
 * @param live whether the panel's data moves on its own (job runs, session activity) and should
 *   re-poll while visible. Panels that only change on operator action stay fetch-once.
 * @param refresh the panel's ONE definition of "get current".
 * @param subtitle the spoke row's second line, read off the delegate's current state — a count,
 *   a model name — so the landing says what is behind each door before you open it.
 */
data class HubPanel(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val live: Boolean = false,
    val refresh: (ChatViewModel) -> Unit,
    val subtitle: @Composable (ChatViewModel) -> String,
    val content: @Composable (HubPanelScope) -> Unit,
)

private const val HUB_POLL_MS = 10_000L
private const val LANDING = "status"

/** The spokes, in the order the landing lists them: changing the machine, then reading it. */
val GATEWAY_SPOKES: List<HubPanel> = listOf(
    HubPanel(
        id = "controls",
        label = "Controls",
        icon = KeryxGlyphs.Sliders,
        refresh = { vm -> vm.hub.refreshConfig(); vm.hub.refreshBrains(); vm.hub.refreshReasoningCaps() },
        subtitle = { vm ->
            val caps by vm.hub.reasoningCaps.collectAsState()
            caps?.model?.takeIf { it.isNotBlank() }?.let { "Brain · $it" } ?: "Brain, knobs, config"
        },
        content = { ControlsTab(it.viewModel) },
    ),
    HubPanel(
        id = "jobs",
        label = "Jobs",
        icon = KeryxGlyphs.Calendar,
        live = true,
        refresh = { vm -> vm.hub.refreshJobs() },
        subtitle = { vm ->
            val jobs by vm.hub.jobs.collectAsState()
            jobs.data?.let { "${it.size} scheduled" } ?: "Schedules the agent keeps"
        },
        content = { JobsTab(it.viewModel) },
    ),
    HubPanel(
        id = "sessions",
        label = "Sessions",
        icon = KeryxGlyphs.Stack,
        live = true,
        refresh = { vm -> vm.hub.refreshSessions() },
        subtitle = { vm ->
            val sessions by vm.hub.sessions.collectAsState()
            sessions.data?.let { "${it.size} recent" } ?: "Every transcript, resume or prune"
        },
        content = { SessionsTab(it.viewModel, it.closeSpace) },
    ),
    HubPanel(
        id = "skills",
        label = "Skills",
        icon = KeryxGlyphs.Star,
        refresh = { vm -> vm.hub.refreshSkills() },
        subtitle = { vm ->
            val skills by vm.hub.skills.collectAsState()
            skills.data?.let { "${it.size} learned" } ?: "What the agent knows how to do"
        },
        content = { SkillsTab(it.viewModel) },
    ),
    HubPanel(
        id = "tools",
        label = "Tools",
        icon = KeryxGlyphs.Plug,
        refresh = { vm -> vm.hub.refreshToolsets() },
        subtitle = { vm ->
            val toolsets by vm.hub.toolsets.collectAsState()
            toolsets.data?.let { t -> "${t.toolsets.size} toolsets" } ?: "What the agent can reach"
        },
        content = { ToolsTab(it.viewModel) },
    ),
)

@Composable
fun GatewaySpace(viewModel: ChatViewModel, health: LinkHealth, onDismiss: () -> Unit) {
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    // The open spoke, by id — survives recreation so a rotation mid-Jobs stays on Jobs.
    var spokeId by rememberSaveable { mutableStateOf<String?>(null) }
    val spoke = GATEWAY_SPOKES.firstOrNull { it.id == spokeId }
    BackHandler(enabled = spoke != null) { spokeId = null }

    // The landing's own refresh: health, models, caps — and one pull of each spoke's data, so
    // the rows can say what is behind them. Once per opening; the spokes re-pull on visit.
    val refreshLanding: (ChatViewModel) -> Unit = remember {
        { vm ->
            vm.hub.refreshHealth(); vm.hub.refreshModels(); vm.hub.refreshReasoningCaps()
            GATEWAY_SPOKES.forEach { it.refresh(vm) }
        }
    }
    val visibleId = spoke?.id ?: LANDING
    val fetched = remember { mutableSetOf<String>() }
    LaunchedEffect(visibleId) {
        if (!fetched.add(visibleId)) return@LaunchedEffect
        if (spoke == null) refreshLanding(viewModel) else spoke.refresh(viewModel)
    }
    // Live refresh (1.20): what is on screen re-polls while the space is open; the landing's
    // health is live, and so is any spoke that says so. Suspended while the app backgrounds.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(visibleId, lifecycleOwner) {
        if (spoke != null && !spoke.live) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(HUB_POLL_MS)
                if (spoke == null) viewModel.hub.refreshHealth() else spoke.refresh(viewModel)
            }
        }
    }

    KeryxSpace(
        title = spoke?.label ?: "Gateway",
        onClose = onDismiss,
        onBack = if (spoke != null) ({ spokeId = null }) else null,
        standalone = false,
        liveSlot = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeryxBreathingDot(
                    color = linkHealthColor(health),
                    alive = health == LinkHealth.LIVE || health == LinkHealth.OK,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = linkHealthLabel(health) +
                        (gatewayUrl.takeIf { it.isNotBlank() }?.let { url ->
                            " · " + url.removePrefix("https://").removePrefix("http://").trimEnd('/')
                        } ?: ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = { if (spoke == null) refreshLanding(viewModel) else spoke.refresh(viewModel) }) {
                Icon(KeryxGlyphs.Refresh, contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        Spacer(Modifier.height(6.dp))
        val scope = remember(viewModel, health, onDismiss) { HubPanelScope(viewModel, health, onDismiss) }
        // Stepping into a spoke and back is a page turn, not a pager — the same settle/leave
        // pair every other place uses.
        AnimatedContent(
            targetState = spoke,
            transitionSpec = { keryxPop().togetherWith(keryxVanish()) },
            label = "gatewaySpoke",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                if (page == null) {
                    StatusTab(viewModel, health, onDismiss, spokes = {
                        gatewaySpokeRows(viewModel) { spokeId = it }
                    })
                } else page.content(scope)
            }
        }
    }
}

/** The spoke rows on the landing — one [KeryxHubRow] each, subtitled from live state. */
private fun LazyListScope.gatewaySpokeRows(viewModel: ChatViewModel, open: (String) -> Unit) {
    item(key = "spokes-label") {
        Spacer(Modifier.height(6.dp))
        SectionLabel("Rooms of the machine")
    }
    items(GATEWAY_SPOKES.size, key = { "spoke-" + GATEWAY_SPOKES[it].id }) { i ->
        val p = GATEWAY_SPOKES[i]
        KeryxHubRow(icon = p.icon, title = p.label, subtitle = p.subtitle(viewModel), onClick = { open(p.id) })
    }
    item(key = "spokes-gap") { Spacer(Modifier.height(8.dp)) }
}

/** Link-health → status color, in the shared semantic palette. */
@Composable
internal fun linkHealthColor(health: LinkHealth): Color = when (health) {
    LinkHealth.LIVE, LinkHealth.OK -> KeryxStatus.good
    LinkHealth.UNKNOWN -> KeryxStatus.warn
    LinkHealth.OFF -> KeryxStatus.idle
    else -> KeryxStatus.bad
}

/** One-line description of a link-health state, shared by the header and the Status panel. */
internal fun linkHealthLabel(health: LinkHealth): String = when (health) {
    LinkHealth.LIVE -> "Streaming live"
    LinkHealth.OK -> "Connected"
    LinkHealth.UNKNOWN -> "Not tested yet"
    LinkHealth.OFF -> "Side-channel off"
    else -> "Unreachable — using Matrix sync"
}
