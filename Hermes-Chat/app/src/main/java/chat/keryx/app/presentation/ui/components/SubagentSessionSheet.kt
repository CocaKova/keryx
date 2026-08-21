package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.HubMessage
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.ToolGrammar

/**
 * What a subagent actually did (2.4).
 *
 * The wing shows a goal, a rollup and the summary that came back — enough to know it worked, and
 * nothing about how. That was the gap Jonny found on device: a delegation lands and there is no
 * way in. A delegated child is not a live gateway session and its event relay is never persisted,
 * so the only durable trace is the session the child wrote, and the gateway hands us its id
 * (`child_session_id`). This opens it.
 *
 * Read-only on purpose. The Sessions tab already owns resuming and deleting a session; a
 * subagent's transcript is evidence about a turn that already happened, and the useful verb here
 * is "show me", not "carry on".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentSessionSheet(
    run: Delegation,
    fetch: suspend (String) -> Result<List<HubMessage>>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⑂", fontSize = 15.sp, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    run.goal.ifBlank { "Delegated task" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val meta = buildList {
                if (run.taskCount > 1) add("task ${run.taskIndex + 1} of ${run.taskCount}")
                if (run.model.isNotBlank()) add(run.model)
                if (run.toolCount > 0) add("${run.toolCount} tool${if (run.toolCount == 1) "" else "s"}")
                run.durationSeconds?.takeIf { it > 0 }?.let { add("${it.toInt()}s") }
                if (run.totalTokens > 0) add("${run.totalTokens / 1000}k tok")
                if (run.apiCalls > 0) add("${run.apiCalls} calls")
                if (run.filesReadN > 0) add("${run.filesReadN} read")
                if (run.filesWrittenN > 0) add("${run.filesWrittenN} written")
                if (run.state == DelegationState.FAILED) add("failed")
                if (run.state == DelegationState.INTERRUPTED) add("interrupted")
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 23.dp),
                )
            }
            Spacer(Modifier.padding(top = 10.dp))

            val state by produceState<Result<List<HubMessage>>?>(initialValue = null, run.sessionId) {
                value = if (run.sessionId.isBlank()) {
                    Result.failure(IllegalStateException("This gateway didn't send a session id"))
                } else {
                    fetch(run.sessionId)
                }
            }
            when (val result = state) {
                null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.width(16.dp).heightIn(min = 16.dp, max = 16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Opening the subagent's session…", fontSize = 12.sp)
                }

                else -> result.fold(
                    onSuccess = { messages ->
                        if (messages.isEmpty()) {
                            // A child that ran entirely inside its parent's turn may never have
                            // been written out — say that, rather than showing an empty box that
                            // looks like a failed load.
                            Text(
                                "The gateway has no stored transcript for this subagent.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (run.summary.isNotBlank()) {
                                Text(
                                    run.summary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(messages) { m -> ChildTurn(m) }
                            }
                        }
                    },
                    onFailure = { e ->
                        Text(
                            "Couldn't open it — ${e.message?.take(120) ?: "unknown error"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (run.summary.isNotBlank()) {
                            Text(
                                run.summary,
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChildTurn(m: HubMessage) {
    val base = MaterialTheme.colorScheme.onSurface
    val isTool = m.toolName.isNotBlank()
    val label = when {
        isTool -> ToolGrammar.title(m.toolName, "", running = false)
        m.role == "assistant" -> "said"
        m.role == "user" -> "was asked"
        else -> m.role
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(base.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same glyph vocabulary as everywhere else — a child's tool call is still a tool call.
            Text(
                if (isTool) ToolGrammar.glyphOf(m.toolName) else "·",
                fontSize = 10.sp,
                color = base.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = base.copy(alpha = 0.6f),
            )
            if (m.toolCallCount > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "×${m.toolCallCount}",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = base.copy(alpha = 0.4f),
                )
            }
        }
        if (m.content.isNotBlank()) {
            Text(
                m.content,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = base.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Whether a wing has anything to open. */
val Delegation.openable: Boolean get() = sessionId.isNotBlank()
