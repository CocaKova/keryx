package chat.keryx.app

import chat.keryx.app.presentation.ui.components.SettingsCatalog
import chat.keryx.app.presentation.ui.components.SettingsRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings catalog (2.10) is an enum and the screen anchors rows by `SettingsRow.NAME`, so
 * a row cannot be anchored without being searchable — the compiler holds that side. This test
 * holds the other: an entry nobody anchored is a search hit that opens a page and lights
 * nothing. It reads the screen's source back, the same move KeryxHapticsTest makes.
 */
class SettingsCatalogTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/java/chat/keryx/app/presentation/ui/components/SettingsDialog.kt"),
            File("app/src/main/java/chat/keryx/app/presentation/ui/components/SettingsDialog.kt"),
        )
        candidates.first { it.exists() }.readText()
    }

    /** Every `SettingsRow.NAME` the screen names — the compiler already proved each one exists. */
    private val anchorsInScreen: Set<String> by lazy {
        Regex("""SettingsRow\.([A-Z0-9_]+)""").findAll(source).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `names read as SECTION_ROW`() {
        SettingsRow.entries.forEach {
            assertTrue("'${it.name}' should be SECTION_ROW", Regex("[A-Z]+_[A-Z0-9_]+").matches(it.name))
        }
    }

    @Test
    fun `every entry lives somewhere on at least one door`() {
        SettingsCatalog.entries.forEach {
            assertTrue("${it.id} has no section on either door", it.sectionDirect != null || it.sectionMatrix != null)
        }
    }

    @Test
    fun `every catalog id is an anchor on the screen`() {
        val missing = SettingsCatalog.entries.map { it.name }.filterNot { it in anchorsInScreen }
        assertTrue("Catalog ids with no anchor in SettingsDialog.kt: $missing", missing.isEmpty())
    }

    @Test
    fun `search matches titles and keywords, and only rows this door has`() {
        assertTrue(SettingsCatalog.search("vibrate", direct = true).any { it == SettingsRow.APPEARANCE_HAPTICS })
        assertTrue(SettingsCatalog.search("e2ee", direct = true).isEmpty())
        assertTrue(SettingsCatalog.search("e2ee", direct = false).any { it == SettingsRow.PRIVACY_E2EE })
        assertTrue(SettingsCatalog.search("a", direct = true).isEmpty())
    }
}
