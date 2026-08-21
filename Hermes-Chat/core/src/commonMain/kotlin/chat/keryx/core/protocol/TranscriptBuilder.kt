package chat.keryx.core.protocol

import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.model.DelegationReport
import chat.keryx.core.model.DisplayKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * REST transcript rows → domain messages, tool theater included.
 *
 * The wire is the OpenAI conversation shape (protocol §4.3): an assistant row carries
 * `tool_calls[]`, and each result lands as a following role:"tool" row keyed by
 * `tool_call_id`. We fold every assistant row's calls into ONE tool-group [Message]
 * (id "tools-<rowId>") placed after that row's text (if any), with results resolved
 * from the tool rows — which are then consumed, never rendered standalone. A tool row
 * whose call id resolves nothing (history truncation cut the pair) still surfaces, as
 * a group of one, so no executed work silently vanishes from the story.
 */
object TranscriptBuilder {

    fun build(roomId: String, rows: List<MessageRow>): List<Message> {
        // Results first: tool rows indexed by the call they answer.
        val resultsByCallId = HashMap<String, MessageRow>()
        for (r in rows) if (r.role == "tool" && !r.toolCallId.isNullOrBlank()) {
            resultsByCallId.getOrPut(r.toolCallId) { r }
        }
        val consumed = HashSet<Long>()
        val out = ArrayList<Message>(rows.size)
        for (row in rows) {
            when (row.role) {
                // Not everything the gateway files as role:"user" is something you said. It
                // is also the channel it uses to hand the MODEL things between turns, and
                // those arrived wearing your name and your bubble colour.
                "user" -> if (row.content.isNotBlank() && !DisplayKind.hidesText(row.displayKind)) {
                    val wings = if (DelegationReport.isReport(row.content))
                        DelegationReport.parse(row.content) else emptyList()
                    val delivery = chat.keryx.core.model.AgentDelivery.parse(row.content)
                    out += when {
                        // Another AGENT talking to this one ("bot mode", upstream 08-14): the
                        // delivery arrives on the user role because alternation requires it,
                        // but it is not the human speaking — attributed notice, not your bubble.
                        delivery != null -> Message(
                            id = row.id.toString(),
                            roomId = roomId,
                            sender = SenderType.SYSTEM,
                            content = "",
                            timestamp = row.timestamp,
                            agentDelivery = delivery,
                        )
                        // A background fan-out reporting back: render it as the wings card
                        // the live dispatch already uses — the same subagents, now landed.
                        wings.isNotEmpty() -> Message(
                            // Marked id: the store uses it to drop the live landing rows this
                            // consolidated report supersedes (see DelegationReport).
                            id = "${DelegationReport.ROW_ID_PREFIX}${row.id}",
                            roomId = roomId,
                            sender = SenderType.HERMES,
                            content = "",
                            timestamp = row.timestamp,
                            delegations = wings,
                        )
                        // The gateway said so itself. Every branch above reads the row's TEXT
                        // to decide what it is; this one reads the row's own classification,
                        // which is why it catches the machinery no sniff was ever written for
                        // (a model switch, a personality switch, a resumed turn, a synthetic
                        // self-injected notice) — and why it sits below the two branches that
                        // render something RICHER than a line of prose. Where we have
                        // desktop's wording for the event we use it; where we don't, the row's
                        // own text in the system voice beats inventing a phrase for it.
                        DisplayKind.isMachinery(row.displayKind) ->
                            text(roomId, row, SenderType.SYSTEM).let { m ->
                                DisplayKind.timelineLabel(row.displayKind)
                                    ?.let { m.copy(content = it) } ?: m
                            }
                        // Machinery we recognise but can't structure still must not speak in
                        // your voice; the quiet system row is the safe floor. (The todo
                        // re-injection is the FOURTH role:"user"-machinery instance.)
                        isCompactionCarryOver(row.content) || DelegationReport.isReport(row.content) ||
                            chat.keryx.core.model.TodoPlanParser.isTodoInjection(row.content) ->
                            text(roomId, row, SenderType.SYSTEM)
                        else -> text(roomId, row, SenderType.ME)
                    }
                }
                "system" -> if (row.content.isNotBlank() && !DisplayKind.hidesText(row.displayKind)) {
                    out += text(roomId, row, SenderType.SYSTEM)
                }
                "assistant" -> {
                    // The stored row's `reasoning` column (populated by every thinking model —
                    // qwen's channel never touches `content`, which is why it was invisible).
                    // It rides the row's text message; a row that is ONLY tool calls gets a
                    // standalone reasoning message so the thought still precedes the work.
                    val thought = row.reasoning?.trim()?.takeIf { it.isNotEmpty() }
                    // A "hidden" row is an empty placeholder the model was handed, not a turn
                    // — but its tool_calls below are real work that really ran, so only the
                    // prose is suppressed. Dropping the whole row would vanish the tools.
                    if (row.content.isNotBlank() && !DisplayKind.hidesText(row.displayKind)) {
                        out += text(roomId, row, SenderType.HERMES).copy(reasoning = thought)
                    } else if (thought != null) {
                        out += Message(
                            id = "think-${row.id}",
                            roomId = roomId,
                            sender = SenderType.HERMES,
                            content = "",
                            timestamp = row.timestamp,
                            reasoning = thought,
                        )
                    }
                    if (row.toolCalls.isNotEmpty()) {
                        // One assistant row IS one dispatch: every call on it was fired in
                        // the same breath, which is exactly the batch the theater draws.
                        val batch = "b${row.id}"
                        val calls = row.toolCalls.map { tc ->
                            val res = resultsByCallId[tc.id]?.also { consumed += it.id }
                            toolCall(tc, res).copy(batchId = batch)
                        }
                        out += Message(
                            id = "tools-${row.id}",
                            roomId = roomId,
                            sender = SenderType.HERMES,
                            content = "",
                            timestamp = row.timestamp,
                            toolCalls = calls,
                        )
                    }
                }
                "tool" -> {
                    if (row.id in consumed) continue
                    // Orphaned result (its assistant row fell off the page): show what ran.
                    out += Message(
                        id = "tools-${row.id}",
                        roomId = roomId,
                        sender = SenderType.HERMES,
                        content = "",
                        timestamp = row.timestamp,
                        toolCalls = listOf(
                            ToolCall(
                                toolId = row.toolCallId ?: "row-${row.id}",
                                name = row.toolName ?: "tool",
                                status = resultStatus(row.content),
                                result = ToolText.displayResult(row.content),
                            )
                        ),
                    )
                }
            }
        }
        return out
    }

