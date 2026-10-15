package chat.keryx.core

import chat.keryx.core.model.KeryxLink
import chat.keryx.core.pair.PairingLink
import chat.keryx.core.pair.PairingLink.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pairing codes from `hermes keryx pair` (3.0): what the scanner accepts, and everything a
 * forged or damaged QR could try that it refuses.
 */
class PairingLinkTest {
    private val code = "Qm9vdHN0cmFwLWNvZGUtMTIz"

    // Exactly what keryx-stream's build_payload writes (quote(v, safe=":/")).
    private fun payload(
        gw: String = "https://box.tail1.ts.net:9119",
        link: String = "http://box.tail1.ts.net:8646",
        code: String = this.code,
        name: String = "box",
        v: String = "1",
    ) = "keryx://pair?v=$v&gw=$gw&link=$link&code=$code&name=$name"

    private fun ok(raw: String) = assertIs<Parsed.Ok>(PairingLink.parse(raw)).pairing
    private fun rejected(raw: String?) = assertIs<Parsed.Rejected>(PairingLink.parse(raw)).why

    @Test
    fun `the plugin's payload parses`() {
        val p = ok(payload(name = "spark%20one"))
        assertEquals("https://box.tail1.ts.net:9119", p.gateway)
        assertEquals("http://box.tail1.ts.net:8646", p.link)
        assertEquals(code, p.code)
        assertEquals("spark one", p.name)
        assertEquals("box.tail1.ts.net", p.host)
        assertFalse(p.unencrypted, "plain http on a tailnet stays on the tailnet")
    }

    @Test
    fun `a missing name falls back to the host's first label`() {
        assertEquals("box", ok(payload(name = "")).name)
    }

    @Test
    fun `a Link on another machine is refused`() {
        val why = rejected(payload(link = "http://evil.example:8646"))
        assertTrue("different machine" in why)
        // Same name, different port, is fine; case doesn't matter.
        ok(payload(gw = "https://BOX.tail1.ts.net:9119", link = "http://box.tail1.ts.net:9000"))
    }

    @Test
    fun `addresses must be plain http(s) with nothing hidden in them`() {
        for (bad in listOf(
            "ftp://box:9119", "javascript:alert(1)", "https://user:pw@box.tail1.ts.net:9119",
            "https://box.tail1.ts.net:9119%3Fx%3D1", "https://box.tail1.ts.net:9119%23frag",
            "https://box.tail1.ts.net:99999", "https://box.tail1.ts.net:", "https://bo%20x:9119",
            "https://box.tail1.ts.net\\@evil:9119", "https://", "https://[]:9119",
        )) {
            assertEquals(PairingLink.DAMAGED, rejected(payload(gw = bad)), bad)
        }
        assertEquals("[::1]", ok(payload(gw = "http://[::1]:9119", link = "http://[::1]:8646")).host)
    }

    @Test
    fun `codes, versions and fields the plugin never writes are refused`() {
        assertEquals(PairingLink.DAMAGED, rejected(payload(code = "short")))
        assertEquals(PairingLink.DAMAGED, rejected(payload(code = "has spaces in it 12345")))
        assertTrue("newer keryx-stream" in rejected(payload(v = "2")))
        assertEquals(PairingLink.DAMAGED, rejected(payload() + "&token=master"))
        assertEquals(PairingLink.DAMAGED, rejected(payload() + "&code=$code"))
        assertEquals(PairingLink.DAMAGED, rejected(payload(name = "%zz")))
        assertEquals(PairingLink.DAMAGED, rejected(payload(name = "%+1")))
        assertEquals(PairingLink.DAMAGED, rejected(payload(name = "%4")))
    }

    @Test
    fun `anything that isn't a pairing link says so`() {
        for (raw in listOf(null, "", "https://example.com", "WIFI:S:home;T:WPA;P:secret;;", "keryx://session/abc")) {
            assertEquals(PairingLink.NOT_PAIRING, rejected(raw))
        }
        assertEquals(PairingLink.NOT_PAIRING, rejected(payload() + "&name=" + "x".repeat(2100)))
    }

    @Test
    fun `names lose control and bidi characters`() {
        assertEquals("[31mbox[0m", ok(payload(name = "%1B%5B31mbox%1B%5B0m%E2%80%AE")).name)
        assertEquals(64, ok(payload(name = "n".repeat(100))).name.length)
    }

    @Test
    fun `plain http to a public host is flagged, private networks are not`() {
        assertTrue(ok(payload(gw = "http://203.0.113.9:9119", link = "http://203.0.113.9:8646")).unencrypted)
        assertFalse(ok(payload(gw = "https://203.0.113.9:9119", link = "https://203.0.113.9:8646")).unencrypted)
        for (host in listOf("10.0.0.5", "192.168.50.2", "172.20.1.1", "100.100.1.1", "127.0.0.1", "nas.local", "localhost", "[fd00::1]")) {
            assertTrue(PairingLink.isPrivateHost(host), host)
        }
        for (host in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "example.com", "[2001:db8::1]")) {
            assertFalse(PairingLink.isPrivateHost(host), host)
        }
    }

    @Test
    fun `a pairing link opened from outside the scanner opens nothing`() {
        // keryx:// is the app's exported deep-link scheme; pairing must never ride it.
        assertNull(KeryxLink.parse(payload()))
        assertTrue(PairingLink.looksLikePairing(payload()))
        assertFalse(PairingLink.looksLikePairing("keryx://session/abc"))
    }
}
