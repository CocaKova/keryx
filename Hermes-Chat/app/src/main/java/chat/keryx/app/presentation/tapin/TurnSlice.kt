package chat.keryx.app.presentation.tapin

import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.ToolRunEntry
import chat.keryx.core.model.Delegation
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.Theater
import chat.keryx.core.model.ToolCall

/**
 * The current turn, cut out of the transcript (2.12).
 *
 * Two doors feed the chat and they disagree about where a live turn lives: the Matrix
 * side-channel keeps it in `LiveStream` and folds its theater into the items; the direct door
 * writes its tool rows, wings and streaming bubble straight into the messages. The one place
 * both have already been reconciled is the rendered item list — so Tap-In reads the turn from
 * there, newest-first, back to the last thing the user said.
 */
data class TurnSlice(
    /** Every call the turn made, oldest first, across all of its runs. */
    val calls: List<ToolCall>,
    /** Every helper the turn sent out, dispatch order, de-duplicated by key. */
    val delegations: List<Delegation>,
    /** The newest reasoning the turn has shown: the streaming bubble's, else a run's. */
    val reasoning: String,
    /** The newest thing the agent said this turn (the streaming bubble while it streams). */
    val answer: String,
) {
    companion object {
        val EMPTY = TurnSlice(emptyList(), emptyList(), "", "")

        /**
         * @param itemsNewestFirst the chat's render items in list order (index 0 = newest).
         * @param structured the side-channel's record of the newest run, when watched live —
         *   swapped in by position so the rail carries durations, verdicts and diffs.
         */
        fun of(itemsNewestFirst: List<ChatRenderItem>, structured: List<ToolCall> = emptyList()): TurnSlice {
            val turn = ArrayList<ChatRenderItem>()
            for (item in itemsNewestFirst) {
                if (item is ChatRenderItem.Single && item.message.sender == SenderType.ME) break
                if (item is ChatRenderItem.DayHeader) continue
                turn += item
            }
            if (turn.isEmpty()) return EMPTY
            val oldestFirst = turn.asReversed()

            val calls = ArrayList<ToolCall>()
            val crew = LinkedHashMap<String, Delegation>()
            var runReasoning = ""
            for (item in oldestFirst) {
                when (item) {
                    is ChatRenderItem.ToolRun -> {
                        for (e in item.entries) when (e) {
                            is ToolRunEntry.Call -> calls += e.call
                            is ToolRunEntry.Delegated -> crew[e.run.key] = e.run
                            else -> Unit
                        }
                        item.reasoning?.takeIf { it.isNotBlank() }?.let { runReasoning = it }
                    }
                    is ChatRenderItem.Single -> {
                        for (d in item.message.delegations) crew[d.key] = d
                        for (c in item.message.toolCalls) if (c !in calls) calls += c
                    }
                    else -> Unit
                }
            }
            // The newest run is the one the side-channel's record describes; enrich by position.
            val enriched = if (structured.isEmpty()) calls else {
                val aligned = Theater.align(calls.map { it.name }, structured)
                calls.mapIndexed { i, c -> aligned[i] ?: c }
            }

            val newestAgent = turn.firstOrNull {
                it is ChatRenderItem.Single && it.message.sender == SenderType.HERMES
            } as? ChatRenderItem.Single
            val reasoning = newestAgent?.message?.reasoning?.takeIf { it.isNotBlank() } ?: runReasoning
            val answer = newestAgent?.message?.content.orEmpty()
            return TurnSlice(enriched, crew.values.toList(), reasoning, answer)
        }
    }
}
