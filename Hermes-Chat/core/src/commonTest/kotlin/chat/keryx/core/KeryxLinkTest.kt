package chat.keryx.core

import chat.keryx.core.model.KeryxLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeryxLinkTest {
    @Test
    fun sessionsAndTapIn() {
        assertEquals(KeryxLink.Session("20261002_abc"), KeryxLink.parse("keryx://session/20261002_abc"))
        assertEquals(KeryxLink.Session("abc", tapIn = true), KeryxLink.parse("keryx://session/abc/tapin"))
        assertEquals(KeryxLink.Session("abc", tapIn = true), KeryxLink.parse("KERYX://tapin/abc"))
        // Round trip, odd characters included.
        val id = "room:with/odd chars"
        assertEquals(KeryxLink.Session(id), KeryxLink.parse(KeryxLink.session(id)))
    }

    @Test
    fun places() {
        assertEquals(KeryxLink.NewChat, KeryxLink.parse("keryx://new"))
        assertEquals(KeryxLink.Composer, KeryxLink.parse("keryx://chat"))
        assertEquals(KeryxLink.Space("missions"), KeryxLink.parse("keryx://missions/"))
        assertEquals(KeryxLink.Mission("t_42"), KeryxLink.parse("keryx://missions/t_42"))
        assertEquals(KeryxLink.Space("runs"), KeryxLink.parse("keryx://runs?from=widget"))
    }

    @Test
    fun anythingElseOpensNothing() {
        assertNull(KeryxLink.parse("https://example.com"))
        assertNull(KeryxLink.parse("keryx://"))
        assertNull(KeryxLink.parse("keryx://session/"))
        assertNull(KeryxLink.parse("keryx://settings/wipe"))
        assertNull(KeryxLink.parse(null))
    }
}
