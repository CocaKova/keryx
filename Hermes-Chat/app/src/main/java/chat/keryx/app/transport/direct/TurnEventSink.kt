package chat.keryx.app.transport.direct

import chat.keryx.core.model.TurnEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The transport's turn events (deltas, breaks, ends), with one extra mode: a batch.
 *
 * Live, events trickle — a coalesced delta every ~33 ms — and `tryEmit` into a buffer never
 * fills. A reconnect replay is the opposite: up to the gateway's whole ring (512 frames) applied
 * in one go, and a `tryEmit` into a full buffer DROPS. The frame most likely to be dropped is the
 * last one — the turn's End, which settles the working banner and raises the turn-end alert. So a
 * replay runs inside [batched]: its events collect, consecutive deltas of one session fold into
 * one, and what is left (a handful per turn) goes out in order before any later live frame.
 */
internal class TurnEventSink(capacity: Int) {
    private val flow = MutableSharedFlow<TurnEvent>(extraBufferCapacity = capacity)
    val events: SharedFlow<TurnEvent> = flow.asSharedFlow()

    @Volatile private var batch: MutableList<TurnEvent>? = null

    fun tryEmit(ev: TurnEvent): Boolean {
        batch?.let { synchronized(it) { it += ev }; return true }
        return flow.tryEmit(ev)
    }

    /** Run [block] with emissions collected, then emit them coalesced. Not reentrant. */
    fun <T> batched(block: () -> T): T {
        val mine = mutableListOf<TurnEvent>()
        batch = mine
        try {
            return block()
        } finally {
            batch = null
            coalesce(synchronized(mine) { mine.toList() }).forEach { flow.tryEmit(it) }
        }
    }

    companion object {
        /** Adjacent deltas of one session become one delta; order and every other event stay. */
        fun coalesce(events: List<TurnEvent>): List<TurnEvent> {
            val out = ArrayList<TurnEvent>(events.size)
            for (ev in events) {
                val prev = out.lastOrNull()
                if (ev is TurnEvent.Delta && prev is TurnEvent.Delta && prev.sessionId == ev.sessionId) {
                    out[out.lastIndex] = TurnEvent.Delta(ev.sessionId, prev.text + ev.text)
                } else {
                    out += ev
                }
            }
            return out
        }
    }
}
