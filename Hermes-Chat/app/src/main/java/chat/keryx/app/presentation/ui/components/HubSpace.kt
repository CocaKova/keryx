package chat.keryx.app.presentation.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.LinkHealth
import kotlinx.coroutines.launch

/**
 * The two gateway spaces and the one shell they share.
 *
 * Until 2.5 this was a single "Agent Hub" carrying six tabs, and the tab's *index* was the thing
 * the code reasoned about: `when (tab) { 0 -> …; 1 -> … }` appeared five times over (fetch on
 * first visit, the ten-second poll, the header's refresh button, the pager body) plus a
 * `LIVE_TABS = setOf(0, 2, 3)`. Adding a panel meant editing five parallel branches in step and
 * getting every index right; the drift was already there when this was written, with the refresh
 * button pulling reasoning caps that first-visit fetch did not.
 *
 * So a panel is now a value, not an index. [HubPanel] carries its own label, its own refresh and
 * its own body, the shell drives all three generically, and adding one is a single entry in a
 * list. The registries below are the only place the app says which panels exist.
 *
 * The split into two spaces (2.5) follows from the same honesty: "what is my server doing" and
 * "what has my agent been doing" are different questions asked at different moments, and six tabs
 * in one scrollable row made you read all of them to answer either.
 */
class HubPanelScope(
    val viewModel: ChatViewModel,
    val health: LinkHealth,
    /** Leave the space entirely — a panel that hands you back to a room uses this. */
    val closeSpace: () -> Unit,
)

/**
 * One panel in a gateway space.
 *
 * @param id stable across releases: it names the panel in saved state and is what a deep link
 *   would address. Never rename casually — the label is what you change to re-word the UI.
 * @param live whether the panel's data moves on its own (gateway state, job runs, session
 *   activity) and should re-poll while visible. Panels that only change on operator action stay
 *   fetch-once.
 * @param refresh the panel's ONE definition of "get current". First visit, the poll and the
 *   header button all call exactly this, so they cannot drift apart again.
 */
data class HubPanel(
    val id: String,
    val label: String,
    val live: Boolean = false,
    val refresh: (ChatViewModel) -> Unit,
    val content: @Composable (HubPanelScope) -> Unit,
)

private const val HUB_POLL_MS = 10_000L

/** The server: what it is doing, changing what it does, and the work it runs unattended. */
val GATEWAY_PANELS: List<HubPanel> = listOf(
    HubPanel(
        id = "status",
        label = "Status",
        live = true,
        refresh = { vm -> vm.refreshHubHealth(); vm.refreshHubModels(); vm.refreshReasoningCaps() },
        content = { StatusTab(it.viewModel, it.health, it.closeSpace) },
    ),
    HubPanel(
        id = "controls",
        label = "Controls",
        refresh = { vm -> vm.refreshHubConfig(); vm.refreshHubBrains(); vm.refreshReasoningCaps() },
        content = { ControlsTab(it.viewModel) },
    ),
    HubPanel(
        id = "jobs",
        label = "Jobs",
        live = true,
        refresh = { vm -> vm.refreshHubJobs() },
        content = { JobsTab(it.viewModel) },
    ),
)

/** The agent: what it has done, what it knows how to do, and what it can reach. */
val WORKSHOP_PANELS: List<HubPanel> = listOf(
    HubPanel(
        id = "sessions",
        label = "Sessions",
        live = true,
        refresh = { vm -> vm.refreshHubSessions() },
        content = { SessionsTab(it.viewModel) },
    ),
    HubPanel(
        id = "skills",
        label = "Skills",
        refresh = { vm -> vm.refreshHubSkills() },
        content = { SkillsTab(it.viewModel) },
    ),
    HubPanel(
        id = "tools",
        label = "Tools",
        refresh = { vm -> vm.refreshHubToolsets() },
        content = { ToolsTab(it.viewModel) },
    ),
)

@Composable
fun GatewaySpace(viewModel: ChatViewModel, health: LinkHealth, onDismiss: () -> Unit) =
    HubSpace("Gateway", GATEWAY_PANELS, viewModel, health, onDismiss)

