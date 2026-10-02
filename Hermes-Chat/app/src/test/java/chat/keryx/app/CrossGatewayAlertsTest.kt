package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.notify.KeryxNotifications
import chat.keryx.app.notify.MissionAlertsWorker
import chat.keryx.core.model.Fleet
import chat.keryx.core.model.GatewayEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.15: mission alerts cross gateways. The background watcher used to build one unpinned
 * settings view, which reads only the ACTIVE gateway, so a mission ending on any other gateway
 * on the fleet never rang until you switched to it. These pin the policy that replaced it.
 */
class CrossGatewayAlertsTest {

    private val spark = GatewayEntry("gw_spark", "Spark", "https://spark.example:9119")
    private val ascent = GatewayEntry("gw_ascent", "Ascent", "https://ascent.example:9119")
    private val laptop = GatewayEntry("gw_laptop", "Laptop", "https://laptop.example:9119")
    private val fleet = Fleet(listOf(spark, ascent, laptop), primaryId = spark.id, activeId = ascent.id)

    @Test
    fun `the watcher checks every gateway on the fleet, active first`() {
        val targets = MissionAlertsWorker.alertTargets("direct", fleet)
        assertEquals(listOf("gw_ascent", "gw_spark", "gw_laptop"), targets)
    }

    @Test
    fun `a non-active gateway is a target - the bug this release fixes`() {
        val targets = MissionAlertsWorker.alertTargets("direct", fleet)
        assertTrue("spark is not active but must still be watched", "gw_spark" in targets)
    }

    @Test
    fun `matrix and a fleetless direct door keep the single unscoped check`() {
        assertEquals(listOf(""), MissionAlertsWorker.alertTargets("matrix", fleet))
        assertEquals(listOf(""), MissionAlertsWorker.alertTargets("direct", Fleet()))
    }

    @Test
    fun `a stale active id never drops or duplicates a target`() {
        val stale = fleet.copy(activeId = "gone")
        assertEquals(listOf("gw_spark", "gw_ascent", "gw_laptop"), MissionAlertsWorker.alertTargets("direct", stale))
    }

    @Test
    fun `the pulse and the worker agree on the active scope`() {
        assertEquals("gw_ascent", MissionAlertsWorker.alertScope("direct", fleet))
        assertEquals("", MissionAlertsWorker.alertScope("matrix", fleet))
    }

    @Test
    fun `a tap switches only to another gateway that is on the fleet`() {
        assertTrue(MissionAlertsWorker.tapNeedsSwitch("gw_spark", transportIsDirect = true, fleet = fleet))
        assertFalse(MissionAlertsWorker.tapNeedsSwitch("gw_ascent", transportIsDirect = true, fleet = fleet))
        assertFalse(MissionAlertsWorker.tapNeedsSwitch("removed", transportIsDirect = true, fleet = fleet))
        assertFalse(MissionAlertsWorker.tapNeedsSwitch(null, transportIsDirect = true, fleet = fleet))
        assertFalse(MissionAlertsWorker.tapNeedsSwitch("", transportIsDirect = true, fleet = fleet))
        assertFalse(MissionAlertsWorker.tapNeedsSwitch("gw_spark", transportIsDirect = false, fleet = fleet))
    }

    @Test
    fun `the same task id on two gateways gets two shade slots`() {
        val a = KeryxNotifications.missionKey("gw_spark", "t_1")
        val b = KeryxNotifications.missionKey("gw_ascent", "t_1")
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `an unscoped alert keeps its pre-2_15 key`() {
        assertEquals("mission:t_1", KeryxNotifications.missionKey("", "t_1"))
    }

    private fun event(id: Long, at: Long) =
        HermesStreamClient.KanbanEvent(id = id, taskId = "t_$id", kind = "completed", createdAt = at)

    @Test
    fun `a gateway resuming from a frozen cursor does not ring last month`() {
        val now = 1_790_000_000L
        val events = listOf(event(1, now - 30 * 86_400), event(2, now - 3_600), event(3, 0L))
        val rung = MissionAlertsWorker.alertsToRing(events, boardOnScreen = false, nowSec = now)
        assertEquals(listOf(2L, 3L), rung.map { it.id })
    }

    @Test
    fun `a millisecond stamp is read as the same instant`() {
        val now = 1_790_000_000L
        assertFalse(MissionAlertsWorker.isStale(event(1, (now - 60) * 1000), now))
        assertTrue(MissionAlertsWorker.isStale(event(2, (now - 2 * 86_400) * 1000), now))
    }
}
