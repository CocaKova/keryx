package chat.keryx.core.protocol

/**
 * The query string for `GET /api/sessions/{id}/messages`.
 *
 * Transport-neutral on purpose: the Android client builds it with OkHttp and an iOS client
 * will build it with something else, but the *shape* is a protocol fact that neither owns
 * (docs/MULTIPLATFORM.md). Every param here has cost a hydration bug at least once, so the
 * shape lives where a test can hold it rather than inline in a URL concatenation.
 *
 * @param limit rows per page; the server caps at [MAX_PAGE] and clamps past it.
 * @param offset how many rows to skip from the end [order] counts from.
 * @param newestFirst `order=latest` — see the `order` note below.
 */
fun sessionMessagesQuery(
    limit: Int,
    offset: Int = 0,
    newestFirst: Boolean = true,
): String =
    "limit=${limit.coerceIn(0, MAX_PAGE)}" +
        "&offset=$offset" +
        "&order=${if (newestFirst) "latest" else "oldest"}" +
        // ── include_compacted ──────────────────────────────────────────────
        // What makes this a DISPLAY read rather than a context read. In-place
        // compaction marks the rows it summarised away `active=0, compacted=1`:
        // durable display history, not deletions (undo/rewind rows are
        // `active=0, compacted=0` and stay excluded either way). Without the
        // flag the server answers with the live context only, so the transcript
        // stops dead at the compaction boundary and "earlier messages" pages
        // into nothing instead of into the past — measured at 81 of 168 rows on
        // a real session. Gateways predating the flag ignore the unknown query
        // param, so sending it is correct in both directions.
        "&include_compacted=true"

/** The server's hard cap on rows per page; asking for more is clamped, not rejected. */
const val MAX_PAGE: Int = 500
