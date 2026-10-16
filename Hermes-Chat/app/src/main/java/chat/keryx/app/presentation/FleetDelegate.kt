package chat.keryx.app.presentation

import chat.keryx.app.domain.repository.SettingsRepository
import chat.keryx.core.transport.ChatTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The doors in (split out of [ChatViewModel] in 3.0): the fleet (2.11) — every gateway this
 * phone can reach, by name — and signing in through either door, the gateway or Matrix. A
 * door or gateway change takes effect on relaunch; the caller relaunches on our answer.
 */
class FleetDelegate(
    deps: GatewayDeps,
    private val transport: ChatTransport,
    /** What to do with a removed gateway's files on disk (KeryxApp owns the layout). */
    private val purgeGatewayFiles: (String) -> Unit,
    /** A paired Link was saved and this process carries on (no relaunch): the ViewModel reads
     *  it back. Its Link clients are built from what it read at start-up. */
    private val onLinkCommitted: () -> Unit = {},
) {
    private val scope = deps.scope
    private val settings: SettingsRepository = deps.settings
    private val toast = deps.toast
    private val matrix get() = transport.matrix

    private val _fleet = MutableStateFlow(settings.fleet)
    /** The registry. Empty on a Matrix-only phone; one row on a phone that only ever met one gateway. */
    val fleet: StateFlow<chat.keryx.core.model.Fleet> = _fleet.asStateFlow()

    private fun saveFleet(next: chat.keryx.core.model.Fleet, switchPending: Boolean = false) {
        settings.commitFleet(next, switchPending)
        _fleet.value = next
    }

    /**
     * Move the workspace onto another gateway. Everything on screen — sessions, cron, bots,
     * the Hub, the archive — is that gateway's, and the spine was built for the one we are
     * on, so the switch takes effect on relaunch (the door toggle's own path). True when the
     * caller should relaunch; false when there was nothing to do.
     */
    fun switchGateway(id: String): Boolean {
        val f = _fleet.value
        if (f.byId(id) == null || id == f.activeId) return false
        saveFleet(f.setActive(id), switchPending = true)
        return true
    }

    /** Rename a gateway. Null = done; else the exact violation, the Desktop's way. */
    fun renameGateway(id: String, name: String): String? =
        runCatching { saveFleet(_fleet.value.rename(id, name)) }.exceptionOrNull()?.message

    /** "Make primary": the fallback, never the switch. */
    fun setPrimaryGateway(id: String) = saveFleet(_fleet.value.setPrimary(id))

    fun setResumeLastGateway(on: Boolean) = saveFleet(_fleet.value.copy(resumeLastGateway = on))

    /**
     * Take a gateway off the fleet and drop everything the phone held for it — sealed
     * credentials, read-marks, hub cache, archive, transcript cache. The gateway itself is
     * untouched; add it again any time. The ACTIVE gateway cannot be removed: switch first,
     * so the workspace is never left standing on a row that no longer exists.
     */
    fun removeGateway(id: String): String? {
        val f = _fleet.value
        if (f.byId(id) == null) return null
        if (id == f.activeId) return "Switch to another gateway first"
        saveFleet(f.remove(id))
        settings.forgetGateway(id)
        purgeGatewayFiles(id)
        return null
    }

    /**
     * The Desktop's Test: is the gateway reachable, and does the credential we hold for it
     * still open the door? Probes the REST leg unauthenticated, then an authed call with THAT
     * gateway's credential (not the active one's). Reports a sentence, never a stack.
     */
    fun testGateway(id: String, onResult: (ok: Boolean, message: String) -> Unit) {
        val entry = _fleet.value.byId(id) ?: return onResult(false, "Not on the fleet")
        scope.launch {
            val scoped = settings.forGateway(id)
            val insecure = settings.allowInsecure
            val rest = chat.keryx.app.transport.direct.GatewayRest(
                entry.url, scoped.directApiKey, insecure,
                chat.keryx.app.transport.direct.DirectAuth(scoped, insecure),
            )
            val st = rest.status().getOrElse {
                return@launch onResult(false, "Unreachable: ${it.message?.take(120) ?: "no answer"}")
            }
            val version = st.version.takeIf { it.isNotBlank() }?.let { " · hermes $it" }.orEmpty()
            if (!scoped.directLoggedIn) return@launch onResult(true, "Reachable$version — not signed in yet")
            rest.validateToken().fold(
                onSuccess = { onResult(true, "Reachable$version") },
                onFailure = { onResult(false, "Reachable$version, but the sign-in was refused: ${it.message?.take(100)}") },
            )
        }
    }

    /**
     * The login screen's second door (plan §5 Phase 4): a gateway URL and an API key instead
     * of a homeserver. Validates against the gateway, persists the choice, and reports
     * [needsRestart] when the process was booted on the other spine — the transport is not
     * hot-swappable under this ViewModel, so the door change takes effect on relaunch.
     */
    fun loginToGateway(
        url: String,
        apiKey: String,
        /** Fires the system browser at the gateway's sign-in page (gated gateways only). */
        launchBrowser: (String) -> Unit,
        onResult: (ok: Boolean, needsRestart: Boolean, message: String?) -> Unit,
        /** The fleet (2.11): the device name for this gateway. Blank = derived from the host. */
        name: String = "",
        /** A scanned pairing code (3.0): once signed in, its one-time code buys this phone a
         *  Hermes Link token of its own. [url] must be the code's gateway. */
        pair: chat.keryx.core.pair.PairingLink? = null,
        /** What the gateway lists this phone as (`hermes keryx devices`). */
        deviceName: String = "",
    ) {
        scope.launch {
            // The fleet row first: a known URL is its existing row (renamed if a name was
            // typed), a new one is added. The row becomes active so every credential written
            // by the sign-in below lands under ITS id. If the sign-in fails the fleet is put
            // back exactly as it was — a gateway that never answered is not registered.
            val before = _fleet.value
            val (added, entry) = try {
                before.add(url, name, { settings.newGatewayIdFor(before) })
            } catch (e: chat.keryx.core.model.Fleet.RejectedException) {
                onResult(false, false, e.message); return@launch
            }
            val isNew = before.byId(entry.id) == null
            val switched = before.activeId.isNotBlank() && before.activeId != entry.id
            var staged = added.setActive(entry.id)
            if (!isNew && name.isNotBlank() && !entry.name.equals(name.trim(), ignoreCase = true)) {
                staged = try { staged.rename(entry.id, name) } catch (e: chat.keryx.core.model.Fleet.RejectedException) {
                    onResult(false, false, e.message); return@launch
                }
            }
            saveFleet(staged)
            fun failed(message: String?) {
                saveFleet(before)
                if (isNew) settings.forgetGateway(entry.id)
                onResult(false, false, message)
            }
            val onOk: (Boolean, Boolean, String?) -> Unit = { ok, restart, message ->
                if (ok) onResult(true, restart || switched, message) else failed(message)
            }
            if (pair != null && hostOf(entry.url) != pair.host) {
                failed("The address changed after the scan. Scan the pairing code again.")
                return@launch
            }
            val insecure = settings.allowInsecure
            // Pairing (3.0): spent only after the sign-in proved itself, so a phone that can't
            // sign in never uses up the code. A failed claim doesn't undo the sign-in: the gateway
            // works, and the Link can still be keyed by hand.
            suspend fun claimLink(): Pair<Pair<String, String>?, String?> {
                val p = pair ?: return null to null
                return chat.keryx.app.transport.direct.LinkPairing.claim(p, deviceName, insecure).fold(
                    onSuccess = { (p.link to it) to null },
                    onFailure = { null to "Signed in. Hermes Link didn't pair: ${it.message}" },
                )
            }
            val probe = chat.keryx.app.transport.direct.GatewayRest(entry.url, apiKey, insecure)
            val st = probe.status().getOrElse {
                failed(it.message?.take(160) ?: "Gateway unreachable")
                return@launch
            }
            if (!st.authRequired) {
                // Ungated gateway: the legacy token dialect, exactly as before.
                if (pair != null && apiKey.isBlank()) {
                    // The code only pairs the Link; this dashboard wants its session token.
                    failed("This gateway doesn't sign in through the browser. Enter its session token, then Connect.")
                    return@launch
                }
                runCatching { if (apiKey.isNotBlank()) probe.validateToken().getOrThrow() }.fold(
                    onSuccess = {
                        settings.directAuthMode =
                            chat.keryx.app.transport.direct.DirectAuth.MODE_TOKEN
                        val (link, notice) = claimLink()
                        commitDirectDoor(entry.url, apiKey, switched, onOk, link, notice)
                    },
                    onFailure = { failed(it.message?.take(160) ?: "Gateway unreachable") },
                )
                return@launch
            }
            // GATED gateway (hermes ≥0.20.5, auth_required): the legacy token is rejected there
            // by upstream design — sign in the way Hermes Desktop does. RFC 8252: our PKCE pair,
            // the SYSTEM browser on the gateway's /login, a loopback listener catching ?code=,
            // then the code+verifier→token exchange. DirectAuth owns the whole dance.
            val auth = chat.keryx.app.transport.direct.DirectAuth(settings, insecure)
            val pending = auth.beginLogin(entry.url)
            launchBrowser(pending.authorizeUrl)
            runCatching {
                auth.awaitLogin(entry.url, pending)
                settings.directAuthMode =
                    chat.keryx.app.transport.direct.DirectAuth.MODE_NATIVE
                // Prove the minted pair actually authenticates before committing the door
                // (mirrors the token path's validateToken gate).
                chat.keryx.app.transport.direct.GatewayRest(entry.url, "", insecure, auth)
                    .validateToken().getOrThrow()
            }.fold(
                onSuccess = {
                    val (link, notice) = claimLink()
                    commitDirectDoor(entry.url, null, switched, onOk, link, notice)
                },
                onFailure = { failed(it.message?.take(160) ?: "Browser sign-in failed") },
            )
        }
    }

    private suspend fun commitDirectDoor(
        url: String,
        apiKey: String?,
        /** The sign-in landed on a gateway other than the one this process was built for. */
        switched: Boolean,
        onResult: (Boolean, Boolean, String?) -> Unit,
        /** A paired Link (3.0): its address and this phone's own token. */
        link: Pair<String, String>? = null,
        /** Said once the door is open (a pairing that didn't take). */
        notice: String? = null,
    ) {
        // One synchronous commit: the caller relaunches the process on our answer,
        // and apply()'s async disk write loses that race (device-caught).
        settings.commitTransportDoor(url, apiKey, "direct", true)
        // After the door commit: the Link's keys are the active gateway's, and only the direct
        // door has one (see SettingsRepositoryImpl.ledgerKey).
        link?.let { (linkUrl, token) -> settings.commitLink(linkUrl, token) }
        val direct = transport as? chat.keryx.app.transport.direct.DirectTransport
        if (direct != null && !switched) {
            // No relaunch to pick the Link up (3.0): without this the pairing took, the device
            // showed in `hermes keryx devices`, and the app went on with the empty Link it
            // started with until its next launch (stock-Hermes pair test, VIRDARA, 10-16).
            if (link != null) onLinkCommitted()
            direct.login()
            notice?.let(toast)
            onResult(true, false, null)
        } else {
            // The relaunch ends this process: the notice waits in prefs for the next one (not in
            // the launch intent, which any app could fill in).
            notice?.let { settings.pendingNotice = it }
            // A door crossing, or a different gateway than the spine was built for (2.11):
            // mark the relaunch as a switch so the boot lands on this row whatever the
            // start-up rule says.
            settings.commitFleet(_fleet.value, switchPending = true)
            onResult(true, true, null)
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull().orEmpty()

    /**
     * The transport TOGGLE: flip the spine for the next process life, keeping BOTH credential
     * sets — the Matrix session stays in Trixnity's store, the sealed direct token stays in
     * prefs. Not a logout; whichever door comes up resumes what it holds (or lands on its own
     * login pane if it holds nothing). The caller relaunches.
     */
    fun switchTransport(mode: String) {
        settings.commitTransportMode(mode)
    }

    /**
     * Step into the demo (3.0 first run): the next process life is the demo gateway, opening on
     * its first chat. Its ledgers are its own (see SettingsRepositoryImpl), so nothing a real
     * door holds is touched. The caller relaunches.
     */
    fun enterDemo() {
        settings.commitTransportMode(chat.keryx.app.transport.demo.DemoTransport.MODE)
        // Written after the switch, so it lands in the demo's ledger.
        settings.lastRoomId = chat.keryx.core.demo.DemoScript.FIRST
    }

    /** A phone that has never had a door: no gateway, never a Matrix sign-in, and the welcome
     *  not yet answered. The login screen opens on the welcome instead of a form. (Not the
     *  Matrix store on disk: the Matrix door makes an empty one at every boot.) */
    val firstRun: Boolean
        get() = !settings.welcomeSeen && settings.fleet.gateways.isEmpty() &&
            settings.lastMatrixUsername.isBlank() && settings.directGatewayUrl.isBlank()

    fun answerWelcome() {
        settings.welcomeSeen = true
    }

    /** Leave the demo for the sign-in, on the gateway door. The caller relaunches. */
    fun leaveDemo() {
        settings.commitTransportMode("direct")
    }

    /** The other door's stored state — the toggle's "resumes signed in" hints. */
    val matrixSessionOnFile: Boolean get() = settings.matrixSessionOnFile
    val directCredentialsOnFile: Boolean
        get() = settings.directLoggedIn && settings.directGatewayUrl.isNotBlank()

    /** Login-form prefill — the durable Matrix credential is the session, never the password. */
    val lastMatrixUsername: String get() = settings.lastMatrixUsername

    fun loginToMatrix(username: String, password: String, onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            val result = matrix?.login(username, password)
                ?: Result.failure(IllegalStateException("This transport has no Matrix login"))
            result.fold(
                onSuccess = {
                    settings.lastMatrixUsername = username
                    onResult(true, "Logged in")
                },
                onFailure = { onResult(false, it.message ?: "Login failed. Check URL or credentials.") },
            )
        }
    }
}
