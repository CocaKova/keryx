package chat.keryx.core.protocol

/** One row of `GET /api/sessions/{id}/messages` — the transport-neutral shape
 *  TranscriptBuilder consumes. Extracted from the Android REST client so the
 *  transcript pipeline lives in :shared (docs/MULTIPLATFORM.md); an iOS client
 *  maps its own HTTP layer onto these same rows. */
data class MessageRow(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String?,
    val timestamp: Long,      // epoch millis
    val reasoning: String?,
    /** role:"tool" rows: which assistant tool_call this row is the result of. */
    val toolCallId: String? = null,
    /** role:"assistant" rows: the calls this turn made (OpenAI wire shape). */
    val toolCalls: List<RestToolCall> = emptyList(),
    /**
     * The gateway's own classification of a row that is machinery rather than speech
     * (`display_kind` — see [chat.keryx.core.model.DisplayKind]). A
     * DB-only sidecar: it is stripped from every provider-bound payload, so it exists
     * purely to tell a UI "this wore the user's role but is not the user". Null on
     * ordinary rows and on gateways predating the column.
     */
    val displayKind: String? = null,
)

/** One entry of an assistant row's `tool_calls[]`: `{id, function: {name, arguments}}`. */
data class RestToolCall(
    val id: String,
    val name: String,
    /** Raw JSON-object string, exactly as the model emitted it. */
    val argumentsJson: String,
)
