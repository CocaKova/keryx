package chat.keryx.app.domain.model

/**
 * Who is typing in a room, split by classification: the agent's typing drives the
 * "Hermes is working" banner, human typers render as a plain "X is typing…" line.
 */
data class TypingState(
    val agentTyping: Boolean = false,
    /** Display names (fallback: MXID localpart) of non-me human typers. */
    val humanNames: List<String> = emptyList(),
)
