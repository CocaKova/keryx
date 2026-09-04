package chat.keryx.app.canary

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.keryx.app.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app opens and stays open.
 *
 * A deliberately shallow check, and separate from [RendererCanaryTest] so a flaky cold start
 * (a device that locks, a transport that cannot reach its gateway) never masks a renderer
 * result. It catches the class of failure where the process dies before anything is drawn —
 * a bad class-initializer reached from startup, a missing resource, a manifest change that
 * doesn't resolve on the target's API level.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartCanaryTest {

    @Test fun mainActivityReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val state = scenario.state
            if (state != Lifecycle.State.RESUMED) {
                throw AssertionError("MainActivity settled at $state, not RESUMED")
            }
        }
    }

    /** Backgrounding and returning is where saved-state and re-composition bugs surface. */
    @Test fun mainActivitySurvivesStopAndResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            if (scenario.state != Lifecycle.State.RESUMED) {
                throw AssertionError("MainActivity did not come back: ${scenario.state}")
            }
        }
    }
}
