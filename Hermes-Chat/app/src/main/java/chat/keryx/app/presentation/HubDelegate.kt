package chat.keryx.app.presentation

import chat.keryx.app.R
import chat.keryx.app.KeryxText
import chat.keryx.app.transport.direct.dashboardSkills
import chat.keryx.app.transport.direct.skillToggle
import chat.keryx.app.transport.direct.skillLock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/**
 * The Agent Hub: every gateway console surface — health, jobs, sessions (verbs and pruning),
 * skills (Forge and trash), toolsets, models, brains, the curated config knobs and the raw
 * config editor, plus the reasoning dial and the slash-command registry.
 */
class HubDelegate(
    deps: GatewayDeps,
    /** The direct door's dashboard client (makeover): skills read there first. Null on Matrix. */
    private val rest: () -> chat.keryx.app.transport.direct.GatewayRest? = { null },
) {
    private val scope = deps.scope
    private val settings = deps.settings
    private val client = deps.client
    private val bareClient = deps.bareClient
    private val toast = deps.toast
    private val notice = deps.notice

    private val _reasoningCaps = MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?>(null)
    val reasoningCaps: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.ReasoningCaps?> = _reasoningCaps.asStateFlow()

    /** Fetch what a brain supports for /reasoning, so the menu can adapt (binary vs effort
     *  scale, current level, model name). [model]/[provider] name the session's live route
     *  (the direct door's catalog), [sessionId] lets the gateway read the stored session's
     *  row when the route is not known yet; all blank = the global default. [liveCurrent]
     *  answers the session's effective level straight from the gateway's live agent (the
     *  direct door's `config.get reasoning`) — it beats the row, which lags a session-scoped
     *  set until the next turn. Silent on failure — the menu keeps its last answer. */
    fun refreshReasoningCaps(
        model: String? = null,
        provider: String? = null,
        sessionId: String? = null,
        liveCurrent: (suspend () -> String?)? = null,
    ) {
        val client = bareClient() ?: return
        scope.launch {
            client.capabilities(model, provider, sessionId)
                .onSuccess { caps ->
                    val live = liveCurrent?.let { runCatching { it() }.getOrNull() }?.trim()?.lowercase()
                    _reasoningCaps.value =
                        if (!live.isNullOrBlank() && caps.mode != "none") caps.copy(current = live) else caps
                }
        }
    }


    /** The gateway's live slash-command registry (empty until fetched; palette falls back to
     *  its preset list). Refreshed at most once a minute, when the "/" palette opens. */
    private val _gatewayCommands =
        MutableStateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>>(emptyList())
    val gatewayCommands: StateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>> =
        _gatewayCommands.asStateFlow()
    private var gatewayCommandsFetchedAt = 0L

    private var gatewayCommandsScope: String? = null

    /**
     * [catalog]: the gateway's own `commands.catalog` (the direct door), which lists skills with
     * their descriptions; keryx-stream's `/keryx/commands` has none. Both are read and merged,
     * the catalog first. [scopeKey] names the chat it was asked for: skills are per profile, so
     * moving to another chat asks again rather than waiting out the minute.
     */
    fun refreshGatewayCommands(
        scopeKey: String? = null,
        catalog: (suspend () -> Result<List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand>>)? = null,
    ) {
        val client = bareClient()
        if (client == null && catalog == null) return
        val now = System.currentTimeMillis()
        if (_gatewayCommands.value.isNotEmpty() && scopeKey == gatewayCommandsScope &&
            now - gatewayCommandsFetchedAt < 60_000L
        ) return
        gatewayCommandsFetchedAt = now
        gatewayCommandsScope = scopeKey
        scope.launch {
            val fromCatalog = catalog?.invoke()?.getOrNull().orEmpty()
            val fromPlugin = client?.commands()?.getOrNull().orEmpty()
            val merged = chat.keryx.app.data.remote.HermesStreamClient.mergeCommands(fromCatalog, fromPlugin)
            if (merged.isNotEmpty()) _gatewayCommands.value = merged
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
            value = value.copy(error = KeryxText.get(R.string.agent_link_off), refreshing = false)
            return
        }
        value = value.copy(refreshing = true)
        scope.launch {
            client.fetch()
                .onSuccess { value = PanelState(data = it) }
                .onFailure {
                    value = value.copy(error = it.message?.take(120) ?: KeryxText.get(R.string.agent_unavailable), refreshing = false)
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
    ) {
        /** The kept runs, newest first — the shelf above the arrivals rail. */
        val pinned: List<chat.keryx.core.model.CronRun> = chat.keryx.core.model.CronPins.of(cards)
        private val pinnedIds: Set<String> = pinned.mapTo(HashSet()) { it.id }
        fun isPinned(runId: String): Boolean = runId in pinnedIds
        /** Whether a session id is one of this board's runs — the top bar's pin only shows
         *  over a scheduled run, never over a conversation. */
        fun isRun(sessionId: String): Boolean = sessionId in runsById
    }

    private val _cron = MutableStateFlow<PanelState<CronBoard>>(PanelState())
    val cron: StateFlow<PanelState<CronBoard>> = _cron.asStateFlow()

    fun refreshCron() {
        val client = client() ?: run {
            _cron.value = _cron.value.copy(error = KeryxText.get(R.string.agent_link_off), refreshing = false)
            return
        }
        _cron.value = _cron.value.copy(refreshing = true)
        scope.launch {
            val jobsRes = client.jobs()
            val runsRes = client.cronSessions()
            val failure = listOf(jobsRes, runsRes).firstNotNullOfOrNull { it.exceptionOrNull() }
            if (failure != null && runsRes.isFailure) {
                _cron.value = _cron.value.copy(error = failure.message?.take(120) ?: KeryxText.get(R.string.agent_unavailable), refreshing = false)
                return@launch
            }
            val jobs = jobsRes.getOrDefault(emptyList())
            val sessions = runsRes.getOrDefault(emptyList())
            val runs = sessions.map {
                chat.keryx.core.model.CronRun(
                    id = it.id,
                    title = it.title.orEmpty().ifBlank { it.id },
                    timestamp = (it.lastActive * 1000).toLong(),
                    pinned = it.pinned,
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

    // --- Cron tiles: a job's output at the top of the session list, Quick-Room style ---------

    private val _pinnedJobs = MutableStateFlow(settings.pinnedCronJobs)
    /** Job names pinned to the drawer's deck, in pin order (phone ledger — see [chat.keryx.core.model.CronTiles]). */
    val pinnedJobs: StateFlow<List<String>> = _pinnedJobs.asStateFlow()

    fun cronJobPinned(name: String): Boolean = name in _pinnedJobs.value

    fun cronSetJobPinned(name: String, pinned: Boolean, undoable: Boolean = true) {
        val now = _pinnedJobs.value
        val next = if (pinned) (if (name in now) now else now + name) else now - name
        if (next == now) return
        _pinnedJobs.value = next
        settings.pinnedCronJobs = next
        val said = KeryxText.get(if (pinned) R.string.agent_job_pinned else R.string.agent_job_unpinned, name)
        // The Undo flips back quietly — an undo that offers to undo itself is a loop.
        if (undoable) notice(KeryxNotice.undo(said) { cronSetJobPinned(name, !pinned, undoable = false) })
    }

    /**
     * The deck's cron tiles: pinned jobs (following their newest run) and runs kept on the
     * gateway. Recomputed whenever the board or the ledger moves, so the tile's unread dot
     * and the run it opens are always the board's current answer.
     */
    val cronTiles: StateFlow<List<chat.keryx.core.model.CronTile>> =
        kotlinx.coroutines.flow.combine(_cron, _pinnedJobs) { panel, jobs ->
            val board = panel.data
            if (board == null) {
                // Cold start: the ledger's jobs get tiles with nothing to open yet, so the
                // deck doesn't blink in after the first fetch.
                jobs.map { name ->
                    chat.keryx.core.model.CronTile(
                        id = chat.keryx.core.model.CronTile.jobId(name), name = name, label = name,
                        runId = null, runTitle = null, timestamp = 0L, unread = false, job = true,
                    )
                }
            } else {
                chat.keryx.core.model.CronTiles.build(board.cards, jobs, board.unread)
            }
        }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /**
     * Keep (or release) one run on the gateway. The shelf moves NOW — the pin is a value flip
     * over the cards — and the PATCH that follows is the gateway agreeing; a refusal flips it
     * back and says why, so the screen never shows a pin the server doesn't hold. The refresh
     * after success is the list confirming (and back-filling a kept run the window had lost).
     */
    fun cronSetPinned(runId: String, pinned: Boolean, undoable: Boolean = true) {
        if (_cron.value.data == null) return
        val client = client() ?: run { toast(KeryxText.get(R.string.agent_link_off)); return }
        fun flip(to: Boolean) {
            val b = _cron.value.data ?: return
            _cron.value = _cron.value.copy(
                data = b.copy(cards = chat.keryx.core.model.CronPins.withPin(b.cards, runId, to)),
            )
        }
        flip(pinned)
        scope.launch {
            client.sessionPin(runId, pinned)
                .onSuccess {
                    val said = KeryxText.get(if (pinned) R.string.agent_run_pinned else R.string.agent_run_unpinned)
                    if (undoable) notice(KeryxNotice.undo(said) { cronSetPinned(runId, !pinned, undoable = false) })
                    refreshCron()
                }
                .onFailure {
                    flip(!pinned)
                    toast(KeryxText.get(if (pinned) R.string.agent_pin_refused else R.string.agent_unpin_refused, it.message?.take(80).toString()))
                }
        }
    }

    /**
     * A run's skimmable headline: its REPORT (the longest assistant text — the last message
     * is usually narration or bookkeeping), digested to (title, lead). Cached per run id —
     * transcripts don't change after the run ends.
     */
    private val cronDigests = java.util.concurrent.ConcurrentHashMap<String, chat.keryx.core.model.CronDigest>()

    /** A run's REPORT text, fetched once per run id ("" = the run has no report, which is
     *  also final). Null only when the fetch failed — a later ask retries. */
    private val cronReports = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun cronReport(runId: String): String? {
        cronReports[runId]?.let { return it }
        val client = client() ?: return null
        val messages = client.sessionMessages(runId).getOrNull() ?: return null
        val report = chat.keryx.core.model.CronHumanize.pickReport(
            messages.filter { it.role == "assistant" }.map { it.content },
        ).orEmpty()
        cronReports[runId] = report
        return report
    }

    suspend fun cronDigest(runId: String): chat.keryx.core.model.CronDigest? {
        cronDigests[runId]?.let { return it }
        val report = cronReport(runId) ?: return null
        val digest = if (report.isEmpty()) chat.keryx.core.model.CronDigest(null, null)
        else chat.keryx.core.model.CronHumanize.digest(report)
        cronDigests[runId] = digest
        return digest
    }

    /**
     * What [latestId]'s report says that [previousId]'s didn't — the diff feed. Both reports
     * come through the same cache the headlines use, so a card that has shown its digest has
     * already paid for half of its delta. Null while either transcript is unfetched, or when
     * either run has no report to compare (a delta against nothing is not "everything is new").
     */
    private val cronDeltas = java.util.concurrent.ConcurrentHashMap<String, chat.keryx.core.model.CronDelta>()

    suspend fun cronDelta(latestId: String, previousId: String): chat.keryx.core.model.CronDelta? {
        val key = "$latestId|$previousId"
        cronDeltas[key]?.let { return it }
        val latest = cronReport(latestId) ?: return null
        val previous = cronReport(previousId) ?: return null
        if (latest.isEmpty() || previous.isEmpty()) return null
        return chat.keryx.core.model.CronDeltaCalc.compute(previous, latest).also { cronDeltas[key] = it }
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
                .onSuccess { applies -> toast(KeryxText.get(R.string.agent_saved_applies, applies.toString())); refreshConfig() }
                .onFailure { toast(KeryxText.get(R.string.agent_change_refused, it.message?.take(80).toString())); refreshConfig() }
        }
    }

    fun reasoningSet(level: String) {
        val client = client() ?: return
        scope.launch {
            client.reasoningSet(level)
                .onSuccess { toast(KeryxText.get(R.string.agent_reasoning_set, chat.keryx.core.model.ReasoningEffort.label(level))); refreshReasoningCaps() }
                .onFailure { toast(KeryxText.get(R.string.agent_change_refused, it.message?.take(80).toString())) }
        }
    }

    suspend fun logs(lines: Int = 120): Result<chat.keryx.app.data.remote.HermesStreamClient.LogsTail> =
        client()?.logsTail(lines)
            ?: Result.failure(IllegalStateException(KeryxText.get(R.string.agent_link_off_short)))

    fun brainSelect(name: String) {
        val client = client() ?: return
        scope.launch {
            client.brainSelect(name)
                .onSuccess { toast(KeryxText.get(R.string.agent_swap_started)) }
                .onFailure { toast(KeryxText.get(R.string.agent_swap_refused, it.message?.take(80).toString())) }
        }
    }
    fun refreshJobs() = _hubJobs.refreshFrom { jobs() }
    fun refreshSessions() = _hubSessions.refreshFrom { sessions() }
    /**
     * Skills (makeover): the dashboard's `/api/skills` first — it knows which are on — and the
     * Link's `/v1/skills` when there is no dashboard or it can't answer. One source failing
     * never blanks the list while the other can fill it.
     */
    fun refreshSkills() {
        val r = rest() ?: return _hubSkills.refreshFrom { skills() }
        _hubSkills.value = _hubSkills.value.copy(refreshing = true)
        scope.launch {
            when (val got = r.dashboardSkills()) {
                is chat.keryx.core.model.Routed.Ok -> _hubSkills.value = PanelState(data = got.value)
                else -> {
                    val c = client()
                    if (c == null) {
                        val why = (got as? chat.keryx.core.model.Routed.Failed)?.message ?: KeryxText.get(R.string.agent_no_skills)
                        _hubSkills.value = _hubSkills.value.copy(error = why.take(120), refreshing = false)
                    } else c.skills()
                        .onSuccess { _hubSkills.value = PanelState(data = it) }
                        .onFailure { _hubSkills.value = _hubSkills.value.copy(error = it.message?.take(120) ?: KeryxText.get(R.string.agent_unavailable), refreshing = false) }
                }
            }
        }
    }

    /** Flip one skill: the row moves now and settles back on a refusal. Applies to new sessions. */
    fun setSkillEnabled(name: String, enabled: Boolean) {
        val r = rest() ?: return
        val before = _hubSkills.value.data ?: return
        _hubSkills.value = _hubSkills.value.copy(data = before.map { if (it.name == name) it.copy(enabled = enabled) else it })
        scope.launch {
            when (val res = r.skillToggle(name, enabled)) {
                is chat.keryx.core.model.Routed.Ok -> toast(KeryxText.get(if (enabled) R.string.agent_skill_on else R.string.agent_skill_off, name))
                is chat.keryx.core.model.Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); refreshSkills() }
                chat.keryx.core.model.Routed.Missing -> { toast(KeryxText.get(R.string.agent_skill_switch_unsupported)); refreshSkills() }
            }
        }
    }
    /** Lock one skill against the agent's edits (3.0, Hermes 0.22.6+): the row moves now and
     *  settles back on a refusal. Takes effect at once, in running sessions too. */
    fun setSkillLocked(name: String, locked: Boolean) {
        val r = rest() ?: return
        val before = _hubSkills.value.data ?: return
        _hubSkills.value = _hubSkills.value.copy(data = before.map { if (it.name == name) it.copy(locked = locked) else it })
        scope.launch {
            when (val res = r.skillLock(name, locked)) {
                is chat.keryx.core.model.Routed.Ok -> toast(KeryxText.get(if (locked) R.string.agent_skill_locked else R.string.agent_skill_unlocked, name))
                is chat.keryx.core.model.Routed.Failed -> { toast(KeryxText.get(R.string.agent_skill_change_failed, name, res.message.take(80))); refreshSkills() }
                chat.keryx.core.model.Routed.Missing -> { toast(KeryxText.get(R.string.agent_skill_lock_unsupported)); refreshSkills() }
            }
        }
    }
    fun refreshToolsets() = _hubToolsets.refreshFrom { toolsets() }

    // --- Session pruner --------------------------------------------------------------------------

    suspend fun sessionsPrunePreview(
        olderThanDays: Int,
        maxMessages: Int?,
        includeArchived: Boolean,
    ): Result<chat.keryx.app.data.remote.HermesStreamClient.PruneResult> =
        client()?.sessionsPrune(olderThanDays, maxMessages, includeArchived, dryRun = true)
            ?: Result.failure(IllegalStateException(KeryxText.get(R.string.agent_link_off_short)))

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
                    toast(KeryxText.plural(R.plurals.agent_pruned, res.removed, res.removed))
                    refreshSessions()
                    onDone(res.removed)
                }
                .onFailure {
                    toast(KeryxText.get(R.string.agent_prune_failed, it.message?.take(80).toString()))
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
            ?: Result.failure(IllegalStateException(KeryxText.get(R.string.agent_link_off_short)))

    /** Save a SKILL.md rewrite. [onDone] gets (success, message) — the gateway's own note on
     *  success ("index refreshes for new sessions") or its validation/scan error verbatim. */
    fun skillSave(name: String, content: String, onDone: (Boolean, String) -> Unit) {
        val client = client() ?: run { onDone(false, KeryxText.get(R.string.agent_link_off_short)); return }
        scope.launch {
            client.skillPut(name, content)
                .onSuccess { note -> refreshSkills(); onDone(true, note) }
                .onFailure { onDone(false, it.message ?: KeryxText.get(R.string.agent_save_failed)) }
        }
    }

    /** Create a skill from the phone. [onDone] gets (success, message); the gateway runs the same
     *  frontmatter validation and security scan the agent's own skill_manage tool does. */
    fun skillCreate(name: String, content: String, category: String?, onDone: (Boolean, String) -> Unit) {
        val client = client() ?: run { onDone(false, KeryxText.get(R.string.agent_link_off_short)); return }
        scope.launch {
            client.skillCreate(name, content, category)
                .onSuccess { refreshSkills(); onDone(true, KeryxText.get(R.string.agent_skill_created)) }
                .onFailure { onDone(false, it.message ?: KeryxText.get(R.string.agent_create_failed)) }
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
        val client = client() ?: run { onDone(false, KeryxText.get(R.string.agent_link_off_short)); return }
        scope.launch {
            client.skillDelete(name)
                .onSuccess { trashId ->
                    refreshSkills()
                    refreshSkillTrash()
                    // The gateway hands back the trash id exactly so this can be one tap.
                    val said = KeryxText.get(R.string.agent_skill_trashed, name)
                    if (trashId.isNotBlank()) notice(KeryxNotice.undo(said) { skillRestore(trashId, name) })
                    else toast(said)
                    onDone(true, KeryxText.get(R.string.agent_moved_to_trash))
                }
                .onFailure {
                    toast(KeryxText.get(R.string.agent_delete_refused, it.message?.take(80).toString()))
                    onDone(false, it.message ?: KeryxText.get(R.string.agent_delete_failed_short))
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
                    toast(KeryxText.get(R.string.agent_restored, name))
                }
                .onFailure { toast(KeryxText.get(R.string.agent_restore_failed, it.message?.take(80).toString())) }
        }
    }

    fun skillPurge(id: String, name: String) {
        val client = client() ?: return
        scope.launch {
            client.skillPurge(id)
                .onSuccess { refreshSkillTrash(); toast(KeryxText.get(R.string.agent_purged, name)) }
                .onFailure { toast(KeryxText.get(R.string.agent_purge_failed, it.message?.take(80).toString())) }
        }
    }

    // --- Raw config editor (1.25) ----------------------------------------------------------------

    suspend fun configRaw(): Result<chat.keryx.app.data.remote.HermesStreamClient.RawConfig> =
        client()?.configRawGet()
            ?: Result.failure(IllegalStateException(KeryxText.get(R.string.agent_link_off_short)))

    /** Save config.yaml wholesale. [onDone] gets (success, message, needsForce) — needsForce means
     *  the gateway thinks this is a truncated paste and wants an explicit confirmation. */
    fun configRawSave(
        content: String,
        baseHash: String?,
        force: Boolean,
        onDone: (Boolean, String, Boolean) -> Unit,
    ) {
        val client = client() ?: run { onDone(false, KeryxText.get(R.string.agent_link_off_short), false); return }
        scope.launch {
            client.configRawPut(content, baseHash, force)
                .onSuccess { res ->
                    // The curated knobs read the same file — re-pull so they don't show stale values.
                    refreshConfig()
                    onDone(true, res.backup?.let { KeryxText.get(R.string.agent_saved_backup, it.substringAfterLast('/')) }
                        ?: KeryxText.get(R.string.agent_saved), false)
                }
                .onFailure { e ->
                    val needsForce =
                        (e as? chat.keryx.app.data.remote.HermesStreamClient.GatewayError)?.needsForce == true
                    onDone(false, e.message ?: KeryxText.get(R.string.agent_save_failed), needsForce)
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
                    toast(KeryxText.get(R.string.agent_toolset_failed, it.message?.take(80).toString()))
                    refreshToolsets()
                }
        }
    }

    /** Pause/resume/run a scheduled job, then re-pull the list so the card reflects reality. */
    fun jobAction(jobId: String, action: String, undoable: Boolean = true) {
        val client = client() ?: return
        scope.launch {
            client.jobAction(jobId, action)
                .onSuccess {
                    // Said out loud: from the Runs page a run fires in the background and the
                    // card only changes when it lands, so a silent tap read as a dead button.
                    val said = KeryxText.get(
                        when (action) {
                            "run" -> R.string.agent_job_running_now
                            "pause" -> R.string.agent_job_paused
                            "resume" -> R.string.agent_job_resumed
                            else -> R.string.agent_done
                        },
                    )
                    // Pause and resume are each other's undo; a run that has started is not.
                    val back = when (action) { "pause" -> "resume"; "resume" -> "pause"; else -> null }
                    if (back != null && undoable) notice(KeryxNotice.undo(said) { jobAction(jobId, back, undoable = false) })
                    else toast(said)
                    refreshJobs(); refreshCron()
                }
                .onFailure {
                    val why = it.message?.take(80).toString()
                    toast(
                        KeryxText.get(
                            when (action) {
                                "run" -> R.string.agent_job_run_failed
                                "pause" -> R.string.agent_job_pause_failed
                                "resume" -> R.string.agent_job_resume_failed
                                else -> R.string.agent_job_action_failed
                            },
                            why,
                        ),
                    )
                }
        }
    }

    fun jobDelete(jobId: String) {
        val client = client() ?: return
        scope.launch {
            client.jobDelete(jobId)
                .onSuccess { toast(KeryxText.get(R.string.agent_job_deleted)); refreshJobs(); refreshCron() }
                .onFailure { toast(KeryxText.get(R.string.agent_delete_failed, it.message?.take(80).toString())) }
        }
    }

    fun jobCreate(name: String, schedule: String, prompt: String, deliver: String) {
        val client = client() ?: return
        scope.launch {
            client.jobCreate(name, schedule, prompt, deliver)
                .onSuccess { toast(KeryxText.get(R.string.agent_job_scheduled)); refreshJobs() }
                .onFailure { toast(KeryxText.get(R.string.agent_schedule_failed, it.message?.take(80).toString())) }
        }
    }

    suspend fun sessionMessages(
        sessionId: String,
    ): Result<List<chat.keryx.app.data.remote.HermesStreamClient.HubMessage>> =
        client()?.sessionMessages(sessionId)
            ?: Result.failure(IllegalStateException(KeryxText.get(R.string.agent_link_off_short)))

    // --- Sessions: actionable verbs (1.20) -------------------------------------------------------

    fun sessionRename(sessionId: String, title: String, onDone: (Boolean) -> Unit = {}) {
        val client = client() ?: run { onDone(false); return }
        scope.launch {
            client.sessionRename(sessionId, title)
                .onSuccess { refreshSessions(); onDone(true) }
                .onFailure { toast(KeryxText.get(R.string.agent_rename_failed, it.message?.take(80).toString())); onDone(false) }
        }
    }

    fun sessionDelete(sessionId: String, onDone: (Boolean) -> Unit = {}) {
        val client = client() ?: run { onDone(false); return }
        scope.launch {
            client.sessionDelete(sessionId)
                .onSuccess { toast(KeryxText.get(R.string.agent_session_deleted)); refreshSessions(); onDone(true) }
                .onFailure { toast(KeryxText.get(R.string.agent_delete_failed, it.message?.take(80).toString())); onDone(false) }
        }
    }

    /** [onDone] receives the forked session on success (null on failure) — forking without
     *  being taken to the fork left people hunting the list for it (device-caught 2026-09-01). */
    fun sessionFork(sessionId: String, onDone: (chat.keryx.app.data.remote.HermesStreamClient.HubSession?) -> Unit = {}) {
        val client = client() ?: run { onDone(null); return }
        scope.launch {
            client.sessionFork(sessionId)
                .onSuccess { fork -> toast(KeryxText.get(R.string.agent_forked)); refreshSessions(); onDone(fork) }
                .onFailure { toast(KeryxText.get(R.string.agent_fork_failed, it.message?.take(80).toString())); onDone(null) }
        }
    }

    fun jobEdit(jobId: String, name: String, schedule: String, prompt: String, deliver: String) {
        val client = client() ?: return
        scope.launch {
            client.jobUpdate(jobId, name, schedule, prompt, deliver)
                .onSuccess { toast(KeryxText.get(R.string.agent_job_updated)); refreshJobs() }
                .onFailure { toast(KeryxText.get(R.string.agent_update_failed, it.message?.take(80).toString())) }

        }
    }

}
