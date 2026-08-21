package chat.keryx.app.presentation

import chat.keryx.core.protocol.MessageParser
import chat.keryx.core.protocol.StreamTailTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/** The Run Console (1.20): see the region doc inside — one live run, streaming in this scope. */
class ConsoleDelegate(deps: GatewayDeps) {
    private val scope = deps.scope
    private val settings = deps.settings
    private val client = deps.client
    private val toast = deps.toast

    private companion object {
        const val CONSOLE_RUNS_KEY = "keryx://console/runs"
        const val CONSOLE_RUNS_MAX = 20
        const val CONSOLE_TOOL_LINES_MAX = 30
        // Publish cadence for windowed transcript snapshots — matches the chat overlay's feel.
        const val CONSOLE_PUBLISH_MS = 120L
    }

    // --- Run Console (1.20) -----------------------------------------------------------------------
    // The dashboard-chat equivalent over the STOCK gateway API: POST /v1/runs + its SSE event
    // stream, or a live turn into a persisted session (Resume). One live run at a time — the
    // console is a cockpit, not a fleet manager. State lives here (scope), so the run
    // keeps streaming while the sheet is closed; the Status tab shows an activity row meanwhile.

    data class ConsoleApproval(val command: String, val tool: String, val choices: List<String>)

    /** The live console surface. [transcript] is TAIL-WINDOWED while [live] (the A1 O(window)
     *  contract — a run can stream an arbitrarily long answer); the full text lands at finish. */
    data class ConsoleUi(
        val runId: String? = null,
        /** "run" = /v1/runs; "session" = resumed session turn. */
        val kind: String = "run",
        val prompt: String = "",
        /** idle | starting | streaming | waiting | completed | failed | cancelled | lost */
        val status: String = "idle",
        val transcript: String = "",
        val reasoningTail: String = "",
        /** Recent tool-activity lines, oldest first (bounded). */
        val tools: List<String> = emptyList(),
        val approval: ConsoleApproval? = null,
        val error: String? = null,
    ) {
        val live: Boolean get() = status == "starting" || status == "streaming" || status == "waiting"
    }

    private val _console = MutableStateFlow(ConsoleUi())
    val ui: StateFlow<ConsoleUi> = _console.asStateFlow()

    /** Session the next console launch resumes into (set by the Sessions tab), null = fresh run. */
    private val _consoleSessionTarget =
        MutableStateFlow<chat.keryx.app.data.remote.HermesStreamClient.HubSession?>(null)
    val sessionTarget: StateFlow<chat.keryx.app.data.remote.HermesStreamClient.HubSession?> =
        _consoleSessionTarget.asStateFlow()

    fun setSessionTarget(session: chat.keryx.app.data.remote.HermesStreamClient.HubSession?) {
        _consoleSessionTarget.value = session
    }

    /** Past runs this app launched, newest first (persisted; the gateway can't list runs). A
     *  "running" run-kind entry from a previous process life is re-polled by [consoleReconcile];
     *  session-kind entries can't be (disconnect cancels them) and load as "lost". */
    private val _consoleRuns = MutableStateFlow(
        settings.hubSnapshot(CONSOLE_RUNS_KEY)?.let { cached ->
            runCatching {
                chat.keryx.app.data.remote.HubJson.consoleRuns(
                    kotlinx.serialization.json.Json.parseToJsonElement(cached).jsonObject,
                )
            }.getOrNull()
        }?.map { if (it.status == "running" && it.kind == "session") it.copy(status = "lost") else it }
            .orEmpty(),
    )
    val runs: StateFlow<List<chat.keryx.app.data.remote.HermesStreamClient.ConsoleRun>> =
        _consoleRuns.asStateFlow()

    private var consoleJob: kotlinx.coroutines.Job? = null

    private fun consoleRegistryUpsert(run: chat.keryx.app.data.remote.HermesStreamClient.ConsoleRun) {
        _consoleRuns.value = (listOf(run) + _consoleRuns.value.filter { it.id != run.id })
            .take(CONSOLE_RUNS_MAX)
        settings.putHubSnapshot(
            CONSOLE_RUNS_KEY,
            chat.keryx.app.data.remote.HubJson.buildConsoleRuns(_consoleRuns.value).toString(),
        )
    }

    /** Launch a run (or resume into the selected session) and start streaming its events. */
    fun launch(prompt: String) {
        if (_console.value.live) {
            toast("A run is already live — stop it or let it finish")
            return
        }
        val client = client() ?: run {
            toast("Hermes Link is off — enable it in Settings")
            return
        }
        val target = _consoleSessionTarget.value
        val kind = if (target == null) "run" else "session"
        _console.value = ConsoleUi(prompt = prompt, status = "starting", kind = kind)
        consoleJob?.cancel()
        consoleJob = scope.launch {
            if (target == null) {
                client.runCreate(prompt)
                    .onSuccess { runId ->
                        _console.update { it.copy(runId = runId) }
                        consoleRegistryUpsert(
                            chat.keryx.app.data.remote.HermesStreamClient.ConsoleRun(
                                id = runId, kind = "run", prompt = prompt,
                                startedAt = System.currentTimeMillis(), status = "running",
                            ),
                        )
                        consoleCollect(client.runEvents(runId), runId)
                    }
                    .onFailure { e ->
                        _console.update { it.copy(status = "failed", error = e.message?.take(200)) }
                    }
            } else {
                val pseudoId = "session:${target.id}"
                _console.update { it.copy(runId = pseudoId) }
                consoleRegistryUpsert(
                    chat.keryx.app.data.remote.HermesStreamClient.ConsoleRun(
                        id = pseudoId, kind = "session", prompt = prompt,
                        startedAt = System.currentTimeMillis(), status = "running",
                    ),
                )
                consoleCollect(client.sessionChatStream(target.id, prompt), pseudoId)
            }
        }
    }

