package chat.keryx.app.presentation

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.McpCatalogEntry
import chat.keryx.app.transport.direct.McpProbe
import chat.keryx.app.transport.direct.McpServer
import chat.keryx.app.transport.direct.SoulText
import chat.keryx.app.transport.direct.currentProfile
import chat.keryx.app.transport.direct.mcpCatalog
import chat.keryx.app.transport.direct.mcpInstall
import chat.keryx.app.transport.direct.mcpServers
import chat.keryx.app.transport.direct.mcpTest
import chat.keryx.app.transport.direct.mcpSetEnabled
import chat.keryx.app.transport.direct.saveSoul
import chat.keryx.app.transport.direct.soul
import chat.keryx.core.model.Routed
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The agent page's own spokes (makeover): the MCP servers the agent reaches and the SOUL.md it
 * is. Dashboard routes, so the direct door only — [rest] is null on Matrix and in the demo, and
 * both spokes hide there. Fetched when the page opens and when a spoke is visited, never polled.
 */
class AgentDelegate(
    deps: GatewayDeps,
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val toast = deps.toast

    private val _mcp = MutableStateFlow(HubDelegate.PanelState<List<McpServer>>())
    val mcp: StateFlow<HubDelegate.PanelState<List<McpServer>>> = _mcp.asStateFlow()
    private val _mcpAvailable = MutableStateFlow<Boolean?>(null)
    val mcpAvailable: StateFlow<Boolean?> = _mcpAvailable.asStateFlow()

    private val _soul = MutableStateFlow(HubDelegate.PanelState<SoulText>())
    val soul: StateFlow<HubDelegate.PanelState<SoulText>> = _soul.asStateFlow()
    private val _soulAvailable = MutableStateFlow<Boolean?>(null)
    val soulAvailable: StateFlow<Boolean?> = _soulAvailable.asStateFlow()

    /** The profile this gateway's dashboard speaks for — the agent's name on the page. */
    private val _profile = MutableStateFlow<String?>(null)
    val profile: StateFlow<String?> = _profile.asStateFlow()

    private var mcpJob: Job? = null
    private var soulJob: Job? = null

    fun refreshMcp() {
        val r = rest() ?: return
        if (mcpJob?.isActive == true) return
        _mcp.value = _mcp.value.copy(refreshing = true)
        mcpJob = scope.launch {
            when (val got = r.mcpServers()) {
                is Routed.Ok -> { _mcpAvailable.value = true; _mcp.value = HubDelegate.PanelState(data = got.value) }
                is Routed.Failed -> { _mcpAvailable.value = true; _mcp.value = _mcp.value.copy(error = got.message, refreshing = false) }
                Routed.Missing -> { _mcpAvailable.value = false; _mcp.value = HubDelegate.PanelState() }
            }
        }
    }

    /**
     * Flip one server. The row moves at once and settles back if the gateway says no — a switch
     * that waits on a round trip feels broken. Hermes applies it from the next session on.
     */
    fun setMcpEnabled(name: String, enabled: Boolean) {
        val r = rest() ?: return
        val before = _mcp.value.data ?: return
        _mcp.value = _mcp.value.copy(data = before.map { if (it.name == name) it.copy(enabled = enabled) else it })
        scope.launch {
            when (val res = r.mcpSetEnabled(name, enabled)) {
                is Routed.Ok -> toast(if (enabled) "$name on — from the next session" else "$name off — from the next session")
                is Routed.Failed -> { toast("Couldn't change $name: ${res.message.take(80)}"); refreshMcp() }
                Routed.Missing -> { toast("This gateway can't switch MCP servers from here."); refreshMcp() }
            }
        }
    }

    // --- The catalog, and a server's test (makeover) -------------------------------------------

    private val _catalog = MutableStateFlow(HubDelegate.PanelState<List<McpCatalogEntry>>())
    val catalog: StateFlow<HubDelegate.PanelState<List<McpCatalogEntry>>> = _catalog.asStateFlow()
    /** Null until asked; false on a dashboard without the catalog route, which hides the card. */
    private val _catalogAvailable = MutableStateFlow<Boolean?>(null)
    val catalogAvailable: StateFlow<Boolean?> = _catalogAvailable.asStateFlow()
    private var catalogJob: Job? = null

    fun refreshMcpCatalog() {
        val r = rest() ?: return
        if (catalogJob?.isActive == true) return
        _catalog.value = _catalog.value.copy(refreshing = true)
        catalogJob = scope.launch {
            when (val got = r.mcpCatalog()) {
                is Routed.Ok -> { _catalogAvailable.value = true; _catalog.value = HubDelegate.PanelState(data = got.value) }
                is Routed.Failed -> { _catalogAvailable.value = true; _catalog.value = _catalog.value.copy(error = got.message, refreshing = false) }
                Routed.Missing -> { _catalogAvailable.value = false; _catalog.value = HubDelegate.PanelState() }
            }
        }
    }

    /** How an install went. The words are the screen's (they are translated); this only says which. */
    sealed interface InstallOutcome {
        data object Installed : InstallOutcome
        /** A git build: the gateway carries on after answering; the catalog is re-read shortly. */
        data object Building : InstallOutcome
        data class Refused(val message: String) : InstallOutcome
        data object Unsupported : InstallOutcome
    }

    fun installMcp(entry: McpCatalogEntry, env: Map<String, String>, onDone: (InstallOutcome) -> Unit) {
        val r = rest() ?: run { onDone(InstallOutcome.Unsupported); return }
        scope.launch {
            when (val res = r.mcpInstall(entry.name, env.filterValues { it.isNotBlank() })) {
                is Routed.Ok -> {
                    onDone(if (res.value) InstallOutcome.Building else InstallOutcome.Installed)
                    refreshMcp(); refreshMcpCatalog()
                    if (res.value) {
                        // A clone and a build take a while; look again once, without polling.
                        kotlinx.coroutines.delay(BUILD_RECHECK_MS)
                        refreshMcp(); refreshMcpCatalog()
                    }
                }
                is Routed.Failed -> onDone(InstallOutcome.Refused(res.message))
                Routed.Missing -> onDone(InstallOutcome.Unsupported)
            }
        }
    }

    /** One server's last test, by name: [Testing] while the gateway connects to it. */
    sealed interface ProbeState {
        data object Testing : ProbeState
        data class Done(val probe: McpProbe) : ProbeState
        data class Failed(val message: String) : ProbeState
    }

    private val _probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    val probes: StateFlow<Map<String, ProbeState>> = _probes.asStateFlow()

    /** Ask the gateway to connect to [name], list its tools and let go — the dashboard's Test. */
    fun testMcp(name: String) {
        val r = rest() ?: return
        if (_probes.value[name] == ProbeState.Testing) return
        _probes.value = _probes.value + (name to ProbeState.Testing)
        scope.launch {
            val state = when (val res = r.mcpTest(name)) {
                is Routed.Ok -> ProbeState.Done(res.value)
                is Routed.Failed -> ProbeState.Failed(res.message)
                Routed.Missing -> ProbeState.Failed("")
            }
            _probes.value = _probes.value + (name to state)
        }
    }

    fun refreshSoul() {
        val r = rest() ?: return
        if (soulJob?.isActive == true) return
        _soul.value = _soul.value.copy(refreshing = true)
        soulJob = scope.launch {
            val name = when (val p = r.currentProfile()) {
                is Routed.Ok -> p.value
                // An older dashboard without the profile route is still one profile: the default.
                else -> "default"
            }
            _profile.value = name
            when (val got = r.soul(name)) {
                is Routed.Ok -> { _soulAvailable.value = true; _soul.value = HubDelegate.PanelState(data = got.value) }
                is Routed.Failed -> { _soulAvailable.value = true; _soul.value = _soul.value.copy(error = got.message, refreshing = false) }
                Routed.Missing -> { _soulAvailable.value = false; _soul.value = HubDelegate.PanelState() }
            }
        }
    }

    fun saveSoul(content: String, onDone: (Boolean) -> Unit) {
        val r = rest() ?: run { onDone(false); return }
        val profile = _soul.value.data?.profile ?: _profile.value ?: "default"
        scope.launch {
            when (val res = r.saveSoul(profile, content)) {
                is Routed.Ok -> {
                    _soul.value = HubDelegate.PanelState(data = SoulText(profile, content, exists = true))
                    toast("Soul saved — new sessions start with it")
                    onDone(true)
                }
                is Routed.Failed -> { toast("Couldn't save: ${res.message.take(80)}"); onDone(false) }
                Routed.Missing -> { toast("This gateway can't edit the soul from here."); onDone(false) }
            }
        }
    }

    private companion object {
        const val BUILD_RECHECK_MS = 20_000L
    }
}