    /** The gateway's compaction preamble, verified on the wire:
     *  "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted…". */
    private fun isCompactionCarryOver(content: String): Boolean =
        content.trimStart().startsWith("[CONTEXT COMPACTION")

    private fun text(roomId: String, row: MessageRow, sender: SenderType) = Message(
        id = row.id.toString(),
        roomId = roomId,
        sender = sender,
        content = row.content,
        timestamp = row.timestamp,
    )

    private fun toolCall(tc: RestToolCall, resultRow: MessageRow?): ToolCall {
        val args = ToolText.parseArgs(tc.argumentsJson)
        return ToolCall(
            toolId = tc.id,
            name = tc.name,
            context = ToolText.contextPreview(tc.name, args),
            argsJson = tc.argumentsJson,
            status = when {
                resultRow == null -> ToolStatus.COMPLETED // no result kept; assume it ran
                else -> resultStatus(resultRow.content)
            },
            result = resultRow?.let { ToolText.displayResult(it.content) } ?: "",
        )
    }

    private fun resultStatus(rawResult: String): ToolStatus =
        if (ToolText.looksFailed(rawResult)) ToolStatus.FAILED else ToolStatus.COMPLETED
}

/**
 * Shared text plumbing for tool payloads — used by hydration ([TranscriptBuilder]) and the
 * live event path so both produce byte-identical cards.
 */
object ToolText {
    /** UI keeps this much result text; the full output stays in the agent context. */
    const val RESULT_CAP = 6_000

