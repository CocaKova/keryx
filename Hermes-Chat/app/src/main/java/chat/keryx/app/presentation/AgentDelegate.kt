package chat.keryx.app.presentation

import chat.keryx.app.R
import chat.keryx.app.KeryxText
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.McpCatalogEntry
import chat.keryx.app.transport.direct.McpProbe
import chat.keryx.app.transport.direct.McpServer
import chat.keryx.app.transport.direct.SoulText
import chat.keryx.app.transport.direct.UsageReport
import chat.keryx.app.transport.direct.AgentPlugin
import chat.keryx.app.transport.direct.McpAuthStyle
import chat.keryx.app.transport.direct.PluginCatalogEntry
import chat.keryx.app.transport.direct.PluginInstallResult
import chat.keryx.app.transport.direct.mcpAdd
import chat.keryx.app.transport.direct.mcpAuthFlow
import chat.keryx.app.transport.direct.mcpAuthStart
import chat.keryx.app.transport.direct.mcpRemove
import chat.keryx.app.transport.direct.pluginCatalog
import chat.keryx.app.transport.direct.pluginInstall
import chat.keryx.app.transport.direct.pluginRemove
import chat.keryx.app.transport.direct.pluginSetEnabled
import chat.keryx.app.transport.direct.pluginsHub
import chat.keryx.app.transport.direct.usage
import chat.keryx.app.transport.direct.currentProfile
import chat.keryx.app.transport.direct.profileDisplayName
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

    /** The profile's display name, when someone set one (stock Hermes: `hermes profile rename`). */
    private val _profileName = MutableStateFlow<String?>(null)
    val profileName: StateFlow<String?> = _profileName.asStateFlow()

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
                is Routed.Ok -> toast(KeryxText.get(if (enabled) R.string.agent_mcp_on else R.string.agent_mcp_off, name))
                is Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); refreshMcp() }
                Routed.Missing -> { toast(KeryxText.get(R.string.agent_mcp_switch_unsupported)); refreshMcp() }
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

    // --- Usage (makeover) -------------------------------------------------------------------

    private val _usage = MutableStateFlow(HubDelegate.PanelState<UsageReport>())
    val usage: StateFlow<HubDelegate.PanelState<UsageReport>> = _usage.asStateFlow()
    private val _usageAvailable = MutableStateFlow<Boolean?>(null)
    val usageAvailable: StateFlow<Boolean?> = _usageAvailable.asStateFlow()
    /** The window the page reads: 7, 30 or 90 days, the dashboard's own presets. */
    private val _usageDays = MutableStateFlow(30)
    val usageDays: StateFlow<Int> = _usageDays.asStateFlow()
    private var usageJob: Job? = null

    fun setUsageDays(days: Int) {
        if (days == _usageDays.value) return
        _usageDays.value = days
        usageJob?.cancel()
        // The old window's numbers would read as the new one's: clear them while it loads.
        _usage.value = HubDelegate.PanelState()
        refreshUsage()
    }

    fun refreshUsage() {
        val r = rest() ?: return
        if (usageJob?.isActive == true) return
        _usage.value = _usage.value.copy(refreshing = true)
        val days = _usageDays.value
        usageJob = scope.launch {
            when (val got = r.usage(days)) {
                is Routed.Ok -> { _usageAvailable.value = true; _usage.value = HubDelegate.PanelState(data = got.value) }
                is Routed.Failed -> { _usageAvailable.value = true; _usage.value = _usage.value.copy(error = got.message, refreshing = false) }
                Routed.Missing -> { _usageAvailable.value = false; _usage.value = HubDelegate.PanelState() }
            }
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
            (r.profileDisplayName(name) as? Routed.Ok)?.let { _profileName.value = it.value }
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
                    toast(KeryxText.get(R.string.agent_soul_saved))
                    onDone(true)
                }
                is Routed.Failed -> { toast(KeryxText.get(R.string.agent_couldnt_save_reason, res.message.take(80))); onDone(false) }
                Routed.Missing -> { toast(KeryxText.get(R.string.agent_soul_edit_unsupported)); onDone(false) }

            }
        }
    }

    // --- Plugins (3.0): the dashboard's plugin hub, catalog and install -------------------------

    private val _plugins = MutableStateFlow(HubDelegate.PanelState<List<AgentPlugin>>())
    val plugins: StateFlow<HubDelegate.PanelState<List<AgentPlugin>>> = _plugins.asStateFlow()
    /** Null until asked; false on a dashboard without the plugin hub, which hides the spoke. */
    private val _pluginsAvailable = MutableStateFlow<Boolean?>(null)
    val pluginsAvailable: StateFlow<Boolean?> = _pluginsAvailable.asStateFlow()
    private val _pluginCatalog = MutableStateFlow(HubDelegate.PanelState<List<PluginCatalogEntry>>())
    val pluginCatalog: StateFlow<HubDelegate.PanelState<List<PluginCatalogEntry>>> = _pluginCatalog.asStateFlow()
    private var pluginsJob: Job? = null
    private var pluginCatalogJob: Job? = null

    fun refreshPlugins() {
        val r = rest() ?: return
        if (pluginsJob?.isActive == true) return
        _plugins.value = _plugins.value.copy(refreshing = true)
        pluginsJob = scope.launch {
            when (val got = r.pluginsHub()) {
                is Routed.Ok -> { _pluginsAvailable.value = true; _plugins.value = HubDelegate.PanelState(data = got.value) }
                is Routed.Failed -> { _pluginsAvailable.value = true; _plugins.value = _plugins.value.copy(error = got.message, refreshing = false) }
                Routed.Missing -> { _pluginsAvailable.value = false; _plugins.value = HubDelegate.PanelState() }
            }
        }
    }

    fun refreshPluginCatalog() {
        val r = rest() ?: return
        if (pluginCatalogJob?.isActive == true) return
        _pluginCatalog.value = _pluginCatalog.value.copy(refreshing = true)
        pluginCatalogJob = scope.launch {
            when (val got = r.pluginCatalog()) {
                is Routed.Ok -> _pluginCatalog.value = HubDelegate.PanelState(data = got.value)
                is Routed.Failed -> _pluginCatalog.value = _pluginCatalog.value.copy(error = got.message, refreshing = false)
                Routed.Missing -> _pluginCatalog.value = HubDelegate.PanelState(data = emptyList())
            }
        }
    }

    /** Flip a plugin: the row moves at once and settles back on a refusal. Hermes loads it on restart. */
    fun setPluginEnabled(name: String, enabled: Boolean) {
        val r = rest() ?: return
        val before = _plugins.value.data ?: return
        _plugins.value = _plugins.value.copy(data = before.map {
            if (it.name == name) it.copy(status = if (enabled) "enabled" else "disabled") else it
        })
        scope.launch {
            when (val res = r.pluginSetEnabled(name, enabled)) {
                is Routed.Ok -> toast(KeryxText.get(if (enabled) R.string.agent_plugin_on else R.string.agent_plugin_off, name))
                is Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); refreshPlugins() }
                Routed.Missing -> { toast(KeryxText.get(R.string.agent_plugin_unsupported)); refreshPlugins() }
            }
        }
    }

    fun removePlugin(name: String, onDone: (Boolean) -> Unit) {
        val r = rest() ?: return onDone(false)
        scope.launch {
            when (val res = r.pluginRemove(name)) {
                is Routed.Ok -> { toast(KeryxText.get(R.string.agent_plugin_removed, name)); onDone(true); refreshPlugins(); refreshPluginCatalog() }
                is Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); onDone(false) }
                Routed.Missing -> { toast(KeryxText.get(R.string.agent_plugin_unsupported)); onDone(false) }
            }
        }
    }

    sealed interface PluginInstallOutcome {
        data class Installed(val result: PluginInstallResult) : PluginInstallOutcome
        data class Refused(val message: String) : PluginInstallOutcome
        data object Unsupported : PluginInstallOutcome
    }

    /** Install from the catalog ([catalogName]) or from a git URL or `owner/repo` ([identifier]). */
    fun installPlugin(identifier: String?, catalogName: String?, onDone: (PluginInstallOutcome) -> Unit) {
        val r = rest() ?: return onDone(PluginInstallOutcome.Unsupported)
        scope.launch {
            when (val res = r.pluginInstall(identifier?.trim(), catalogName)) {
                is Routed.Ok -> { onDone(PluginInstallOutcome.Installed(res.value)); refreshPlugins(); refreshPluginCatalog() }
                is Routed.Failed -> onDone(PluginInstallOutcome.Refused(cleanDetail(res.message)))
                Routed.Missing -> onDone(PluginInstallOutcome.Unsupported)
            }
        }
    }

    // --- An MCP server by URL (3.0) -------------------------------------------------------------

    sealed interface McpAddOutcome {
        data object Added : McpAddOutcome
        /** OAuth: the server is saved; open [url] to sign in, and the flow is watched from here. */
        data class SignIn(val url: String) : McpAddOutcome
        data class Refused(val message: String) : McpAddOutcome
        data object Unsupported : McpAddOutcome
    }

    /**
     * Save a server that lives at [url]. With [McpAuthStyle.OAUTH] the sign-in starts straight
     * after, and the page to open comes back through [onDone]; the flow is then polled until the
     * gateway has its tokens (or gives up), and the list is read again.
     */
    fun addMcpByUrl(name: String, url: String, auth: McpAuthStyle, token: String?, onDone: (McpAddOutcome) -> Unit) {
        val r = rest() ?: return onDone(McpAddOutcome.Unsupported)
        scope.launch {
            when (val res = r.mcpAdd(name.trim(), url.trim(), auth, token?.trim())) {
                is Routed.Ok -> {
                    refreshMcp()
                    if (auth == McpAuthStyle.OAUTH) signIn(name.trim(), onDone) else onDone(McpAddOutcome.Added)
                }
                is Routed.Failed -> onDone(McpAddOutcome.Refused(cleanDetail(res.message)))
                Routed.Missing -> onDone(McpAddOutcome.Unsupported)
            }
        }
    }

    /** Sign an OAuth server in again (an expired grant, or one added elsewhere). */
    fun signInMcp(name: String, onDone: (McpAddOutcome) -> Unit) {
        scope.launch { signIn(name, onDone) }
    }

    private suspend fun signIn(name: String, onDone: (McpAddOutcome) -> Unit) {
        val r = rest() ?: return onDone(McpAddOutcome.Unsupported)
        val flow = when (val got = r.mcpAuthStart(name)) {
            is Routed.Ok -> got.value
            is Routed.Failed -> return onDone(McpAddOutcome.Refused(cleanDetail(got.message)))
            Routed.Missing -> return onDone(McpAddOutcome.Unsupported)
        }
        val page = flow.authorizationUrl
        if (page == null || flow.status == "error") {
            return onDone(McpAddOutcome.Refused(flow.error ?: KeryxText.get(R.string.agent_mcp_signin_failed_plain)))
        }
        onDone(McpAddOutcome.SignIn(page))
        // The gateway waits up to five minutes for the browser to come back; watch for as long.
        repeat(AUTH_POLLS) {
            kotlinx.coroutines.delay(AUTH_POLL_MS)
            val now = (r.mcpAuthFlow(flow.flowId) as? Routed.Ok)?.value ?: return@repeat
            if (now.done) {
                if (now.status == "approved") toast(KeryxText.get(R.string.agent_mcp_signed_in, name))
                else toast(KeryxText.get(R.string.agent_mcp_signin_failed, name, (now.error ?: "").take(80)))
                refreshMcp()
                return
            }
        }
    }

    fun removeMcp(name: String, onDone: (Boolean) -> Unit) {
        val r = rest() ?: return onDone(false)
        scope.launch {
            when (val res = r.mcpRemove(name)) {
                is Routed.Ok -> { toast(KeryxText.get(R.string.agent_mcp_removed, name)); onDone(true); refreshMcp(); refreshMcpCatalog() }
                is Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); onDone(false) }
                Routed.Missing -> onDone(false)
            }
        }
    }

    /** The server's own words from `HTTP 400 for /path — {"detail": "..."}`, else the whole line. */
    private fun cleanDetail(message: String): String =
        Regex("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(message)?.groupValues?.get(1)?.take(200)
            ?: message.take(200)

    private companion object {
        const val BUILD_RECHECK_MS = 20_000L
        const val AUTH_POLL_MS = 3_000L
        const val AUTH_POLLS = 100
    }
}
