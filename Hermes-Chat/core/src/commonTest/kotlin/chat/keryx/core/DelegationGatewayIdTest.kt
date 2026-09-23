package chat.keryx.core

import chat.keryx.core.model.Delegation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Steer, stop and tail address a helper by the gateway's own id; our fallback key is not one. */
class DelegationGatewayIdTest {

    @Test
    fun gateway_ids_are_addressable_and_fallback_keys_are_not() {
        assertTrue(Delegation(key = "sa-0-1a2b3c4d").hasGatewayId)
        assertFalse(Delegation(key = "${Delegation.FALLBACK_PREFIX}0").hasGatewayId)
        assertFalse(Delegation(key = "").hasGatewayId)
    }
}
