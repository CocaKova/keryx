package chat.keryx.app.note

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import chat.keryx.app.KeryxApp
import chat.keryx.app.R
import chat.keryx.app.transport.direct.DirectTransport
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Quick Settings tile (2.13 Part B): "Note to <agent>", two swipes from anywhere.
 *
 * A tap opens [NoteActivity] — a one-line composer over whatever you were doing — and the tile
 * itself mirrors the gateway link: ACTIVE while the socket is up, INACTIVE otherwise, so the
 * shade already says whether a note will land before you write it. The link is read the way
 * the widget reads it, off the process's one transport; nothing here dials anything.
 *
 * The label names the agent when the roster has named it (the default profile's friendly
 * name), and says "agent" when it has not: Keryx is public and the name is the install's.
 */
class NoteTileService : TileService() {

    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as? KeryxApp ?: return
        val direct = app.transport as? DirectTransport
        val up: Flow<Boolean> = direct?.linkState()?.map { it == LinkState.CONNECTED } ?: app.transport.isLoggedIn()
        val roster: Flow<List<BotProfile>> = direct?.agents() ?: flowOf(emptyList())
        watch?.cancel()
        // Collected only between start and stop listening — the tile object is only valid then.
        watch = app.appScope.launch {
            combine(up, roster) { live, agents -> live to agentName(agents) }
                .distinctUntilChanged()
                .collect { (live, name) -> withContext(Dispatchers.Main) { paint(live, name) } }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    private fun paint(live: Boolean, name: String?) {
        val tile = qsTile ?: return
        tile.state = if (live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (name != null) getString(R.string.note_tile_label_to, name) else getString(R.string.note_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_keryx)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (live) R.string.note_tile_live else R.string.note_tile_offline)
        }
        runCatching { tile.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        // A note is a message to the agent; behind the keyguard that is not yours to send yet.
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        val intent = Intent(this, NoteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** The default profile's friendly name, when the install gave it one. "Default" is not a name. */
        fun agentName(agents: List<BotProfile>): String? =
            agents.firstOrNull { it.isDefault }
                ?.let { it.title.ifBlank { it.displayName } }
                ?.takeIf { it.isNotBlank() }

        /** Ask the system to wake the tile so it re-reads the link — the link watch calls this. */
        fun requestListening(context: Context) {
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, NoteTileService::class.java))
            }
        }
    }
}
