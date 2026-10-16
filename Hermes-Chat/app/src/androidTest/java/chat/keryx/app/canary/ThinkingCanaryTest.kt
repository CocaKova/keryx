package chat.keryx.app.canary

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import chat.keryx.app.presentation.ui.components.HermesThinkingAnimation
import chat.keryx.app.presentation.ui.components.THINKING_STYLES
import chat.keryx.app.theme.HermesChatTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every loading animation (3.0: Quill and Constellation joined), drawn on a real device across
 * one cycle in both grounds, on a clock the test steps by hand. A crash gate first: the marks are
 * hand-built paths and transforms, and a bad one takes the draw pass down mid-turn.
 *
 * Run with `-e shots 1` and each style also leaves `thinking-<style>-<ms>-<ground>.png` in the
 * debug app's files/shots, so its motion can be looked at frame by frame without a gateway.
 */
@RunWith(Parameterized::class)
class ThinkingCanaryTest(private val style: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun styles(): List<String> = THINKING_STYLES

        /** The longest cycle (Constellation, 4.5 s) end to end. */
        private val FRAMES_MS = (0L..4200L step 600L).toList()
    }

    @get:Rule val compose = createComposeRule()

    @Test fun drawsAcrossACycleInBothGrounds() {
        val dark = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesChatTheme(darkTheme = dark.value) {
                Surface {
                    Box(Modifier.padding(12.dp)) {
                        HermesThinkingAnimation(style = style, label = "Reading importer.py")
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnUiThread { dark.value = isDark }
            // One frame for the new ground to land before the first capture.
            compose.mainClock.advanceTimeByFrame()
            var at = 0L
            for (ms in FRAMES_MS) {
                compose.mainClock.advanceTimeBy(ms - at)
                at = ms
                val image = compose.onRoot().captureToImage()
                if (InstrumentationRegistry.getArguments().getString("shots") == "1") {
                    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shots").apply { mkdirs() }
                    val name = "thinking-${style.lowercase().replace(' ', '-')}-$ms-${if (isDark) "dark" else "light"}.png"
                    File(dir, name).outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
