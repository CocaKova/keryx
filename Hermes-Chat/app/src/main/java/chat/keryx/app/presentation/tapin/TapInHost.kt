package chat.keryx.app.presentation.tapin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.SubagentSessionSheet
import chat.keryx.app.presentation.ui.components.ToolRunEntry
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.ToolCall
import kotlinx.coroutines.delay

/**
 * Tap-In, wired (2.12): collects the chat's live state, cuts the current turn out of the
 * rendered items, projects, and draws. Lives beside the chat rather than inside it so the chat
 * screen's only concern is whether the door is open.
 *
 * The helper sheet is hosted here, inside the space's own window, so a card tapped on the deck
 * opens over the deck and not under it.
 */
@Composable
fun TapInHost(
    viewModel: ChatViewModel,
    /** The chat's render items, newest first — both doors already reconciled into them. */
    itemsNewestFirst: List<ChatRenderItem>,
    /** The side-channel's record of the newest run, when watched live. */
    structured: List<ToolCall>,
    onClose: () -> Unit,
) {
    val currentRoom by viewModel.currentRoom.collectAsState()
    val liveStream by viewModel.liveStream.collectAsState()
    val awaitingReply by viewModel.awaitingReply.collectAsState()
    val sessionStatus by viewModel.sessionStatus.collectAsState()
    val workLabel by viewModel.workLabel.collectAsState()
    val workStartedAt by viewModel.workStartedAt.collectAsState()
    val usage by viewModel.contextUsage.collectAsState()

    val roomId = currentRoom?.id
    val stream = liveStream?.takeIf { it.roomId == roomId }
    val running = awaitingReply || stream?.status == chat.keryx.app.presentation.LiveStreamStatus.STREAMING

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { now = System.currentTimeMillis(); delay(1_000) }
    }

    val slice = remember(itemsNewestFirst, structured) { TurnSlice.of(itemsNewestFirst, structured) }
    // The Matrix door keeps its live text out of the items; the direct door writes it in. Take
    // the stream's when it has one, the slice's otherwise — never both.
    val reasoning = stream?.reasoning?.takeIf { it.isNotBlank() } ?: slice.reasoning
    val answer = stream?.text?.takeIf { it.isNotBlank() } ?: slice.answer
    val roomUsage = usage?.takeIf { it.roomId == roomId }

    val state = remember(slice, reasoning, answer, running, sessionStatus, workLabel, workStartedAt, now, roomUsage, stream?.charsPerSec) {
        TapIn.project(
            running = running,
            status = sessionStatus,
            workLabel = workLabel,
            calls = slice.calls,
            delegations = slice.delegations,
            reasoning = reasoning,
            answer = answer,
            startedAtMs = workStartedAt ?: stream?.startedAt,
            nowMs = now,
            usedTokens = roomUsage?.used,
            maxTokens = roomUsage?.max,
            model = roomUsage?.model.orEmpty(),
            charsPerSec = stream?.charsPerSec ?: 0f,
        )
    }

    var helper by remember { mutableStateOf<Delegation?>(null) }
    TapInScreen(state = state, onClose = onClose, onOpenHelper = { helper = it })

    helper?.let { picked ->
        // Re-resolved by key so a card tapped while flying keeps growing, as the chat's does.
        val live = itemsNewestFirst.asSequence()
            .filterIsInstance<ChatRenderItem.ToolRun>()
            .flatMap { it.entries.asSequence() }
            .filterIsInstance<ToolRunEntry.Delegated>()
            .map { it.run }
            .firstOrNull { it.key == picked.key }
            ?: slice.delegations.firstOrNull { it.key == picked.key }
            ?: picked
        SubagentSessionSheet(
            run = live,
            fetch = { id -> viewModel.hub.sessionMessages(id) },
            onDismiss = { helper = null },
        )
    }
}
