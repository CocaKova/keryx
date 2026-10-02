package chat.keryx.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A tool's result, read rather than dumped (2.16).
 *
 * The shell tools hand back one JSON envelope — `terminal`'s `{"output": "399", "exit_code": 0,
 * "error": null}`, `execute_code`'s `{"status": "success", "output": …, "exit_code": 0, …}` —
 * and the card printed that envelope verbatim, so the one number that says whether the command
 * worked sat unread at the end of a JSON line. [body] is what the command printed, [exitCode]
 * the verdict, [error] the tool's own complaint.
 *
 * Every other shape passes through untouched — plain text, a `read_file` payload, a `patch`
 * result, a `process` status — because unwrapping a shape we don't know would hide fields the
 * reader needs. A result the 6 000-char display cap cut mid-JSON still gets its output read
 * from the envelope's opening, so a long build log does not fall back to raw JSON exactly when
 * it matters most.
 *
 * Pure Kotlin (KMP rule): no android.* here.
 */
data class ToolOutput(
    /** What to show as the output: the envelope's `output` when unwrapped, else the text as is. */
    val body: String,
    /** The command's exit status; null when the result carries none. */
    val exitCode: Int? = null,
    /** The tool's `error` field when it said something; null for `null` or blank. */
    val error: String? = null,
) {
    /** Did the result say the command failed — a non-zero exit, or an error of its own? */
    val bad: Boolean get() = (exitCode != null && exitCode != 0) || error != null

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Keys the shell envelope may carry besides `output` without being a different shape. */
        private const val OUTPUT = "output"
        private const val EXIT = "exit_code"
        private const val ERROR = "error"

        /** The envelope's opening as the display cap leaves it: `{"output": "…` running off the end. */
        private val TRUNCATED_HEAD = Regex("""^\{\s*"output"\s*:\s*"""")
        private val TRUNCATED_EXIT = Regex(""""exit_code"\s*:\s*(-?\d+)""")
        private const val CUT_NOTE = "… [truncated]"

        fun parse(result: String): ToolOutput {
            val t = result.trim()
            if (!t.startsWith("{")) return ToolOutput(result)
            val o = runCatching { json.parseToJsonElement(t) as? JsonObject }.getOrNull()
                ?: return truncated(t) ?: ToolOutput(result)
            val out = (o[OUTPUT] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val exit = (o[EXIT] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
            val err = (o[ERROR] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                ?.trim()?.takeIf { it.isNotEmpty() }
            // Unwrap only the shell envelope: an output string AND an exit code, or nothing but
            // the three envelope keys. A `process` status ({"status":"killed","output":…}) keeps
            // its JSON — "killed" is the news there, not the output.
            val envelope = out != null && (exit != null || o.keys.all { it == OUTPUT || it == EXIT || it == ERROR })
            return if (envelope) ToolOutput(out!!.trimEnd(), exit, err)
            else ToolOutput(result, exit, err)
        }

        /**
         * The cap cut the envelope: decode the `output` string up to wherever the text ends and
         * take the exit code only if the cut left it in. Null when it is not the envelope at all.
         */
        private fun truncated(raw: String): ToolOutput? {
            // ToolText.displayResult's own cut note goes back on after the decode, once.
            val t = raw.removeSuffix(CUT_NOTE).trimEnd()
            val head = TRUNCATED_HEAD.find(t) ?: return null
            val sb = StringBuilder()
            var i = head.range.last + 1
            var closed = false
            while (i < t.length) {
                val c = t[i]
                if (c == '"') { closed = true; break }
                if (c == '\\' && i + 1 < t.length) {
                    when (val n = t[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = t.substring(i + 2, minOf(i + 6, t.length))
                            val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); i += 4 } else sb.append("\\u")
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            val rest = if (closed) t.substring(i) else ""
            val exit = TRUNCATED_EXIT.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            val body = sb.toString().trimEnd()
            return ToolOutput(if (closed) body else "$body\n$CUT_NOTE", exit)
        }
    }
}
