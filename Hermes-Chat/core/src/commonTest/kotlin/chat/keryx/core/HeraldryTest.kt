package chat.keryx.core

import chat.keryx.core.model.Heralds
import chat.keryx.core.model.RoomSigil
import chat.keryx.core.model.RoomSigils
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The pure heraldry layer (2.3 §1). These rules decide which sender is an agent at all and what
 * colour it wears, so they are worth pinning down away from Compose.
 */
class HeraldryTest {

    // --- parseIds ------------------------------------------------------------------------------

    @Test
    fun `a single id still parses — the 2_2 config keeps working`() {
        assertEquals(listOf("@hermes:example.com"), Heralds.parseIds("@hermes:example.com"))
    }

    @Test
    fun `commas newlines and spaces all separate`() {
        assertEquals(
            listOf("@scout:x.com", "@atlas:x.com", "@vega:x.com"),
            Heralds.parseIds("@scout:x.com, @atlas:x.com\n@vega:x.com"),
        )
    }

    @Test
    fun `blank config is no heralds, not one empty herald`() {
        assertEquals(emptyList<String>(), Heralds.parseIds("   \n , ; "))
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(listOf("@scout:x.com"), Heralds.parseIds("@scout:x.com,@scout:x.com"))
    }

    // --- isHerald ------------------------------------------------------------------------------

    @Test
    fun `matches exactly and case-insensitively`() {
        val ids = listOf("@Hermes:Example.com")
        assertTrue(Heralds.isHerald("@hermes:example.com", ids))
    }

    @Test
    fun `matches on bare localpart so a homeserver rename does not orphan the agent`() {
        assertTrue(Heralds.isHerald("@scout:other.server", listOf("@scout:example.org")))
    }

    @Test
    fun `a human in the room is not a herald`() {
        assertFalse(Heralds.isHerald("@sam:example.org", listOf("@scout:example.org")))
    }

    @Test
    fun `no configured ids means nobody is a herald`() {
        assertFalse(Heralds.isHerald("@scout:example.org", emptyList()))
    }

    // --- slots ---------------------------------------------------------------------------------

    @Test
    fun `a council of six never shares a hue`() {
        val ids = listOf("@scout:x", "@atlas:x", "@ledger:x", "@vega:x", "@hermes:x", "@nova:x")
        val slots = Heralds.assignSlots(ids)
        assertEquals(6, slots.size)
        assertEquals(6, slots.values.toSet().size)
    }

    @Test
    fun `adding a herald does not repaint the ones already seated`() {
        val before = Heralds.assignSlots(listOf("@scout:x", "@atlas:x"))
        val after = Heralds.assignSlots(listOf("@scout:x", "@atlas:x", "@vega:x"))
        assertEquals(before["scout"], after["scout"])
        assertEquals(before["atlas"], after["atlas"])
    }

    @Test
    fun `the hash is stable — a hue must not move between launches`() {
        assertEquals(Heralds.stableHash("scout"), Heralds.stableHash("scout"))
        assertNotEquals(Heralds.stableHash("scout"), Heralds.stableHash("atlas"))
    }

    // --- resolve -------------------------------------------------------------------------------

    private val gold = 0xFFF0B429L
    private val ember = 0xFFE55A00L

    @Test
    fun `the primary herald wears the user's own accents`() {
        val h = Heralds.resolve(
            senderId = "@hermes:x", senderName = "Hermes",
            ids = listOf("@hermes:x", "@scout:x"), overrides = emptyMap(),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertTrue(h.primary)
        assertEquals(gold, h.accentArgb)
        assertEquals(ember, h.accent2Argb)
    }

    @Test
    fun `a second herald takes a palette hue, not the theme's`() {
        val h = Heralds.resolve(
            senderId = "@scout:x", senderName = "Scout",
            ids = listOf("@hermes:x", "@scout:x"), overrides = emptyMap(),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertFalse(h.primary)
        assertNotEquals(gold, h.accentArgb)
        assertTrue(Heralds.PALETTE.any { it.first == h.accentArgb })
    }

    @Test
    fun `a lone configured agent is primary — a 1 to 1 room looks exactly like 2_2`() {
        val h = Heralds.resolve(
            senderId = "@hermes:x", senderName = "Hermes",
            ids = listOf("@hermes:x"), overrides = emptyMap(),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertTrue(h.primary)
        assertEquals(gold, h.accentArgb)
    }

    @Test
    fun `a user override beats both the palette and the theme`() {
        val mine = 0xFF00FF00L
        val h = Heralds.resolve(
            senderId = "@scout:x", senderName = "Scout",
            ids = listOf("@hermes:x", "@scout:x"), overrides = mapOf("scout" to mine),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertEquals(mine, h.accentArgb)
        // accent2 is derived by shading, so it must be darker but still opaque.
        assertNotEquals(mine, h.accent2Argb)
        assertEquals(0xFFL, (h.accent2Argb shr 24) and 0xFF)
    }

    @Test
    fun `an override on the primary herald is honoured too`() {
        val mine = 0xFF123456L
        val h = Heralds.resolve(
            senderId = "@hermes:x", senderName = "Hermes",
            ids = listOf("@hermes:x"), overrides = mapOf("hermes" to mine),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertEquals(mine, h.accentArgb)
    }

    @Test
    fun `the display name falls back to the localpart when there is none`() {
        val h = Heralds.resolve(
            senderId = "@scout:x", senderName = "",
            ids = listOf("@scout:x"), overrides = emptyMap(),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertEquals("scout", h.name)
    }

    @Test
    fun `a raw mxid as a display name is not shown as the name`() {
        val h = Heralds.resolve(
            senderId = "@scout:x", senderName = "@scout:x",
            ids = listOf("@scout:x"), overrides = emptyMap(),
            themeAccent = gold, themeAccent2 = ember,
        )
        assertEquals("scout", h.name)
    }

    @Test
    fun `shade darkens without touching alpha`() {
        val shaded = Heralds.shade(0xFF808080L, 0.5f)
        assertEquals(0xFFL, (shaded shr 24) and 0xFF)
        assertEquals(0x40L, (shaded shr 16) and 0xFF)
    }

    // --- the blank-config fallback ---------------------------------------------------------------

    @Test
    fun `with nobody configured, the one other member of a room is its herald`() {
        assertEquals(
            listOf("@hermes:example.org"),
            RoomSigils.soloHerald(listOf("@sam:example.org", "@hermes:example.org"), "@sam:example.org"),
        )
    }

    @Test
    fun `a room full of people keeps its letter, because a wrong staff is worse than one`() {
        assertEquals(
            emptyList<String>(),
            RoomSigils.soloHerald(
                listOf("@sam:example.org", "@hermes:example.org", "@scout:example.org"),
                "@sam:example.org",
            ),
        )
    }

    @Test
    fun `a room of one is a room of no heralds`() {
        assertEquals(emptyList<String>(), RoomSigils.soloHerald(listOf("@sam:example.org"), "@sam:example.org"))
    }

    @Test
    fun `my own id is matched case-insensitively, so I am never my own herald`() {
        assertEquals(
            listOf("@hermes:example.org"),
            RoomSigils.soloHerald(listOf("@Sam:example.org", "@hermes:example.org"), "@sam:example.org"),
        )
    }

    @Test
    fun `the solo herald still resolves to a single sigil`() {
        val heralds = RoomSigils.soloHerald(listOf("@sam:example.org", "@hermes:example.org"), "@sam:example.org")
        assertEquals(RoomSigil.Single("hermes"), RoomSigils.of(heralds))
    }
}
