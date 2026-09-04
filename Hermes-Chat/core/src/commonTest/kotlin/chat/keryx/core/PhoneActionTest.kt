package chat.keryx.core

import chat.keryx.core.model.PhoneAction
import chat.keryx.core.protocol.MessageParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneActionTest {

    @Test
    fun `every kind parses with its arguments and labels itself`() {
        fun a(s: String) = PhoneAction.parse(s) ?: error("no action from $s")
        assertEquals("Open example.com", a("url|https://www.example.com/path?q=1").label)
        assertEquals("Call +15125550100", a("dial|+15125550100").label)
        assertEquals("Text +15125550100", a("sms|+15125550100|on my way").label)
        assertEquals(listOf("+15125550100", "on my way"), a("sms|+15125550100|on my way|").args)
        assertEquals("Email monika@example.com", a("email|monika@example.com|Week 2|draft attached").label)
        assertEquals("Add to calendar · Roger · saltcreek.tech", a("calendar|Roger · saltcreek.tech|2026-09-05T14:00|2026-09-05T15:00|Zoom").label)
        assertEquals("Alarm 07:30 · gym", a("alarm|07:30|gym").label)
        assertEquals("Timer 10 min", a("timer|10m").label)
        assertEquals("Timer 1 h 30 min · rice", a("timer|1h30m|rice").label)
        assertEquals("Timer 1 min 30 s", a("timer|90").label)
        assertEquals("Timer 1 min 30 s", a("timer|1:30").label)
        assertEquals("Navigate to H-E-B Mueller, Austin", a("navigate|H-E-B Mueller, Austin").label)
        assertEquals("Search “dgx spark qsfp cable”", a("search|dgx spark qsfp cable").label)
        assertEquals("Play Boards of Canada", a("play|Boards of Canada").label)
        assertEquals("Open Spotify", a("open|Spotify").label)
        assertEquals("Copy “sk-local”", a("copy|sk-local").label)
        assertEquals("Torch on", a("torch|ON").label)
        assertEquals("Torch off", a("torch|off").label)
        assertEquals("Share “hello there”", a("share|hello there").label)
    }

    @Test
    fun `what is not an action stays out`() {
        assertNull(PhoneAction.parse("dance|now"))           // unknown kind
        assertNull(PhoneAction.parse("dial"))                // missing argument
        assertNull(PhoneAction.parse("dial|"))               // empty argument
        assertNull(PhoneAction.parse("url|example.com"))     // not a link
        assertNull(PhoneAction.parse("alarm|25:99"))         // not a clock
        assertNull(PhoneAction.parse("alarm|7"))             // not HH:MM
        assertNull(PhoneAction.parse("timer|soon"))          // not a duration
        assertNull(PhoneAction.parse("timer|0"))             // zero
        assertNull(PhoneAction.parse("torch|maybe"))
        assertNull(PhoneAction.parse("calendar|x|tomorrow")) // not ISO-ish
        assertNull(PhoneAction.parse("url|https://a|https://b")) // too many args
    }

    @Test
    fun `durations and clocks read the shorthand people write`() {
        assertEquals(600, PhoneAction.timerSeconds("10m"))
        assertEquals(5400, PhoneAction.timerSeconds("1h30m"))
        assertEquals(45, PhoneAction.timerSeconds("45s"))
        assertEquals(125, PhoneAction.timerSeconds("2:05"))
        assertEquals(7 to 5, PhoneAction.clockOf("7:05"))
        assertNull(PhoneAction.clockOf("24:00"))
        assertTrue(PhoneAction.isoLike("2026-09-05"))
        assertTrue(PhoneAction.isoLike("2026-09-05T14:00:00-05:00"))
        assertTrue(PhoneAction.isoLike("2026-09-05 14:00"))
    }

    @Test
    fun `a duration too big for a timer is not a duration`() {
        // 1200000h is 4,320,000,000 s. Int arithmetic wrapped that to +25,032,704 — positive,
        // so the marker parsed, the tile read "Timer 6953 h 31 min 44 s", and the clock intent
        // was handed a 289-day timer.
        assertNull(PhoneAction.timerSeconds("1200000h"))
        assertNull(PhoneAction.parse("timer|1200000h"))
        assertNull(PhoneAction.timerSeconds("99999999999999999999h"))
        assertNull(PhoneAction.parse("timer|99999999999999999999m"))
        // Everything a person would actually set still reads.
        assertEquals(3600, PhoneAction.timerSeconds("1h"))
        assertEquals(86_400, PhoneAction.timerSeconds("24h"))
        assertEquals("Timer 1 h", PhoneAction.parse("timer|1h")?.label)
    }

    @Test
    fun `markers in prose become hands and leave the prose clean`() {
        val text = "Roger's office is on Burnet. ⟦keryx:do|navigate|Salt Creek Tech, Burnet Rd, Austin⟧ Want me to ring him first? ⟦keryx:do|dial|+15125550100⟧"
        val k = MessageParser.extractKeryx(text)
        assertEquals(2, k.hands.size)
        assertEquals(PhoneAction.Kind.NAVIGATE, k.hands[0].kind)
        assertEquals(PhoneAction.Kind.DIAL, k.hands[1].kind)
        assertEquals("Roger's office is on Burnet.  Want me to ring him first?", k.text)
        assertTrue(k.actions.isEmpty())
        // Rendered: a Hands segment rides with the message.
        val segs = MessageParser.parse(text)
        assertTrue(segs.any { it is MessageParser.Segment.Hands && it.actions.size == 2 })
    }

    @Test
    fun `a marker that is not an action stays literal and a code span is a mention`() {
        val bad = "Try ⟦keryx:do|dance|now⟧ maybe"
        val k = MessageParser.extractKeryx(bad)
        assertTrue(k.hands.isEmpty())
        assertEquals(bad, k.text)
        val code = "The grammar is `⟦keryx:do|dial|+1555⟧` — literal in code."
        assertTrue(MessageParser.extractKeryx(code).hands.isEmpty())
        assertEquals(code, MessageParser.extractKeryx(code).text)
    }

    @Test
    fun `hands and ask live together and an unclosed marker at line end still counts`() {
        val text = "Two options.\n⟦keryx:do|alarm|06:45|flight\n⟦keryx:ask|Set it|Skip⟧"
        val k = MessageParser.extractKeryx(text)
        assertEquals(listOf("Set it", "Skip"), k.actions)
        assertEquals(1, k.hands.size)
        assertEquals("Alarm 06:45 · flight", k.hands.single().label)
        assertEquals("Two options.", k.text)
        // Repeats collapse; the cap holds.
        val many = (1..6).joinToString(" ") { "⟦keryx:do|timer|${it}m⟧" } + " ⟦keryx:do|timer|1m⟧"
        assertEquals(PhoneAction.MAX_PER_MESSAGE, MessageParser.phoneActions(many).size)
    }
}
