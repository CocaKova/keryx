package chat.keryx.app

import chat.keryx.app.presentation.tour.KeryxHints
import chat.keryx.app.presentation.tour.KeryxTour
import chat.keryx.app.presentation.ui.nav.KeryxDest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * First-visit hints (3.0): each hinted space says its piece once, on a header that really wears
 * the hint, and the hints stay the app's own (the agent's tour can't name them).
 */
class KeryxHintsTest {

    private val spaces = listOf(
        KeryxDest.Missions, KeryxDest.Runs, KeryxDest.Bots, KeryxDest.Gateway, KeryxDest.TapIn,
        KeryxDest.Artifact(path = "/home/you/page.html", name = "page.html"),
    )

    @Test
    fun `each hinted space has its own hint, said until it has been seen`() {
        val ids = spaces.map { KeryxHints.idFor(it) ?: error("no hint for ${it.route}") }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(KeryxHints.ALL, ids.toSet())
        for (dest in spaces) {
            val step = KeryxHints.due(dest, emptySet()) ?: error("no hint due for ${dest.route}")
            assertEquals(KeryxHints.idFor(dest), step.target)
            assertFalse(step.title.isNullOrBlank())
            assertFalse(step.text.isNullOrBlank())
            assertNull(KeryxHints.due(dest, setOf(step.target!!)))
        }
    }

    @Test
    fun `places without a hint, and no place at all, say nothing`() {
        assertNull(KeryxHints.due(KeryxDest.Settings, emptySet()))
        assertNull(KeryxHints.due(KeryxDest.Archive, emptySet()))
        assertNull(KeryxHints.due(null, emptySet()))
    }

    @Test
    fun `the agent's tour can't point at a hint`() {
        val catalog = KeryxTour.catalog(KeryxTour.Context(direct = true, link = true, demo = true)).map { it.id }
        assertTrue(catalog.none { it in KeryxHints.ALL })
    }

    @Test
    fun `every hint is worn by a space's header`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val source = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val worn = Regex("""hint = (?:if \([^)]*\) )?chat\.keryx\.app\.presentation\.tour\.KeryxHints\.([A-Z_]+)""")
            .findAll(source).map { it.groupValues[1] }.toSet()
        val constants = KeryxHints::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.isAccessible = true; (it.get(null) as String) to it.name }
        for (id in KeryxHints.ALL) assertTrue("no space wears $id", constants[id] in worn)
    }
}
