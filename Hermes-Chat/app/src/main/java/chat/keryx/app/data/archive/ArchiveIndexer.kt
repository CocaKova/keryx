package chat.keryx.app.data.archive

import chat.keryx.app.data.remote.MatrixService
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.MessageParser
import chat.keryx.app.presentation.ui.components.groupChatItems
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
 * What gets indexed is decided by the SAME grouping the chat renders with ([groupChatItems]):
 * messages are buffered into contiguous agent blocks and only what would render as a dialogue
 * bubble ([ChatRenderItem.Single]) enters the index. Everything the chat folds into a tool-run
 * accordion — tool calls, headerless progress sends, mid-run fences, edited progress messages
 * that end as bare "⏰ Scheduling …" lines — is machinery, not dialogue, and stays out. This is
 * the structural invariant, not a pattern blocklist: if the chat shows it as a bubble, search
 * can find it; if the chat tucks it into a run card, search ignores it.
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
        val myId = client.userId.full
        store.ensureAccount(myId)
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

        // The current contiguous agent block, newest-first (the walk direction). Flushed through
        // the chat's grouping whenever a block boundary lands — a human/media message, exactly
        // where the chat's own walk breaks blocks.
        val block = ArrayList<Message>(64)
        var frontier: String? = store.backfillFrontier(roomId)
        var frontierDirty = false
        fun flushBlock() {
            if (block.isNotEmpty()) {
                fresh += store.insertAll(indexableEntries(block))
                block.clear()
            }
            if (frontierDirty) {
                frontier?.let { store.setBackfillFrontier(roomId, it) }
                frontierDirty = false
            }
            publish(running = true)
        }

        /** One backwards walk from [fromEventId]. [ceiling] is an exact bound: ground at-or-below
         *  that event was fully processed by an earlier sweep, so the walk ends at the first
         *  block boundary past it (never mid-block — a cut-off block could misread a progress
         *  line as a bubble; the boundary overshoot re-inserts a few known events, which dedupe).
         *  [stopAtKnown] is the heuristic fallback for pre-ceiling installs: a long run of
         *  already-indexed events. [trackFrontier] marks this walk as breaking new ground: it
         *  records how deep it got (every visited event, message or not) so the next sweep
         *  resumes there. Returns true when the walk ran out of history on its own — it reached
         *  the start of the room. */
        suspend fun walk(
            fromEventId: net.folivo.trixnity.core.model.EventId,
            ceiling: String?,
            stopAtKnown: Boolean,
            trackFrontier: Boolean,
        ): Boolean {
            var knownStreak = 0
            var pastCeiling = 0
            var stoppedEarly = false
            client.room.getTimelineEvents(rid, fromEventId, GetEvents.Direction.BACKWARDS) {
                fetchSize = 100
                fetchTimeout = 30.seconds
            }.transformWhile { eventFlow ->
                // Wait (bounded) for decryption; an event that never resolves is skipped, not fatal.
                val ev = withTimeoutOrNull(10_000) { eventFlow.first { it.content != null } }
                    ?: withTimeoutOrNull(2_000) { eventFlow.firstOrNull() }
                if (ev != null) emit(ev)
                if (ev != null && (pastCeiling > 0 || ev.event.id.full == ceiling)) pastCeiling++
                knownStreak = if (ev != null && store.hasEvent(ev.event.id.full)) knownStreak + 1 else 0
                val rc = ev?.content?.getOrNull() as? RoomMessageEventContent
                val breaker = rc is RoomMessageEventContent.FileBased ||
                    (rc != null && ev.event.sender.full == myId && !rc.body.trimStart().startsWith("/"))
                // The hard caps bound both stops if the history has no breaker for ages.
                val ceilingStop = pastCeiling > 0 && (breaker || pastCeiling >= KNOWN_STREAK_HARD_STOP)
                val knownStop = stopAtKnown && knownStreak >= KNOWN_STREAK_STOP &&
                    (breaker || knownStreak >= KNOWN_STREAK_HARD_STOP)
                val continueWalk = !(ceilingStop || knownStop)
                if (!continueWalk) stoppedEarly = true
                continueWalk
            }.collect { ev ->
                if (trackFrontier) {
                    frontier = ev.event.id.full
                    frontierDirty = true
                }
                val m = toMessage(ev, myId) ?: return@collect
                block += m
                val breaksBlock = m.mediaKind != null ||
                    (m.sender == SenderType.ME && !m.content.trimStart().startsWith("/"))
                if (breaksBlock) flushBlock()
            }
            flushBlock()
            return !stoppedEarly
        }

        // Catch-up: from the newest event down to the last finished sweep's ceiling (or, on a
        // pre-ceiling index, until the known-ground heuristic fires). The very first sweep has
        // no walked ground at all — that walk IS the backfill, and it tracks its frontier so an
        // interruption never costs the progress made.
        val resumeFrom = frontier
        val ceiling = store.catchupCeiling(roomId)
        val firstEver = !wasComplete && resumeFrom == null && ceiling == null
        var reachedStart = walk(
            anchor.event.id,
            ceiling = ceiling,
            stopAtKnown = !firstEver,
            trackFrontier = firstEver,
        )
        // The catch-up finished cleanly (early returns above throw on cancellation), so ground
        // below this sweep's anchor is now covered: it is the next sweep's exact stop line.
        store.setCatchupCeiling(roomId, anchor.event.id.full)

        // Resume an unfinished backfill where it last left off — never from the top. Before the
        // frontier existed, every sweep re-walked all of history and the Archive sat on
        // "reaching back…" forever.
        if (!wasComplete && !reachedStart && resumeFrom != null) {
            try {
                reachedStart = walk(
                    net.folivo.trixnity.core.model.EventId(resumeFrom),
                    ceiling = null,
                    stopAtKnown = false,
                    trackFrontier = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A frontier the timeline can no longer resolve (e.g. the client store was
                // rebuilt) would fail every sweep from now on — drop it so the next sweep
                // restarts the backfill cleanly from the top.
                android.util.Log.e("KeryxArchive", "backfill resume failed for $roomId", e)
                store.clearBackfillFrontier(roomId)
                publish(running = false, error = "backfill resume failed — will restart")
                return
            }
        }

        // Running out of history means the backfill is whole; the frontier has done its job.
        val complete = wasComplete || reachedStart
        if (complete && !wasComplete) {
            store.setBackfillComplete(roomId)
            store.clearBackfillFrontier(roomId)
        }
        publish(running = false, complete = complete)
    }

    /** Minimal domain mapping for grouping: ME for the user's own events, HERMES for everyone
     *  else. (The archive has no group-room rendering stakes — the grouping only needs to know
     *  which side of the conversation a message is on.) */
    private fun toMessage(ev: TimelineEvent, myId: String): Message? {
        val rc = ev.content?.getOrNull() as? RoomMessageEventContent ?: return null
        // m.replace edit events: Trixnity folds the edit into the original — the carrier is noise.
        if (rc.relatesTo is RelatesTo.Replace) return null
        val mediaKind = when (rc) {
            is RoomMessageEventContent.FileBased.Image -> chat.keryx.core.model.MediaKind.IMAGE
            is RoomMessageEventContent.FileBased.Video -> chat.keryx.core.model.MediaKind.VIDEO
            is RoomMessageEventContent.FileBased.Audio -> chat.keryx.core.model.MediaKind.AUDIO
            is RoomMessageEventContent.FileBased.File -> chat.keryx.core.model.MediaKind.FILE
            else -> null
        }
        var body = rc.body
        if (rc.relatesTo?.replyTo != null) body = stripReplyFallback(body)
        val sender = ev.event.sender.full
        return Message(
            id = ev.event.id.full,
            roomId = ev.event.roomId.full,
            sender = if (sender == myId) SenderType.ME else SenderType.HERMES,
            content = body,
            timestamp = ev.event.originTimestamp,
            senderId = sender,
            mediaKind = mediaKind,
            fileName = (rc as? RoomMessageEventContent.FileBased)?.fileName ?: "",
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

    companion object {
        private const val KNOWN_STREAK_STOP = 25
        private const val KNOWN_STREAK_HARD_STOP = 500

        /**
         * The index-worthy entries of one contiguous block, [newestFirst] exactly as walked.
         * Runs the chat's own grouping: dialogue bubbles in, tool-run internals out, telemetry
         * out. Pure — this is the whole indexing policy, unit-testable without a device.
         */
        fun indexableEntries(newestFirst: List<Message>): List<ArchiveStore.Entry> =
            groupChatItems(newestFirst)
                .filterIsInstance<ChatRenderItem.Single>()
                .mapNotNull { single -> entryFor(single.message) }

        private fun entryFor(m: Message): ArchiveStore.Entry? {
            if (m.content.isBlank() && m.mediaKind == null) return null
            if (m.mediaKind == null && MessageParser.isTelemetryMessage(m.content)) return null
            val body = searchableText(m.content, fromMe = m.sender == SenderType.ME)
            if (m.mediaKind == null && body.isBlank()) return null
            return ArchiveStore.Entry(
                eventId = m.id,
                roomId = m.roomId,
                sender = m.senderId,
                timestamp = m.timestamp,
                mediaKind = m.mediaKind?.name,
                fileName = m.fileName,
                body = body,
            )
        }

        /**
         * What of a message body belongs in the search index: the answer, not the machinery.
         * The user's own messages are always all answer. For everyone else, a body carrying
         * agent chrome (tool calls, reasoning, telemetry, action-output payloads) is reduced to
         * its prose and table segments — searching should surface what was *said*, never the
         * innards of a tool invocation. A chrome-free body (any normal human or agent message)
         * passes through whole.
         */
        fun searchableText(body: String, fromMe: Boolean): String {
            if (fromMe) return body.trim()
            // cacheable=false: historical bodies are parsed once at index time — letting them
            // churn the render LRU would evict the live chat's committed messages.
            val segments = MessageParser.parse(body, cacheable = false)
            val chrome = segments.any {
                it is MessageParser.Segment.Tools || it is MessageParser.Segment.Thinking ||
                    it is MessageParser.Segment.Telemetry || it is MessageParser.Segment.ActionOutput
            }
            if (!chrome) return MessageParser.extractKeryx(body).text.trim()
            return segments.mapNotNull { seg ->
                when (seg) {
                    is MessageParser.Segment.Text -> MessageParser.extractKeryx(seg.text).text
                    is MessageParser.Segment.Table ->
                        (seg.header + seg.rows.flatten()).joinToString(" ")
                    else -> null
                }
            }.joinToString("\n").trim()
        }
    }
}
