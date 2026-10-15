package chat.keryx.app.presentation.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.keryx.app.audio.TtsController
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.MessageBubble
import chat.keryx.app.presentation.ui.components.TelemetryMessageRow
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType

/**
 * What a transcript row can do, in one object the screen remembers once (3.0 perf §1). The moving
 * parts (the transcript, whether a turn runs, the read-aloud phase) are read through [State] when
 * an action fires, not captured when the row draws: a row whose callbacks captured `messages`
 * got new callbacks on every streaming tick, so no bubble on screen could skip.
 */
@Stable
internal class MessageRowActions(
    private val viewModel: ChatViewModel,
    private val tts: TtsController,
    private val messages: State<List<Message>>,
    private val awaitingReply: State<Boolean>,
    private val ttsState: State<TtsController.State>,
    private val speak: State<(Message) -> Unit>,
    private val jump: State<(String) -> Unit>,
) {
    fun reply(m: Message) = viewModel.setReplyTarget(m)
    fun react(m: Message, emoji: String) = viewModel.media.sendReaction(m.id, emoji)
    fun jumpTo(id: String) = jump.value(id)
    fun delete(m: Message) = viewModel.matrixRooms.deleteMessage(m.roomId, m.id)
    fun toggleKeep(m: Message) = viewModel.archive.toggleSaved(m)
    suspend fun media(m: Message): ByteArray? = viewModel.media.loadMessageMedia(m.roomId, m.id)
    fun reactions(m: Message) = viewModel.media.reactionsFlow(m.roomId, m.id)
    fun undo() = viewModel.undoLastTurn()
    fun retry() = viewModel.retryExchange()
    fun branch(m: Message) = viewModel.turns.branchFrom(m)

    fun speakOrStop(m: Message) {
        val s = ttsState.value
        if (s.messageId == m.id && s.phase != TtsController.Phase.IDLE) tts.stop() else speak.value(m)
    }

    /** Your own words back in the composer; the newest of them, between turns, also takes the
     *  exchange back (2.16). Judged at the tap, against the transcript as it is then. */
    fun edit(m: Message) = viewModel.editAndResend(
        m,
        takeBack = viewModel.transportIsDirect && !awaitingReply.value &&
            m.id == messages.value.lastOrNull { it.sender == SenderType.ME }?.id,
    )
}

/**
 * One message row of the transcript. Every parameter is a value or the remembered [actions], so
 * Compose compares them by equality and an unchanged row skips while a turn streams elsewhere in
 * the list. The screen works out the per-row flags (is this the newest reply, is a turn running).
 */
