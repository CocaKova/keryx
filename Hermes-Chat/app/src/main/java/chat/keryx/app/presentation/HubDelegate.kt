package chat.keryx.app.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/**
 * The Agent Hub: every gateway console surface — health, jobs, sessions (verbs and pruning),
 * skills (Forge and trash), toolsets, models, brains, the curated config knobs and the raw
 * config editor, plus the reasoning dial and the slash-command registry.
 */
class HubDelegate(deps: GatewayDeps) {
    private val scope = deps.scope
    private val settings = deps.settings
    private val client = deps.client
    private val bareClient = deps.bareClient
    private val toast = deps.toast

    private val _reasoningCaps = MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?>(null)
    val reasoningCaps: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?> = _reasoningCaps.asStateFlow()

    /** Fetch what the active brain supports for /reasoning, so the menu can adapt (binary vs
     *  effort scale, current level, model name). Silent on failure — the menu falls back to the
     *  generic effort list. */
    fun refreshReasoningCaps() {
        val client = bareClient() ?: return
        scope.launch {
            client.capabilities()
                .onSuccess { _reasoningCaps.value = it }
        }
    }


    /** The gateway's live slash-command registry (empty until fetched; palette falls back to
     *  its preset list). Refreshed at most once a minute, when the "/" palette opens. */
    private val _gatewayCommands =
        MutableStateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>>(emptyList())
    val gatewayCommands: StateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>> =
        _gatewayCommands.asStateFlow()
    private var gatewayCommandsFetchedAt = 0L

    fun refreshGatewayCommands() {
        val client = bareClient() ?: return
        val now = System.currentTimeMillis()
        if (_gatewayCommands.value.isNotEmpty() && now - gatewayCommandsFetchedAt < 60_000L) return
        gatewayCommandsFetchedAt = now
        scope.launch {
            client.commands()
                .onSuccess { if (it.isNotEmpty()) _gatewayCommands.value = it }
        }
    }


    // --- Agent Hub — gateway console panels ------------------------------------------------------

    /** One hub panel's fetch state. A failed refresh keeps the last good [data] so the panel
     *  degrades to a stale-but-visible snapshot with the error line on top, never a blank. */
    data class PanelState<T>(
        val data: T? = null,
        val error: String? = null,
        val refreshing: Boolean = false,
    )

    private fun <T> MutableStateFlow<PanelState<T>>.refreshFrom(
        fetch: suspend chat.keryx.app.data.remote.HermesStreamClient.() -> Result<T>,
    ) {
        val client = client() ?: run {
            value = value.copy(error = "Hermes Link is off — enable it in Settings", refreshing = false)
            return
        }
        value = value.copy(refreshing = true)
        scope.launch {
            client.fetch()
                .onSuccess { value = PanelState(data = it) }
                .onFailure {
                    value = value.copy(error = it.message?.take(120) ?: "unavailable", refreshing = false)
                }
        }
    }

    /** A panel seeded from the offline cache: the last gateway answer renders instantly (even
     *  cold-start offline), then the first real refresh replaces it. Parse failures = empty seed. */
    private fun <T> seededPanel(
        path: String,
        parse: (kotlinx.serialization.json.JsonObject) -> T,
    ): MutableStateFlow<PanelState<T>> = MutableStateFlow(
        PanelState(
            data = settings.hubSnapshot(path)?.let { cached ->
                runCatching {
                    parse(kotlinx.serialization.json.Json.parseToJsonElement(cached).jsonObject)
                }.getOrNull()
            },
        ),
    )

    private val _hubHealth = seededPanel("/health/detailed", chat.keryx.app.data.remote.HubJson::health)
    val health: StateFlow<PanelState<chat.keryx.app.data.remote.HermesStreamClient.HubHealth>> =
        _hubHealth.asStateFlow()
    private val _hubJobs = seededPanel("/api/jobs", chat.keryx.app.data.remote.HubJson::jobs)
    val jobs: StateFlow<PanelState<List<chat.keryx.app.data.remote.HermesStreamClient.HubJob>>> =
        _hubJobs.asStateFlow()
    private val _hubSessions = seededPanel("/api/sessions", chat.keryx.app.data.remote.HubJson::sessions)
    val sessions: StateFlow<PanelState<List<chat.keryx.app.data.remote.HermesStreamClient.HubSession>>> =
        _hubSessions.asStateFlow()
    private val _hubSkills = seededPanel("/v1/skills", chat.keryx.app.data.remote.HubJson::skills)
    val skills: StateFlow<PanelState<List<chat.keryx.app.data.remote.HermesStreamClient.HubSkill>>> =
        _hubSkills.asStateFlow()
    private val _hubToolsets = seededPanel("/keryx/toolsets", chat.keryx.app.data.remote.HubJson::toolsets)
    val toolsets: StateFlow<PanelState<chat.keryx.app.data.remote.HermesStreamClient.HubToolsets>> =
        _hubToolsets.asStateFlow()

