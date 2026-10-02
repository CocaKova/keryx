package chat.keryx.core.model

import chat.keryx.core.protocol.CompactionCarryOver
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.protocol.TranscriptBuilder
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A conversation, taken out of the gateway (2.16): `GET /api/sessions/{id}/export`, shared as a
 * file. The route offers one format, JSON — the session row plus EVERY message row, rewound and
 * compaction-archived ones included (an audit read, so a re-import restores it whole). That is
 * the complete record, and it is shared as-is. Markdown is the readable one, built here from the
 * same export: the conversation as the transcript shows it, which is what you paste into a doc.
 */
enum class ExportFormat(
    val extension: String,
    /**
     * The share sheet's type. Markdown goes out as text/plain on purpose: a target that filters
     * on text/plain (notes apps, most chat apps) never matches "text/markdown", and the `.md`
     * name already tells a files app what it holds.
     */
    val mime: String,
) {
    MARKDOWN("md", "text/plain"),
    JSON("json", "application/json"),
}

/** The session row as the export carries it — only what the readable header uses. */
data class ExportMeta(
    val id: String,
    val title: String,
    val source: String,
    val model: String,
    val startedAtMs: Long,
)

/** One message row of the export, with the flags the audit read keeps. */
data class ExportRow(
    val row: MessageRow,
    /** 0 = rewound (Undo/Rewind) or compaction-archived. */
    val active: Boolean,
    /** Compaction-archived display history: no longer in the model's context, still the story. */
    val compacted: Boolean,
    /** `display_metadata.model_only`: handed to the model, never part of any display. */
    val modelOnly: Boolean,
)

data class SessionExportDoc(val meta: ExportMeta, val rows: List<ExportRow>)

object SessionExport {

    /**
     * `keryx-<title>-<yyyyMMdd-HHmm>-<id8>.<ext>`: sorts by date in a files app, says what it is
     * at a glance, and two exports of one chat a minute apart don't overwrite each other's name
     * in somebody's Downloads. The title is slugged to ASCII so no share target chokes on it.
     */
    fun fileName(title: String, sessionId: String, format: ExportFormat, atMs: Long, tz: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(tz)
        val stamp = "${t.year}${pad(t.monthNumber)}${pad(t.dayOfMonth)}-${pad(t.hour)}${pad(t.minute)}"
        val id = sessionId.filter { it.isLetterOrDigit() }.takeLast(8).ifEmpty { "session" }
        return "keryx-${slug(title)}-$stamp-$id.${format.extension}"
    }

    /** Lowercase ASCII words joined by dashes, at most 40 chars, never empty. */
    fun slug(title: String): String {
        val words = title.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
        val out = StringBuilder()
        for (w in words) {
            val next = if (out.isEmpty()) w else "-$w"
            if (out.length + next.length > 40) break
            out.append(next)
        }
        return out.toString().ifEmpty { "session" }
    }

