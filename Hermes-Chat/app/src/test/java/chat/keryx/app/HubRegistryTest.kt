package chat.keryx.app

import chat.keryx.app.presentation.ui.components.GATEWAY_PANELS
import chat.keryx.app.presentation.ui.components.WORKSHOP_PANELS
import chat.keryx.app.presentation.ui.nav.KeryxDest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway spaces' registries and the routes that address them (2.5).
 *
 * Worth pinning because both are things a future panel gets wrong silently: a duplicated panel id
 * makes the shell's first-visit fetch skip a panel (it dedupes by id, so the second one renders
 * empty until you pull to refresh), and a renamed route strands a saved back stack or a pinned
 * intent on a destination that no longer answers to that name.
 */
class HubRegistryTest {

    private val panels = GATEWAY_PANELS + WORKSHOP_PANELS

    @Test
    fun `the split is the one Jonny chose`() {
        assertEquals(listOf("Status", "Controls", "Jobs"), GATEWAY_PANELS.map { it.label })
        assertEquals(listOf("Sessions", "Skills", "Tools"), WORKSHOP_PANELS.map { it.label })
    }

    @Test
    fun `panel ids are unique across both spaces`() {
        // Across both, not within each: the shell keys its fetched-set by id, and a panel moving
        // from one space to the other must not collide with something already there.
        val ids = panels.map { it.id }
        assertEquals("Duplicate panel ids: $ids", ids.size, ids.toSet().size)
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
            setOf("status", "jobs", "sessions"),
            panels.filter { it.live }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `every destination resolves from its own route`() {
        for (dest in listOf(
            KeryxDest.Archive, KeryxDest.Missions, KeryxDest.Gateway,
            KeryxDest.Workshop, KeryxDest.Settings,
        )) {
            assertEquals(dest, KeryxDest.fromRoute(dest.route))
        }
    }

    @Test
    fun `the retired hub route still lands somewhere`() {
        // A back stack saved by 2.4 says "hub". It must not restore to nothing.
        assertEquals(KeryxDest.Gateway, KeryxDest.fromRoute("hub"))
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
            KeryxDest.Workshop, KeryxDest.Settings,
        ).map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertNotNull(KeryxDest.fromRoute("gateway"))
    }
}
