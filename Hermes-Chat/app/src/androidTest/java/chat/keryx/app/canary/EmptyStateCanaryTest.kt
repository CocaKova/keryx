package chat.keryx.app.canary

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import chat.keryx.app.presentation.ui.components.ArchiveBookmarkIllustration
import chat.keryx.app.presentation.ui.components.ArchiveMediaIllustration
import chat.keryx.app.presentation.ui.components.ArchiveScrollIllustration
import chat.keryx.app.presentation.ui.components.BotsIllustration
import chat.keryx.app.presentation.ui.components.KeryxEmptyState
import chat.keryx.app.presentation.ui.components.MissionBoardIllustration
import chat.keryx.app.presentation.ui.components.ProjectsFolderIllustration
import chat.keryx.app.presentation.ui.components.RunsClockIllustration
import chat.keryx.app.theme.HermesChatTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every space's empty state (3.0), composed and drawn on a real device in both grounds. Like the
 * render canary it is a crash gate first: the drawings are hand-built paths, and a bad arc or
 * transform takes the draw pass down, not a pixel.
 *
 * Run with `-e shots 1` and each case also leaves `<name>-light.png` / `<name>-dark.png` in the
 * debug app's files/shots, for a look without a gateway that has nothing on it:
 * `adb exec-out run-as chat.keryx.app.debug cat files/shots/runs-dark.png > runs-dark.png`.
 */
@RunWith(Parameterized::class)
class EmptyStateCanaryTest(private val caseName: String, private val case: EmptyCase) {

    class EmptyCase(val content: @Composable () -> Unit) {
        override fun toString() = "EmptyCase"
    }

    companion object {
        private fun case(name: String, content: @Composable () -> Unit) = arrayOf<Any>(name, EmptyCase(content))

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            case("missions") {
                KeryxEmptyState(
                    "No missions on the board", "Give your agent a mission.",
                    illustration = { MissionBoardIllustration() }, actionLabel = "Give it a mission", onAction = {},
                )
            },
            case("runs") {
                KeryxEmptyState(
                    "Nothing scheduled yet", "Schedule a job here, or ask your agent to.",
                    illustration = { RunsClockIllustration() }, actionLabel = "Schedule a job", onAction = {},
                )
            },
            case("bots") {
                KeryxEmptyState(
                    "No bots yet", "Each bot is a profile on your gateway.",
                    illustration = { BotsIllustration() }, actionLabel = "Make the first bot", onAction = {},
                )
            },
            case("projects") {
                KeryxEmptyState(
                    "No projects yet", "A project is a workspace folder.",
                    illustration = { ProjectsFolderIllustration() }, actionLabel = "New project", onAction = {},
                )
            },
            case("archive-search") {
                KeryxEmptyState("Nothing remembered yet", "The index fills as sessions are swept.", illustration = { ArchiveScrollIllustration() })
            },
            case("archive-saved") {
                KeryxEmptyState("Nothing kept yet", "Long-press a message and tap the bookmark.", illustration = { ArchiveBookmarkIllustration() })
            },
            case("archive-media") {
                KeryxEmptyState("No photos or files yet", "Pictures and files gather here.", illustration = { ArchiveMediaIllustration() })
            },
        )
    }

    @get:Rule val compose = createComposeRule()

    @Test fun drawsInBothGrounds() {
        val dark = mutableStateOf(false)
        compose.setContent {
            HermesChatTheme(darkTheme = dark.value) {
                Surface { case.content() }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnUiThread { dark.value = isDark }
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage()
            if (InstrumentationRegistry.getArguments().getString("shots") == "1") {
                val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots").apply { mkdirs() }
                File(dir, "$caseName-${if (isDark) "dark" else "light"}.png").outputStream().use {
                    image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
