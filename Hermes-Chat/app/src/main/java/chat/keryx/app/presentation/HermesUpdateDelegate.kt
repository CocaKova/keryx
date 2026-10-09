package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.updateAction
import chat.keryx.app.transport.direct.updateCheck
import chat.keryx.app.transport.direct.updateReceipt
import chat.keryx.app.transport.direct.updateStart
import chat.keryx.core.model.HermesUpdateParser
import chat.keryx.core.model.PluginUpdateStatus
import chat.keryx.core.model.Routed
import chat.keryx.core.model.UpdateActionStatus
import chat.keryx.core.model.UpdateCheck
import chat.keryx.core.model.UpdateObservation
import chat.keryx.core.model.UpdateOutcome
import chat.keryx.core.model.UpdatePhase
import chat.keryx.core.model.UpdatePlan
import chat.keryx.core.model.UpdatePlanner
import chat.keryx.core.model.UpdateReceipt
import chat.keryx.core.model.UpdateRoute
import chat.keryx.core.model.UpdateRun
import chat.keryx.core.model.UpdateWatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Hermes update from the phone (2.16) — the Hub's Update spoke.
 *
 * Reads the plugin's `/keryx/update` on the Link and the dashboard's `/api/hermes/update*` on the
 * direct door (see [chat.keryx.core.model.UpdatePlanner] for who runs the button and why). Every
 * read is the panel asking — on opening, on Check, and while something it started is in flight,
 * through [pollTick], which the panel calls on its own on-screen loop. Nothing here polls on its
 * own: an update left running while you look elsewhere is read again when you come back, and
 * its receipt says how it went whenever that is.
 */
