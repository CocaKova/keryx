package chat.keryx.app.canary

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import chat.keryx.app.MainActivity
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import java.io.File
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * The accessibility pass (3.0): Android's own checker (ATF) over the screens a stranger meets
 * first, in the demo so no gateway is needed. It reports, it does not gate: every screen's
 * findings land in files/a11y/report.txt (and logcat), with the semantics tree beside them, so
 * a finding can be traced to its node. Run with `-e class chat.keryx.app.canary.A11yCanaryTest`.
 *
 * The test borrows the debug app's settings: it opens the demo door and puts back whatever
 * door the emulator had when it is done. Set `adb shell settings put system font_scale 2.0`
 * first for the 200% pass (and back to 1.0 after).
 *
 * Expected and accepted (3.0 pass, 10-19): the KERYX wordmark's contrast (a logotype, which
 * WCAG 1.4.3 exempts), and the menu badge's digit, where the checker samples the badge's own
 * fill as the text colour (the digit is ink on amber, ~5:1).
 */
class A11yCanaryTest {

    companion object {
        private const val PREFS = "hermes_settings"
        private var savedMode: String? = null
        private var savedWelcome = false

        @JvmStatic @BeforeClass fun openTheDemo() {
            val prefs = InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            savedMode = prefs.getString("transport_mode", null)
            savedWelcome = prefs.getBoolean("welcome_seen", false)
            prefs.edit().putString("transport_mode", "demo").putBoolean("welcome_seen", true).commit()
        }

        @JvmStatic @AfterClass fun putTheDoorBack() {
            val prefs = InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (savedMode == null) remove("transport_mode") else putString("transport_mode", savedMode)
                putBoolean("welcome_seen", savedWelcome)
            }.commit()
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val report = StringBuilder()

    /** Every finding at WARNING or worse, per screen; the checker never throws mid-walk. */
    private fun check(screen: String) {
        compose.waitForIdle()
        val validator = AccessibilityValidator()
            .setRunChecksFromRootView(true)
            .setThrowExceptionFor(AccessibilityCheckResultType.WARNING)
        compose.enableAccessibilityChecks(validator)
        report.append("\n===== ").append(screen).append(" =====\n")
        runCatching {
            val png = File(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "a11y").apply { mkdirs() },
                screen.replace(Regex("[^a-z0-9]+"), "-") + ".png")
            png.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            compose.onRoot().tryPerformAccessibilityChecks()
            report.append("clean\n")
        } catch (e: Throwable) {
            // ATF's exception lists every finding, one per line, with the node it was found on.
            report.append(e.javaClass.simpleName).append(":\n").append(e.message).append('\n')
        } finally {
            compose.disableAccessibilityChecks()
        }
        report.append("--- tree ---\n").append(runCatching { compose.onRoot(useUnmergedTree = false).printToString(maxDepth = 40) }.getOrElse { it.toString() }).append('\n')
        write()
    }

    private fun tryClick(node: () -> SemanticsNodeInteraction, what: String): Boolean =
        runCatching { node().performClick(); compose.waitForIdle(); Thread.sleep(600); true }
            .getOrElse { report.append("(could not open $what: ${it.javaClass.simpleName})\n"); false }

    @Test fun strangersFirstScreens() {
        Thread.sleep(2500)
        // A phone with no door yet opens on Connect, the first screen a stranger meets.
        val connect = runCatching { compose.onNode(hasText("Look around first", substring = true)).assertExists() }.isSuccess
        if (connect) {
            check("connect")
            tryClick({ compose.onNode(hasText("Look around first", substring = true)) }, "the demo")
            Thread.sleep(3000)  // the demo opens its first room and plays its first reply
        }
        for (ground in listOf("light", "dark")) {
            check("chat $ground")
            if (openDrawer()) {
                check("drawer $ground")
                if (tryClick({ compose.onNode(hasContentDescription("Settings")) }, "Settings")) {
                    check("settings $ground")
                    androidx.test.espresso.Espresso.pressBack(); Thread.sleep(800)
                }
                if (ground == "light" && openDrawer()) {
                    // The drawer's theme button: light → dark for the second pass.
                    tryClick({ compose.onNode(hasContentDescription("Switch to dark")) }, "dark")
                }
                closeDrawer()
            }
            runCatching {
                // Whichever demo room is open: its first words, the user's or the agent's.
                val said = listOf("photo importer takes forever", "Morning brief:", "Good morning", "Groceries", "backup")
                    .firstNotNullOf { s -> compose.onAllNodes(hasText(s, substring = true)).fetchSemanticsNodes().firstOrNull()?.let { s } }
                compose.onAllNodes(hasText(said, substring = true))[0]
                    .performTouchInput { longClick() }
                Thread.sleep(800)
                // Only a menu that opened is checked and closed: a Back with nothing open leaves
                // the app (at 200% text the long-press can land between bubbles).
                if (compose.onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty()) {
                    check("long-press menu $ground")
                    androidx.test.espresso.Espresso.pressBack(); Thread.sleep(600)
                } else report.append("(the long-press opened no menu)\n")
            }.onFailure { report.append("(could not open the long-press menu: ${it.javaClass.simpleName})\n") }
        }
        // Back to following the phone: dark → light → system.
        if (openDrawer()) {
            tryClick({ compose.onNode(hasContentDescription("Switch to light")) }, "light")
            tryClick({ compose.onNode(hasContentDescription("Follow system")) }, "system")
            closeDrawer()
        }

        write()
    }

    private fun drawerIsOpen() =
        runCatching { compose.onNode(hasContentDescription("Close navigation menu")).assertExists() }.isSuccess

    private fun openDrawer(): Boolean =
        drawerIsOpen() || tryClick({ compose.onNode(hasContentDescription("Menu", substring = true)) }, "the drawer")

    private fun closeDrawer() {
        if (drawerIsOpen()) tryClick({ compose.onNode(hasContentDescription("Close navigation menu")) }, "close the drawer")
    }

    private fun write() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "a11y").apply { mkdirs() }
        File(dir, "report.txt").writeText(report.toString())
        report.lineSequence().filter { it.startsWith("[") || it.startsWith("=====") }.forEach { android.util.Log.i("KeryxA11y", it) }
    }
}
