package chat.keryx.core.model

/** One step of a live turn, as the gateway streams it (see ChatRepository.turnEvents). */
sealed interface TurnEvent {
    val sessionId: String

    /** Answer text as it arrives (`message.delta`, or an interim segment the stream skipped). */
    data class Delta(override val sessionId: String, val text: String) : TurnEvent

    /** A mid-turn segment boundary (tool batch between two prose runs). */
    data class Break(override val sessionId: String) : TurnEvent

    /** The turn is over. [finalText] is the committed answer (may repeat streamed text);
     *  [error] = the turn died (the text, if any, is the failure's words). */
    data class End(override val sessionId: String, val finalText: String, val error: Boolean) : TurnEvent
}
