package chat.keryx.app.transport.direct

import chat.keryx.core.protocol.MessageRow

/**
 * Pure paging arithmetic over the gateway's transcript endpoint, kept free of the transport so
 * the Archive walk and the context window are unit-tested without a socket.
 *
 * The endpoint pages newest-first by OFFSET (offset = how many rows back from the newest the
 * page starts), and each page arrives in chronological order. [pageUntil] stitches pages into
 * one chronological list, oldest first, stopping when [enough] says the caller has what it
 * came for or the server runs dry (a short page).
 */
object TranscriptPages {

    /**
     * Fetch pages from the newest backwards until [enough] holds over the rows gathered so
     * far, or a page comes back short. Returns the rows oldest→newest and whether the walk
     * reached the beginning of the session.
     */
    suspend fun pageUntil(
        pageSize: Int,
        fetch: suspend (offset: Int) -> List<MessageRow>,
        enough: (rows: List<MessageRow>) -> Boolean,
    ): Walk {
        val acc = ArrayList<MessageRow>()
        val seen = HashSet<Long>()
        var offset = 0
        while (true) {
            val page = fetch(offset)
            val fresh = page.filterNot { it.id in seen }
            fresh.forEach { seen += it.id }
            // Pages are chronological; the walk goes backwards, so older pages go in front.
            acc.addAll(0, fresh)
            offset += page.size
            if (page.size < pageSize) return Walk(acc, exhausted = true)
            if (enough(acc)) return Walk(acc, exhausted = false)
        }
    }

    data class Walk(val rows: List<MessageRow>, val exhausted: Boolean)

    /** The gateway row a client-side message id points at: prose rows are the bare row id,
     *  synthesized ones (`tools-N`, `think-N`, delegation reports) carry it as a suffix. */
    fun rowIdOf(messageId: String): Long? =
        messageId.substringAfterLast('-').toLongOrNull()

    /** [before]/[after] neighbours of [index] in [items] — the Archive's context window. */
    fun <T> window(items: List<T>, index: Int, before: Int, after: Int): List<T> {
        if (index !in items.indices) return emptyList()
        val from = (index - before).coerceAtLeast(0)
        val to = (index + after + 1).coerceAtMost(items.size)
        return items.subList(from, to)
    }
}
