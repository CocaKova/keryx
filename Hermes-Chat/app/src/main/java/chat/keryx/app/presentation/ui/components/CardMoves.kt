package chat.keryx.app.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// The owner's hands on a card (2.14): the `hermes kanban` moves that are not an answer to an ask.
// A card that asked for something still answers from the AskPanel; these are for everything else —
// a stuck run, a parked brief, the wrong profile, a card that is simply finished.

/** One owner move. [verb] is the gateway's action name; [confirm] = asks before it happens. */
enum class CardMove(
    val verb: String,
    val label: String,
    val explain: String,
    val confirm: Boolean = false,
    /** null = no note field; false = optional; true = required. */
    val noteRequired: Boolean? = false,
) {
    START("promote", "Start it", "Moves it to ready — the dispatcher picks it up on its next pass."),
    UNBLOCK("unblock", "Unblock", "Back to work as it is. The next run starts from where the last one stopped."),
    RECLAIM(
        "reclaim", "Stop this run",
        "Ends the running worker and puts the card back in ready, so a fresh run takes it.",
        confirm = true,
    ),
    REASSIGN("reassign", "Hand to…", "Another profile runs it next. A running card is stopped first."),
    PAUSE(
        "block", "Pause",
        "Parks it as waiting on you. The reason is what the next run reads when you unblock it.",
        confirm = true, noteRequired = true,
    ),
    DONE(
        "complete", "Mark done",
        "Closes it by hand. The note becomes its summary, and cards waiting on it can start.",
        confirm = true,
    ),
    ARCHIVE("archive", "Archive", "Takes it off the board. A running worker is stopped.", confirm = true, noteRequired = null);

    /** The toast after the gateway said yes. */
    fun landed(status: String, assignee: String): String = when (this) {
        REASSIGN -> "Handed to ${assignee.ifBlank { "nobody" }}" + (if (status.isNotBlank()) " — $status" else "")
        DONE -> "Marked done"
        ARCHIVE -> "Archived"
        PAUSE -> "Paused — it waits on you"
        else -> "$label — now ${status.ifBlank { "moving" }}"
    }
}

/** The moves that make sense from [status]. Review has its own verdicts in the AskPanel, so it
 *  only offers the moves that aren't a verdict. Order = most likely first. */
internal fun movesFor(status: String): List<CardMove> = when (status) {
    "triage" -> listOf(CardMove.START, CardMove.REASSIGN, CardMove.ARCHIVE)
    "todo" -> listOf(CardMove.START, CardMove.REASSIGN, CardMove.PAUSE, CardMove.DONE, CardMove.ARCHIVE)
    "blocked" -> listOf(CardMove.UNBLOCK, CardMove.REASSIGN, CardMove.DONE, CardMove.ARCHIVE)
    "scheduled" -> listOf(CardMove.UNBLOCK, CardMove.REASSIGN, CardMove.ARCHIVE)
    "ready" -> listOf(CardMove.REASSIGN, CardMove.PAUSE, CardMove.DONE, CardMove.ARCHIVE)
    "running" -> listOf(CardMove.RECLAIM, CardMove.REASSIGN, CardMove.PAUSE, CardMove.ARCHIVE)
    "review" -> listOf(CardMove.REASSIGN, CardMove.ARCHIVE)
    "done" -> listOf(CardMove.ARCHIVE)
    else -> emptyList()
}

/** A row of move chips; tapping one either acts at once or opens [MoveDialog]. */
@Composable
internal fun CardMovesPanel(
    status: String,
    currentAssignee: String,
    profiles: List<String>,
    onMove: (move: CardMove, note: String, assignee: String) -> Unit,
) {
    val moves = movesFor(status)
    if (moves.isEmpty()) return
    var pending by remember(status) { mutableStateOf<CardMove?>(null) }
    Column {
        KeryxSectionHeader(label = "Your moves", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            moves.forEachIndexed { i, move ->
                MoveChip(move.label, primary = i == 0, destructive = move == CardMove.ARCHIVE || move == CardMove.RECLAIM) {
                    // One tap for the obvious, reversible moves; a dialog for anything that ends a
                    // run, closes the card, needs a reason, or needs a profile picked.
                    if (move.confirm || move == CardMove.REASSIGN) pending = move else onMove(move, "", "")
                }
            }
        }
    }
    pending?.let { move ->
        MoveDialog(
            move = move,
            profiles = profiles.filter { it != currentAssignee },
            onConfirm = { note, assignee -> pending = null; onMove(move, note, assignee) },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun MoveChip(label: String, primary: Boolean, destructive: Boolean, onClick: () -> Unit) {
    val hue = when {
        destructive -> KeryxStatus.bad
        else -> MaterialTheme.colorScheme.primary
    }
    val ground = if (primary && !destructive) hue else hue.copy(alpha = 0.12f)
    val ink: Color = if (primary && !destructive) contrastColorFor(hue) else keryxAccentInk(hue)
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        color = ink,
        modifier = Modifier
            .clip(RoundedCornerShape(KeryxRadius.chip))
            .background(ground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun MoveDialog(
    move: CardMove,
    profiles: List<String>,
    onConfirm: (note: String, assignee: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf(if (move == CardMove.REASSIGN) profiles.firstOrNull().orEmpty() else "") }
    val ready = (move.noteRequired != true || note.isNotBlank()) &&
        (move != CardMove.REASSIGN || assignee.isNotBlank())
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(KeryxRadius.sheet), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                KeryxSectionHeader(move.label.removeSuffix("…"))
                Spacer(Modifier.height(8.dp))
                Text(move.explain, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (move == CardMove.REASSIGN) {
                    Spacer(Modifier.height(12.dp))
                    if (profiles.isEmpty()) {
                        Text("No other profile to hand it to.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        profiles.forEach { p ->
                            val selected = p == assignee
                            Text(
                                p.replaceFirstChar { it.uppercase() },
                                fontSize = 12.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(KeryxRadius.chip))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    )
                                    .clickable { assignee = p }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                if (move.noteRequired != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                when {
                                    move.noteRequired == true -> "Why — the next run reads this"
                                    move == CardMove.DONE -> "What got done (optional)"
                                    else -> "A note on the card (optional)"
                                },
                                fontSize = 12.sp,
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        shape = RoundedCornerShape(KeryxRadius.field),
                        minLines = 2,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(enabled = ready, onClick = { onConfirm(note.trim(), assignee) }) {
                        Text(move.label.removeSuffix("…").let { if (move == CardMove.REASSIGN) "Hand over" else it })
                    }
                }
            }
        }
    }
}
