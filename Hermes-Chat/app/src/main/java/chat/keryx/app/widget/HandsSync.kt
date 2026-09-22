package chat.keryx.app.widget

import chat.keryx.app.KeryxApp
import chat.keryx.app.note.NoteTileService
import chat.keryx.app.transport.direct.DirectTransport
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The two hands on the launcher (2.13) follow the link. The widget's card and the tile's
 * state both say whether the gateway is reachable; a socket that drops or comes back would
 * otherwise wait up to fifteen minutes for the periodic worker to notice. One collector in
 * the app scope, one line per surface. The run path has its own hook ([KeryxWidget.refresh]
 * from the run notice) — this is only the link.
 */
object HandsSync {
    fun observe(app: KeryxApp) {
        val direct = app.transport as? DirectTransport ?: return
        app.appScope.launch {
            // The first value is the state the widget was drawn with when it asked; only a
            // CHANGE is news, and CONNECTING flickers are collapsed by the snapshot's own wait.
            direct.linkState().distinctUntilChanged().drop(1).collect {
                KeryxWidget.refresh(app)
                NoteTileService.requestListening(app)
            }
        }
    }
}