@Composable
internal fun MessageRow(
    item: ChatRenderItem.Single,
    replyTo: Message?,
    bubbleStyle: String,
    animationStyle: String,
    textScale: Float,
    showSender: Boolean,
    showTelemetry: Boolean,
    arrival: Boolean,
    flashed: Boolean,
    kept: Boolean,
    speaking: Boolean,
    isDirect: Boolean,
    canDeleteMessages: Boolean,
    archiveAvailable: Boolean,
    /** The newest reply, between turns, on the direct door: the one exchange that can be taken back. */
    canUndo: Boolean,
    canRetry: Boolean,
    actions: MessageRowActions,
    viewModel: ChatViewModel,
) {
    val message = item.message
    // Structured reasoning (both producers fill Message.reasoning — 3.1 §B1): the quiet "Thought
    // for Ns" disclosure, above whatever the turn said. A reasoning-ONLY row (a turn that was all
    // thought before its tools) is just the disclosure — no empty bubble under it. On the Matrix
    // door content keeps its <think> lines (the parse owns what counts as thought), so "only" is
    // judged by the parse, not by blankness.
    val thought = message.reasoning?.takeIf { it.isNotBlank() }
    // Remembered by content, and parsed only when the body could hold a thought marker at all:
    // this ran a full parse of the streaming row on every tick (2.19.1).
    val reasoningOnly = remember(message.content, thought != null) {
        thought != null && message.content.isNotBlank() &&
            (message.content.contains('<') || message.content.contains('◁') || message.content.contains("💭") || message.content.contains("🧠")) &&
            chat.keryx.core.protocol.MessageParser.isReasoningOnly(message.content)
    }
    if (thought != null && (message.content.isBlank() || reasoningOnly)) {
        chat.keryx.app.presentation.ui.components.ReasoningDisclosure(
            reasoning = thought,
            seconds = message.reasoningSeconds,
            streaming = message.isStreaming,
            stateKey = "think-${message.id}",
        )
        return
    }
    // A compaction's handoff row: the summary of everything above it. A divider, not a wall of
    // the agent's notes in its own voice (2.13.11).
    if (chat.keryx.core.protocol.CompactionCarryOver.isCarryOver(message.content)) {
        chat.keryx.app.presentation.ui.components.CompactionDivider(message)
        return
    }
    // Gateway machinery (a model switch, a resumed turn, a note left for the model): the same
    // quiet rule, never a bubble (2.16).
    message.mark?.let { mark ->
        chat.keryx.app.presentation.ui.components.TimelineMarkDivider(mark, "mark-${message.id}")
        return
    }
    // Automated telemetry never gets a chat bubble: it renders as a quiet, low-contrast block (or
    // nothing at all when telemetry is hidden).
    val isTelem = remember(message.content, message.sender) {
        message.sender == SenderType.HERMES &&
            chat.keryx.app.presentation.ui.components.isTelemetryMessage(message)
    }
    if (isTelem) {
        if (chat.keryx.app.presentation.ui.components.showsTelemetryRow(message, showTelemetry)) {
            TelemetryMessageRow(message, textScale = textScale)
        }
        return
    }
    // Live reactions: updates the moment anyone adds/removes one — no manual refresh.
    val reactionsFlow = remember(message.id) { actions.reactions(message) }
    // Flash halo when this message is the target of a quote-jump.
    val flashColor by animateColorAsState(
        targetValue = if (flashed) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else Color.Transparent,
        animationSpec = tween(if (flashed) 220 else 900),
        label = "quoteFlash",
    )
    // The gateway reply-threads every chunk of a turn to the triggering message; a quote of the
    // user's own last message is noise (grouping marks those suppressed). Genuine references
    // further back still show.
    val quotedId = message.replyToId.takeUnless { item.suppressQuote }
    Column {
        if (thought != null) {
            chat.keryx.app.presentation.ui.components.ReasoningDisclosure(
                reasoning = thought,
                seconds = message.reasoningSeconds,
                streaming = false,
                stateKey = "think-${message.id}",
            )
        }
        val failure = message.failure
        if (failure != null) {
            // A failed turn is a card, not a bubble that says "Error:" (2.10).
            chat.keryx.app.presentation.ui.components.TurnFailureCard(
                message = message,
                failure = failure,
                viewModel = viewModel,
                textScale = textScale,
            )
        } else MessageBubble(
            message = message,
            replyTo = replyTo,
            bubbleStyle = bubbleStyle,
            animationStyle = animationStyle,
            textScale = textScale,
            showSender = showSender,
            arrival = arrival,
            reactionsFlow = reactionsFlow,
            // Resolve media by event id in the repo, which handles both plaintext (mxc) and
            // E2EE-encrypted files and falls back to the thumbnail.
            mediaLoader = { actions.media(message) },
            onReply = { actions.reply(message) },
            onReact = { emoji -> actions.react(message, emoji) },
            onQuoteClick = quotedId?.let { target -> { actions.jumpTo(target) } },
            // Redaction: own messages only — the agent's power level owns the rest. A Matrix
            // power; the gateway keeps its transcript, so the direct door offers no dead
            // affordance.
            onDelete = if (message.sender == SenderType.ME && canDeleteMessages) {
                { actions.delete(message) }
            } else null,
            kept = kept,
            onToggleKeep = if (archiveAvailable) {
                { actions.toggleKeep(message) }
            } else null,
            speaking = speaking,
            onSpeak = if (message.sender == SenderType.HERMES) {
                { actions.speakOrStop(message) }
            } else null,
            // Take back the last exchange (2.10): only the newest reply, only on the direct door,
            // never while a turn runs — the gateway refuses that anyway, and a button that would
            // be refused is not a button.
            onUndoTurn = if (canUndo) {
                { actions.undo() }
            } else null,
            // Retry rides the same gate as the undo (2.11.9): it IS an undo with the prompt
            // re-sent, so a refused undo is a refused retry.
            // Branch from here (2.16): any saved line of the conversation.
            onBranch = if (isDirect && message.content.isNotBlank() &&
                (message.sender == SenderType.ME || message.sender == SenderType.HERMES)
            ) {
                { actions.branch(message) }
            } else null,
            // Edit & resend (2.16): your own words back in the composer.
            onEdit = if (message.sender == SenderType.ME && message.content.isNotBlank()) {
                { actions.edit(message) }
            } else null,
            onRetry = if (canRetry) {
                { actions.retry() }
            } else null,
            modifier = Modifier.background(flashColor, RoundedCornerShape(18.dp)),
        )
    }
}
