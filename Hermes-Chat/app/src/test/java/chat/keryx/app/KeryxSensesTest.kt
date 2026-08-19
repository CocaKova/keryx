package chat.keryx.app

import chat.keryx.app.senses.KeryxSenses
import chat.keryx.app.senses.SenseReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Senses (2.3 §4) — the pure half. These functions decide what the phone tells the agent and how
 * often, so every rule that could leak more than the user agreed to is pinned here: the marker's
 * exact shape, the ME-side strip, the half-hour throttle, and which changes are allowed to jump
 * that queue. No Android types are involved, which is exactly why they were split out.
 */
class KeryxSensesTest {

    private val full = SenseReading(
        battery = "22%·charging",
        localTime = "23:10 CDT",
        place = "Austin TX (±1 km)",
    )

    // --- Marker ---------------------------------------------------------------------------------

    @Test
    fun marker_allThreeSenses_isTheDocumentedShape() {
        assertEquals(
            "⟦keryx:sense|battery=22%·charging|local=23:10 CDT|at=Austin TX (±1 km)⟧",
            KeryxSenses.marker(full),
        )
    }

    @Test
    fun marker_omitsAbsentParts() {
        assertEquals(
            "⟦keryx:sense|local=23:10 CDT⟧",
            KeryxSenses.marker(SenseReading(null, "23:10 CDT", null)),
        )
        assertEquals(
            "⟦keryx:sense|battery=78%|at=Austin TX (±1 km)⟧",
            KeryxSenses.marker(SenseReading("78%", null, "Austin TX (±1 km)")),
        )
    }

    @Test
    fun marker_nothingEnabled_isEmpty() {
        assertEquals("", KeryxSenses.marker(SenseReading(null, null, null)))
        assertTrue(SenseReading(null, null, null).isEmpty)
        assertFalse(full.isEmpty)
    }

    // --- Strip ----------------------------------------------------------------------------------

    @Test
    fun strip_removesTrailingMarkerAndTheNewlineThatCarriedIt() {
        val marker = KeryxSenses.marker(full)
        assertEquals("how's the deploy?", KeryxSenses.stripMarker("how's the deploy?\n$marker"))
    }

    @Test
    fun strip_leavesMessagesWithoutAMarkerAlone() {
        assertEquals("plain text", KeryxSenses.stripMarker("plain text"))
        assertEquals("", KeryxSenses.stripMarker(""))
        // A different marker family must survive untouched.
        assertEquals(
            "pick one ⟦keryx:ask|Approve|Deny⟧",
            KeryxSenses.stripMarker("pick one ⟦keryx:ask|Approve|Deny⟧"),
        )
    }

    @Test
    fun strip_onlyEatsTheTail_notAMidMessageLookalike() {
        val text = "I pasted ⟦keryx:sense|battery=9%⟧ into the log, look"
        assertEquals(text, KeryxSenses.stripMarker(text))
    }

    @Test
    fun strip_survivesTrailingWhitespace() {
        assertEquals("hey", KeryxSenses.stripMarker("hey\n⟦keryx:sense|battery=22%·charging⟧  \n"))
    }

    // --- payloadClass ---------------------------------------------------------------------------

    @Test
    fun payloadClass_ignoresTheClock() {
        // The clock changes every minute; if it counted, the throttle would never hold.
        assertEquals(
            KeryxSenses.payloadClass(full),
            KeryxSenses.payloadClass(full.copy(localTime = "23:59 CDT")),
        )
    }

    @Test
    fun payloadClass_changesWhenTheChargerIsPluggedIn() {
        val unplugged = full.copy(battery = "22%")
        assertTrue(KeryxSenses.payloadClass(full) != KeryxSenses.payloadClass(unplugged))
    }

    @Test
    fun payloadClass_quantisesBatteryToTwentyPointSteps() {
        val a = full.copy(battery = "63%")
        val b = full.copy(battery = "72%") // same 60–79 step: no news
        val c = full.copy(battery = "58%") // crossed down into 40–59: news
        assertEquals(KeryxSenses.payloadClass(a), KeryxSenses.payloadClass(b))
        assertTrue(KeryxSenses.payloadClass(a) != KeryxSenses.payloadClass(c))
    }

    @Test
    fun payloadClass_changesWhenThePlaceChanges() {
        assertTrue(
            KeryxSenses.payloadClass(full) !=
                KeryxSenses.payloadClass(full.copy(place = "Round Rock TX (±1 km)")),
        )
    }

    @Test
    fun payloadClass_distinguishesSensesBeingTurnedOff() {
        val noBattery = full.copy(battery = null)
        val noPlace = full.copy(place = null)
        assertTrue(KeryxSenses.payloadClass(full) != KeryxSenses.payloadClass(noBattery))
        assertTrue(KeryxSenses.payloadClass(full) != KeryxSenses.payloadClass(noPlace))
    }

