package chat.keryx.app

import chat.keryx.core.protocol.MAX_PAGE
import chat.keryx.core.protocol.sessionMessagesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript-hydration query shape. Three hydration bugs have come from a wrong param
 * here, and none of them threw — the app just quietly showed the wrong half of a session.
 */
class SessionMessagesQueryTest {

    @Test
    fun `hydration asks for the newest page by default`() {
        assertEquals(
            "limit=120&offset=0&order=latest&include_compacted=true",
            sessionMessagesQuery(limit = 120),
        )
    }

    /**
     * The 0.6.4 bug: `order=oldest` opened a long session on its ancient beginning. Paging
     * direction is not cosmetic — with `latest`, offset walks BACKWARDS from the newest row.
     */
    @Test
    fun `offset pages into the past, not forward from the beginning`() {
        assertTrue(sessionMessagesQuery(limit = 120, offset = 120).contains("order=latest"))
        assertTrue(sessionMessagesQuery(limit = 120, offset = 120).contains("offset=120"))
    }

    @Test
    fun `oldest order is still reachable for callers that want the head`() {
        assertTrue(sessionMessagesQuery(limit = 10, newestFirst = false).contains("order=oldest"))
    }

    /**
     * Compaction-archived rows (`active=0, compacted=1`) are durable display history. Drop
     * this flag and the transcript ends at the compaction boundary with every earlier turn
     * unreachable — silently, since the truncated page looks like a complete short session.
     */
    @Test
    fun `every page is a display read, including backfill pages`() {
        for (offset in listOf(0, 120, 240, 3600)) {
            assertTrue(
                "offset=$offset dropped include_compacted",
                sessionMessagesQuery(limit = 120, offset = offset).contains("include_compacted=true"),
            )
        }
    }

    @Test
    fun `limit is clamped to the server cap rather than sent over it`() {
        assertTrue(sessionMessagesQuery(limit = MAX_PAGE * 4).contains("limit=$MAX_PAGE"))
        assertTrue(sessionMessagesQuery(limit = -1).contains("limit=0"))
    }
}
