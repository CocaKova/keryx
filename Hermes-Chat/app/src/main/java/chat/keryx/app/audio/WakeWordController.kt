package chat.keryx.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.core.model.LinkState
import chat.keryx.core.model.WakeDetection
import chat.keryx.core.model.WakePolicy
import chat.keryx.core.model.WakeProtocol
import chat.keryx.core.model.WakeReason
import chat.keryx.core.model.WakeReconcile
import chat.keryx.core.model.WakeReconcileAction
import chat.keryx.core.model.WakeStartResult
import chat.keryx.core.model.WakeStatus
import chat.keryx.core.model.WakeStopResult
import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.app.notify.WakeEarService
import chat.keryx.app.util.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The ear (Phase F, harvested from Talaria's D4): "Hey Hermes" on the phone. Process-scoped like
 * the socket it rides — DIRECT DOOR ONLY: the lease is bound to the gateway WebSocket, and the
 * summon opens the Call, which the Matrix door has no gateway STT/TTS for.
 *
 * Shape (desktop `store/wake-word.ts`, transposed): the GATEWAY owns the detector and a
 * single-owner mic lease keyed to our transport; this class is the phone's cache of that
 * truth plus the two things only the phone can do — keep a microphone open ([WakeFeeder]
 * inside a mic-type foreground service, [WakeEarService]) and turn a `wake.detected` into a
 * chime + the Call. Every `wake.*` response we see refreshes [ui].
 *
 * Consent is layered on purpose: [SettingsRepository.wakeWordEnabled] is THIS device's opt-in
 * (a mic that never closes is a battery and privacy decision per phone), and the gateway's
 * `wake_word.enabled` is flipped with `persist:true` on the same gesture so `hermes` on the
 * box agrees. Passive paths (reconnect, post-call re-arm) never pass `persist`.
 *
 * Lease etiquette: the ear yields the mic to the Call ([pauseForVoice]) and reconciles after
 * ([resumeAfterVoice]) exactly like desktop's `resumeWakeAfterVoice` — resume, then verify
 * against `wake.status` with spaced retries, because the server pauses itself on detection
 * and a lost race leaves the ear silently off until the user re-toggles.
 */
class WakeWordController(
    private val context: Context,
    private val transport: DirectTransport,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean,
) {
    data class Ui(
        /** This device's opt-in (the Settings switch). */
        val enabled: Boolean = false,
        /** Armed on the gateway, owned by this socket, and our mic is feeding it. */
        val listening: Boolean = false,
        /** An arm/stop is in flight (first arm may lazy-install the engine). */
        val pending: Boolean = false,
        /** Why we are not listening, in words — empty while listening. */
        val notice: String = "",
        val phrase: String = "hey hermes",
        /** Enabled but deliberately idle by battery policy (unplugged / mobile data / idle). */
        val resting: Boolean = false,
        val policy: WakePolicy = WakePolicy(),
        /** The user's mode preference (on-device vs stream to the gateway). */
        val onDevicePreferred: Boolean = true,
        /** The mode actually in effect while listening: true = nothing leaves the phone. */
        val onDevice: Boolean = false,
    )

    /** A detection the UI has not acted on yet. [nonce] guards config-change replays. */
    data class Summon(val nonce: Long, val detection: WakeDetection)

    private val _ui = MutableStateFlow(
        Ui(enabled = settings.wakeWordEnabled, policy = policyFromSettings(), onDevicePreferred = settings.wakeOnDevice),
    )
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _summon = MutableStateFlow<Summon?>(null)
    /** Pending detection for the chat surface to open the Call on; null once consumed. */
    val summon: StateFlow<Summon?> = _summon.asStateFlow()

    private val leaseMutex = Mutex()
    @Volatile private var feeder: WakeFeeder? = null
    /** The zero-network ear; built on first use (loads ~2.6 MB of models), kept for the process. */
    @Volatile private var local: LocalWakeDetector? = null
    private fun localDetector(): LocalWakeDetector? {
        local?.let { return it }
        val d = LocalWakeDetector(
            context,
            onWake = { onDetected(WakeDetection(phrase = _ui.value.phrase, profile = null, startNewSession = true)) },
            onFatal = { why -> stopFeeder(); _ui.update { it.copy(listening = false, notice = why) } },
        )
        if (!d.available) { d.close(); return null }
        local = d
        return d
    }
    /** Local mode wanted AND possible on this device. Decided per arm, cached in [Ui.onDevice]. */
    private fun useLocal(): Boolean = _ui.value.onDevicePreferred && localDetector() != null
    private var summonTimeout: Job? = null
    private var connected = false
    /** The Call holds the mic; reconnects must not re-arm underneath it. */
    @Volatile private var voiceActive = false
    /** The consent gesture happened while the socket was down: the NEXT arm must carry
     *  `persist:true`, or the gateway keeps answering `disabled` to a switch that reads on. */
    @Volatile private var persistOwed = false
    /** Last moment a human was provably around the ear (gesture, detection, app visible). */
    @Volatile private var lastActivityAt = System.currentTimeMillis()
    private var idleWatch: Job? = null

    init {
        scope.launch {
            transport.linkState().collect { st ->
                val now = st == LinkState.CONNECTED
                if (now == connected) return@collect
                connected = now
                if (now) onGatewayReady() else onLinkLost()
            }
        }
        scope.launch { transport.wakeDetections.collect { onDetected(it) } }
        watchPolicyFacts()
    }

    // ---- battery policy ---------------------------------------------------------------

    private fun policyFromSettings() = WakePolicy(
        onlyWhileCharging = settings.wakeOnlyWhileCharging,
        notOnCellular = settings.wakeNotOnCellular,
        idleHours = settings.wakeIdleHours,
    )

    fun setPolicy(policy: WakePolicy) {
        settings.wakeOnlyWhileCharging = policy.onlyWhileCharging
        settings.wakeNotOnCellular = policy.notOnCellular
        settings.wakeIdleHours = policy.idleHours
        _ui.update { it.copy(policy = policy) }
        touch()
        reevaluate()
    }

    private fun touch() { lastActivityAt = System.currentTimeMillis() }

    private fun facts(): WakePolicy.Facts {
        val charging = runCatching {
            val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }.getOrDefault(true)
        val cellular = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            // A VPN (Tailscale) carries its underlying transport bits since Android 10, so
            // "cellular under the VPN" still reads as cellular here.
            caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        }.getOrDefault(false)
        return WakePolicy.Facts(charging = charging, cellular = cellular, idleMs = System.currentTimeMillis() - lastActivityAt)
    }

    /** Charger / network / idle changed: rest or wake the ear to match the policy. */
    private fun reevaluate() {
        if (!_ui.value.enabled) return
        val reason = _ui.value.policy.restReason(facts())
        val ui = _ui.value
        if (reason != null && !ui.resting) {
            scope.launch { rest(reason) }
        } else if (reason == null && ui.resting && connected && !voiceActive) {
            scope.launch { arm(persist = false) }
        } else if (reason != null && ui.resting && ui.notice != reason) {
            _ui.update { it.copy(notice = reason) }
            WakeEarService.update(context, ui.phrase, listening = false, detail = reason)
        }
    }

    private suspend fun rest(reason: String) = leaseMutex.withLock {
        val wasLocal = _ui.value.onDevice
        stopFeeder()
        // Release the server lease too: a paused-but-owned detector holds the gateway's mic
        // slot for nothing. Never persist — this is policy, not a gesture.
        if (!wasLocal) runCatching { transport.gatewayRequest("wake.stop") }
        _ui.update { it.copy(listening = false, pending = false, resting = true, notice = reason) }
        WakeEarService.update(context, _ui.value.phrase, listening = false, detail = reason)
    }

    private fun watchPolicyFacts() {
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) { reevaluate() }
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) { reevaluate() }
                override fun onLost(n: android.net.Network) { reevaluate() }
            })
        }
        idleWatch = scope.launch {
            while (true) {
                delay(60_000)
                if (_ui.value.enabled && _ui.value.policy.idleHours > 0) reevaluate()
            }
        }
    }

    // ---- gestures ---------------------------------------------------------------------

    /** The Settings switch. `on` needs RECORD_AUDIO already granted (the UI asks first). */
    fun setEnabled(on: Boolean) {
        settings.wakeWordEnabled = on
        persistOwed = on
        touch()
        _ui.update { it.copy(enabled = on, notice = "", resting = false) }
        scope.launch {
            if (on) {
                if (!hasMicPermission()) {
                    settings.wakeWordEnabled = false
                    _ui.update { it.copy(enabled = false, notice = "microphone permission needed") }
                    return@launch
                }
                // Bring the service up NOW, on the gesture (the app is visible): a later
                // "plug in → wake" from the background could not start it (Android 12+).
                WakeEarService.start(context, _ui.value.phrase)
                arm(persist = true)
            } else {
                disarm(persist = true)
            }
        }
    }

    /** Mode switch (Settings): stop whatever is running, re-arm in the new mode. */
    fun setOnDevice(on: Boolean) {
        settings.wakeOnDevice = on
        _ui.update { it.copy(onDevicePreferred = on) }
        if (!_ui.value.enabled) return
        scope.launch {
            disarm(persist = false)
            _ui.update { it.copy(enabled = true) } // disarm() doesn't clear it, but be explicit
            if (connected || on) arm(persist = false)
        }
    }

    /** The app came to the foreground: the one moment Android lets a mic service start.
     *  If the user opted in but the ear isn't up (process was restarted), bring it up. */
    fun appVisible() {
        touch() // a human is here — idle clock restarts
        if (!_ui.value.enabled || _ui.value.listening || _ui.value.pending || voiceActive) return
        if (connected || _ui.value.onDevicePreferred) scope.launch { arm(persist = false) }
    }

    /** The Call is opening its own AudioRecord: give up the mic and pause the server lease. */
    fun pauseForVoice() {
        voiceActive = true
        summonTimeout?.cancel()
        _summon.value = null
        WakeEarService.clearHeard(context)
        stopFeeder()
        _ui.update { it.copy(listening = false, notice = if (it.enabled) "paused for the call" else "") }
        if (!_ui.value.onDevice) scope.launch {
            runCatching { transport.gatewayRequest("wake.pause") }
        }
    }

    /** The Call ended: land the ear back where config says it belongs (desktop reconcile). */
    fun resumeAfterVoice() {
        voiceActive = false
        touch()
        if (!_ui.value.enabled) return
        _ui.value.policy.restReason(facts())?.let { reason -> scope.launch { rest(reason) }; return }
        if (_ui.value.onDevice) { scope.launch { arm(persist = false) }; return }
        scope.launch {
            runCatching { transport.gatewayRequest("wake.resume") }
                .onFailure { return@launch } // no socket — gateway.ready re-arms later
            repeat(3) {
                if (!_ui.value.enabled || voiceActive) return@launch
                val decided = runCatching { reconcileOnce() }.getOrDefault(false)
                if (decided) return@launch
                delay(1_500)
            }
        }
    }

    /** The UI took the summon. The TTL keeps running: "taken" is not "the Call opened" (the
     *  chat surface may not be composed) — only [pauseForVoice] settles it. */
    fun consumeSummon(nonce: Long) {
        _summon.update { if (it?.nonce == nonce) null else it }
    }

    // ---- gateway lifecycle ------------------------------------------------------------

    private fun onGatewayReady() {
        // A fresh socket = a fresh transport = the server's lease (if any) belonged to a
        // transport that is now dead. Re-arm from scratch; never persist on this path.
        if (!_ui.value.enabled || voiceActive) return
        if (_ui.value.onDevice && _ui.value.listening) return // the phone never stopped listening
        scope.launch { arm(persist = false) }
    }

    private fun onLinkLost() {
        // On-device mode needs no gateway to HEAR; it needs one to answer, and the Call says
        // so itself. Keep listening.
        if (_ui.value.onDevice && _ui.value.listening) return
        stopFeeder()
        _ui.update { it.copy(listening = false, notice = if (it.enabled) "waiting for the gateway" else "") }
    }

    private fun onDetected(d: WakeDetection) {
        KLog.i(TAG) { "wake.detected phrase=${d.phrase} profile=${d.profile} new=${d.startNewSession}" }
        touch()
        // The server pauses its detector on detection; free OUR mic before the Call opens
        // hers (two AudioRecords rarely coexist). Chime BEFORE capture starts.
        stopFeeder()
        _ui.update { it.copy(listening = false, notice = "heard you") }
        WakeChime.play()
        val nonce = System.nanoTime()
        _summon.value = Summon(nonce, d)
        if (!isForeground()) WakeEarService.notifyHeard(context, d.phrase)
        // Nobody picked it up (phone face-down, notification ignored): don't leave the ear
        // paused forever — put it back exactly like an ended call would.
        summonTimeout?.cancel()
        summonTimeout = scope.launch {
            delay(SUMMON_TTL_MS)
            _summon.update { if (it?.nonce == nonce) null else it }
            WakeEarService.clearHeard(context)
            if (!voiceActive) resumeAfterVoice()
        }
    }

    // ---- lease operations (serialised) --------------------------------------------------

    private suspend fun arm(persist: Boolean) = leaseMutex.withLock {
        if (!_ui.value.enabled) return
        val persistNow = persist || persistOwed
        // Battery policy before anything touches the mic or the radio. A gesture-arm still
        // persists consent server-side below only if we actually go on to arm; while resting
        // the switch reads on and the notice says why nothing is happening.
        _ui.value.policy.restReason(facts())?.let { reason ->
            _ui.update { it.copy(pending = false, listening = false, resting = true, notice = reason) }
            WakeEarService.update(context, _ui.value.phrase, listening = false, detail = reason)
            return
        }
        _ui.update { it.copy(resting = false) }
        if (useLocal()) {
            persistOwed = false // no gateway lease in this mode; nothing to persist
            attachLocal()
            return
        }
        _ui.update { it.copy(onDevice = false) }
        _ui.update {
            it.copy(pending = true, notice = if (persistNow) "arming — first use may take a minute while the gateway installs the engine" else it.notice)
        }
        try {
            // Status first (desktop `armWakeWord`): learn phrase/availability even if arming
            // is refused, and if the lease is somehow already ours, just reattach the feed.
            val status = runCatching { WakeStatus.from(transport.gatewayRequest("wake.status", surfaceParams())) }.getOrNull()
            if (status != null) {
                status.phrase?.let { p -> _ui.update { it.copy(phrase = p) } }
                if (status.listening && status.ownedByCaller && status.clientCapture) {
                    attachFeeder(status.frameLength)
                    return
                }
                if (!status.available) {
                    _ui.update { it.copy(pending = false, listening = false, notice = WakeReason.text("unavailable", status.hint)) }
                    return
                }
            }
            val res = WakeStartResult.from(
                transport.gatewayRequest(
                    "wake.start",
                    surfaceParams { if (persistNow) put("persist", JsonPrimitive(true)) },
                    timeoutMs = WakeProtocol.START_TIMEOUT_MS,
                ),
            )
            persistOwed = false // the gateway heard the gesture (whatever it answered)
            applyStart(res)
        } catch (e: Exception) {
            _ui.update { it.copy(pending = false, listening = false, notice = e.message?.take(120) ?: "gateway unreachable") }
        }
    }

    private fun applyStart(res: WakeStartResult) {
        res.phrase?.let { p -> _ui.update { it.copy(phrase = p) } }
        // The switch went off while wake.start was in flight (engine install can take a
        // minute): the queued disarm will release the lease — don't open the mic first.
        if (!_ui.value.enabled) { _ui.update { it.copy(pending = false, listening = false) }; return }
        if (!res.started) {
            stopFeeder()
            _ui.update { it.copy(pending = false, listening = false, notice = WakeReason.text(res.reason, res.hint)) }
            return
        }
        if (!res.clientCapture) {
            // The gateway armed its OWN microphone (capture: local — a box with a mic and
            // `wake_word.capture: local`). Nothing for the phone to feed; say so honestly.
            _ui.update { it.copy(pending = false, listening = false, notice = "the gateway is listening with its own microphone (capture: local)") }
            return
        }
        attachFeeder(res.frameLength)
    }

    private fun attachFeeder(frameLength: Int) {
        if (!hasMicPermission()) {
            _ui.update { it.copy(pending = false, listening = false, notice = "microphone permission needed") }
            return
        }
        if (!WakeEarService.start(context, _ui.value.phrase)) {
            // Android 14+: a microphone service can only start while the app is visible.
            _ui.update { it.copy(pending = false, listening = false, notice = "open Keryx once to start the ear") }
            return
        }
        stopFeeder()
        val f = WakeFeeder(
            frameLength = frameLength,
            request = { m, p -> transport.gatewayRequest(m, p, timeoutMs = 15_000) },
            onFatal = { why ->
                stopFeeder()
                _ui.update { it.copy(listening = false, notice = why) }
            },
        )
        feeder = f
        if (f.start()) {
            _ui.update { it.copy(pending = false, listening = true, notice = "") }
            WakeEarService.update(context, _ui.value.phrase, listening = true, detail = null)
        } else {
            feeder = null
            _ui.update { it.copy(pending = false, listening = false) }
        }
    }

    private suspend fun disarm(persist: Boolean) = leaseMutex.withLock {
        val wasLocal = _ui.value.onDevice
        stopFeeder()
        _ui.update { it.copy(pending = true) }
        // A persist:true stop still goes out from local mode when the gateway had been told
        // "enabled" by an earlier streaming-mode gesture — cheap, and keeps both switches honest.
        val res = if (wasLocal && !persist) null else runCatching {
            WakeStopResult.from(
                transport.gatewayRequest("wake.stop", buildJsonObject { if (persist) put("persist", JsonPrimitive(true)) }),
            )
        }.getOrNull()
        _ui.update { it.copy(pending = false, listening = false, resting = false, onDevice = false, notice = if (res == null || res.stopped) "" else WakeReason.text(res.reason, null)) }
        WakeEarService.stop(context)
    }

    /** One desktop-style reconcile pass. True when the ear reached a rest state (armed, or
     *  correctly off); false = transient, worth another try. */
    private suspend fun reconcileOnce(): Boolean {
        val status = WakeStatus.from(transport.gatewayRequest("wake.status", surfaceParams()))
        status.phrase?.let { p -> _ui.update { it.copy(phrase = p) } }
        return when (WakeReconcile.decide(status)) {
            WakeReconcileAction.REST_OFF -> {
                _ui.update { it.copy(listening = false, notice = WakeReason.text(if (!status.enabled) "disabled" else "unavailable", status.hint)) }
                true
            }
            WakeReconcileAction.REATTACH_FEED -> {
                if (status.clientCapture) attachFeeder(status.frameLength)
                _ui.value.listening
            }
            WakeReconcileAction.ARM -> {
                val res = WakeStartResult.from(
                    transport.gatewayRequest("wake.start", surfaceParams(), timeoutMs = WakeProtocol.START_TIMEOUT_MS),
                )
                applyStart(res)
                // `owned` = another surface holds the mic lease — theirs to keep, stop trying.
                res.started || res.reason == "owned"
            }
        }
    }

    private fun stopFeeder() {
        feeder?.stop()
        feeder = null
        local?.stop()
    }

    /** Zero-network mode: mic → LiteRT on the phone. Same service, same notification. */
    private fun attachLocal() {
        if (!hasMicPermission()) {
            _ui.update { it.copy(pending = false, listening = false, notice = "microphone permission needed") }
            return
        }
        if (!WakeEarService.start(context, _ui.value.phrase)) {
            _ui.update { it.copy(pending = false, listening = false, notice = "open Keryx once to start the ear") }
            return
        }
        stopFeeder()
        val d = localDetector() ?: run {
            _ui.update { it.copy(pending = false, listening = false, notice = "on-device detector unavailable") }
            return
        }
        if (d.start()) {
            _ui.update { it.copy(pending = false, listening = true, onDevice = true, notice = "") }
            WakeEarService.update(context, _ui.value.phrase, listening = true, detail = "on this phone — nothing streams until you say it")
        } else {
            _ui.update { it.copy(pending = false, listening = false) }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private inline fun surfaceParams(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            // "gui" = the desktop-remote surface class: prefers client capture and shares the
            // `wake_word.surface` scoping desktop gets. Keryx IS a remote GUI.
            put("surface", JsonPrimitive("gui"))
            put("client_capture", JsonPrimitive(true))
            extra()
        }

    private companion object {
        const val TAG = "KeryxWake"
        const val SUMMON_TTL_MS = 60_000L
    }
}