    // --- shouldSend -----------------------------------------------------------------------------

    private val payload = KeryxSenses.payloadClass(full)
    private val half = KeryxSenses.MIN_INTERVAL_MS

    @Test
    fun shouldSend_firstEverMessageInARoom() {
        assertTrue(KeryxSenses.shouldSend(1_000_000L, 0L, null, payload))
    }

    @Test
    fun shouldSend_isQuietInsideTheWindowWhenNothingChanged() {
        assertFalse(KeryxSenses.shouldSend(1_000_000L + half - 1, 1_000_000L, payload, payload))
    }

    @Test
    fun shouldSend_reSendsOnceTheWindowElapses() {
        assertTrue(KeryxSenses.shouldSend(1_000_000L + half, 1_000_000L, payload, payload))
    }

    @Test
    fun shouldSend_jumpsTheQueueWhenThePayloadChangedClass() {
        val moved = KeryxSenses.payloadClass(full.copy(place = "Dallas TX (±1 km)"))
        assertTrue(KeryxSenses.shouldSend(1_000_000L + 60_000L, 1_000_000L, payload, moved))
    }

    @Test
    fun shouldSend_recoversFromAClockThatWentBackwards() {
        // A stale future timestamp must not mute the room forever.
        assertTrue(KeryxSenses.shouldSend(1_000L, 9_000_000L, payload, payload))
    }

    // --- Decoration gate ------------------------------------------------------------------------

    @Test
    fun slashCommandsAndBlankTextAreNeverDecorated() {
        assertFalse(KeryxSenses.isDecoratable("/help"))
        assertFalse(KeryxSenses.isDecoratable("   /clear"))
        assertFalse(KeryxSenses.isDecoratable(""))
        assertFalse(KeryxSenses.isDecoratable("   \n "))
        assertTrue(KeryxSenses.isDecoratable("what's the weather where I am?"))
        assertTrue(KeryxSenses.isDecoratable("a/b is not a command"))
    }

    // --- Formatting -----------------------------------------------------------------------------

    @Test
    fun formatBattery_bothStates() {
        assertEquals("22%·charging", KeryxSenses.formatBattery(22, charging = true))
        assertEquals("78%", KeryxSenses.formatBattery(78, charging = false))
    }

    @Test
    fun formatClock_padsAndAppendsTheZone() {
        assertEquals("23:10 CDT", KeryxSenses.formatClock(23, 10, "CDT"))
        assertEquals("07:05 GMT", KeryxSenses.formatClock(7, 5, "GMT"))
        assertEquals("07:05", KeryxSenses.formatClock(7, 5, ""))
    }

    @Test
    fun roundCoord_keepsAboutAKilometreOfAmbiguity() {
        assertEquals(30.27, KeryxSenses.roundCoord(30.267153), 1e-9)
        assertEquals(-97.74, KeryxSenses.roundCoord(-97.743061), 1e-9)
    }

    @Test
    fun formatPlace_namedAndUnnamed() {
        assertEquals(
            "Austin TX (±1 km)",
            KeryxSenses.formatPlace(30.267153, -97.743061, "Austin", "TX"),
        )
        assertEquals(
            "Austin (±1 km)",
            KeryxSenses.formatPlace(30.267153, -97.743061, "Austin", null),
        )
        assertEquals(
            "30.27,-97.74 (±1 km)",
            KeryxSenses.formatPlace(30.267153, -97.743061, null, "TX"),
        )
        assertEquals(
            "30.27,-97.74 (±1 km)",
            KeryxSenses.formatPlace(30.267153, -97.743061, "  ", "TX"),
        )
    }

    @Test
    fun regionLabel_shortensWhatTheGeocoderActuallyReturns() {
        assertEquals("TX", KeryxSenses.regionLabel("Texas", "US"))
        assertEquals("NY", KeryxSenses.regionLabel("New York", "US"))
        assertEquals("TX", KeryxSenses.regionLabel("TX", "US"))
        // Non-US long admin areas fall back to the country, never to a mouthful.
        assertEquals("DE", KeryxSenses.regionLabel("Nordrhein-Westfalen", "de"))
        assertNull(KeryxSenses.regionLabel(null, null))
        assertNull(KeryxSenses.regionLabel("Nordrhein-Westfalen", "  "))
    }

    @Test
    fun lastSentLabel_readsAsASentence() {
        val now = 1_800_000_000_000L
        assertEquals("Never", KeryxSenses.lastSentLabel(now, 0L))
        assertEquals("just now", KeryxSenses.lastSentLabel(now, now - 30_000L))
        assertEquals("12m ago", KeryxSenses.lastSentLabel(now, now - 12 * 60_000L))
        assertEquals("3h ago", KeryxSenses.lastSentLabel(now, now - 3 * 3_600_000L))
        assertEquals("2d ago", KeryxSenses.lastSentLabel(now, now - 2 * 86_400_000L))
    }
}
