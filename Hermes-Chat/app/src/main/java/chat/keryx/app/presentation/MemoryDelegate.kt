package chat.keryx.app.presentation

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.memoryDelete
import chat.keryx.app.transport.direct.memoryEdit
import chat.keryx.app.transport.direct.memoryEntries
import chat.keryx.app.transport.direct.memoryEntryText
import chat.keryx.app.transport.direct.memoryStatus
import chat.keryx.core.model.MemoryEntry
import chat.keryx.core.model.MemoryStatus
import chat.keryx.core.model.Routed
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Hub's Memory spoke (2.16): what the agent remembers — its own notes (MEMORY.md) and what
 * it knows about you (USER.md) — readable, searchable, and correctable from the phone.
 *
 * Dashboard routes only, so the direct door only: [rest] is the door's own REST client (null on
 * Matrix, where the spoke never shows). Fetched when the Gateway opens and when the spoke is
 * visited, never polled — memory changes when the agent writes it, and a pull-to-refresh is
 * cheaper than ten seconds of graph builds forever.
 */
class MemoryDelegate(
    deps: GatewayDeps,
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val toast = deps.toast

    /**
     * One read of both halves. [status] is null when `/api/memory` is missing or failed;
     * [entries] null when `/api/learning/graph` is. Each half that is missing hides its part.
     */
    data class Book(
        val status: MemoryStatus?,
        val entries: List<MemoryEntry>?,
    )

    private val _book = MutableStateFlow(HubDelegate.PanelState<Book>())
    val book: StateFlow<HubDelegate.PanelState<Book>> = _book.asStateFlow()

    /** Null until the first read answers; false = this gateway serves neither half (or the
     *  door has no dashboard), and the spoke hides. */
    private val _available = MutableStateFlow<Boolean?>(null)
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private var inFlight: Job? = null

    fun refresh() {
        val r = rest() ?: run { _available.value = false; return }
        if (inFlight?.isActive == true) return
        _book.value = _book.value.copy(refreshing = true)
        inFlight = scope.launch {
            val status = r.memoryStatus()
            val entries = r.memoryEntries()
            if (status is Routed.Missing && entries is Routed.Missing) {
                _available.value = false
                _book.value = HubDelegate.PanelState()
                return@launch
            }
            _available.value = true
            val failure = listOf(entries, status).firstNotNullOfOrNull { (it as? Routed.Failed)?.message }
            val last = _book.value.data
            _book.value = HubDelegate.PanelState(
                data = Book(
                    // A failed half keeps its last good answer; a missing half stays null.
                    status = when (status) {
                        is Routed.Ok -> status.value
                        is Routed.Failed -> last?.status
                        Routed.Missing -> null
                    },
                    entries = when (entries) {
                        is Routed.Ok -> entries.value
                        is Routed.Failed -> last?.entries
                        Routed.Missing -> null
                    },
                ),
                error = failure,
            )
        }
    }

    /** The entry's whole text — past the card's 1,200-char clip, and the edit's prefill. */
    suspend fun entryText(id: String): Result<String> {
        val r = rest() ?: return Result.failure(IllegalStateException("The gateway door is closed."))
        return when (val got = r.memoryEntryText(id)) {
            is Routed.Ok -> Result.success(got.value)
            is Routed.Failed -> Result.failure(IllegalStateException(got.message))
            Routed.Missing -> Result.failure(IllegalStateException("This gateway can't open one entry."))
        }
    }

    /**
     * Rewrite one entry. [onDone] gets (ok, message): the gateway's own sentence either way —
     * its char-cap refusal and its "stale, refresh" refusal are both things you act on. A
     * success re-reads the list, because the entry's id is a digest of its text and just changed.
     */
    fun save(id: String, content: String, onDone: (Boolean, String) -> Unit) =
        mutate(onDone, after = "Saved. Sessions already running keep the copy they started with.") {
            it.memoryEdit(id, content.trim())
        }

    fun delete(id: String, onDone: (Boolean, String) -> Unit) =
        mutate(onDone, after = "Forgotten. Sessions already running keep the copy they started with.") {
            it.memoryDelete(id)
        }

    private fun mutate(
        onDone: (Boolean, String) -> Unit,
        after: String,
        call: suspend (GatewayRest) -> Routed<String>,
    ) {
        val r = rest() ?: run { onDone(false, "The gateway door is closed."); return }
        scope.launch {
            when (val res = call(r)) {
                is Routed.Ok -> {
                    toast(after)
                    onDone(true, res.value)
                    refresh()
                }
                is Routed.Failed -> onDone(false, res.message)
                Routed.Missing -> onDone(false, "This gateway can't change memory from here.")
            }
        }
    }
}