    private val _hubModels = seededPanel("/v1/models", chat.keryx.app.data.remote.HubJson::models)
    val models: StateFlow<PanelState<List<chat.keryx.app.data.remote.HermesStreamClient.HubModel>>> =
        _hubModels.asStateFlow()
    private val _hubConfig = seededPanel("/keryx/config", chat.keryx.app.data.remote.HubJson::configKnobs)
    val config: StateFlow<PanelState<List<chat.keryx.app.data.remote.HermesStreamClient.ConfigKnob>>> =
        _hubConfig.asStateFlow()
    private val _hubBrains = seededPanel("/keryx/brains", chat.keryx.app.data.remote.HubJson::brains)
    val brains: StateFlow<PanelState<chat.keryx.app.data.remote.HermesStreamClient.Brains>> =
        _hubBrains.asStateFlow()

    // --- The Runs surface (harvest: Talaria's CronSpace — scheduled runs are READ, not chatted) ---

    /** Everything the Runs tab draws: one card per job, live job rows paired by name, and
     *  what's arrived since the user last looked. */
    data class CronBoard(
        val cards: List<chat.keryx.core.model.CronJobCard>,
        val jobsByName: Map<String, chat.keryx.app.data.remote.HermesStreamClient.HubJob>,
        val unread: chat.keryx.core.model.CronUnread,
        /** Runs the session list serves, by id — the reader opens straight from a card. */
        val runsById: Map<String, chat.keryx.app.data.remote.HermesStreamClient.HubSession>,
    )

    private val _cron = MutableStateFlow<PanelState<CronBoard>>(PanelState())
    val cron: StateFlow<PanelState<CronBoard>> = _cron.asStateFlow()

    fun refreshCron() {
        val client = client() ?: run {
            _cron.value = _cron.value.copy(error = "Hermes Link is off — enable it in Settings", refreshing = false)
            return
        }
        _cron.value = _cron.value.copy(refreshing = true)
        scope.launch {
            val jobsRes = client.jobs()
            val runsRes = client.cronSessions()
            val failure = listOf(jobsRes, runsRes).firstNotNullOfOrNull { it.exceptionOrNull() }
            if (failure != null && runsRes.isFailure) {
                _cron.value = _cron.value.copy(error = failure.message?.take(120) ?: "unavailable", refreshing = false)
                return@launch
            }
            val jobs = jobsRes.getOrDefault(emptyList())
            val sessions = runsRes.getOrDefault(emptyList())
            val runs = sessions.map {
                chat.keryx.core.model.CronRun(
                    id = it.id,
                    title = it.title.orEmpty().ifBlank { it.id },
                    timestamp = (it.lastActive * 1000).toLong(),
                )
            }
            val cards = chat.keryx.core.model.CronGrouping.group(runs, jobs.map { it.name })
            // First sight of this surface stamps the baseline: what already existed is history.
            if (settings.cronBaseline <= 0L) {
                settings.cronBaseline = chat.keryx.core.model.CronUnreadCalc.baselineOf(cards)
            }
            val seen = chat.keryx.core.model.CronUnreadCalc.prune(
                settings.cronSeenIds,
                chat.keryx.core.model.CronUnreadCalc.knownIds(cards),
            ).also { if (it.size != settings.cronSeenIds.size) settings.cronSeenIds = it }
            _cron.value = PanelState(
                data = CronBoard(
                    cards = cards,
                    jobsByName = jobs.associateBy { it.name },
                    unread = chat.keryx.core.model.CronUnreadCalc.compute(cards, seen, settings.cronBaseline),
                    runsById = sessions.associateBy { it.id },
                ),
            )
        }
    }

    /** Opening a run IS reading it — recompute the badge without a refetch. */
    fun cronMarkSeen(runId: String) {
        settings.cronSeenIds = settings.cronSeenIds + runId
        recomputeCronUnread()
    }

