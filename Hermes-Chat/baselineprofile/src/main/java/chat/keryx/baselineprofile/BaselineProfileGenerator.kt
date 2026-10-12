package chat.keryx.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the journey runs, against the profileGen build (no R8, so
 * the rules carry source names). tools/baseline_profile.sh copies the result to
 * app/src/main/baseline-prof.txt, which AGP compiles into every release. A sideloaded install
 * gets it from profileinstaller on first launch, and ART compiles those paths ahead of time
 * at the next idle maintenance instead of interpreting them on every cold start.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        keryxJourney()
    }
}
