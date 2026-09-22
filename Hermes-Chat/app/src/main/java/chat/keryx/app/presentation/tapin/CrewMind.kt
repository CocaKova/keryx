package chat.keryx.app.presentation.tapin

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall

/**
 * One helper's mind (2.13): what it is thinking, what it has said, what it has done — read off
 * the child's own session, not off the parent's wire.
 *
 * Why this exists: the parent wire drops a child's text on purpose (`docs/features/streaming.md`
 * rule 4), so a crew card could only ever show tool names — Jonny's "I wouldn't know what to
 * steer". Resuming the child's session id opens the gateway's watch window, which mirrors the
 * child's frames as native deltas on that sid; those land in an ordinary store, and this is the
 * projection of that store. Pure, so it can be pinned in a JVM test.
 *
 * @property earlier what the gateway's transcript tail held when the window opened — the part
 *   that ran before we were watching. Shown folded; it is context, not the live thing.
 * @property thinking the child's reasoning as it streams (the newest in-flight message's).
 * @property saying its answer text so far.
 * @property tools every call it made, oldest first, across all its messages.
 * @property empty nothing has arrived through the window yet — the caller falls back to the
 *   trail the parent wire already gave it, rather than showing a blank.
 */
data class CrewMind(
    val earlier: String = "",
    val thinking: String = "",
    val saying: String = "",
    val tools: List<ToolCall> = emptyList(),
    val streaming: Boolean = false,
) {
    val empty: Boolean get() = thinking.isBlank() && saying.isBlank() && tools.isEmpty()

    companion object {
        /** How much of the tail is worth folding in: the last screens, not the whole file. */
        const val EARLIER_MAX_CHARS = 4_000

        fun of(messages: List<Message>, earlier: String = ""): CrewMind {
            // The watch window mirrors the child's goal as a one-time header delta and then its
            // words; both arrive as agent text. The user side is the parent's goal prompt when
            // the child's stored rows are readable — not the child's mind, so skipped.
            val agent = messages.filter { it.sender == SenderType.HERMES }
            val tools = agent.flatMap { it.toolCalls }
            val live = agent.lastOrNull { it.isStreaming } ?: agent.lastOrNull()
            val thinking = live?.reasoning?.takeIf { it.isNotBlank() }
                ?: agent.asReversed().firstNotNullOfOrNull { it.reasoning?.takeIf { r -> r.isNotBlank() } }
                .orEmpty()
            val saying = agent.joinToString("\n\n") { it.content }.trim()
            return CrewMind(
                earlier = trimEarlier(earlier),
                thinking = thinking,
                saying = saying,
                tools = tools,
                streaming = live?.isStreaming == true,
            )
        }

        /** Keep the end of the tail, on a line boundary, so a fold never opens mid-word. */
        fun trimEarlier(tail: String): String {
            val t = tail.trim()
            if (t.length <= EARLIER_MAX_CHARS) return t
            val cut = t.substring(t.length - EARLIER_MAX_CHARS)
            val nl = cut.indexOf('\n')
            return if (nl in 0 until cut.length - 1) cut.substring(nl + 1) else cut
        }
    }
}
