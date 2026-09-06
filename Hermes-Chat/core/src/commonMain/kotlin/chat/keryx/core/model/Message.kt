package chat.keryx.core.model

data class Message(
    val id: String,
    val roomId: String,
    val sender: SenderType,
    val content: String,
    val timestamp: Long,
    val senderId: String = "",
    val senderName: String = "",
    val isStreaming: Boolean = false,
    /** Structured tool invocations (direct transport: gateway tool.start/complete or REST
     *  tool_calls rows). Non-empty ⇒ this message renders as a tool-theater block; [content]
     *  is usually blank. The Matrix path leaves this empty — its calls are parsed back out of
     *  [content] at grouping time, into the same [ToolCall] shape. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Subagents dispatched during this turn (direct transport `subagent.*`). Live-only: the
     *  relay is not persisted, so a reloaded turn shows the `delegate_task` call instead. */
    val delegations: List<Delegation> = emptyList(),
    val mediaUrl: String? = null,             // mxc:// for media messages (null when E2EE-encrypted)
    val mediaKind: MediaKind? = null,
    val fileName: String = "",
    val replyToId: String? = null,            // event id this message is a reply to, if any
    /** The model's reasoning for this turn — live deltas accumulated, or a stored row's
     *  reasoning column (direct path). Rendered as a quiet collapsed disclosure above the
     *  message; never part of [content]. The Matrix path gathers its 💭-parsed reasoning at
     *  grouping time instead. */
    val reasoning: String? = null,
    /** Live-measured thinking time in whole seconds; null when unmeasured (hydrated turns —
     *  the gateway does not persist it), in which case the disclosure just says "Thought". */
    val reasoningSeconds: Int? = null,
    /** Set when this message is really one agent messaging another (2.3 §2) — it renders as an
     *  attributed notice, never as that sender simply speaking. */
    val agentDelivery: AgentDelivery? = null,
    /** Set when this message IS a failed turn (2.10): the gateway's own account of which layer
     *  broke. Renders as the failure card, never as a reply that happens to say "Error:". */
    val failure: TurnFailure? = null,
)

/**
 * The gateway's `error_surface` (hermes ≥ 2026.8.21): the layer that failed, a code, and
 * whether retrying could change anything. [message] is the failure's own words. A gateway that
 * predates the descriptor sends none — [layer] is then "" and the card renders generically.
 */
data class TurnFailure(
    val layer: String,
    val code: String,
    val retryable: Boolean,
    val message: String,
) {
    /** What to call it, by layer — the Desktop's names, in the herald's voice. */
    val title: String get() = when (layer) {
        "provider" -> "The provider refused"
        "endpoint" -> "The endpoint didn't answer"
        "streaming" -> "The stream dropped"
        "auth" -> "The provider wants a key"
        "billing" -> "Out of credit"
        "gateway" -> "The gateway stumbled"
        "runtime" -> "The agent couldn't start"
        "disk" -> "The gateway's disk is full"
        else -> "The turn failed"
    }

    companion object {
        /** The plain-English layer names, for a "Copy details" line. */
        fun fromWire(layer: String?, code: String?, retryable: Boolean?, message: String): TurnFailure =
            TurnFailure(layer = layer.orEmpty(), code = code.orEmpty(), retryable = retryable ?: true, message = message)
    }
}

/** A single aggregated reaction on a message. */
data class MessageReaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

enum class MediaKind { IMAGE, AUDIO, VIDEO, FILE }

enum class SenderType {
    ME,      // the logged-in user (right-aligned)
    HERMES,  // the configured Hermes agent
    OTHER,   // any other participant
    SYSTEM
}

enum class ToolStatus {
    EXECUTING,
    COMPLETED,
    FAILED,

    /** The producer saw no verdict (committed text without a ✓/❌) — not success, not failure.
     *  The card's faint "·" mark; a watched turn's live record fills it in for real. */
    UNKNOWN,
}
