package chat.keryx.core

import chat.keryx.core.model.Fleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The fleet's rules (2.11) — the Desktop's registry rules, held on the phone: unique names,
 * URL dedupe, Primary as the fallback and never the switch, the boot target, and a JSON shape
 * that survives a round trip and a hand edit.
 */
class FleetTest {
    private var seq = 0
    private fun id(): String = "id${++seq}"

    @Test
    fun `first gateway becomes primary and active`() {
        val (f, e) = Fleet().add("http://spark.lan:9119/", "Spark", ::id)
        assertEquals("http://spark.lan:9119", e.url)
        assertEquals(e.id, f.primaryId)
        assertEquals(e.id, f.activeId)
        assertEquals(false, f.hasChoice)
    }

    @Test
    fun `second gateway is neither primary nor active until asked`() {
        val (f1, a) = Fleet().add("http://spark.lan:9119", "Spark", ::id)
        val (f2, b) = f1.add("http://ascent.lan:9119", "Ascent", ::id)
        assertEquals(a.id, f2.primaryId)
        assertEquals(a.id, f2.activeId)
        assertTrue(f2.hasChoice)
        assertEquals(b.id, f2.setPrimary(b.id).primaryId)
        // Make primary does not switch the workspace (Desktop rule).
        assertEquals(a.id, f2.setPrimary(b.id).activeId)
        assertEquals(b.id, f2.setActive(b.id).activeId)
    }

    @Test
    fun `names are unique case-insensitively`() {
        val (f, _) = Fleet().add("http://a", "Homelab", ::id)
        assertFailsWith<Fleet.RejectedException> { f.add("http://b", "homelab", ::id) }
        val (f2, b) = f.add("http://b", "Work", ::id)
        assertFailsWith<Fleet.RejectedException> { f2.rename(b.id, "HOMELAB") }
        assertEquals("Work", f2.rename(b.id, "Work").byId(b.id)!!.name) // renaming to itself is fine
    }

    @Test
    fun `a known URL is the same row, not a second one`() {
        val (f, a) = Fleet().add("HTTP://Spark.LAN:9119/", "Spark", ::id)
        val (f2, again) = f.add("http://spark.lan:9119", "Something else", ::id)
        assertSame(f, f2)
        assertEquals(a.id, again.id)
        assertEquals("Spark", again.name)
    }

    @Test
    fun `blank name derives from the host and suffixes until unique`() {
        val (f, a) = Fleet().add("https://spark.lan:9119", "", ::id)
        assertEquals("spark.lan", a.name)
        val (f2, b) = f.add("https://spark.lan:9120", "", ::id)
        assertEquals("spark.lan 2", b.name)
        assertEquals(2, f2.size)
    }

    @Test
    fun `remove hands primary and active to the first remaining row`() {
        val (f1, a) = Fleet().add("http://a", "A", ::id)
        val (f2, b) = f1.add("http://b", "B", ::id)
        val (f3, c) = f2.add("http://c", "C", ::id)
        val without = f3.remove(a.id)
        assertEquals(b.id, without.primaryId)
        assertEquals(b.id, without.activeId)
        val onlyC = without.remove(b.id)
        assertEquals(c.id, onlyC.primaryId)
        assertTrue(onlyC.remove(c.id).isEmpty)
        assertEquals("", onlyC.remove(c.id).primaryId)
    }

    @Test
    fun `boot target honours the resume toggle and falls back`() {
        val (f1, a) = Fleet().add("http://a", "A", ::id)
        val (f2, b) = f1.add("http://b", "B", ::id)
        val onB = f2.setActive(b.id)
        assertEquals(b.id, onB.bootTarget()?.id)
        assertEquals(a.id, onB.copy(resumeLastGateway = false).bootTarget()?.id)
        // The last-used gateway vanished (a removal raced a relaunch): Primary.
        assertEquals(a.id, onB.copy(activeId = "gone").bootTarget()?.id)
        // Even Primary is gone: the first row.
        assertEquals(a.id, onB.copy(activeId = "gone", primaryId = "gone").bootTarget()?.id)
        assertNull(Fleet().bootTarget())
    }

    @Test
    fun `json round trip keeps everything`() {
        val (f1, _) = Fleet().add("http://a:1", "A", ::id)
        val (f2, b) = f1.add("http://b:2/prefix", "B", ::id)
        val f = f2.setActive(b.id).copy(resumeLastGateway = false)
        val back = Fleet.fromJson(f.toJson())
        assertEquals(f, back)
    }

    @Test
    fun `json with dangling pointers heals to the first row`() {
        val raw = """{"v":1,"primary":"nope","active":"nope","gateways":[{"id":"x","name":"X","url":"http://x"}]}"""
        val f = Fleet.fromJson(raw)
        assertEquals("x", f.primaryId)
        assertEquals("x", f.activeId)
        assertTrue(f.resumeLastGateway)
        assertNotNull(f.active)
        assertTrue(Fleet.fromJson("").isEmpty)
        assertTrue(Fleet.fromJson("not json").isEmpty)
    }

    @Test
    fun `url normalisation keeps a proxy path's case`() {
        assertEquals("http://host:9119/Hermes", Fleet.normalizeUrl("  HTTP://Host:9119/Hermes// "))
        assertEquals("", Fleet.normalizeUrl("   "))
        assertEquals("host.lan", Fleet.deriveName("https://Host.LAN:9119/x"))
        assertEquals("host.lan", Fleet.hostLabelOf("https://host.lan"))
    }
}

private fun Fleet.Companion.hostLabelOf(url: String) =
    chat.keryx.core.model.GatewayEntry("i", "n", normalizeUrl(url)).hostLabel