@Composable
fun WorkshopSpace(viewModel: ChatViewModel, health: LinkHealth, onDismiss: () -> Unit) =
    HubSpace("Workshop", WORKSHOP_PANELS, viewModel, health, onDismiss)

/**
 * A hard fling that runs a panel's LazyColumn into its edge used to spill the leftover velocity
 * into the sheet's own nested-scroll handling — the sheet dragged a few px and sprang back, over
 * and over (the "scroll down hard and the UI glitches up and down" stutter). Swallow everything a
 * fling leaves unconsumed before it reaches the sheet; real finger drags (UserInput) pass through
 * untouched, so swipe-down-to-dismiss still works.
 */
private val FlingTamer = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.SideEffect) available else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * The shell both spaces are. Panels fetch on first visit, the volatile ones re-poll gently while
 * visible, and the whole space degrades to cached snapshots offline — the panels themselves keep
 * stale data on screen and float the error above it ([PanelErrorLine]).
 */
@Composable
private fun HubSpace(
    title: String,
    panels: List<HubPanel>,
    viewModel: ChatViewModel,
    health: LinkHealth,
    onDismiss: () -> Unit,
) {
    val gatewayUrl by viewModel.gatewayUrl.collectAsState()
    // 2.1: tabs became pager pages — swipe moves between them. currentPage drives the tab-row
    // visuals live during a drag; settledPage is what the fetch/poll effects key on, so panels
    // skimmed past mid-swipe never fire their network refresh.
    val pagerScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { panels.size })
    val settled = panels.getOrNull(pagerState.settledPage)
    val accent = MaterialTheme.colorScheme.primary

    // First visit per opening. Panels may already hold the offline-cache seed (or the last
    // opening's snapshot) — that renders instantly while this refresh runs behind it, so a space
    // is never blank and never silently stale. Keyed by panel id, not index: a reordered registry
    // must not make the shell think it has already fetched something else.
    val fetched = remember { mutableSetOf<String>() }
    LaunchedEffect(settled?.id) {
        val panel = settled ?: return@LaunchedEffect
        if (!fetched.add(panel.id)) return@LaunchedEffect
        panel.refresh(viewModel)
    }

    // Live refresh (1.20): the visible panel re-polls while the space is open — gateway state, job
    // runs and session activity move without us. repeatOnLifecycle suspends the loop when the app
    // backgrounds (same discipline as the Missions board poll); changing panel restarts the
    // effect, so only the panel actually on screen polls.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(settled?.id, lifecycleOwner) {
        val panel = settled ?: return@LaunchedEffect
        if (!panel.live) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                kotlinx.coroutines.delay(HUB_POLL_MS)
                panel.refresh(viewModel)
            }
        }
    }

    // 1.21: the Hub graduated from a bottom sheet to its own full-screen space; 1.23: that space
    // scaffold (dusk gradient, emblem, letter-spaced title, breathing live line, close X) is the
    // shared KeryxSpace — a gateway space just supplies its link-health line and refresh action.
    KeryxSpace(
        title = title,
        onClose = onDismiss,
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
            IconButton(onClick = { settled?.refresh(viewModel) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        Spacer(Modifier.height(6.dp))

        val visible = pagerState.currentPage
        // Three panels fit a phone's width, so the row reads as a whole rather than as a strip you
        // have to scroll to discover. It stays Scrollable rather than fixed because a fourth panel
        // (the Hermes update view) is already written and should widen this row, not break it.
        ScrollableTabRow(
            selectedTabIndex = visible,
            edgePadding = 12.dp,
            containerColor = Color.Transparent,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[visible]),
                    color = accent,
                )
            },
            divider = { HorizontalDivider(color = accent.copy(alpha = 0.12f)) },
        ) {
            panels.forEachIndexed { i, panel ->
                Tab(
                    selected = visible == i,
                    onClick = { pagerScope.launch { pagerState.animateScrollToPage(i) } },
                    text = {
                        Text(panel.label, fontSize = 12.sp,
                            fontWeight = if (visible == i) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    selectedContentColor = accent,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val scope = remember(viewModel, health, onDismiss) {
            HubPanelScope(viewModel, health, onDismiss)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).nestedScroll(FlingTamer),
            verticalAlignment = Alignment.Top,
        ) { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                panels[page].content(scope)
            }
        }
    }
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
