package chat.keryx.app

import chat.keryx.app.presentation.ui.components.GATEWAY_SPOKES
import chat.keryx.app.presentation.ui.nav.KeryxDest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gateway's spoke registry and the routes that address the places (2.5, reshaped 2.10).
 *
 * Worth pinning because both are things a future panel gets wrong silently: a duplicated panel id
 * makes the shell's first-visit fetch skip a panel (it dedupes by id, so the second one renders
 * empty until you pull to refresh), and a renamed route strands a saved back stack or a pinned
 * intent on a destination that no longer answers to that name.
 */
class HubRegistryTest {

    private val panels = GATEWAY_SPOKES

    @Test
    fun `the spokes are the ones Jonny chose, in reading order`() {
        // Runs left the hub 2026-09-01 ("the crons are hard to get to") — it is the Runs DOOR
        // now (RunsSpace); Jobs stays because managing schedules is server administration.
        // 2.10 folded the Workshop back in as spokes: changing the machine first, then reading it.
        assertEquals(listOf("Controls", "Jobs", "Sessions", "Skills", "Tools"), GATEWAY_SPOKES.map { it.label })
    }

    @Test
    fun `spoke ids are unique and never the landing's`() {
        // The shell keys its fetched-set by id, and the landing owns "status".
        val ids = panels.map { it.id }
        assertEquals("Duplicate spoke ids: $ids", ids.size, ids.toSet().size)
        assertTrue("status" !in ids)
    }

    @Test
    fun `every panel is addressable and labelled`() {
        for (panel in panels) {
            assertTrue("A panel has a blank id", panel.id.isNotBlank())
            assertTrue("Panel ${panel.id} has a blank label", panel.label.isNotBlank())
            // Ids are saved state and would be deep links; keep them boring.
            assertTrue(
                "Panel id '${panel.id}' should be lowercase and symbol-free",
                panel.id.all { it.isLowerCase() || it.isDigit() || it == '-' },
            )
        }
    }

    @Test
    fun `only data that moves on its own re-polls`() {
        // Every ten seconds, forever, while you are looking at it — so this is a deliberate list,
        // not a default. Skills and tools change on operator action; they stay fetch-once.
        assertEquals(
            // Runs polls too — inside its own door now (RunsSpace runs the 10s loop itself);
            // the landing's health polls on its own, outside this registry.
            setOf("jobs", "sessions"),
            panels.filter { it.live }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `every destination resolves from its own route`() {
        for (dest in listOf(
            KeryxDest.Archive, KeryxDest.Missions, KeryxDest.Gateway,
            KeryxDest.Runs, KeryxDest.Bots, KeryxDest.Settings,
        )) {
            assertEquals(dest, KeryxDest.fromRoute(dest.route))
        }
    }

    @Test
    fun `an artifact route carries its arguments through a save and restore`() {
        // The viewer (2.13) is the one place with arguments; a restored stack must rebuild the
        // same viewer, spaces and ampersands in the path included.
        val byPath = KeryxDest.Artifact(path = "/home/sy/out/mock v1 & co.html", name = "mock v1 & co.html")
        assertEquals(byPath, KeryxDest.fromRoute(byPath.route))
        val byBytes = KeryxDest.Artifact(path = null, name = "page.html", roomId = "!room:x", eventId = "\$ev#media:0")
        assertEquals(byBytes, KeryxDest.fromRoute(byBytes.route))
        // A route with nothing to open is not a place.
        assertEquals(null, KeryxDest.fromRoute("artifact?path=&name=x&room=&event="))
        assertEquals(null, KeryxDest.fromRoute("artifact"))
    }

    @Test
    fun `the retired routes still land somewhere`() {
        // A back stack saved by 2.4 says "hub"; one saved by 2.9 says "workshop". Neither may
        // restore to nothing — both land on the Gateway, where their panels live now.
        assertEquals(KeryxDest.Gateway, KeryxDest.fromRoute("hub"))
        assertEquals(KeryxDest.Gateway, KeryxDest.fromRoute("workshop"))
    }

    @Test
    fun `an unknown route resolves to nothing rather than a default`() {
        // Restoring an unrecognised route as some arbitrary place would drop you somewhere you
        // never were; the nav state filters nulls out instead.
        assertEquals(null, KeryxDest.fromRoute("workshopp"))
        assertEquals(null, KeryxDest.fromRoute(""))
    }

    @Test
    fun `routes are distinct`() {
        val routes = listOf(
            KeryxDest.Archive, KeryxDest.Missions, KeryxDest.Gateway,
            KeryxDest.Runs, KeryxDest.Bots, KeryxDest.Settings,
        ).map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertNotNull(KeryxDest.fromRoute("gateway"))
    }
}
