package chat.keryx.app

import chat.keryx.app.presentation.ui.components.SettingsCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings catalog (2.10) is a list written by hand, and the screen's anchors are strings
 * written by hand; the two drift the moment someone adds a row to one and not the other. A
 * search hit that opens a page and lights nothing is a lie, and a row nobody can search for is
 * a row that might as well not exist. This test reads the screen's source back — the same move
 * KeryxHapticsTest makes — and holds the two lists to each other.
 */
class SettingsCatalogTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/chat/keryx/app/presentation/ui/components/SettingsDialog.kt"),
            File("app/src/main/java/chat/keryx/app/presentation/ui/components/SettingsDialog.kt"),
        )
        candidates.first { it.exists() }.readText()
    }

    private val anchorsInScreen: Set<String> by lazy {
        val a = Regex("""anchor = "([a-z0-9.]+)"""").findAll(source).map { it.groupValues[1] }
        val b = Regex("""SettingsAnchor\("([a-z0-9.]+)"\)""").findAll(source).map { it.groupValues[1] }
        (a + b).toSet()
    }

    @Test
    fun `ids are unique and dotted`() {
        val ids = SettingsCatalog.entries.map { it.id }
        assertEquals("Duplicate ids: $ids", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("'$it' should be section.row", Regex("[a-z]+\\.[a-z0-9]+").matches(it)) }
    }

    @Test
    fun `every entry lives somewhere on at least one door`() {
        SettingsCatalog.entries.forEach {
            assertTrue("${it.id} has no section on either door", it.sectionDirect != null || it.sectionMatrix != null)
        }
    }

    @Test
    fun `every catalog id is an anchor on the screen`() {
        val missing = SettingsCatalog.entries.map { it.id }.filterNot { it in anchorsInScreen }
        assertTrue("Catalog ids with no anchor in SettingsDialog.kt: $missing", missing.isEmpty())
    }

    @Test
    fun `every anchor on the screen is in the catalog`() {
        val ids = SettingsCatalog.entries.map { it.id }.toSet()
        val orphans = anchorsInScreen.filterNot { it in ids }
        assertTrue("Anchors nobody can search for: $orphans", orphans.isEmpty())
    }

    @Test
    fun `search matches titles and keywords, and only rows this door has`() {
        assertTrue(SettingsCatalog.search("vibrate", direct = true).any { it.id == "appearance.haptics" })
        assertTrue(SettingsCatalog.search("e2ee", direct = true).isEmpty())
        assertTrue(SettingsCatalog.search("e2ee", direct = false).any { it.id == "privacy.e2ee" })
        assertTrue(SettingsCatalog.search("a", direct = true).isEmpty())
    }
}
