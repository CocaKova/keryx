package chat.keryx.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An inter-agent delivery — upstream's "bot mode" (2.3 §2, ported from Talaria's B22 work): one
 * agent messages another via `hermes -p <agent> chat -q "Message from …"`, and the delivery lands
 * in the RECEIVING agent's transcript as an ordinary message row.
 *
 * The law that matters: gateway machinery filed under someone else's voice must be parsed back
 * into what it is, and a parse failure must never let it speak as that someone. Here a non-match
 * IS ordinary conversation, so the regex is the whole gate — and it is anchored (desktop's
 * `AGENT_MESSAGE_RE`) so it cannot fire mid-prose.
 *
 * Pure Kotlin: no android.*, so the rules are unit-testable.
 */
data class AgentDelivery(
    /** Display name of the sending agent ("Sterling", "Eats Tests"). */
    val sender: String,
    /** Machine handle when present ("mr-tester"); falls back to [sender]. */
    val handle: String,
    /** The delivered message body. */
    val body: String,
) {
    companion object {
        // Desktop's AGENT_MESSAGE_RE, ported verbatim: `Message from 🤖 Name (@handle): body`
        // (robot glyph and handle both optional) or the legacy `[Message from agent 'Name']`.
        private val RE = Regex(
            "^(?:Message from (?:🤖\\s*)?([^:\\n(]{1,64}?)(?:\\s*\\(@([a-z0-9][a-z0-9_-]{0,63})\\))?:\\s*" +
                "|\\[Message from agent '([^']{1,64})'\\]\\s*)([\\s\\S]*)$"
        )

        fun parse(text: String): AgentDelivery? {
            val m = RE.find(text.trim()) ?: return null
            val sender = (m.groupValues[1].ifBlank { m.groupValues[3] }).trim().ifBlank { "agent" }
            val handle = m.groupValues[2].trim().ifBlank { m.groupValues[3].trim() }.ifBlank { sender }
            return AgentDelivery(sender = sender, handle = handle, body = m.groupValues[4].trim())
        }
    }
}

/**
 * The SENDING half of the same exchange. Where [AgentDelivery] reads a delivery that arrived, this
 * reads one going out: the convention is not a gateway call but a `terminal` invocation, so the
 * only trace in the sender's own transcript is a shell command.
 *
 * Rendered as a terminal card that reads like ops tooling — a command and an exit code. The truth
 * is that it MESSAGED someone, which is a conversation event, so it gets the same quiet notice the
 * receiving side shows.
 */
object AgentDeliveryCommand {

    // Desktop's DELIVERY_COMMAND_RE, ported. Anchored on a command boundary (start of string, a
    // shell separator, or the `hermes` word itself) so a `-p` buried in unrelated prose or another
    // program's flags cannot claim to be a delivery.
    private val RE = Regex(
        "(?:^|[;&|]\\s*|\\bhermes\\s+)-p\\s+(\"?)([a-z0-9][a-z0-9_-]{0,63})\\1\\s+chat\\b" +
            "[\\s\\S]*?-q\\s+[\"']Message from",
        RegexOption.IGNORE_CASE,
    )

    /** The recipient profile this command delivers to, or null when it is an ordinary command. */
    fun targetOf(command: String): String? =
        RE.find(command)?.groupValues?.get(2)?.lowercase()

    /**
     * The recipient for a tool call, or null when the call is not a delivery.
     *
     * Divergence from Talaria: there the arguments arrive as a JSON blob and the command has to be
     * dug out of its `command` field. Keryx parses tool calls out of the agent's *rendered text*,
     * where a fenced `terminal` call already carries the whole command verbatim. Both shapes are
     * accepted — JSON first, then the raw string — because which one shows up depends on how the
     * brain chose to render that turn, not on anything we control.
     */
    fun targetOfCall(toolName: String, args: String): String? {
        if (toolName == MESSAGE_AGENT) return messageAgentField(args, "target")?.let(::cleanTarget)
        if (toolName != "terminal") return null
        val command = jsonStringField(args, "command") ?: args
        return targetOf(command)
    }

    /** The Bot Mode tool (2.18 renders it): `message_agent(target=…, message=…)`. */
    const val MESSAGE_AGENT = "message_agent"

    /** What a `message_agent` call said, or null for any other call. */
    fun messageOfCall(toolName: String, args: String): String? =
        if (toolName == MESSAGE_AGENT) messageAgentField(args, "message")?.trim()?.ifBlank { null } else null

