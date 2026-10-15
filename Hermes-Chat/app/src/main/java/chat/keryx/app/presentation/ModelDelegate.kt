package chat.keryx.app.presentation

import chat.keryx.core.model.ModelCatalog
import chat.keryx.core.model.ModelChoice
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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
    /** The reasoning dial's probe; a switched brain owns a different ladder. */
    private val hub: HubDelegate,
    private val recordCommandUse: (String) -> Unit = {},
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
    fun clear() { _catalog.value = null; catalogRoomId = null; fetchedAt = 0L }

    /** Which room the catalog describes, and when it was read — the picker's TTL (2.10). */
    private var catalogRoomId: String? = null
    private var fetchedAt = 0L

    /**
     * Read the catalog — unless the one held is this room's and younger than [CATALOG_TTL_MS],
     * in which case the picker opens on it instantly (the Desktop refreshes its catalogs every
     * twenty minutes and keeps them warm; a picker that re-read the wire on every open paid a
     * round trip to show the same list). [force] is the picker's own refresh button.
     */
    fun refresh(force: Boolean = false) {
        if (_loading.value) return
        val roomId = currentRoomId()
        val now = System.currentTimeMillis()
        if (!force && _catalog.value != null && roomId == catalogRoomId && now - fetchedAt < CATALOG_TTL_MS) return
        _loading.value = true
        scope.launch {
            val result = transport.gateway?.let { gw ->
                if (roomId != null) gw.modelOptions(roomId)
                else Result.failure(IllegalStateException("no session open"))
            } ?: hubClient()?.modelOptions()
                ?: Result.failure(IllegalStateException("Hermes Link is off"))
            result.onSuccess { _catalog.value = it; catalogRoomId = roomId; fetchedAt = System.currentTimeMillis() }
                .onFailure { android.util.Log.w("KeryxModel", "catalog: ${it.message}") }
            _loading.value = false
        }
    }

    /**
     * Switch to [choice]. [global] makes it the gateway's default for every session (`--global`,
     * config.yaml) — the reasoning menu's "every session" — instead of this session only.
     * Every client of the gateway follows a global pick, not just this phone.
     */
    fun select(choice: ModelChoice, global: Boolean = false) {
        val roomId = currentRoomId() ?: return
        val gw = transport.gateway
        if (gw == null) {
            // A room command: the gateway pins the room's session to it and confirms in-line.
            sendRoomCommand("/model ${choice.name} --provider ${choice.provider}" + if (global) " --global" else "")
            remember(choice)
            awaitingConfirm = null
            return
        }
        val confirm = awaitingConfirm == choice
        awaitingConfirm = null
        val reach = if (global) "every session" else "this session"
        scope.launch {
            gw.selectModel(roomId, choice.name, choice.provider, confirm, global)
                .onSuccess { out ->
                    when {
                        out.confirmRequired -> {
                            awaitingConfirm = choice
                            toast("${out.message.ifBlank { "This model is expensive." }} Tap it again to confirm.")
                        }
                        out.deferred -> {
                            remember(choice)
                            toast("${out.model} takes over at the next turn — $reach")
                            _catalog.value = _catalog.value?.copy(
                                model = out.model.ifBlank { choice.name }, provider = choice.provider,
                            )
                            refreshReasoningCaps() // the session's ladder belongs to the new brain
                        }
                        else -> {
                            remember(choice)
                            toast("${out.model} — $reach")
                            _catalog.value = _catalog.value?.copy(
                                model = out.model.ifBlank { choice.name }, provider = choice.provider,
                            )
                            refreshReasoningCaps() // the session's ladder belongs to the new brain
                        }
                    }
                }
                .onFailure { toast("Switch refused: ${it.message?.take(80)}") }
        }
    }

    private val direct get() = transport as? chat.keryx.app.transport.direct.DirectTransport

    // --- Dynamic reasoning control: rides Hermes' native `/reasoning` command (per-session scope).

    /**
     * Levels this process has watched a model refuse. In memory on purpose: the walk-back itself
     * lands on the gateway, which remembers, so this only has to stop the SAME dead rung being
     * tried twice inside one run.
     */
    private val refusedLevels: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * A turn died because the brain refused the thinking level: step down one rung and say so.
     *
     * The scale is Hermes'; which rungs a brain can actually render is decided by its chat
     * template, and nothing knows that list until a real turn dies on it. So the level that
     * killed this turn is recorded and the next one down is set for this session — never
     * globally, and never all the way to off (see [ReasoningEffort.fallbackBelow]).
     *
     * One dead turn can announce itself on three channels (`message.complete` with an error, the
     * buffered lifecycle line, the `error` event). `refusedLevels.add` is the gate: the first
     * announcement to name a rung owns the walk-back, and the other two return — otherwise one
     * refusal would fall three rungs.
     */
    suspend fun walkBackReasoning() {
        val d = direct ?: return
        val roomId = currentRoomId() ?: return
        val caps = hub.reasoningCaps.value
        val model = caps?.model.orEmpty().trim()
        // Authoritative: what the gateway says is in force right now, not what the pill last drew.
        val current = d.reasoningEffort(roomId).getOrNull()?.takeIf { it.isNotBlank() }
            ?: caps?.current.orEmpty()
        if (current.isBlank()) return
        if (model.isBlank()) {
            // Nothing to key the memory on — say the true thing rather than guess a rung.
            toast("This brain refused that thinking level — pick a lower one.")
            return
        }
        if (!refusedLevels.add(chat.keryx.core.model.ReasoningEffort.rejectionKey(model, current))) return

        val label = { level: String ->
            chat.keryx.core.model.ReasoningEffort.label(level, caps?.labels.orEmpty()).ifBlank { level }
        }
        val next = chat.keryx.core.model.ReasoningEffort.fallbackBelow(model, current, refusedLevels)
        if (next == null) {
            toast("This brain refused ${label(current)} thinking, and there's nothing lower to try.")
            return
        }
        d.setReasoningEffort(roomId, next, global = false)
            .onSuccess {
                toast("This brain refused ${label(current)} thinking — dropped to ${label(next)}.")
                refreshReasoningCaps()
            }
            .onFailure {
                toast("This brain refused ${label(current)} thinking; couldn't drop to ${label(next)}.")
            }
    }

    /** Re-probe the reasoning dial for the CURRENT room's brain, not the profile default.
     *  Direct door: the session's live route (the catalog's model + provider, once fetched)
     *  and its stored id, with the effective level read straight off the live agent. Matrix:
     *  the global default, as before — a room has no session id the gateway can look up. */
    fun refreshReasoningCaps() {
        val roomId = currentRoomId()
        val d = direct
        val catalog = if (d != null) catalog.value else null
        hub.refreshReasoningCaps(
            model = catalog?.model,
            provider = catalog?.provider,
            sessionId = if (d != null) roomId else null,
            liveCurrent = if (d != null && roomId != null) ({ d.reasoningEffort(roomId).getOrNull() }) else null,
        )
    }

    /** A pick from the reasoning menu: `<level>` (this session), `<level> --global` (every
     *  session), or `show` / `hide` / `reset`. The direct door sets a level through the
     *  gateway's own `config.set` — it lands on the live agent at once and echoes the
     *  authoritative value — where the slash text only reaches a worker copy. Either way the
     *  pill re-probes afterwards instead of waiting for the next tap. */
    fun sendReasoningCommand(arg: String) {
        recordCommandUse("/reasoning")
        val parts = arg.trim().split(Regex("\\s+"))
        val level = parts.firstOrNull().orEmpty()
        val global = parts.drop(1).any { it == "--global" }
        val d = direct
        val roomId = currentRoomId()
        val isLevel = level.isNotBlank() && level !in setOf("show", "hide", "reset", "status")
        if (d != null && roomId != null && isLevel) {
            scope.launch {
                d.setReasoningEffort(roomId, level, global)
                    .onSuccess { v ->
                        val said = chat.keryx.core.model.ReasoningEffort.label(v.ifBlank { level }, hub.reasoningCaps.value?.labels.orEmpty())
                        toast(if (global) "Reasoning → $said for every session" else "Reasoning → $said — this session")
                        refreshReasoningCaps()
                    }
                    .onFailure { toast("Reasoning refused: ${it.message?.take(80)}") }
            }
            return
        }
        sendRoomCommand("/reasoning $arg".trim())
        scope.launch { delay(1200); refreshReasoningCaps() }
    }
}

/** How long a read catalog stays good for — the Desktop's own cadence. */
private const val CATALOG_TTL_MS = 20 * 60_000L