    /** The export's body → its rows; null when it isn't an export (an error page, a proxy). */
    fun parse(body: String): SessionExportDoc? {
        val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val id = o.str("id") ?: return null
        val messages = o["messages"] as? JsonArray ?: return null
        val meta = ExportMeta(
            id = id,
            title = o.str("title").orEmpty(),
            source = o.str("source").orEmpty(),
            model = o.str("model").orEmpty(),
            startedAtMs = ((o["started_at"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).let { (it * 1000).toLong() },
        )
        val rows = messages.mapNotNull { el -> (el as? JsonObject)?.let(::row) }
        return SessionExportDoc(meta, rows)
    }

    private fun row(o: JsonObject): ExportRow? {
        val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val meta = o["display_metadata"] as? JsonObject
        return ExportRow(
            row = MessageRow(
                id = id,
                role = o.str("role") ?: "assistant",
                content = textContent(o["content"]),
                toolName = o.str("tool_name"),
                timestamp = ((o["timestamp"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).let { (it * 1000).toLong() },
                reasoning = o.str("reasoning") ?: o.str("reasoning_content"),
                toolCallId = o.str("tool_call_id"),
                toolCalls = (o["tool_calls"] as? JsonArray).orEmpty().mapNotNull { tc ->
                    val call = tc as? JsonObject ?: return@mapNotNull null
                    val fn = call["function"] as? JsonObject ?: return@mapNotNull null
                    RestToolCall(
                        id = call.str("id") ?: call.str("call_id") ?: return@mapNotNull null,
                        name = fn.str("name") ?: return@mapNotNull null,
                        argumentsJson = fn.str("arguments") ?: "{}",
                    )
                },
                displayKind = o.str("display_kind"),
            ),
            active = flag(o["active"], default = true),
            compacted = flag(o["compacted"], default = false),
            modelOnly = flag(meta?.get("model_only"), default = false),
        )
    }

    /**
     * The rows a reader sees, in order: what is live plus what compaction archived, never the
     * rows an Undo/Rewind took back, never model-only scaffolding. A compaction re-inserts the
     * turns it keeps as fresh live rows, so an archived row whose twin is live is dropped — the
     * server's display read does the same by display order, which the export doesn't carry.
     */
    fun readableRows(rows: List<ExportRow>): List<MessageRow> {
        fun key(r: MessageRow) = r.role + "\u0000" + r.content + "\u0000" + r.toolCallId.orEmpty() +
            "\u0000" + r.toolCalls.joinToString(",") { it.id }
        val liveKeys = rows.filter { it.active && !it.modelOnly }.mapTo(HashSet()) { key(it.row) }
        return rows.filter { r ->
            !r.modelOnly && when {
                r.active -> true
                r.compacted -> key(r.row) !in liveKeys
                else -> false
            }
        }.map { it.row }
    }

    /** Tool output kept per call in the readable file; the JSON export has every byte. */
    const val TOOL_RESULT_CAP = 600
    /** Reasoning kept per turn in the readable file. */
    const val REASONING_CAP = 1500

    /**
     * The readable transcript. [agentName] is what the agent's turns are headed with (a Bot
     * Chat's bot, else "Hermes"); times are in [tz]. Tool calls are one line each with a short
     * output; long reasoning and output are cut with a mark that says so, never silently.
     */
    fun markdown(doc: SessionExportDoc, agentName: String, tz: TimeZone): String {
        val messages = TranscriptBuilder.build(doc.meta.id, readableRows(doc.rows))
        val sb = StringBuilder()
        val title = doc.meta.title.ifBlank { "Untitled session" }
        sb.append("# ").append(title.replace('\n', ' ')).append("\n\n")
        val facts = listOfNotNull(
            "session `${doc.meta.id}`",
            doc.meta.source.takeIf { it.isNotBlank() },
            doc.meta.model.takeIf { it.isNotBlank() }?.let { "model $it" },
            doc.meta.startedAtMs.takeIf { it > 0 }?.let { "started ${dateTime(it, tz)}" },
        )
        sb.append("_").append(facts.joinToString(" · ")).append("_\n\n")
        sb.append("> Exported from Keryx. Tool output is trimmed to $TOOL_RESULT_CAP characters and reasoning to ")
            .append("$REASONING_CAP; the JSON export carries every row in full.\n")
        var lastDay = ""
        for (m in messages) {
            val day = date(m.timestamp, tz)
            if (m.timestamp > 0 && day != lastDay) {
                sb.append("\n---\n\n**").append(day).append("**\n")
                lastDay = day
            }
            appendMessage(sb, m, agentName, tz)
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun appendMessage(sb: StringBuilder, m: Message, agentName: String, tz: TimeZone) {
        val time = if (m.timestamp > 0) " · " + time(m.timestamp, tz) else ""
        when {
            m.sender == SenderType.SYSTEM && m.agentDelivery != null -> {
                val d = m.agentDelivery
                sb.append("\n#### ").append(d.sender).append(" → ").append(agentName).append(time).append("\n\n")
                sb.append(d.body.trim()).append("\n")
            }
            m.sender == SenderType.SYSTEM -> {
                val line = if (CompactionCarryOver.isCarryOver(m.content)) "context compacted" else
                    m.content.trim().lines().firstOrNull { it.isNotBlank() }.orEmpty().take(200)
                if (line.isNotEmpty()) sb.append("\n_— ").append(line).append(" —_\n")
            }
            m.toolCalls.isNotEmpty() -> {
                sb.append("\n")
                for (tc in m.toolCalls) appendTool(sb, tc)
            }
            m.delegations.isNotEmpty() -> {
                val n = m.delegations.size
                sb.append("\n_— ").append(if (n == 1) "1 subagent" else "$n subagents").append(" reported back —_\n")
            }
            else -> {
                val who = if (m.sender == SenderType.ME) "You" else agentName
                val text = m.content.trim()
                val thought = m.reasoning?.trim().orEmpty()
                if (text.isEmpty() && thought.isEmpty()) return
                sb.append("\n### ").append(who).append(time).append("\n\n")
                if (thought.isNotEmpty()) {
                    val clipped = clip(thought, REASONING_CAP)
                    sb.append(clipped.lines().joinToString("\n") { "> $it".trimEnd() }).append("\n")
                    if (text.isNotEmpty()) sb.append("\n")
                }
                if (text.isNotEmpty()) sb.append(text).append("\n")
            }
        }
    }

    private fun appendTool(sb: StringBuilder, tc: ToolCall) {
        val verdict = when (tc.status) {
            ToolStatus.COMPLETED -> "ok"
            ToolStatus.FAILED -> "failed"
            else -> "no verdict"
        }
        sb.append("- `").append(tc.name).append("`")
        tc.context.trim().takeIf { it.isNotEmpty() }?.let { sb.append(" ").append(inlineCode(it)) }
        sb.append(" — ").append(verdict).append("\n")
        val out = tc.result.trim()
        if (out.isNotEmpty()) {
            val clipped = clip(out, TOOL_RESULT_CAP)
            val fence = fenceFor(clipped)
            sb.append("  ").append(fence).append("\n")
            clipped.lines().forEach { sb.append("  ").append(it).append("\n") }
            sb.append("  ").append(fence).append("\n")
        }
    }

    /** A code fence longer than any backtick run inside [text], so output can't close it early. */
    fun fenceFor(text: String): String {
        var longest = 0
        var run = 0
        for (c in text) {
            if (c == '`') { run++; if (run > longest) longest = run } else run = 0
        }
        return "`".repeat(maxOf(3, longest + 1))
    }

    /** An inline code span that survives backticks in the text (CommonMark's double-tick form). */
    fun inlineCode(text: String): String {
        val one = text.replace('\n', ' ')
        return if ('`' in one) "`` $one ``" else "`$one`"
    }

    private fun clip(s: String, cap: Int): String =
        if (s.length <= cap) s else s.take(cap).trimEnd() + "\n… (${s.length - cap} more characters in the JSON export)"

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun date(ms: Long, tz: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        return "${t.year}-${pad(t.monthNumber)}-${pad(t.dayOfMonth)}"
    }

    private fun time(ms: Long, tz: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        return "${pad(t.hour)}:${pad(t.minute)}"
    }

    private fun dateTime(ms: Long, tz: TimeZone) = date(ms, tz) + " " + time(ms, tz)

    private fun flag(el: JsonElement?, default: Boolean): Boolean {
        val p = el as? JsonPrimitive ?: return default
        return p.booleanOrNull ?: p.intOrNull?.let { it != 0 } ?: default
    }

    /** `content` is a string or a multimodal part list — flatten to the readable text. */
    private fun textContent(el: JsonElement?): String = when (el) {
        is JsonArray -> el.joinToString("\n") { part ->
            val p = part as? JsonObject ?: return@joinToString ""
            p.str("text") ?: p.str("content") ?: if (p.str("type") == "image_url") "[image]" else ""
        }.trim()
        is JsonPrimitive -> el.contentOrNull.orEmpty()
        else -> ""
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