    /**
     * The arguments as the call carries them: JSON from the model, or — on a transcript that
     * compaction summarised — the gateway's one-line `target=juno message=…` digest.
     */
    private fun messageAgentField(args: String, field: String): String? {
        jsonStringField(args, field)?.let { return it }
        val digest = Regex("\\b$field=(.*?)(?=\\s+\\w+=|\\s+\\(\\d[\\d,]* chars|$)", RegexOption.DOT_MATCHES_ALL)
        return digest.find(args)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    /** `@Juno` / `juno` / `spark/researcher` → the name to show; the `@` is addressing. */
    private fun cleanTarget(raw: String): String? = raw.trim().removePrefix("@").trim().ifBlank { null }

    /**
     * The teammate's reply to a `message_agent` call, as it comes back into the SENDER's chat:
     * the delivery runs as a background process, and its completion notification carries the
     * reply as the process output —
     * `[IMPORTANT: Background process … completed normally (exit code 0).\nCommand: …bot_mode_dm.py
     * … hermes -p milo chat … -c 'Bot Chat' …\nOutput:\n<reply>]`. Read back into what it is: a
     * message from that bot. Only a clean, single completion of a delivery counts; a failed one
     * (non-zero exit, a typed `[reason: …]`) stays the gateway's own notice, because when the
     * mechanism breaks the mechanism is what you need to see.
     */
    fun completionReply(content: String): AgentDelivery? {
        val text = content.trim()
        if (!text.startsWith(COMPLETION_HEAD)) return null
        if (text.indexOf(COMPLETION_HEAD, 1) >= 0) return null // a batch of several: leave it whole
        val head = text.substringBefore('\n')
        if (!head.contains("(exit code 0)")) return null
        val command = Regex("(?m)^Command: (.*)$").find(text)?.groupValues?.get(1) ?: return null
        if (!command.contains(DELIVERY_SCRIPT)) return null
        val target = DELIVERY_TARGET.find(command)?.groupValues?.get(2)?.lowercase() ?: return null
        val outputAt = text.indexOf("\nOutput:\n").takeIf { it >= 0 } ?: return null
        val output = text.substring(outputAt + "\nOutput:\n".length).removeSuffix("]").trim()
        val reply = (liveOwnerReply(output) ?: replyText(output)).ifBlank { return null }
        return AgentDelivery(sender = BotRoster.pretty(target), handle = target, body = reply)
    }

    /**
     * When the recipient's Bot Chat is open somewhere, the delivery hands the message to that live
     * chat and prints one JSON line instead of the CLI's text: `{"reply": …, "status": "settled",
     * "delivery_id": …}`, exit 0 also for `queued` and `claimed` (`bot_mode_dm.py`
     * `_wait_live_dm`). Only `settled` with a reply is an answer; anything else has not been
     * answered yet, so it returns "" and the gateway's own notice stays. Null: not that layout.
     */
    private fun liveOwnerReply(output: String): String? {
        if (!output.startsWith("{")) return null
        val status = jsonStringField(output, "status") ?: return null
        if (jsonStringField(output, "delivery_id") == null) return null
        return if (status == "settled") jsonStringField(output, "reply")?.trim().orEmpty() else ""
    }

    private const val COMPLETION_HEAD = "[IMPORTANT: Background process"
    private const val DELIVERY_SCRIPT = "bot_mode_dm.py"
    private val DELIVERY_TARGET = Regex("(?:^|\\s)-p\\s+(['\"]?)([a-z0-9][a-z0-9_-]{0,63})\\1\\s+chat\\b", RegexOption.IGNORE_CASE)

    /**
     * The recipient's reply, dug out of the terminal output.
     *
     * The delivery convention runs the recipient under `-Q`, whose contract is "suppress banner,
     * spinner and tool previews; only output the final response and session info" — and it prints
     * that session info as a `session_id: <id>` line immediately before the answer. So the last
     * such line IS the boundary: everything after it is the reply, everything before is the run's
     * own bookkeeping.
     *
     * ⚠️ Desktop merely deletes `session_id:` lines and keeps the rest, which is why a notice
     * reading "Message from theo" opened onto startup warnings, a deprecation block and the CLI's
     * ASCII reasoning box before reaching the word the agent actually said. Deliberate divergence:
     * cut at the documented boundary instead. Output with no such line keeps its whole text —
     * better whole noise than a wrong cut.
     *
     * Some payloads arrive JSON-wrapped as `{"output": "…"}`; that is unwrapped first, and a
     * wrapping code fence (how Keryx's notes carry stdout) is peeled.
     */
    fun replyText(rawResult: String): String {
        var raw = rawResult
        if (raw.trimStart().startsWith("{")) {
            jsonStringField(raw, "output")?.let { raw = it }
        }
        raw = stripFence(raw)
        val lines = raw.lines()
        val boundary = lines.indexOfLast { SESSION_ID_LINE.matches(it.trim()) }
        val after = if (boundary >= 0) lines.drop(boundary + 1).joinToString("\n").trim() else ""
        // The CLI prints its session line on EITHER side of the answer: before it under the shell
        // convention, after it as the delivery process's last line (seen live: Milo's whole reply
        // above `session_id:` and nothing below — cutting after it kept nothing). Nothing after
        // the boundary means the answer is above it, minus the run's own bookkeeping.
        val body = when {
            boundary < 0 -> lines.joinToString("\n").trim()
            after.isNotEmpty() -> after
            else -> lines.take(boundary).filterNot { CLI_BOOKKEEPING.matches(it.trim()) }.joinToString("\n").trim()
        }
        // The recipient may echo the convention back in its own answer; the prefix is addressing,
        // not content, and the notice already names who replied.
        return AgentDelivery.parse(body)?.body ?: body
    }

    /** Keryx carries header-less tool output as a fenced note — peel the fence before cutting. */
    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return text
        val lines = t.lines()
        val body = lines.drop(1).dropLastWhile { it.isBlank() }
        return (if (body.lastOrNull()?.trim() == "```") body.dropLast(1) else body).joinToString("\n")
    }

    private val SESSION_ID_LINE = Regex("^session_id:\\s.*$")

    /** Lines the resumed one-shot run prints about itself, never part of what the agent said. */
    private val CLI_BOOKKEEPING = Regex("^(?:Session \\S+ found but has no messages\\. Starting fresh\\.|↻ Resumed session .*)$")

    private val json = Json { ignoreUnknownKeys = true }

    /** One string field out of a JSON object, or null for anything else — malformed args are
     *  ordinary here (a model can emit whatever), so they must not throw. */
    private fun jsonStringField(raw: String, field: String): String? = runCatching {
        ((json.parseToJsonElement(raw) as? JsonObject)?.get(field) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    }.getOrNull()
}