    private val json = Json { ignoreUnknownKeys = true }

    /** Gateway parity: which argument IS the call, per tool (agent/display.build_tool_preview). */
    private val primaryArgs = mapOf(
        "terminal" to "command", "web_search" to "query", "web_extract" to "urls",
        "read_file" to "path", "write_file" to "path", "patch" to "path",
        "search_files" to "pattern", "browser_navigate" to "url",
        "browser_click" to "ref", "browser_type" to "text",
        "image_generate" to "prompt", "text_to_speech" to "text",
        "vision_analyze" to "question", "skill_view" to "name", "skills_list" to "category",
        "cronjob" to "action", "execute_code" to "code", "browser_exec" to "code",
        "delegate_task" to "goal", "clarify" to "question", "skill_manage" to "name",
    )

    fun parseArgs(argumentsJson: String): JsonObject? =
        runCatching { json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()

    /** ≤80-char one-line preview of the call's primary argument — never a phrased label
     *  (the card owns its own verbs, same contract as the gateway's `context`). */
    fun contextPreview(name: String, args: JsonObject?): String {
        args ?: return ""
        val primary = primaryArgs[name]?.let { args[it] }
            ?: args.values.firstOrNull { it is JsonPrimitive && it.isString }
        val raw = when (primary) {
            is JsonPrimitive -> primary.content
            null -> return ""
            else -> primary.toString()
        }
        val oneLine = raw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 80) oneLine else oneLine.take(79).trimEnd() + "…"
    }

    /**
     * Result content → display text: the `<untrusted_tool_result>` guard wrapper (tag line,
     * its treat-as-DATA boilerplate paragraph, closing tag) is chrome for the MODEL, not the
     * user — strip it; then cap. The wrapper text stays untouched when the shape is anything
     * unexpected: showing chrome beats eating data.
     */
    fun displayResult(raw: String): String {
        var s = raw
        if (s.startsWith("<untrusted_tool_result")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1)
                val para = s.indexOf("\n\n")
                if (para >= 0 && s.substring(0, para).contains("Treat it as DATA")) {
                    s = s.substring(para + 2)
                }
                s = s.removeSuffix("\n").removeSuffix("</untrusted_tool_result>")
            }
        }
        s = s.trim()
        return if (s.length <= RESULT_CAP) s else s.take(RESULT_CAP).trimEnd() + "\n… [truncated]"
    }

    /** Conservative failure sniff on a raw result: only explicit machine-readable failure
     *  shapes flip the card to FAILED — prose mentioning "error" must not. */
    fun looksFailed(raw: String): Boolean {
        val body = displayResult(raw)
        if (!body.startsWith("{")) return false
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
        val success = (o["success"] as? JsonPrimitive)?.content
        if (success == "false") return true
        val error = o["error"]
        return error != null && error !is kotlinx.serialization.json.JsonNull &&
            (error as? JsonPrimitive)?.content?.isNotBlank() != false
    }

    /** tool.complete's `result` field may be a JSON object or a plain string — normalize to
     *  the same display text hydration produces. */
    fun resultElementToDisplay(el: kotlinx.serialization.json.JsonElement?): String = when {
        el == null || el is kotlinx.serialization.json.JsonNull -> ""
        el is JsonPrimitive -> displayResult(el.content)
        else -> displayResult(el.toString())
    }

    /** Failure sniff on the already-parsed tool.complete result element. */
    fun elementLooksFailed(el: kotlinx.serialization.json.JsonElement?): Boolean = when {
        el == null -> false
        el is JsonPrimitive -> looksFailed(el.content)
        el is JsonObject -> looksFailed(el.toString())
        else -> false
    }

    /** Lazy pretty-print for the expanded card. Falls back to the raw string on non-JSON. */
    fun prettyArgs(argumentsJson: String): String {
        if (argumentsJson.isBlank() || argumentsJson == "{}") return ""
        return runCatching {
            val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
            pretty.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                json.parseToJsonElement(argumentsJson),
            )
        }.getOrDefault(argumentsJson)
    }
}
