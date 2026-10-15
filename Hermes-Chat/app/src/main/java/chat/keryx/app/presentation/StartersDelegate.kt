package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Empty-chat starters (2.17.3; split out of [ChatViewModel] in 3.0): three switches, then the
 * AI-written prompts by agent, from keryx-stream's `/keryx/suggestions`.
 */
class StartersDelegate(
    deps: GatewayDeps,
    /** Names the agent an empty room runs as. */
    private val bots: BotsDelegate,
) {
    private val scope = deps.scope
    private val settings: SettingsRepository = deps.settings
    private val client = deps.client

    private val _starterSuggestions = MutableStateFlow(settings.starterSuggestions)
    val starterSuggestions: StateFlow<Boolean> = _starterSuggestions.asStateFlow()
    fun setStarterSuggestions(on: Boolean) { _starterSuggestions.value = on; settings.starterSuggestions = on }

    private val _starterAiPrompts = MutableStateFlow(settings.starterAiPrompts)
    val starterAiPrompts: StateFlow<Boolean> = _starterAiPrompts.asStateFlow()
    fun setStarterAiPrompts(on: Boolean) { _starterAiPrompts.value = on; settings.starterAiPrompts = on }

    private val _starterReminders = MutableStateFlow(settings.starterReminders)
    val starterReminders: StateFlow<Boolean> = _starterReminders.asStateFlow()
    fun setStarterReminders(on: Boolean) { _starterReminders.value = on; settings.starterReminders = on }

    /** AI-written starters by agent profile ("" = the gateway's own), as the gateway last gave them. */
    private val _aiStarters = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val aiStarters: StateFlow<Map<String, List<String>>> = _aiStarters.asStateFlow()
    private val starterLoads = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Whose starters an empty [roomId] shows: the agent it runs as. */
    fun starterKey(roomId: String?): String = bots.agentOf(roomId)?.name.orEmpty()

    /**
     * Ask the gateway for [roomId]'s agent's starters. It answers from a cache it refreshes on its
     * own; a cold profile says `pending`, so this asks again a few times while the model writes.
     * A gateway without the route (an older keryx-stream) leaves the generic starters in place.
     */
    fun loadStarters(roomId: String?) {
        if (!_starterSuggestions.value || !_starterAiPrompts.value) return
        val key = starterKey(roomId)
        if (starterLoads[key]?.isActive == true) return
        val client = client() ?: return
        starterLoads[key] = scope.launch {
            repeat(4) {
                val answer = client.suggestions(key.ifBlank { null }).getOrNull() ?: return@launch
                if (answer.prompts.isNotEmpty()) _aiStarters.value = _aiStarters.value + (key to answer.prompts)
                if (!answer.pending) return@launch
                kotlinx.coroutines.delay(6_000)
            }
        }
    }
}
