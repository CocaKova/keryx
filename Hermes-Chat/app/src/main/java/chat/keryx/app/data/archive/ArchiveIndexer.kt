package chat.keryx.app.data.archive

import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.presentation.ui.components.MessageParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.folivo.trixnity.client.room
import net.folivo.trixnity.client.store.TimelineEvent
import net.folivo.trixnity.clientserverapi.model.rooms.GetEvents
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import kotlin.time.Duration.Companion.seconds

/**
 * Walks a room's timeline backwards from the newest event and writes every message into the
 * [ArchiveStore]. Trixnity fills timeline gaps from the server as the walk crosses them, so the
 * first sweep of a room is the big backfill (it can run minutes for months of history); after
 * that the same walk stops within a few dozen events of the top and a sweep is near-instant.
 *
 * E2EE: old events decrypt from the megolm keys already in the client's store — the walk waits
 * (bounded) for each event's decryption. An event whose keys are truly gone is indexed as absent
 * (skipped), never blocks the sweep.
 */
class ArchiveIndexer(
    private val matrix: MatrixService,
    private val store: ArchiveStore,
) {

    data class Progress(
        val roomId: String,
        val indexed: Int,
        val freshThisSweep: Int,
        val running: Boolean,
        val complete: Boolean,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private var job: Job? = null

    /** Start (or keep running) a sweep of [roomId]. A sweep already running for the same room is
     *  left alone; one for another room is replaced. */
    fun sweep(scope: CoroutineScope, roomId: String) {
        if (job?.isActive == true && _progress.value?.roomId == roomId) return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                runSweep(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KeryxArchive", "sweep failed for $roomId", e)
                _progress.value = Progress(
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

    private suspend fun runSweep(roomId: String) {
        val client = matrix.client.value ?: return
        store.ensureAccount(client.userId.full)
        val rid = RoomId(roomId)
        val wasComplete = store.backfillComplete(roomId)
        var fresh = 0
        fun publish(running: Boolean, complete: Boolean = wasComplete, error: String? = null) {
            _progress.value = Progress(roomId, store.count(roomId), fresh, running, complete, error)
        }
        publish(running = true)

        val anchor = withTimeoutOrNull(15_000) {
            client.room.getLastTimelineEvent(rid).firstOrNull()?.firstOrNull()
        }
        if (anchor == null) {
            publish(running = false, error = "no timeline yet")
            return
        }

        val batch = ArrayList<ArchiveStore.Entry>(64)
        var knownStreak = 0
        var stoppedEarly = false
        client.room.getTimelineEvents(rid, anchor.event.id, GetEvents.Direction.BACKWARDS) {
            fetchSize = 100
            fetchTimeout = 30.seconds
        }.transformWhile { eventFlow ->
            // Wait (bounded) for decryption; an event that never resolves is skipped, not fatal.
            val ev = withTimeoutOrNull(10_000) { eventFlow.first { it.content != null } }
                ?: withTimeoutOrNull(2_000) { eventFlow.firstOrNull() }
            if (ev != null) emit(ev)
            // Once the room was fully backfilled, a long run of already-known events means the
            // sweep has caught up to a previous one — stop instead of re-walking all of history.
            knownStreak = if (ev != null && store.hasEvent(ev.event.id.full)) knownStreak + 1 else 0
            val continueWalk = !(wasComplete && knownStreak >= KNOWN_STREAK_STOP)
            if (!continueWalk) stoppedEarly = true
            continueWalk
        }.collect { ev ->
            toEntry(ev)?.let { batch += it }
            if (batch.size >= 50) {
                fresh += store.insertAll(batch)
                batch.clear()
                publish(running = true)
            }
        }
        fresh += store.insertAll(batch)

        // The walk ending on its own means it reached the start of the room: backfill is whole.
        val complete = wasComplete || !stoppedEarly
        if (complete && !wasComplete) store.setBackfillComplete(roomId)
        publish(running = false, complete = complete)
    }

    private fun toEntry(ev: TimelineEvent): ArchiveStore.Entry? {
        val rc = ev.content?.getOrNull() as? RoomMessageEventContent ?: return null
        // m.replace edit events: Trixnity folds the edit into the original — the carrier is noise.
        if (rc.relatesTo is RelatesTo.Replace) return null
        val mediaKind = when (rc) {
            is RoomMessageEventContent.FileBased.Image -> "IMAGE"
            is RoomMessageEventContent.FileBased.Video -> "VIDEO"
            is RoomMessageEventContent.FileBased.Audio -> "AUDIO"
            is RoomMessageEventContent.FileBased.File -> "FILE"
            else -> null
        }
        val fileName = (rc as? RoomMessageEventContent.FileBased)?.fileName ?: ""
        var body = rc.body
        if (rc.relatesTo?.replyTo != null) body = stripReplyFallback(body)
        // Index what the user actually sees: ⟦keryx⟧ markers stripped, telemetry spam dropped.
        body = MessageParser.extractKeryx(body).text.trim()
        if (mediaKind == null && (body.isBlank() || MessageParser.isTelemetryMessage(rc.body))) {
            return null
        }
        return ArchiveStore.Entry(
            eventId = ev.event.id.full,
            roomId = ev.event.roomId.full,
            sender = ev.event.sender.full,
            timestamp = ev.event.originTimestamp,
            mediaKind = mediaKind,
            fileName = fileName,
            body = body,
        )
    }

    /** Same mx-reply fallback strip the chat mapper applies (kept local: data must not depend on
     *  the repository's private helpers). */
    private fun stripReplyFallback(body: String): String {
        val lines = body.lines()
        var i = 0
        while (i < lines.size && lines[i].startsWith(">")) i++
        if (i < lines.size && lines[i].isBlank()) i++
        val rest = lines.drop(i).joinToString("\n").trim()
        return rest.ifBlank { body.trim() }
    }

    private companion object {
        const val KNOWN_STREAK_STOP = 25
    }
}
