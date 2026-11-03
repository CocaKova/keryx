package chat.keryx.app

import chat.keryx.app.presentation.ui.nav.KeryxDest
import chat.keryx.app.presentation.ui.nav.KeryxNavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 continuity: a place that hands you to the chat leaves a way back, and only one. */
class KeryxNavStateTest {
    private fun nav(vararg places: KeryxDest) = KeryxNavState(places.toList())

    @Test fun stepAsideLowersTheStackAndKeepsItAsTheTrail() {
        val nav = nav(KeryxDest.Gateway, KeryxDest.Runs)
        nav.stepAside()
        assertTrue(nav.atFloor)
        assertEquals(KeryxDest.Runs, nav.returnTarget)
    }

    @Test fun returnBackRaisesTheWholeTrailInOrder() {
        val nav = nav(KeryxDest.Gateway, KeryxDest.Runs)
        nav.stepAside()
        nav.returnBack()
        assertEquals(KeryxDest.Runs, nav.top)
        nav.back()
        assertEquals(KeryxDest.Gateway, nav.top)
        nav.back()
        assertTrue(nav.atFloor)
        assertNull("the trail is spent once walked", nav.returnTarget)
    }

    @Test fun noWayBackIsOfferedWhileAPlaceIsOpen() {
        val nav = nav(KeryxDest.Bots)
        nav.stepAside()
        nav.open(KeryxDest.Settings)
        assertNull(nav.returnTarget)
        nav.back()
        assertEquals("closing the unrelated place shows the way back again", KeryxDest.Bots, nav.returnTarget)
    }

    @Test fun choosingAnotherChatForgetsTheTrail() {
        val nav = nav(KeryxDest.Missions)
        nav.stepAside()
        nav.forgetTrail()
        assertNull(nav.returnTarget)
    }

    @Test fun openingATrailPlaceByItsDoorIsTheWayBack() {
        val nav = nav(KeryxDest.Gateway, KeryxDest.Runs)
        nav.stepAside()
        nav.open(KeryxDest.Gateway)
        assertEquals(KeryxDest.Gateway, nav.top)
        assertNull(nav.returnTarget)
        nav.back()
        assertEquals(KeryxDest.Runs, nav.top)
    }

    @Test fun homeKeepsTheTrail() {
        val nav = nav(KeryxDest.Bots)
        nav.stepAside()
        nav.open(KeryxDest.Archive)
        nav.home()
        assertEquals(KeryxDest.Bots, nav.returnTarget)
    }

    @Test fun stepAsideAtTheFloorChangesNothing() {
        val nav = nav()
        nav.stepAside()
        assertNull(nav.returnTarget)
    }
}
