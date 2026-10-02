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
