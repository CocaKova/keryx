package chat.keryx.app.data.archive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The producer half of the Archive: something that fills the [ArchiveStore] for a room and
 * reports how far it got. One store, two producers — the Matrix timeline walk
 * ([ArchiveIndexer]) and the gateway's REST transcript pages ([RestArchiveIndexer]). The
 * consumer side (search, media, saved, context) never learns which one is behind it.
 */
interface ArchiveSweeper {
    /** Live progress of the current sweep (null before the first sweep of this process). */
    val progress: StateFlow<ArchiveIndexer.Progress?>

    /** Start (or keep running) a sweep of [roomId]. A sweep already running for the same room
     *  is left alone; one for another room is replaced. */
    fun sweep(scope: CoroutineScope, roomId: String)

    /** Sweep several rooms one after another in one job (2.10, the cross-session Archive).
     *  Progress reports the whole index, under the room id "*". The default sweeps the first. */
    fun sweepMany(scope: CoroutineScope, roomIds: List<String>) {
        roomIds.firstOrNull()?.let { sweep(scope, it) }
    }
}
