package chat.keryx.app.canary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import chat.keryx.app.presentation.ui.components.MessageContent
import chat.keryx.app.theme.HermesChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The render canary: every body in [RenderCorpus] composed and laid out on a real device, in
 * both grounds, settled and streaming.
 *
 * It asserts almost nothing about what appears. That is deliberate — this is not a UI test, it
 * is a crash gate. The bugs it exists to catch (an ICU class-initializer that throws, a nested
 * horizontal scroller Compose refuses to measure) do not produce a wrong pixel; they take the
 * process down. Reaching the end of a case *is* the assertion.
 *
 * One parameterized case per body rather than a loop inside one test, for two reasons: a
 * compose rule accepts `setContent` exactly once (a loop over it fails every case after the
 * first on the rule, not on the app), and a red run should name the body that died.
 */
@RunWith(Parameterized::class)
class RendererCanaryTest(private val caseName: String, private val body: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = RenderCorpus.all.map { arrayOf(it.name, it.body) }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun rendersInBothGroundsSettledAndStreaming() {
        val dark = mutableStateOf(false)
        val streaming = mutableStateOf(false)

        compose.setContent {
            HermesChatTheme(darkTheme = dark.value) {
                Surface {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        // The app's own bubble path — it applies its own horizontal scroller to
                        // a code block, which is where the nested-scroller crash was measured.
                        MessageContent(
                            content = body,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            isStreaming = streaming.value,
                            isAgent = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // A streaming body is parsed cache-free through a tail window — different code from the
        // settled path, so it is walked rather than assumed equivalent. Same for the dark
        // ground: the tokenizer is themed, so its spans are recomputed.
        for ((isDark, isStreaming) in listOf(true to false, false to true, true to true)) {
            compose.runOnUiThread {
                dark.value = isDark
                streaming.value = isStreaming
            }
            compose.waitForIdle()
        }
    }
}