    fun cronMarkAllSeen() {
        val board = _cron.value.data ?: return
        settings.cronSeenIds = settings.cronSeenIds +
            chat.keryx.core.model.CronUnreadCalc.knownIds(board.cards)
        recomputeCronUnread()
    }

    private fun recomputeCronUnread() {
        val board = _cron.value.data ?: return
        _cron.value = _cron.value.copy(
            data = board.copy(
                unread = chat.keryx.core.model.CronUnreadCalc.compute(
                    board.cards, settings.cronSeenIds, settings.cronBaseline,
                ),
            ),
        )
    }

    /**
     * A run's skimmable headline: its REPORT (the longest assistant text — the last message
     * is usually narration or bookkeeping), digested to (title, lead). Cached per run id —
     * transcripts don't change after the run ends.
     */
    private val cronDigests = java.util.concurrent.ConcurrentHashMap<String, chat.keryx.core.model.CronDigest>()

    suspend fun cronDigest(runId: String): chat.keryx.core.model.CronDigest? {
        cronDigests[runId]?.let { return it }
        val client = client() ?: return null
        return client.sessionMessages(runId).getOrNull()?.let { messages ->
            val report = chat.keryx.core.model.CronHumanize.pickReport(
                messages.filter { it.role == "assistant" }.map { it.content },
            ) ?: return@let chat.keryx.core.model.CronDigest(null, null)
            chat.keryx.core.model.CronHumanize.digest(report)
        }?.also { cronDigests[runId] = it }
    }

    fun refreshHealth() = _hubHealth.refreshFrom { healthDetailed() }
    fun refreshModels() = _hubModels.refreshFrom { models() }
    fun refreshConfig() = _hubConfig.refreshFrom { configKnobs() }
    fun refreshBrains() = _hubBrains.refreshFrom { brains() }

    // --- Gateway Controls (1.21) ------------------------------------------------------------------

    /** Persist one whitelisted knob, then re-pull so the control reflects what the gateway
     *  actually stored. The toast carries the knob's own effect scope ("next turn", …). */
    fun configSet(key: String, value: kotlinx.serialization.json.JsonPrimitive) {
        val client = client() ?: return
        scope.launch {
            client.configSet(key, value)
                .onSuccess { applies -> toast("Saved — applies $applies"); refreshConfig() }
                .onFailure { toast("Change refused: ${it.message?.take(80)}"); refreshConfig() }
        }
    }

    fun reasoningSet(level: String) {
        val client = client() ?: return
        scope.launch {
            client.reasoningSet(level)
                .onSuccess { toast("Reasoning → $level (next session)"); refreshReasoningCaps() }
                .onFailure { toast("Change refused: ${it.message?.take(80)}") }
        }
    }

    suspend fun logs(lines: Int = 120): Result<chat.keryx.app.data.remote.HermesStreamClient.LogsTail> =
        client()?.logsTail(lines)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    fun brainSelect(name: String) {
        val client = client() ?: return
        scope.launch {
            client.brainSelect(name)
                .onSuccess { toast("Swap started — watch the active brain") }
                .onFailure { toast("Swap refused: ${it.message?.take(80)}") }
        }
    }
    fun refreshJobs() = _hubJobs.refreshFrom { jobs() }
    fun refreshSessions() = _hubSessions.refreshFrom { sessions() }
    fun refreshSkills() = _hubSkills.refreshFrom { skills() }
    fun refreshToolsets() = _hubToolsets.refreshFrom { toolsets() }

    // --- Session pruner --------------------------------------------------------------------------

    suspend fun sessionsPrunePreview(
        olderThanDays: Int,
        maxMessages: Int?,
        includeArchived: Boolean,
    ): Result<chat.keryx.app.data.remote.HermesStreamClient.PruneResult> =
        client()?.sessionsPrune(olderThanDays, maxMessages, includeArchived, dryRun = true)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    /** The wet prune — permanent, transcripts included. Callers are responsible for having shown
     *  the dry-run count and the destructive confirm first. [onDone] gets the removed count. */
    fun sessionsPrune(
        olderThanDays: Int,
        maxMessages: Int?,
        includeArchived: Boolean,
        onDone: (Int?) -> Unit,
    ) {
        val client = client() ?: run { onDone(null); return }
        scope.launch {
            client.sessionsPrune(olderThanDays, maxMessages, includeArchived, dryRun = false)
                .onSuccess { res ->
                    toast("Pruned ${res.removed} session${if (res.removed == 1) "" else "s"}")
                    refreshSessions()
                    onDone(res.removed)
                }
                .onFailure {
                    toast("Prune failed: ${it.message?.take(80)}")
                    onDone(null)
                }
        }
    }

