package chat.keryx.app.presentation

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.McpServer
import chat.keryx.app.transport.direct.SoulText
import chat.keryx.app.transport.direct.currentProfile
import chat.keryx.app.transport.direct.mcpServers
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
}
