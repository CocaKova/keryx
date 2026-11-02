package chat.keryx.app.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import chat.keryx.app.R

/**
 * A room's name as shown (3.0 languages). When the gateway gives a session no title, the
 * transport names it in English ("New session", "Session", "Branch") — and that English is
 * also what the roster merge and the title checks compare against, so it stays English in
 * the data. Only the face changes: here, at the moment it is drawn.
 */
@Composable
fun shownRoomName(name: String): String = when (name) {
    "New session" -> stringResource(R.string.chat_new_session)
    "Session" -> stringResource(R.string.p2_room_untitled_session)
    "Branch" -> stringResource(R.string.p2_room_untitled_branch)
    else -> name
}