    // --- Skill Forge -----------------------------------------------------------------------------

    /** The skill open in the Forge sheet (lookup name — dir basename or display name; the sheet
     *  switches to the canonical name the gateway hands back). Null = closed. One StateFlow so the
     *  Skills tab and in-chat SkillDistilled pills share a single hosted sheet. */
    private val _skillForgeTarget = MutableStateFlow<String?>(null)
    val skillForgeTarget: StateFlow<String?> = _skillForgeTarget.asStateFlow()

    fun openSkillForge(name: String) {
        if (name.isBlank()) return
        _skillForgeTarget.value = name
    }

    fun closeSkillForge() {
        _skillForgeTarget.value = null
    }

    suspend fun skillDetail(name: String): Result<chat.keryx.app.data.remote.HermesStreamClient.SkillDetail> =
        client()?.skillGet(name)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    /** Save a SKILL.md rewrite. [onDone] gets (success, message) — the gateway's own note on
     *  success ("index refreshes for new sessions") or its validation/scan error verbatim. */
    fun skillSave(name: String, content: String, onDone: (Boolean, String) -> Unit) {
        val client = client() ?: run { onDone(false, "Hermes Link is off"); return }
        scope.launch {
            client.skillPut(name, content)
                .onSuccess { note -> refreshSkills(); onDone(true, note) }
                .onFailure { onDone(false, it.message ?: "save failed") }
        }
    }

    /** Create a skill from the phone. [onDone] gets (success, message); the gateway runs the same
     *  frontmatter validation and security scan the agent's own skill_manage tool does. */
    fun skillCreate(name: String, content: String, category: String?, onDone: (Boolean, String) -> Unit) {
        val client = client() ?: run { onDone(false, "Hermes Link is off"); return }
        scope.launch {
            client.skillCreate(name, content, category)
                .onSuccess { refreshSkills(); onDone(true, "created — index refreshes for new sessions") }
                .onFailure { onDone(false, it.message ?: "create failed") }
        }
    }

    // --- Skill trash (1.25) ----------------------------------------------------------------------

    private val _skillTrash =
        MutableStateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.TrashedSkill>>(emptyList())
    val skillTrash: StateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.TrashedSkill>> =
        _skillTrash.asStateFlow()

    fun refreshSkillTrash() {
        val client = client() ?: return
        scope.launch {
            client.skillTrash().onSuccess { _skillTrash.value = it }
        }
    }

    /** Move a skill to the trash. The toast carries the undo affordance's whole reason for
     *  existing — nothing is actually gone until it's purged. */
    fun skillDelete(name: String, onDone: (Boolean, String) -> Unit) {
        val client = client() ?: run { onDone(false, "Hermes Link is off"); return }
        scope.launch {
            client.skillDelete(name)
                .onSuccess {
                    refreshSkills()
                    refreshSkillTrash()
                    toast("Moved “$name” to trash — restore it from Skills ▸ Trash")
                    onDone(true, "moved to trash")
                }
                .onFailure {
                    toast("Delete refused: ${it.message?.take(80)}")
                    onDone(false, it.message ?: "delete failed")
                }
        }
    }

    fun skillRestore(id: String, name: String) {
        val client = client() ?: return
        scope.launch {
            client.skillRestore(id)
                .onSuccess {
                    refreshSkills()
                    refreshSkillTrash()
                    toast("Restored “$name”")
                }
                .onFailure { toast("Restore failed: ${it.message?.take(80)}") }
        }
    }

    fun skillPurge(id: String, name: String) {
        val client = client() ?: return
        scope.launch {
            client.skillPurge(id)
                .onSuccess { refreshSkillTrash(); toast("Purged “$name” for good") }
                .onFailure { toast("Purge failed: ${it.message?.take(80)}") }
        }
    }

    // --- Raw config editor (1.25) ----------------------------------------------------------------

