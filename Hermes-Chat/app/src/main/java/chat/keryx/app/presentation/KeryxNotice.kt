package chat.keryx.app.presentation

/**
 * One thing the app has to tell you in passing (2.16): a result, a refusal, or a change you can
 * take back. It replaces the system Toast, which could not carry an action, wore the launcher's
 * grey whatever the theme said, and floated over the composer's text.
 *
 * [onAction] runs only when the action is tapped; a notice that times out or is superseded
 * never runs it. Plain text in, plain notice out — `viewModel.toast("…")` keeps working.
 */
data class KeryxNotice(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
) {
    val hasAction: Boolean get() = !actionLabel.isNullOrBlank() && onAction != null

    companion object {
        /** A plain notice stays a little longer than Toast.LENGTH_LONG (3.5 s) read: one line,
         *  read once. Accessibility settings stretch both (see the host). */
        const val PLAIN_MS = 4_000L

        /** One that offers to undo stays long enough to change your mind after reading it. */
        const val ACTION_MS = 8_000L

        /** The one word the reversible verbs share, so every Undo reads the same. */
        const val UNDO = "Undo"

        fun undo(text: String, onUndo: () -> Unit) = KeryxNotice(text, UNDO, onUndo)

        fun durationMillis(notice: KeryxNotice): Long = if (notice.hasAction) ACTION_MS else PLAIN_MS
    }
}

/**
 * Which notices get shown, and in what order. Pure, so the rules are tested rather than
 * eyeballed:
 *
 * - **No echoes.** A notice with the text of the one on screen or the last one waiting is
 *   dropped when it offers nothing new: a retry loop that fails the same way ten times says so
 *   once (the "never an error toast loop" rule, older gateways).
 * - **An Undo is never kept waiting.** Archive two sessions in a row and the second Undo must be
 *   on screen now, not after the first one's eight seconds — so a notice with an action cuts the
 *   one on screen short (its action is NOT run; walking away from an Undo is declining it).
 * - **The queue is short.** Anything past [MAX_PENDING] drops the oldest waiting plain notice; a
 *   notice that offers an action is never the one dropped while a plain one could be.
 */
object KeryxNoticeQueue {
    const val MAX_PENDING = 3

    data class Decision(
        /** Cut the notice on screen short and show the head of [pending] next. */
        val supersede: Boolean,
        val pending: List<KeryxNotice>,
        /** The incoming notice was an echo and was dropped. */
        val dropped: Boolean = false,
    )

    fun admit(showing: KeryxNotice?, pending: List<KeryxNotice>, incoming: KeryxNotice): Decision {
        if (incoming.text.isBlank() && !incoming.hasAction) return Decision(false, pending, dropped = true)
        val last = pending.lastOrNull() ?: showing
        if (!incoming.hasAction && last != null && last.text == incoming.text) {
            return Decision(false, pending, dropped = true)
        }
        if (incoming.hasAction) {
            // Jumps the queue: on screen next, everything else keeps its place behind it. An
            // older Undo for the same words is the same offer — the newer one replaces it.
            val rest = pending.filterNot { it.hasAction && it.text == incoming.text }
            return Decision(showing != null, trim(listOf(incoming) + rest, protectHead = true))
        }
        return Decision(false, trim(pending + incoming, protectHead = false))
    }

    /** Oldest plain notice first; failing that, the oldest action — never a protected head. */
    private fun trim(queue: List<KeryxNotice>, protectHead: Boolean): List<KeryxNotice> {
        val q = queue.toMutableList()
        val from = if (protectHead) 1 else 0
        while (q.size > MAX_PENDING) {
            val plain = (from until q.size).firstOrNull { !q[it].hasAction }
            q.removeAt(plain ?: from)
        }
        return q
    }
}
