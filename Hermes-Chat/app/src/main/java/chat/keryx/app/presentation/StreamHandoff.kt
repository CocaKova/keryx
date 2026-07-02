package chat.keryx.app.presentation

import chat.keryx.app.presentation.ui.components.MessageParser

/**
 * Decides when the transient side-channel overlay hands off to the durable Matrix event.
 *
 * The streamed text and the committed Matrix body are *almost* the same bytes, but not exactly:
 * the Matrix copy may carry a prepended 💭 reasoning block, ⟦keryx⟧ markers, tool-chrome lines,
 * a runtime footer, and different whitespace. We compare only each side's normalized prose so the
 * overlay is dropped exactly when its replacement is already in the timeline — that same-frame
 * swap is what makes the transition invisible (no pop, no jump).
 *
 * Pure Kotlin (no Android/Compose deps) so the handoff rules are unit-testable.
 */
object StreamHandoff {

    /** Reduce a message body to its dialogue prose: no reasoning, no tool lines, no telemetry,
     *  no markers, whitespace collapsed. */
    fun normalize(body: String): String =
        MessageParser.parse(body)
            .filterIsInstance<MessageParser.Segment.Text>()
            .joinToString(" ") { it.text }
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * True when [matrixBody] is the committed form of [streamedFinal]. Exact normalized equality
     * first; then prefix containment either way (the final commit may append a footnote the stream
     * never saw, or the stream may have a tail the commit trimmed); finally a long-shared-prefix
     * heuristic for bodies that diverge only in trailing punctuation/cursor artifacts.
     */
    fun matches(matrixBody: String, streamedFinal: String): Boolean {
        val a = normalize(matrixBody)
        val b = normalize(streamedFinal)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length >= 24 && b.length >= 24 && (a.startsWith(b) || b.startsWith(a))) return true
        val n = minOf(a.length, b.length, 160)
        return n >= 48 && a.take(n) == b.take(n)
    }
}

/** Lifecycle of the tier-1 live stream overlay. */
enum class LiveStreamStatus {
    /** SSE connect in flight — nothing rendered yet. */
    CONNECTING,
    /** Tokens are flowing; the overlay bubble is live. */
    STREAMING,
    /** Generation finished; holding the full text until the Matrix event syncs in. */
    AWAITING_SYNC,
    /** The channel dropped mid-stream; partial text is held with a recovery alert until the
     *  final message arrives via the Matrix sync loop. */
    INTERRUPTED,
}

/** The transient, room-scoped live response being streamed over the side-channel. */
data class LiveStream(
    val roomId: String,
    val text: String,
    val status: LiveStreamStatus,
    val startedAt: Long,
    /** The exact final body reported by the `stop` event (null until then). */
    val finalText: String? = null,
)
