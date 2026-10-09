package chat.keryx.core

import chat.keryx.core.model.FriendlyError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FriendlyErrorTest {
    @Test fun httpCodesAndNetworkFailuresReadAsAdvice() {
        assertTrue(FriendlyError.of("HTTP 404").contains("keryx-stream"))
        assertTrue(FriendlyError.of("HTTP 401 for /api/x").contains("Sign in again"))
        assertTrue(FriendlyError.of("HTTP 502").contains("HTTP 502"))
        assertTrue(FriendlyError.of("Unable to resolve host \"spark\": No address associated with hostname").startsWith("Can't find"))
        assertTrue(FriendlyError.of("gateway socket send failed").startsWith("Not connected"))
        assertTrue(FriendlyError.of("timeout").contains("too long"))
    }

    @Test fun unknownTextPassesThrough() {
        assertEquals("Failed to enumerate skills", FriendlyError.of("Failed to enumerate skills"))
        assertTrue(FriendlyError.of(null).isNotBlank())
    }
}
