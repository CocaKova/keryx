package chat.keryx.core.model

import chat.keryx.core.protocol.MessageParser

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

    /** What the banner says about [last] and whether the turn reads as answered. */
    data class Reading(val label: String, val settled: Boolean)

    /** Pick the quiet window from the agent's latest message, and the "what it's doing" label.
     *  Mid-run (a tool call, pure reasoning, or automated telemetry → more is coming) waits long;
     *  a real answer settles fast. Telemetry counting as an "answer" was why the working banner and
     *  quips vanished after one automated check-in even though the agent was still mid-run.
     *  (Moved from ChatViewModel in 3.0.) */
    fun read(last: Message): Reading {
        val segs = MessageParser.parse(last.content)
        // Structure first (3.1 §C1): a direct-door tool message carries its work in
        // Message.toolCalls with content "" — the parse found nothing and the banner said
        // "Working" forever where Matrix names the tool. The parse stays the fallback for
        // text-borne facts.
        val tools = last.toolCalls.ifEmpty {
            segs.filterIsInstance<MessageParser.Segment.Tools>().flatMap { it.calls }
        }
        val hasReasoning = !last.reasoning.isNullOrBlank() ||
            segs.any { it is MessageParser.Segment.Thinking }
        val isTelemetry = MessageParser.isTelemetryMessage(last.content)
        // Judge the turn by how the message ENDS, not by what it contains: a message that runs
        // tools and then delivers the answer is a finished turn, but "any tool call anywhere"
        // used to force the long mid-run window — the cloud (and the newest card's working
        // shimmer) outstayed an answer already on screen by minutes, and re-opening the room
        // re-lit it via the openedMidRun recency check. Mid-run is a trailing tool call, bare
        // reasoning, or a structured tool payload; any visible content after those (prose, a
        // table, a diagram, ask-chips) is the answer having landed.
        val tail = segs.lastOrNull {
            !(it is MessageParser.Segment.Telemetry) &&
                !(it is MessageParser.Segment.Text && it.text.isBlank())
        }
        val hasAnswer = !isTelemetry && tail != null &&
            tail !is MessageParser.Segment.Tools &&
            tail !is MessageParser.Segment.Thinking &&
            tail !is MessageParser.Segment.ActionOutput
        val label = when {
            // Telemetry first: a "⏳ Working…" heartbeat parses as a tool-shaped line too, and
            // "Running Working" is not a label.
            isTelemetry -> "Working"
            // The shared grammar names it, so the banner says what the theater says: "Reading
            // a.txt", not "Running read_file" (and never the "Running Reading" a friendly
            // progress line used to produce).
            !hasAnswer && tools.isNotEmpty() -> tools.last().let {
                ToolGrammar.title(it.name, ToolGrammar.targetOf(it.name, it.context), running = true)
            }
            hasReasoning && !hasAnswer -> "Reasoning"
            else -> "Working"
        }
        // The background review's post-turn "💾 Self-improvement review" is telemetry that
        // arrives AFTER the answer — it must settle immediately, not hold the working banner
        // through the full mid-run quiet window.
        if (isTelemetry && MessageParser.isSelfImprovementReview(last.content)) return Reading(label, settled = true)
        return Reading(label, settled = hasAnswer)
    }

    /** The message the work state is read from: [latest], unless it is a runtime footer or a
     *  blank placeholder that landed after the real answer (moved from ChatViewModel in 3.0). */
    fun subject(messages: List<Message>, latest: Message): Message {
        // Judge the work state from the real answer, not from a runtime footer or a blank
        // placeholder that landed after it. HERMES-only: the blank-agent-id blindness this filter
        // once hedged against is now solved structurally in senderTypeOf (legacy agent-room
        // fallback), and "not mine" would let a HUMAN's group-room message relabel the banner.
        // Structure counts as content (3.1 §C1, both doors symmetric): the direct door's live
        // overlay carries its thought in Message.reasoning with content "" — skipping it made
        // the cloud say "Working" during pure thinking where Matrix says "Reasoning"
        // (device-caught on the 08-24 fluency walk).
        val hasStructure = !latest.reasoning.isNullOrBlank() || latest.toolCalls.isNotEmpty()
        if (!MessageParser.isRuntimeFooterMessage(latest.content) &&
            (latest.content.isNotBlank() || hasStructure)
        ) {
            // The direct door's overlay publishes the turn's sealed items (prose, tool calls)
            // and then the streaming placeholder, which carries the turn's WHOLE thought for
            // as long as the turn runs. So while a tool executes, `latest` is a blank
            // placeholder wearing an old thought, and the banner — and the Call's caption —
            // said "Reasoning" through every tool of a multi-step run (09-05: "I can't
            // see tool calls, it just says reasoning"). A tool still running is what the agent
            // is doing NOW; the thought wins only once the newest tool has returned.
            if (latest.isStreaming && latest.content.isBlank()) {
                runningTool(messages, latest)?.let { return it }
            }
            return latest
        }
        return messages.asReversed()
            .asSequence()
            .dropWhile { it.id == latest.id }
            .firstOrNull {
                it.roomId == latest.roomId &&
                    it.sender == SenderType.HERMES &&
                    it.content.isNotBlank() &&
                    !MessageParser.isTelemetryMessage(it.content)
            }
            ?: latest
    }
}