    /** Collect one normalized event stream into the console state. Delta work is O(delta): the
     *  trackers own the buffers, and a ~[CONSOLE_PUBLISH_MS] ticker publishes windowed snapshots
     *  instead of re-materializing per token (the 1.19 streaming contract, applied here). */
    private suspend fun consoleCollect(
        events: kotlinx.coroutines.flow.Flow<chat.keryx.app.data.remote.HermesStreamClient.RunEvent>,
        id: String,
    ) {
        val answer = StreamTailTracker(MessageParser.STREAM_RENDER_WINDOW, sanitize = true)
        val reasoning = StreamTailTracker(600, sanitize = false)
        var dirty = false
        val publisher = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(CONSOLE_PUBLISH_MS)
                if (dirty) {
                    dirty = false
                    _console.update {
                        it.copy(
                            transcript = answer.windowText(),
                            reasoningTail = reasoning.windowText(),
                            status = if (it.status == "starting") "streaming" else it.status,
                        )
                    }
                }
            }
        }
        fun toolLine(line: String) = _console.update {
            it.copy(tools = (it.tools + line).takeLast(CONSOLE_TOOL_LINES_MAX))
        }
        try {
            events.collect { ev ->
                when (ev) {
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Opened ->
                        _console.update { it.copy(status = "streaming") }
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Delta -> {
                        answer.append(ev.text); dirty = true
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Reasoning -> {
                        reasoning.append(ev.text); dirty = true
                    }
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.ToolStarted ->
                        toolLine("⚙ ${ev.tool}" + ev.preview.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" }.orEmpty())
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.ToolCompleted ->
                        toolLine((if (ev.failed) "✗" else "✓") + " ${ev.tool}")
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.ApprovalRequest ->
                        _console.update {
                            it.copy(status = "waiting", approval = ConsoleApproval(ev.command, ev.tool, ev.choices))
                        }
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.ApprovalResponded ->
                        _console.update { it.copy(status = "streaming", approval = null) }
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Completed ->
                        consoleFinish(id, "completed", output = ev.output.ifBlank { answer.sanitizedFullText() })
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Failed ->
                        consoleFinish(id, "failed", output = answer.sanitizedFullText(), error = ev.error)
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.Cancelled ->
                        consoleFinish(id, "cancelled", output = answer.sanitizedFullText())
                    is chat.keryx.app.data.remote.HermesStreamClient.RunEvent.StreamLost ->
                        consoleFinish(id, "lost", output = answer.sanitizedFullText(), error = ev.reason)
                }
            }
        } finally {
            publisher.cancel()
        }
    }

    private fun consoleFinish(id: String, status: String, output: String, error: String? = null) {
        _console.update {
            it.copy(
                status = status,
                transcript = output,
                reasoningTail = "",
                approval = null,
                error = error?.take(200),
            )
        }
        _consoleRuns.value.firstOrNull { it.id == id }?.let { entry ->
            consoleRegistryUpsert(entry.copy(status = status, output = output, error = error.orEmpty()))
        }
    }

    /** Resolve the pending approval gate. Only /v1/runs carries an approval transport. */
    fun approve(choice: String) {
        val c = _console.value
        val runId = c.runId ?: return
        if (c.kind != "run") return
        val client = client() ?: return
        scope.launch {
            // approval.responded arrives on the event stream and flips the UI back itself.
            client.runApproval(runId, choice)
                .onFailure { toast("Approval failed: ${it.message?.take(80)}") }
        }
    }

    fun stop() {
        val c = _console.value
        if (!c.live) return
        if (c.kind == "run" && c.runId != null) {
            val client = client() ?: return
            scope.launch {
                // run.cancelled arrives on the stream; consoleFinish runs from there.
                client.runStop(c.runId)
                    .onFailure { toast("Stop failed: ${it.message?.take(80)}") }
            }
        } else {
            // Session dialect has no stop route — disconnecting cancels the turn server-side.
            consoleJob?.cancel()
            consoleFinish(c.runId ?: return, "cancelled", output = "")
        }
    }

    /** Clear a finished run off the console so a new prompt starts clean (registry keeps it). */
    fun reset() {
        if (!_console.value.live) _console.value = ConsoleUi()
    }

    /** Re-poll registry entries stranded "running" by a process death (run-kind only — the
     *  gateway still knows their status even though their event queues are long gone). */
    fun reconcile() {
        val client = client() ?: return
        val live = _console.value.runId
        val stale = _consoleRuns.value.filter { it.status == "running" && it.kind == "run" && it.id != live }
        if (stale.isEmpty()) return
        scope.launch {
            stale.forEach { entry ->
                client.runStatus(entry.id)
                    .onSuccess { s ->
                        val status = when (s.status) {
                            "completed", "failed", "cancelled" -> s.status
                            "queued", "running", "waiting_for_approval" -> "lost"
                            else -> "lost"
                        }
                        consoleRegistryUpsert(entry.copy(status = status, output = s.output, error = s.error))
                    }
                    .onFailure { consoleRegistryUpsert(entry.copy(status = "lost")) }
            }
        }
    }

}
