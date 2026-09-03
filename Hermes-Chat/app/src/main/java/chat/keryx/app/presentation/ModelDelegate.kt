package chat.keryx.app.presentation

import chat.keryx.core.model.ModelCatalog
import chat.keryx.core.model.ModelChoice
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The model picker behind the composer's model pill. One catalog shape on both doors; two
 * ways to act on it. The direct door asks the gateway for the session's own overlay and
 * switches through `config.set` (session scope, deferred while a turn runs, confirm-gated for
 * expensive models). The Matrix door reads the catalog over Hermes Link and switches the way a
 * room always has — `/model <name> --provider <slug>` as a room command, which the gateway
 * scopes to the room and redacts once processed.
 */
class ModelDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    private val currentRoomId: () -> String?,
    private val sendRoomCommand: (String) -> Unit,
    /** Fired once the gateway accepted a switch (now or at the next turn): the session's
     *  reasoning ladder belongs to the new brain, so the owner re-probes it. */
    private val onSwitched: () -> Unit = {},
    /** The phone's recents ledger (settings-backed); null in plain-JVM tests. */
    private val readRecents: (() -> List<String>)? = null,
    private val writeRecents: ((List<String>) -> Unit)? = null,
) {
    private val scope = deps.scope
    private val hubClient = deps.client
    private val toast = deps.toast

    private val _catalog = MutableStateFlow<ModelCatalog?>(null)
    val catalog: StateFlow<ModelCatalog?> = _catalog.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _recents = MutableStateFlow(readRecents?.invoke().orEmpty())
    /** `provider|model` keys, newest first — the picker resolves them against the live catalog. */
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    private fun remember(choice: ModelChoice) {
        val next = chat.keryx.core.model.ModelPicker.pushRecent(_recents.value, choice)
        _recents.value = next
        writeRecents?.invoke(next)
    }

    /** The choice the gateway asked us to confirm; the same choice tapped again confirms it. */
    private var awaitingConfirm: ModelChoice? = null

    /** Forget the catalog: it described another room's session. The pill falls back to the
     *  caps probe's model until the picker is opened and re-fetches for this room. */
    fun clear() { _catalog.value = null }

    fun refresh() {
        if (_loading.value) return
        val roomId = currentRoomId()
        _loading.value = true
        scope.launch {
            val result = transport.gateway?.let { gw ->
                if (roomId != null) gw.modelOptions(roomId)
                else Result.failure(IllegalStateException("no session open"))
            } ?: hubClient()?.modelOptions()
                ?: Result.failure(IllegalStateException("Hermes Link is off"))
            result.onSuccess { _catalog.value = it }
                .onFailure { android.util.Log.w("KeryxModel", "catalog: ${it.message}") }
            _loading.value = false
        }
    }

    fun select(choice: ModelChoice) {
        val roomId = currentRoomId() ?: return
        val gw = transport.gateway
        if (gw == null) {
            // A room command: the gateway pins the room's session to it and confirms in-line.
            sendRoomCommand("/model ${choice.name} --provider ${choice.provider}")
            remember(choice)
            awaitingConfirm = null
            return
        }
        val confirm = awaitingConfirm == choice
        awaitingConfirm = null
        scope.launch {
            gw.selectModel(roomId, choice.name, choice.provider, confirm)
                .onSuccess { out ->
                    when {
                        out.confirmRequired -> {
                            awaitingConfirm = choice
                            toast("${out.message.ifBlank { "This model is expensive." }} Tap it again to confirm.")
                        }
                        out.deferred -> {
                            remember(choice)
                            toast("${out.model} takes over at the next turn")
                            _catalog.value = _catalog.value?.copy(
                                model = out.model.ifBlank { choice.name }, provider = choice.provider,
                            )
                            onSwitched()
                        }
                        else -> {
                            remember(choice)
                            toast("${out.model} — this session")
                            _catalog.value = _catalog.value?.copy(
                                model = out.model.ifBlank { choice.name }, provider = choice.provider,
                            )
                            onSwitched()
                        }
                    }
                }
                .onFailure { toast("Switch refused: ${it.message?.take(80)}") }
        }
    }
}
