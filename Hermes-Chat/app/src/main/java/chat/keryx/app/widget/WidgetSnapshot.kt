package chat.keryx.app.widget

import android.content.Context
import chat.keryx.app.KeryxApp
import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.core.model.LinkState
import chat.keryx.core.model.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read the widget's inputs from the process and project them.
 *
 * Through the app's ONE transport, never a socket of the widget's own: the process that draws
 * the widget is the app process, and [KeryxApp] has already dialled the gateway in `onCreate`
 * (or restored the Matrix session). The tail is a [DirectTransport.peekLatest] — a REST page,
 * no `session.resume`, so a widget on the home screen never costs the gateway a live agent
 * (the drawer's old trap, 2.6.2). Every wait is bounded: a widget that takes seconds to draw
 * is a widget the launcher gives up on.
 */
object WidgetSnapshot {

    /** How long a cold process gets to say whether it is connected before we call it so. */
    private const val LINK_WAIT_MS = 2_500L
    private const val ROSTER_WAIT_MS = 3_000L
    private const val PEEK_WAIT_MS = 4_000L

    suspend fun read(context: Context): WidgetState {
        val app = context.applicationContext as? KeryxApp ?: return WidgetState.empty(WidgetState.Link.OFFLINE)
        val transport = app.transport
        val direct = transport as? DirectTransport
        // A door still dialling reads as reconnecting, not offline — but only for a moment: a
        // process that was woken just to draw us has not had time to say anything yet.
        val link: LinkState? = if (direct != null) {
            withTimeoutOrNull(LINK_WAIT_MS) { direct.linkState().first { it != LinkState.CONNECTING } }
                ?: direct.linkState().first()
        } else {
            // The Matrix door has no socket truth of its own here; signed in is as live as it gets.
            val ok = withTimeoutOrNull(LINK_WAIT_MS) { transport.isLoggedIn().first { it } } ?: false
            if (ok) LinkState.CONNECTED else LinkState.DISCONNECTED
        }
        val runs = direct?.runActivities()?.value.orEmpty()
        val rooms = withTimeoutOrNull(ROSTER_WAIT_MS) { transport.getRooms().first { it.isNotEmpty() } }.orEmpty()
        val room = WidgetState.pick(rooms, runs)
        val latest: List<Message> = if (room == null) emptyList() else withTimeoutOrNull(PEEK_WAIT_MS) {
            if (direct != null) direct.peekLatest(room.id, 3)
            else transport.getMessages(room.id, 3).map { it.takeLast(3) }.first { it.isNotEmpty() }
        }.orEmpty()
        return WidgetState.from(link, room, latest, runs, now = System.currentTimeMillis())
    }
}
