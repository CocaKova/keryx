package chat.keryx.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import chat.keryx.app.presentation.tour.KeryxTour
import chat.keryx.app.presentation.tour.placeTourCard
import chat.keryx.core.tour.TourPreset
import chat.keryx.core.tour.TourSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's own tour (3.0): what it may point at on each door, where its card sits, and a check
 * that every target it can name is worn by a real control (a target nobody wears would make a
 * stop wait out its timeout and talk mid-screen).
 */
class KeryxTourTest {
    private val direct = KeryxTour.Context(direct = true, link = true)
    private val matrix = KeryxTour.Context(direct = false, link = false)

    private fun ids(ctx: KeryxTour.Context) = KeryxTour.catalog(ctx).map { it.id }.toSet()

    @Test
    fun `the direct door with Link offers every stop, ending on the gateway with Add a gateway`() {
        val full = KeryxTour.builtIn(TourPreset.FULL, ids(direct), direct)
        assertEquals(12, full.size)
        assertEquals(KeryxTour.MACHINE, full.last().target)
        assertEquals(KeryxTour.CTA_ADD_GATEWAY, full.last().cta?.id)
        assertEquals(
            listOf(
                KeryxTour.FLIGHT_PLAN, KeryxTour.APPROVAL,
                KeryxTour.COMPOSER, KeryxTour.MODEL, KeryxTour.NEW_SESSION, KeryxTour.DRAWER_BUTTON,
            ),
            KeryxTour.builtIn(TourPreset.QUICK, ids(direct), direct).map { it.target },
        )
    }

    @Test
    fun `Matrix without Link never points at what it doesn't draw`() {
        val offered = ids(matrix)
        // The model pill is left to the screen (the host keeps only what is worn): its footer
        // shows with Link's caps OR a context reading, which no door rule can know.
        for (gone in listOf(KeryxTour.NEW_SESSION, KeryxTour.LENSES, KeryxTour.RUNS, KeryxTour.BOTS, KeryxTour.FLIGHT_PLAN, KeryxTour.APPROVAL)) {
            assertFalse(gone, gone in offered)
        }
        val full = KeryxTour.builtIn(TourPreset.FULL, offered, matrix)
        assertTrue(full.all { it.target in offered })
        // No fleet on Matrix: the last stop just ends.
        assertNull(full.last().cta)
    }

    @Test
    fun `the demo's tour opens by saying what it is and ends on connecting a real gateway`() {
        val demo = KeryxTour.Context(direct = true, link = false, demo = true)
        val full = KeryxTour.builtIn(TourPreset.FULL, ids(demo), demo)
        assertNull("the welcome is narration, mid-screen", full.first().target)
        assertEquals(KeryxTour.MACHINE, full.last().target)
        assertEquals(KeryxTour.CTA_CONNECT, full.last().cta?.id)
        assertFalse(KeryxTour.RUNS in full.map { it.target })
        // Straight after the welcome: the agent at work, which is what the demo opens on.
        assertEquals(listOf(KeryxTour.FLIGHT_PLAN, KeryxTour.APPROVAL), full.drop(1).take(2).map { it.target })
        assertTrue(full[2].text.orEmpty().contains("Try it"))
    }

    @Test
    fun `a real gateway's tour never tells you to try the demo's approval`() {
        val stop = KeryxTour.builtIn(TourPreset.QUICK, ids(direct), direct).first { it.target == KeryxTour.APPROVAL }
        assertFalse(stop.text.orEmpty().contains("Try it"))
    }

    @Test
    fun `every target the tour can name is worn by a control`() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.first { it.exists() }
        val source = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val worn = Regex("""tourTarget\(KeryxTour\.([A-Z_]+)\)""").findAll(source).map { it.groupValues[1] }.toSet()
        val named = KeryxTour.catalog(direct).map { it.id }
        val constants = KeryxTour::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.isAccessible = true; (it.get(null) as String) to it.name }
        for (id in named) assertTrue("no control wears $id", constants[id] in worn)
    }

    // A 1080 × 2400 phone, 16 px margins, a 600 × 300 card.
    private fun place(target: Rect?, side: TourSide? = null) =
        placeTourCard(target, 600, 300, 1080, 2400, side, margin = 16, gap = 14, insetTop = 100, insetBottom = 60)

    @Test
    fun `narration sits mid-screen`() {
        assertEquals(IntOffset(240, 1050), place(null))
    }

    @Test
    fun `a target low on the screen gets its card above, a high one below`() {
        val composer = Rect(16f, 2200f, 1064f, 2320f)
        assertEquals(IntOffset(240, 2200 - 14 - 300), place(composer))
        val menu = Rect(10f, 120f, 130f, 240f)
        // Centred on the button, then held off the left edge.
        assertEquals(IntOffset(16, 254), place(menu))
    }

    @Test
    fun `an asked-for side is honoured when it fits, and dropped when it doesn't`() {
        val door = Rect(700f, 1200f, 900f, 1300f)
        assertEquals(IntOffset(700 - 14 - 600, 1250 - 150), place(door, TourSide.LEFT))
        // No room to the right of a door near the edge: auto placement (below) instead, held
        // off the right edge.
        assertEquals(IntOffset(1080 - 16 - 600, 1300 + 14), place(door, TourSide.RIGHT))
    }
}
