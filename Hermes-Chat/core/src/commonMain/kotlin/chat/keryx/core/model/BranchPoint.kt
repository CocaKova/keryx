package chat.keryx.core.model

import chat.keryx.core.protocol.MessageRow

/**
 * Where "Branch from here" cuts (2.16). `session.branch {count}` keeps the first `count` rows of
 * the gateway's *visible* history — user and assistant rows with text (hermes
 * `tui_gateway/methods_session.py` `_visible_branch_history`) — so the count is the tapped
 * message's place among those rows, counted from the start, inclusive.
 */
object BranchPoint {

    /** The rows the gateway counts. */
    fun visible(row: MessageRow): Boolean =
        (row.role == "user" || row.role == "assistant") && row.content.isNotBlank()

    /**
     * The count that keeps everything up to and including the message [messageId] — found by its
     * row id (a hydrated row, or one of its derived rows: "think-12", "tools-12"), else, for a row
     * the transcript still knows by its live name, by its text from the end ([content], [mine]).
     * [rows] must be the WHOLE history, oldest first. Null when the message isn't among them.
     */
    fun count(rows: List<MessageRow>, messageId: String, content: String, mine: Boolean): Int? {
        val rowId = messageId.toLongOrNull() ?: messageId.substringAfterLast('-').toLongOrNull()
            ?.takeIf { !messageId.startsWith("live-") }
        var at = if (rowId != null) rows.indexOfFirst { it.id == rowId } else -1
        if (at < 0) {
            val text = content.trim()
            if (text.isEmpty()) return null
            val role = if (mine) "user" else "assistant"
            at = rows.indexOfLast { it.role == role && it.content.trim() == text }
        }
        if (at < 0) return null
        val n = rows.subList(0, at + 1).count(::visible)
        return n.takeIf { it > 0 }
    }
}

/**
 * A typed `/branch [name]` or `/fork [name]` (2.19). Sent as text it reaches Hermes' command
 * worker, which holds no copy of the chat and answers "No conversation to branch — send a
 * message first." every time (device, 2026-10-09). Keryx forks the whole conversation itself,
 * through the same `session.branch` the message menu uses. `--here` is a thread option for
 * messaging platforms; a Keryx fork always opens as its own conversation, so it is dropped.
 */
object BranchCommand {
    private val RE = Regex("""^/(branch|fork)(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)

    /** The fork's name ("" = let Hermes name it), or null when [text] isn't the command. */
    fun parse(text: String): String? {
        val m = RE.matchEntire(text.trim()) ?: return null
        return (m.groupValues[2]).split(Regex("\\s+")).filter { it.isNotBlank() && it != "--here" }
            .joinToString(" ")
    }
}