    suspend fun configRaw(): Result<chat.keryx.app.data.remote.HermesStreamClient.RawConfig> =
        client()?.configRawGet()
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    /** Save config.yaml wholesale. [onDone] gets (success, message, needsForce) — needsForce means
     *  the gateway thinks this is a truncated paste and wants an explicit confirmation. */
    fun configRawSave(
        content: String,
        baseHash: String?,
        force: Boolean,
        onDone: (Boolean, String, Boolean) -> Unit,
    ) {
        val client = client() ?: run { onDone(false, "Hermes Link is off", false); return }
        scope.launch {
            client.configRawPut(content, baseHash, force)
                .onSuccess { res ->
                    // The curated knobs read the same file — re-pull so they don't show stale values.
                    refreshConfig()
                    onDone(true, res.backup?.let { "Saved. Backup: ${it.substringAfterLast('/')}" }
                        ?: "Saved.", false)
                }
                .onFailure { e ->
                    val needsForce =
                        (e as? chat.keryx.app.data.remote.HermesStreamClient.GatewayError)?.needsForce == true
                    onDone(false, e.message ?: "save failed", needsForce)
                }
        }
    }

    /** Flip one toolset on/off for the agent's platform, then re-pull so the switch reflects
     *  what the gateway actually persisted (locked refusals surface as the gateway's words). */
    fun toolsetToggle(name: String, enabled: Boolean) {
        val client = client() ?: return
        scope.launch {
            client.setToolsetEnabled(name, enabled)
                .onSuccess { refreshToolsets() }
                .onFailure {
                    toast("Toolset change failed: ${it.message?.take(80)}")
                    refreshToolsets()
                }
        }
    }

    /** Pause/resume/run a scheduled job, then re-pull the list so the card reflects reality. */
    fun jobAction(jobId: String, action: String) {
        val client = client() ?: return
        scope.launch {
            client.jobAction(jobId, action)
                .onSuccess { refreshJobs() }
                .onFailure { toast("Job $action failed: ${it.message?.take(80)}") }
        }
    }

    fun jobDelete(jobId: String) {
        val client = client() ?: return
        scope.launch {
            client.jobDelete(jobId)
                .onSuccess { toast("Job deleted"); refreshJobs() }
                .onFailure { toast("Delete failed: ${it.message?.take(80)}") }
        }
    }

    fun jobCreate(name: String, schedule: String, prompt: String, deliver: String) {
        val client = client() ?: return
        scope.launch {
            client.jobCreate(name, schedule, prompt, deliver)
                .onSuccess { toast("Job scheduled"); refreshJobs() }
                .onFailure { toast("Schedule failed: ${it.message?.take(80)}") }
        }
    }

    suspend fun sessionMessages(
        sessionId: String,
    ): Result<List<chat.keryx.app.data.remote.HermesStreamClient.HubMessage>> =
        client()?.sessionMessages(sessionId)
            ?: Result.failure(IllegalStateException("Hermes Link is off"))

    // --- Sessions: actionable verbs (1.20) -------------------------------------------------------

    fun sessionRename(sessionId: String, title: String, onDone: (Boolean) -> Unit = {}) {
        val client = client() ?: run { onDone(false); return }
        scope.launch {
            client.sessionRename(sessionId, title)
                .onSuccess { refreshSessions(); onDone(true) }
                .onFailure { toast("Rename failed: ${it.message?.take(80)}"); onDone(false) }
        }
    }

    fun sessionDelete(sessionId: String, onDone: (Boolean) -> Unit = {}) {
        val client = client() ?: run { onDone(false); return }
        scope.launch {
            client.sessionDelete(sessionId)
                .onSuccess { toast("Session deleted"); refreshSessions(); onDone(true) }
                .onFailure { toast("Delete failed: ${it.message?.take(80)}"); onDone(false) }
        }
    }

    /** [onDone] receives the forked session on success (null on failure) — forking without
     *  being taken to the fork left people hunting the list for it (device-caught 2026-09-01). */
    fun sessionFork(sessionId: String, onDone: (chat.keryx.app.data.remote.HermesStreamClient.HubSession?) -> Unit = {}) {
        val client = client() ?: run { onDone(null); return }
        scope.launch {
            client.sessionFork(sessionId)
                .onSuccess { fork -> toast("Forked — transcript carried forward"); refreshSessions(); onDone(fork) }
                .onFailure { toast("Fork failed: ${it.message?.take(80)}"); onDone(null) }
        }
    }

    fun jobEdit(jobId: String, name: String, schedule: String, prompt: String, deliver: String) {
        val client = client() ?: return
        scope.launch {
            client.jobUpdate(jobId, name, schedule, prompt, deliver)
                .onSuccess { toast("Job updated"); refreshJobs() }
                .onFailure { toast("Update failed: ${it.message?.take(80)}") }
        }
    }

}
