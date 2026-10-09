package chat.keryx.app

import chat.keryx.app.data.repository.HubSnapshots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSnapshotsTest {
    @Test fun keepsThePanelsReadBack_plainAndGatewayScoped() {
        assertTrue(HubSnapshots.keeps("/api/sessions"))
        assertTrue(HubSnapshots.keeps("/api/sessions.gw-1"))
        assertTrue(HubSnapshots.keeps("keryx://console/runs.gw-1"))
    }

    @Test fun dropsPerIdPaths() {
        assertFalse(HubSnapshots.keeps("/api/sessions/20261009_101010_abc/messages"))
        assertFalse(HubSnapshots.keeps("/api/sessions/20261009_101010_abc/messages.gw-1"))
        assertFalse(HubSnapshots.keeps("/keryx/kanban/task/t_42"))
        assertFalse(HubSnapshots.keeps("/keryx/skills/review"))
    }
}
