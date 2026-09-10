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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.keryx.app.data.remote.HermesStreamClient.HubMessage
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.DelegationBeat
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.ToolGrammar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
 *
 * Two sources, one sheet (2.11). A child that has LANDED has a stored session, and that is the
 * fuller record, so it wins. A child still FLYING has none — its transcript is written when it
 * finishes — but its `subagent.*` frames are arriving right now, and [Delegation.trail] is
 * holding them. Opening a running wing shows that, live, and swaps itself for the stored
 * session the moment the wing settles. Before this the sheet could only be opened after the
 * fact, which is the wrong half of a delegation to be able to watch.
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
            // The sheet keeps its own clock while the child is flying, for the same reason the
            // wing does: a running subagent with no elapsed reads the same at 5s and at 4min.
            val now by produceState(0L, run.running) {
                value = if (run.running) System.currentTimeMillis() else 0L
                while (run.running && isActive) {
                    delay(1_000)
                    value = System.currentTimeMillis()
                }
            }
            val meta = buildList {
                if (run.taskCount > 1) add("task ${run.taskIndex + 1} of ${run.taskCount}")
                if (run.model.isNotBlank()) add(run.model)
                if (run.toolCount > 0) add("${run.toolCount} tool${if (run.toolCount == 1) "" else "s"}")
                run.elapsedSeconds(now)?.takeIf { it > 0 }?.let { add("${it.toInt()}s") }
                if (run.running) add("running")
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

            if (run.running) {
                // No stored session exists yet — the child writes its transcript when it lands.
                // What we have is what we watched arrive, and it is still arriving.
                TrailList(run.trail, live = true)
                return@Column
            }

            // Re-keyed on `running` as well as the id, so a sheet opened over a flying wing
            // fetches the real transcript the moment that wing settles underneath it.
            val state by produceState<Result<List<HubMessage>>?>(initialValue = null, run.sessionId, run.running) {
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
                            // looks like a failed load. If we WATCHED it, though, say that
                            // instead and show what we saw: a second-hand record beats none,
                            // as long as it is labelled as the one we kept rather than the one
                            // the gateway holds.
                            Text(
                                if (run.trail.isNotEmpty())
                                    "The gateway stored no transcript for this subagent — this is what it was seen doing."
                                else
                                    "The gateway has no stored transcript for this subagent.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (run.trail.isNotEmpty()) {
                                Spacer(Modifier.padding(top = 8.dp))
                                TrailList(run.trail, live = false)
                            }
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

/**
 * The child's own record, oldest first — one row per thing it did.
 *
 * Full type sizes on purpose. The wing this opened from is 9.5sp at 40% alpha because it is a
 * margin note inside somebody else's reply bubble; a sheet is a place you came to in order to
 * read, and the same restraint here would just be small text with more room around it.
 *
 * @param live whether the trail is still growing — the newest row is marked and followed.
 */
@Composable
private fun TrailList(trail: List<DelegationBeat>, live: Boolean) {
    val base = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.tertiary
    if (trail.isEmpty()) {
        Text(
            if (live) "It hasn't reported anything yet." else "Nothing was recorded for this subagent.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val listState = rememberLazyListState()
    // Follow the tail only while it is growing: scrolling a settled trail out from under
    // somebody reading it would be the same bug as a chat that jumps.
    LaunchedEffect(trail.size, live) {
        if (live) listState.animateScrollToItem(trail.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        itemsIndexed(trail) { i, beat ->
            val newest = live && i == trail.lastIndex
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                // Same glyph vocabulary as the transcript — a child's tool call is still a
                // tool call, and should not need a second alphabet to be read.
                Text(
                    if (beat.kind == "tool") ToolGrammar.glyphOf(beat.name) else "·",
                    fontSize = 11.sp,
                    color = if (newest) accent else base.copy(alpha = 0.45f),
                    modifier = Modifier.width(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (beat.name.isNotBlank()) {
                        Text(
                            beat.name,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (newest) FontWeight.SemiBold else FontWeight.Normal,
                            color = base.copy(alpha = if (newest) 0.95f else 0.75f),
                        )
                    }
                    if (beat.text.isNotBlank()) {
                        Text(
                            beat.text,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = base.copy(alpha = 0.6f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (beat.name.isBlank()) 0.dp else 1.dp),
                        )
                    }
                }
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

