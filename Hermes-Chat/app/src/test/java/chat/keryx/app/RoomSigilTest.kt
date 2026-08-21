package chat.keryx.app

import chat.keryx.core.model.RoomSigil
import chat.keryx.core.model.RoomSigils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer / Quick Rooms sigil decision (2.3 §1's deferred sub-bullet).
 *
 * Worth pinning away from Compose because the rule is a *judgement*, not a lookup: a room with
 * one herald deliberately does NOT wear that herald's hue (every such room would come out
 * identical), while a room with several does.
 */
class RoomSigilTest {

    private val configured = listOf("@silas:silas.local", "@milo:silas.local", "@theo:silas.local")

    // --- heraldsAmong --------------------------------------------------------------------------

    @Test
    fun `a room with no configured agent yields nothing`() {
        assertEquals(
            emptyList<String>(),
            RoomSigils.heraldsAmong(listOf("@jonny:silas.local", "@guest:silas.local"), configured),
        )
    }

    @Test
    fun `no configured heralds means no sigils anywhere — the unconfigured install is untouched`() {
        assertEquals(
            emptyList<String>(),
            RoomSigils.heraldsAmong(listOf("@silas:silas.local"), emptyList()),
        )
    }

    @Test
    fun `the agent is picked out of the member list`() {
        assertEquals(
            listOf("@silas:silas.local"),
            RoomSigils.heraldsAmong(listOf("@jonny:silas.local", "@silas:silas.local"), configured),
        )
    }

    @Test
    fun `a herald is matched by bare localpart across homeservers`() {
        assertEquals(
            listOf("@milo:other.example"),
            RoomSigils.heraldsAmong(listOf("@milo:other.example", "@jonny:silas.local"), configured),
        )
    }

    @Test
    fun `a room the member store has not answered for yet yields nothing`() {
        assertEquals(emptyList<String>(), RoomSigils.heraldsAmong(emptyList(), configured))
    }

    // --- of ------------------------------------------------------------------------------------

    @Test
    fun `no heralds leaves the lettered monogram alone`() {
        assertEquals(RoomSigil.None, RoomSigils.of(emptyList()))
    }

    @Test
    fun `one herald is a Single, keyed by localpart`() {
        assertEquals(RoomSigil.Single("silas"), RoomSigils.of(listOf("@silas:silas.local")))
    }

    @Test
    fun `two heralds stack, in member order`() {
        assertEquals(
            RoomSigil.Stack(listOf("milo", "theo")),
            RoomSigils.of(listOf("@milo:silas.local", "@theo:silas.local")),
        )
    }

    @Test
    fun `the same herald twice is still one life, not a council`() {
        assertEquals(
            RoomSigil.Single("milo"),
            RoomSigils.of(listOf("@milo:silas.local", "@milo:silas.local")),
        )
    }

    @Test
    fun `a stack is capped so the row stays legible at avatar size`() {
        val many = listOf("@a:s", "@b:s", "@c:s", "@d:s", "@e:s")
        val sigil = RoomSigils.of(many) as RoomSigil.Stack
        assertEquals(RoomSigils.MAX_STACK, sigil.heraldKeys.size)
        assertEquals(listOf("a", "b", "c"), sigil.heraldKeys)
    }

    @Test
    fun `a blank id cannot conjure a sigil`() {
        assertEquals(RoomSigil.None, RoomSigils.of(listOf("", "  ")))
    }
}
