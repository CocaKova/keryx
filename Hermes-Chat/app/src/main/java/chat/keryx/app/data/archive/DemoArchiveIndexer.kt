package chat.keryx.app.data.archive

import chat.keryx.core.demo.DemoScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The demo's Archive (3.0 herald): every scripted session filed at once, through the same
 * entries a real sweep writes, so searching the demo finds what its chats say. Into the demo's
 * own store only (see KeryxApp).
 */
class DemoArchiveIndexer(private val store: ArchiveStore) : ArchiveSweeper {

    private val _progress = MutableStateFlow<ArchiveIndexer.Progress?>(null)
    override val progress: StateFlow<ArchiveIndexer.Progress?> = _progress.asStateFlow()

    override fun sweep(scope: CoroutineScope, roomId: String) = sweepMany(scope, listOf(roomId))

    override fun sweepMany(scope: CoroutineScope, roomIds: List<String>) {
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var fresh = 0
            for (s in DemoScript.sessions) {
                if (store.backfillComplete(s.id)) continue
                fresh += store.insertAll(ArchiveIndexer.indexableEntries(DemoScript.transcript(s.id, now).asReversed()))
                store.setBackfillComplete(s.id)
            }
            val room = roomIds.singleOrNull() ?: RestArchiveIndexer.ALL
            _progress.value = ArchiveIndexer.Progress(
                room, store.count(room.takeIf { it != RestArchiveIndexer.ALL }), fresh, running = false, complete = true,
            )
        }
    }
}
