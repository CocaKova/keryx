package chat.keryx.core.model

/**
 * Which message the "what is the agent doing" label should be read from, when the newest
 * one is the direct door's streaming placeholder.
 *
 * The direct door publishes a running turn as its sealed items (prose runs, tool calls) and
 * then one streaming placeholder, which carries the turn's WHOLE thought for as long as the
 * turn runs. So while a tool executes, the newest message is a blank placeholder wearing an
 * old thought — and the working banner, and the Call's caption, said "Reasoning" through every
 * tool of a multi-step run ("I can't see tool calls, it just says reasoning", 09-05).
 *
 * A tool still running is what the agent is doing NOW; the thought wins only once the newest
 * tool has returned.
 */
object WorkState {
    /**
     * The newest tool-bearing agent message before [latest] in its room, if that tool is still
     * running; null when the agent has moved on. The walk stops at the first tool it meets — a
     * finished newest tool means the agent is past it, whatever ran earlier — and never crosses
     * a human's message, because a tool from an earlier turn is not this turn's work.
     */
    fun runningTool(messages: List<Message>, latest: Message): Message? =
        messages.asReversed()
            .asSequence()
            .dropWhile { it.id == latest.id }
            .takeWhile { it.roomId == latest.roomId && it.sender == SenderType.HERMES }
            .firstOrNull { it.toolCalls.isNotEmpty() }
            ?.takeIf { m -> m.toolCalls.any { it.status == ToolStatus.EXECUTING } }
}
