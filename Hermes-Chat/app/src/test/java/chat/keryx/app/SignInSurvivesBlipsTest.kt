package chat.keryx.app

import chat.keryx.app.transport.direct.DirectAuth
import chat.keryx.app.transport.direct.GatewayRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gated sign-in ends only when the gateway says the credential is dead, never on a blip
 * (device-caught 10-20: an expired WS ticket and a refresh during a network drop each sent the
 * phone back to the sign-in screen while its sign-in was fine).
 */
class SignInSurvivesBlipsTest {

    @Test
    fun `one refused upgrade is the ticket, a second in a row is the credential`() {
        // First refusal: no code, so neither the loop nor DirectTransport reads it as terminal.
        assertNull(GatewayRpc.upgradeRejectCode(401, priorRejects = 0))
        assertNull(GatewayRpc.upgradeRejectCode(403, priorRejects = 0))
        // Again before any socket reached Ready: the credential itself, terminal as before.
        assertEquals(401, GatewayRpc.upgradeRejectCode(401, priorRejects = 1))
        assertEquals(403, GatewayRpc.upgradeRejectCode(403, priorRejects = 1))
        assertTrue(GatewayRpc.upgradeRejectCode(401, 1) in GatewayRpc.TERMINAL_CREDENTIAL_CODES)
    }

    @Test
    fun `other upgrade failures report their code unchanged`() {
        assertNull(GatewayRpc.upgradeRejectCode(null, 0))          // no response: network
        assertEquals(502, GatewayRpc.upgradeRejectCode(502, 0))
        assertEquals(404, GatewayRpc.upgradeRejectCode(404, 3))
    }

    @Test
    fun `only the gateway refusing the refresh token ends the sign-in`() {
        assertTrue(DirectAuth.refreshRefused("HTTP 401"))          // expired or invalid
        assertTrue(DirectAuth.refreshRefused("HTTP 400"))          // missing
        assertTrue(DirectAuth.refreshRefused("HTTP 403"))
        assertFalse(DirectAuth.refreshRefused("HTTP 503"))         // auth provider unreachable
        assertFalse(DirectAuth.refreshRefused("HTTP 502"))         // tailscale serve, gateway down
        assertFalse(DirectAuth.refreshRefused("UnknownHostException"))
        assertFalse(DirectAuth.refreshRefused("SocketTimeoutException"))
        assertFalse(DirectAuth.refreshRefused(null))
    }
}
