package chat.keryx.app.data.archive

import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.app.transport.direct.TranscriptPages
import chat.keryx.core.protocol.MAX_PAGE
import chat.keryx.core.protocol.TranscriptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Archive's producer on the direct door. A gateway session is a room; its transcript is
 * the REST page walk the timeline already hydrates from. The walk reads newest-first until it
 * crosses the ceiling (the highest row id an earlier sweep filed) or runs out of history,
 * builds the rows through the same [TranscriptBuilder] the chat renders with, and files what
 * [ArchiveIndexer.indexableEntries] admits — the identical policy the Matrix walk applies, so
 * search means the same thing behind either door.
 *
 * Overshoot is deliberate: the walk stops at a page boundary past the ceiling, never at the
 * ceiling itself, so a block cut mid-run cannot misread a progress line as a bubble. The
 * re-filed tail dedupes on `event_id`.
 *
 * The store is NOT re-keyed to the gateway (no `ensureAccount`): Matrix event ids (`$…`) and
 * gateway row ids (numeric) never collide, and room ids (`!…` vs session ids) neither, so both
 * doors' indexes coexist in the one file and a door crossing costs no backfill.
 */
class RestArchiveIndexer(
    private val rest: () -> GatewayRest?,
    private val store: ArchiveStore,
) : ArchiveSweeper {

    private val _progress = MutableStateFlow<ArchiveIndexer.Progress?>(null)
    override val progress: StateFlow<ArchiveIndexer.Progress?> = _progress.asStateFlow()

    private var job: Job? = null

    override fun sweep(scope: CoroutineScope, roomId: String) {
        if (job?.isActive == true && _progress.value?.roomId == roomId) return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                runSweep(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KeryxArchive", "rest sweep failed for $roomId", e)
                _progress.value = ArchiveIndexer.Progress(
                    roomId = roomId,
                    indexed = store.count(roomId),
                    freshThisSweep = 0,
                    running = false,
                    complete = store.backfillComplete(roomId),
                    error = e.message ?: "sweep failed",
                )
            }
        }
    }

    private suspend fun runSweep(sessionId: String) {
        val wasComplete = store.backfillComplete(sessionId)
        fun publish(fresh: Int, running: Boolean, complete: Boolean = wasComplete, error: String? = null) {
            _progress.value = ArchiveIndexer.Progress(sessionId, store.count(sessionId), fresh, running, complete, error)
        }
        publish(0, running = true)
        val client = rest() ?: run { publish(0, running = false, error = "gateway not connected"); return }

        val ceiling = store.catchupCeiling(sessionId)?.toLongOrNull()
        val walk = TranscriptPages.pageUntil(
            pageSize = MAX_PAGE,
            fetch = { offset -> client.messages(sessionId, limit = MAX_PAGE, offset = offset).getOrThrow() },
            // Enough once the oldest row gathered is at or below what an earlier sweep filed —
            // the block containing the ceiling is then whole.
            enough = { rows -> ceiling != null && rows.first().id <= ceiling },
        )
        val newestFirst = TranscriptBuilder.build(sessionId, walk.rows).asReversed()
        val fresh = store.insertAll(ArchiveIndexer.indexableEntries(newestFirst))
        walk.rows.maxOfOrNull { it.id }?.let { store.setCatchupCeiling(sessionId, it.toString()) }
        val complete = wasComplete || walk.exhausted
        if (complete && !wasComplete) store.setBackfillComplete(sessionId)
        publish(fresh, running = false, complete = complete)
    }
}
