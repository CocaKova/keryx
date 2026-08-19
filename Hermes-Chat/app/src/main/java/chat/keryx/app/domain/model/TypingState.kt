package chat.keryx.app.domain.model

/**
 * Who is typing in a room, split by classification: the agent's typing drives the
 * "Hermes is working" banner, human typers render as a plain "X is typing…" line.
 */
data class TypingState(
    val agentTyping: Boolean = false,
    /** Display names (fallback: MXID localpart) of non-me human typers. */
    val humanNames: List<String> = emptyList(),
    /** MXIDs of the agent accounts currently typing — one sigil each in a council room (2.3). */
    val agentIds: List<String> = emptyList(),
)