class HermesUpdateDelegate(
    deps: GatewayDeps,
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val toast = deps.toast
    private val link: () -> HermesStreamClient? = deps.bareClient

    /**
     * One read of everything. A null source with its `…Missing` flag set is a route this
     * gateway doesn't have (that part hides); null without the flag is "couldn't read it now".
     */
    data class Board(
        val plugin: PluginUpdateStatus? = null,
        val pluginMissing: Boolean = false,
        val check: UpdateCheck? = null,
        val checkMissing: Boolean = false,
        /** Null with [receiptMissing] false = no update has run since receipts landed. */
        val receipt: UpdateReceipt? = null,
        val receiptMissing: Boolean = false,
        val error: String? = null,
        /** A "Check now" in flight: the dashboard's forced check, or the plugin's git fetch. */
        val checking: Boolean = false,
        /** The preflight the panel started is running. */
        val probing: Boolean = false,
        /** When a "Check for updates" last finished (phone clock) — the card says so (2.19). */
        val checkedAtMs: Long = 0L,
    ) {
        val anySource: Boolean get() = !(pluginMissing && checkMissing)
    }

    private val _board = MutableStateFlow<Board?>(null)
    val board: StateFlow<Board?> = _board.asStateFlow()

    /** Null until the first read; false = neither server offers anything here, spoke hides. */
    private val _available = MutableStateFlow<Boolean?>(null)
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    /** The update this phone started, and where it has got to. */
    data class RunView(val run: UpdateRun, val phase: UpdatePhase, val lines: List<String>)

    private val _run = MutableStateFlow<RunView?>(null)
    val run: StateFlow<RunView?> = _run.asStateFlow()

    private var inFlight: Job? = null

    // --- reads ----------------------------------------------------------------------------

    private suspend fun linkRead(path: String, method: String = "GET", body: JsonObject? = null): Routed<JsonObject> {
        val c = link() ?: return Routed.Missing
        return try {
            Routed.Ok(c.linkCall(path, method, body))
        } catch (e: HermesStreamClient.GatewayError) {
            // aiohttp: an unknown path is 404, a known path with another method 405.
            if (e.httpStatus == 404 || e.httpStatus == 405) Routed.Missing
            else Routed.Failed(e.message ?: "HTTP ${e.httpStatus}", e.httpStatus)
        } catch (e: Exception) {
            Routed.Failed(e.message?.take(160) ?: "The Hermes Link didn't answer.")
        }
    }

    private suspend fun readPlugin(): Routed<PluginUpdateStatus> =
        linkRead("/keryx/update").map { HermesUpdateParser.plugin(it) }

    fun refresh() {
        if (inFlight?.isActive == true) return
        inFlight = scope.launch { readAll(forceCheck = false) }
    }

    private suspend fun readAll(forceCheck: Boolean) {
        val r = rest()
        val plugin = readPlugin()
        val check = r?.updateCheck(forceCheck) ?: Routed.Missing
        val receipt = r?.updateReceipt() ?: Routed.Missing
        val last = _board.value
        val failure = listOf(check, plugin, receipt).firstNotNullOfOrNull { (it as? Routed.Failed)?.message }
        val next = Board(
            plugin = plugin.valueOrNull ?: last?.plugin.takeIf { plugin is Routed.Failed },
            pluginMissing = plugin is Routed.Missing,
            check = check.valueOrNull ?: last?.check.takeIf { check is Routed.Failed },
            checkMissing = check is Routed.Missing,
            receipt = if (receipt is Routed.Ok) receipt.value else last?.receipt.takeIf { receipt is Routed.Failed },
            receiptMissing = receipt is Routed.Missing,
            error = failure,
            checking = last?.checking == true && plugin.valueOrNull?.checking == true,
            probing = last?.probing == true && plugin.valueOrNull?.probeRunning != false,
            checkedAtMs = last?.checkedAtMs ?: 0L,
        )
        _board.value = next
        // Once either server has answered, the spoke stays: a dashboard mid-restart reads as
        // "no client" for a moment, and the spoke must not vanish under the update it is watching.
        if (next.anySource) _available.value = true else if (_available.value != true) _available.value = false
    }

    /**
     * "Check for updates". The dashboard asks the update source directly (seconds, and it names
     * the commits); without it, the plugin fetches the refs in the background (~70 s on a big
     * checkout) and [pollTick] re-reads until it is done.
     */
    fun checkNow() {
        val b = _board.value
        if (b?.checking == true) return
        scope.launch {
            if (rest() != null && b?.checkMissing != true) {
                _board.value = (b ?: Board()).copy(checking = true)
                // The plugin's count is the one that survives a dashboard "count unknown" (2.19):
                // have it fetch fresh refs too, and stay "Checking…" until it has.
                val plugin = b?.plugin?.takeIf { it.supported }
                val fetching = plugin != null &&
                    linkRead("/keryx/update/check", "POST", buildJsonObject { }) is Routed.Ok
                readAll(forceCheck = true)
                if (fetching) {
                    _board.value = _board.value?.copy(checking = true)
                } else {
                    _board.value = _board.value?.copy(checking = false)
                    announceCheck()
                }
                return@launch
            }
            // The /keryx handler 400s a POST without a JSON body — an empty object is the body.
            when (val res = linkRead("/keryx/update/check", "POST", buildJsonObject { })) {
                is Routed.Ok -> _board.value = (_board.value ?: Board()).copy(checking = true)
                is Routed.Failed -> toast("Check refused: ${res.message.take(80)}")
                Routed.Missing -> toast("This gateway can't check for updates from the phone.")
            }
        }
    }

    /** A finished check says what it found: tapping the button used to change nothing visible
     *  when the answer was the same as before, which read as "the button doesn't work". */
    private fun announceCheck() {
        val b = _board.value ?: return
        // On the card itself: a toast raised from the Hub never showed (device, 10-09).
        _board.value = b.copy(checkedAtMs = System.currentTimeMillis())
        toast("Checked: " + UpdatePlanner.behindLine(UpdatePlanner.behind(b.plugin, b.check)).replaceFirstChar { it.lowercase() })
    }

    // --- the update -----------------------------------------------------------------------

    /**
     * Start [plan]. The baseline (the newest receipt's stamp, the checkout's head) is taken
     * from the board the confirm was read off, so the watch can tell this run's receipt from
     * the last one without trusting the phone's clock against the server's.
     */
    fun start(plan: UpdatePlan, nowMs: Long = System.currentTimeMillis()) {
        if (_run.value?.phase.let { it != null && it !is UpdatePhase.Finished && it != UpdatePhase.Silent }) return
        val b = _board.value
        val run = UpdateRun(
            plan = plan,
            startedAtMs = nowMs,
            baselineReceipt = b?.receipt?.startedAt,
            baselineHead = b?.plugin?.head?.takeIf { it.isNotBlank() },
        )
        _run.value = RunView(run, UpdatePhase.Working(emptyList(), restarting = false), emptyList())
        scope.launch {
            // The baseline again, fresh, before anything starts: a board whose receipt read had
            // failed would otherwise let the LAST update's receipt pass for this one's verdict.
            val fresh = rest()?.updateReceipt()
            val baselined = if (fresh is Routed.Ok) run.copy(baselineReceipt = fresh.value?.startedAt) else run
            _run.value = _run.value?.copy(run = baselined)
            val refusal: String? = when (plan.route) {
                UpdateRoute.DASHBOARD -> when (val res = rest()?.updateStart() ?: Routed.Missing) {
                    is Routed.Ok -> if (res.value.ok) null else res.value.message.ifBlank { "Hermes refused to update." }
                    is Routed.Failed -> res.message
                    Routed.Missing -> "This gateway can't update from the phone."
                }
                UpdateRoute.PLUGIN -> when (val res = linkRead("/keryx/update", "POST", buildJsonObject { })) {
                    is Routed.Ok -> null
                    // 409 = one was started under ten minutes ago; 501 = no command configured.
                    is Routed.Failed -> res.message
                    Routed.Missing -> "This gateway's plugin can't run an update."
                }
            }
            if (refusal != null) {
                _run.value = RunView(baselined, UpdatePhase.Finished(UpdateOutcome.REFUSED, refusal, null), emptyList())
            }
        }
    }

    /** Clear a finished run off the panel. */
    fun dismissRun() {
        val p = _run.value?.phase
        if (p is UpdatePhase.Finished || p == UpdatePhase.Silent) _run.value = null
    }

    /** Whether the panel's on-screen loop has anything to read. */
    fun needsPoll(): Boolean {
        val p = _run.value?.phase
        val running = p != null && p !is UpdatePhase.Finished && p != UpdatePhase.Silent
        val b = _board.value
        return running || b?.checking == true || b?.probing == true
    }

    /** One look at whatever is in flight. Called by the panel's on-screen loop only. */
    suspend fun pollTick(nowMs: Long = System.currentTimeMillis()) {
        val view = _run.value
        val inRun = view != null && view.phase !is UpdatePhase.Finished && view.phase != UpdatePhase.Silent
        val b = _board.value
        if (!inRun && b?.checking != true && b?.probing != true) return

        val r = rest()
        val plugin = readPlugin()
        plugin.valueOrNull?.let { p ->
            _board.value = _board.value?.copy(
                plugin = p,
                checking = (_board.value?.checking == true) && p.checking,
                probing = (_board.value?.probing == true) && p.probeRunning,
            )
        }
        if (b?.checking == true && plugin.valueOrNull?.checking == false) {
            val err = plugin.valueOrNull?.checkError
            if (!err.isNullOrBlank()) toast("Check failed: ${err.take(80)}") else announceCheck()
        }
        if (!inRun || view == null) return

        val action: Routed<UpdateActionStatus> =
            if (view.run.plan.route == UpdateRoute.DASHBOARD && r != null) r.updateAction() else Routed.Missing
        val receipt: Routed<UpdateReceipt?> = r?.updateReceipt() ?: Routed.Missing
        // Down = no server answered at all — a refusal with a status is still an answer.
        // Mid-update that silence is the restart, not a failure.
        val reachable = listOf(plugin, receipt, action).any { it is Routed.Ok || (it is Routed.Failed && it.status != 0) }
        val obs = UpdateObservation(
            nowMs = nowMs,
            reachable = reachable,
            action = action.valueOrNull,
            receipt = receipt.valueOrNull ?: action.valueOrNull?.receipt,
            pluginHead = plugin.valueOrNull?.head?.takeIf { it.isNotBlank() },
        )
        val phase = UpdateWatch.phase(view.run, obs, view.lines)
        val lines = (phase as? UpdatePhase.Working)?.lines ?: view.lines
        _run.value = view.copy(phase = phase, lines = lines)
        if (phase is UpdatePhase.Finished || phase == UpdatePhase.Silent) {
            // The verdict is in: re-read the board so version, head and the receipt card agree.
            readAll(forceCheck = false)
        }
    }
}
