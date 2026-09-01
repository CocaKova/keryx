package chat.keryx.app.presentation

import chat.keryx.core.model.ShipyardCommitContext
import chat.keryx.core.model.ShipyardDiff
import chat.keryx.core.model.ShipyardRepo
import chat.keryx.core.model.ShipyardReview
import chat.keryx.core.model.ShipyardShipInfo
import chat.keryx.core.model.ShipyardStatus
import chat.keryx.app.data.remote.ShipyardRest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Shipyard — review the agent's work and ship it, from the phone (roadmap §2). One repo
 * open at a time; every mutation re-reads status + review so the screen never shows a stale
 * tree. The door exists only where the gateway declares `git` (see `ReasoningCaps.git`);
 * a 403 `shipyard_off` here means the gateway was switched off underneath us — surfaced
 * as the error line, never a crash.
 */
class ShipyardDelegate(
    deps: GatewayDeps,
) {
    private val scope = deps.scope
    private val toast = deps.toast
    private val settings = deps.settings

    // The Shipyard rides Hermes Link on BOTH doors — the direct door's REST base never
    // mounts the git routes, and Matrix has no gateway seam at all (the 2.6.0 walk found
    // both, 08-31). One client per configured (url, key); rebuilt only when Link changes.
    private var restCache: Pair<String, ShipyardRest>? = null
    private val gateway: ShipyardRest?
        get() {
            val url = settings.gatewayUrl.trim()
            if (!settings.sideChannelEnabled || url.isBlank()) return null
            val fingerprint = url + "\u0000" + settings.gatewayApiKey
            restCache?.let { (fp, rest) -> if (fp == fingerprint) return rest }
            return ShipyardRest(url, settings.gatewayApiKey, settings.allowInsecure)
                .also { restCache = fingerprint to it }
        }

    private val _repos = MutableStateFlow<List<ShipyardRepo>>(emptyList())
    val repos: StateFlow<List<ShipyardRepo>> = _repos.asStateFlow()

    private val _repo = MutableStateFlow<ShipyardRepo?>(null)
    val openRepo: StateFlow<ShipyardRepo?> = _repo.asStateFlow()

    private val _status = MutableStateFlow<ShipyardStatus?>(null)
    val status: StateFlow<ShipyardStatus?> = _status.asStateFlow()

    private val _scope = MutableStateFlow("uncommitted")
    val reviewScope: StateFlow<String> = _scope.asStateFlow()

    private val _review = MutableStateFlow<ShipyardReview?>(null)
    val review: StateFlow<ShipyardReview?> = _review.asStateFlow()

    private val _diff = MutableStateFlow<ShipyardDiff?>(null)
    val diff: StateFlow<ShipyardDiff?> = _diff.asStateFlow()

    private val _openFile = MutableStateFlow<String?>(null)
    val openFile: StateFlow<String?> = _openFile.asStateFlow()

    private val _commitContext = MutableStateFlow<ShipyardCommitContext?>(null)
    val commitContext: StateFlow<ShipyardCommitContext?> = _commitContext.asStateFlow()

    private val _shipInfo = MutableStateFlow<ShipyardShipInfo?>(null)
    val shipInfo: StateFlow<ShipyardShipInfo?> = _shipInfo.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refreshRepos() {
        val gw = gateway ?: return
        scope.launch {
            gw.shipyardRepos()
                .onSuccess { _repos.value = it; _error.value = null }
                .onFailure { _error.value = friendly(it) }
        }
    }

    fun openRepo(repo: ShipyardRepo) {
        _repo.value = repo
        _status.value = null; _review.value = null; _diff.value = null
        _openFile.value = null; _shipInfo.value = null; _commitContext.value = null
        refreshTree()
        val gw = gateway ?: return
        scope.launch { gw.shipyardShipInfo(repo.path).onSuccess { _shipInfo.value = it } }
    }

    fun closeRepo() {
        _repo.value = null
        _status.value = null; _review.value = null; _diff.value = null; _openFile.value = null
    }

    fun setScope(scope: String) {
        if (_scope.value == scope) return
        _scope.value = scope
        _diff.value = null; _openFile.value = null
        refreshTree()
    }

    /** Status + the file list, together — the two things every mutation invalidates. */
    fun refreshTree() {
        val gw = gateway ?: return
        val repo = _repo.value ?: return
        scope.launch {
            gw.shipyardStatus(repo.path)
                .onSuccess { _status.value = it; _error.value = null }
                .onFailure { _error.value = friendly(it) }
            gw.shipyardReview(repo.path, _scope.value)
                .onSuccess { _review.value = it }
                .onFailure { _error.value = friendly(it) }
        }
    }

    fun openFile(path: String, staged: Boolean) {
        val gw = gateway ?: return
        val repo = _repo.value ?: return
        _openFile.value = path
        _diff.value = null
        scope.launch {
            gw.shipyardDiff(repo.path, path, _scope.value, staged)
                .onSuccess { _diff.value = it }
                .onFailure { _error.value = friendly(it) }
        }
    }

    fun closeFile() { _openFile.value = null; _diff.value = null }

    /** [file] null = everything in the tree. */
    fun stage(file: String?, staged: Boolean) = mutate {
        val repo = _repo.value ?: return@mutate
        val gw = gateway ?: return@mutate
        (if (staged) gw.shipyardUnstage(repo.path, file) else gw.shipyardStage(repo.path, file))
            .onFailure { _error.value = friendly(it) }
        refreshTree()
        // Re-read the open diff — staged/unstaged is a different diff.
        _openFile.value?.let { openFile(it, !staged) }
    }

    fun loadCommitContext() {
        val gw = gateway ?: return
        val repo = _repo.value ?: return
        scope.launch { gw.shipyardCommitContext(repo.path).onSuccess { _commitContext.value = it } }
    }

    fun commit(message: String, push: Boolean, done: (ok: Boolean) -> Unit = {}) = mutate {
        val repo = _repo.value ?: return@mutate
        val gw = gateway ?: return@mutate
        gw.shipyardCommit(repo.path, message.trim(), push)
            .onSuccess {
                toast(if (it.pushed) "Committed ${it.sha ?: ""} and pushed" else "Committed ${it.sha ?: ""}")
                _error.value = null
                done(true)
            }
            .onFailure { _error.value = friendly(it); done(false) }
        refreshTree()
        gw.shipyardShipInfo(repo.path).onSuccess { _shipInfo.value = it }
    }

    fun push() = mutate {
        val repo = _repo.value ?: return@mutate
        val gw = gateway ?: return@mutate
        gw.shipyardPush(repo.path)
            .onSuccess { toast("Pushed ${repo.branch ?: ""}".trim()); _error.value = null }
            .onFailure { _error.value = friendly(it) }
        refreshTree()
        gw.shipyardShipInfo(repo.path).onSuccess { _shipInfo.value = it }
    }

    fun clearError() { _error.value = null }

    private fun mutate(block: suspend () -> Unit) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            try { block() } finally { _busy.value = false }
        }
    }

    /** The gateway's error line, shortened; the off-switch gets a sentence of its own. */
    private fun friendly(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            "shipyard_off" in m || "keryx.git is disabled" in m -> "The Shipyard is switched off on the gateway (keryx.git.enabled)."
            m.isBlank() -> "The gateway did not answer."
            else -> m.substringAfter(" — ", m).take(200)
        }
    }
}
