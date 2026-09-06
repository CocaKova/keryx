package chat.keryx.app.presentation.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.SenderType
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.ToolTheaterRun
import chat.keryx.app.presentation.ui.components.keryxLightSweep
import chat.keryx.app.presentation.ui.components.KeryxMotion
import chat.keryx.app.presentation.ui.components.keryxReveal
import chat.keryx.app.presentation.ui.components.keryxConceal
import chat.keryx.app.presentation.ui.components.rememberSweepProgress
import androidx.compose.ui.text.font.FontFamily
import chat.keryx.app.presentation.ui.components.GroupedTimeline
import chat.keryx.app.presentation.ui.components.groupChatItemsIncremental
import chat.keryx.app.presentation.ui.components.withLiveTheater
import chat.keryx.app.presentation.ui.components.ArrivalMark
import chat.keryx.app.presentation.ui.components.MessageBubble
import chat.keryx.app.presentation.ui.components.PendingSendBubble
import chat.keryx.app.presentation.ui.components.StreamingBubble
import chat.keryx.app.presentation.ui.components.TelemetryMessageRow
import chat.keryx.app.presentation.ui.components.WaitingIndicator
import chat.keryx.app.presentation.ui.components.WorkingStatusBar
import chat.keryx.app.presentation.ui.components.replyPreviewText
import chat.keryx.app.presentation.ui.components.shortSender
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// The "/" palette over the composer. Split out of ChatScreen.kt in 2.10 (Phase E): a move,
// zero behaviour change.

internal data class SlashCommand(
    val cmd: String,
    val desc: String,
    val takesArgs: Boolean,
    val aliases: List<String> = emptyList(),
)

/** Offline fallback: the palette before the gateway's live registry has been fetched. */
internal val PRESET_COMMANDS = listOf(
    SlashCommand("/new", "Start a fresh conversation", false),
    SlashCommand("/compress", "Compress / summarize this thread", false),
    SlashCommand("/handoff", "Hand off context to a new session", false),
    SlashCommand("/steer", "Steer the agent mid-task", true),
    SlashCommand("/think", "Ask for deeper reasoning", true),
    SlashCommand("/model", "Switch the active model", true),
    SlashCommand("/reset", "Reset the agent's working state", false),
    SlashCommand("/help", "List what this agent can do", false),
    SlashCommand("/status", "Show agent + system status", false),
    SlashCommand("/memory", "Recall or edit long-term memory", true),
    SlashCommand("/tools", "List available tools", false),
)

@Composable
fun CommandPaletteMenu(
    filter: String,
    recents: List<String>,
    onCommandSelected: (String, Boolean) -> Unit,
    live: List<chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand> = emptyList(),
) {
    // The live registry (what's actually installed on the connected gateway — core commands
    // plus plugin-registered ones) replaces the preset guess once fetched. An args_hint means
    // the command takes arguments: fill the composer instead of auto-sending.
    val all = remember(live) {
        if (live.isEmpty()) PRESET_COMMANDS
        else live.map {
            SlashCommand(
                cmd = it.cmd,
                desc = it.description,
                takesArgs = it.argsHint.isNotBlank(),
                aliases = it.aliases,
            )
        }
    }
    val q = filter.trim().lowercase()
    val matches = all.filter {
        q.isBlank() || it.cmd.removePrefix("/").startsWith(q) ||
            it.aliases.any { a -> a.removePrefix("/").startsWith(q) }
    }
    // Surface recently-used commands first.
    val ordered = matches.sortedByDescending { recents.indexOf(it.cmd).let { i -> if (i < 0) -1 else recents.size - i } }

    if (ordered.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
            items(ordered, key = { it.cmd }) { sc ->
                val isRecent = sc.cmd in recents
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCommandSelected(sc.cmd, sc.takesArgs) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sc.cmd, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (sc.takesArgs) {
                                Spacer(Modifier.width(6.dp))
                                Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                            }
                        }
                        Text(sc.desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    if (isRecent) Text("recent", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
        }
    }
}
