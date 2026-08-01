package chat.keryx.app.data.archive

import chat.keryx.app.data.remote.MatrixService
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
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
        fun flushBlock() {
            if (block.isEmpty()) return
            fresh += store.insertAll(indexableEntries(block))
            block.clear()
            publish(running = true)
        }

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
            // Stop only at a block boundary (a breaker message), so the last flushed block is
            // never truncated mid-run — a cut-off block could misread a progress line as a
            // bubble. A hard cap bounds the walk if the history has no breaker for ages.
            knownStreak = if (ev != null && store.hasEvent(ev.event.id.full)) knownStreak + 1 else 0
            val rc = ev?.content?.getOrNull() as? RoomMessageEventContent
            val breaker = rc is RoomMessageEventContent.FileBased ||
                (rc != null && ev.event.sender.full == myId && !rc.body.trimStart().startsWith("/"))
            val continueWalk = !(wasComplete && knownStreak >= KNOWN_STREAK_STOP &&
                (breaker || knownStreak >= KNOWN_STREAK_HARD_STOP))
            if (!continueWalk) stoppedEarly = true
            continueWalk
        }.collect { ev ->
            val m = toMessage(ev, myId) ?: return@collect
            block += m
            val breaksBlock = m.mediaKind != null ||
                (m.sender == SenderType.ME && !m.content.trimStart().startsWith("/"))
            if (breaksBlock) flushBlock()
        }
        flushBlock()

        // The walk ending on its own means it reached the start of the room: backfill is whole.
        val complete = wasComplete || !stoppedEarly
        if (complete && !wasComplete) store.setBackfillComplete(roomId)
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
            is RoomMessageEventContent.FileBased.Image -> chat.keryx.app.domain.model.MediaKind.IMAGE
            is RoomMessageEventContent.FileBased.Video -> chat.keryx.app.domain.model.MediaKind.VIDEO
            is RoomMessageEventContent.FileBased.Audio -> chat.keryx.app.domain.model.MediaKind.AUDIO
            is RoomMessageEventContent.FileBased.File -> chat.keryx.app.domain.model.MediaKind.FILE
            else -> null
        }
        var body = rc.body
        if (rc.relatesTo?.replyTo != null) body = stripReplyFallback(body)
        val sender = ev.event.sender.full
        return Message(
            id = ev.event.id.full,
            sessionId = ev.event.roomId.full,
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
                roomId = m.sessionId,
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
