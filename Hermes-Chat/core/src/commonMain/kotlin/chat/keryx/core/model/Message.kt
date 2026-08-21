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
    val toolActivity: ToolActivity? = null,
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
)

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

data class ToolActivity(
    val name: String,
    val status: ToolStatus
)

enum class ToolStatus {
    EXECUTING,
    COMPLETED,
    FAILED,

    /** The producer saw no verdict (committed text without a ✓/❌) — not success, not failure.
     *  The card's faint "·" mark; a watched turn's live record fills it in for real. */
    UNKNOWN,
}
