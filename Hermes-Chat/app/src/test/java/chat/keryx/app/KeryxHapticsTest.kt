package chat.keryx.app

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import chat.keryx.app.presentation.ui.components.KeryxHaptics
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tick vocabulary (2.0 Phase 3, finally whole in 2.5).
 *
 * The bug this pins is not subtle and shipped for a long time: Settings ▸ Interface ▸ "Haptic
 * Feedback" was persisted, drawn as a switch, and consulted by nothing. Four call sites buzzed
 * the phone regardless. So the first assertion here is simply that turning it off turns it off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeryxHapticsTest {

    private class Recorder : HapticFeedback {
        val ticks = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            ticks += hapticFeedbackType
        }
    }

    @Test
    fun `the setting silences every tick, including the two-beat one`() = runTest {
        val rec = Recorder()
        val haptics = KeryxHaptics(rec, enabled = false, scope = this)
        haptics.commit()
        haptics.press()
        haptics.completion()
        advanceUntilIdle() // the completion tick is a coroutine; let it have its chance to fire
        assertEquals("A disabled vocabulary must not buzz at all", emptyList<HapticFeedbackType>(), rec.ticks)
    }

    @Test
    fun `commit is one tick and press is one tick`() = runTest {
        val rec = Recorder()
        val haptics = KeryxHaptics(rec, enabled = true, scope = this)
        haptics.commit()
        assertEquals(1, rec.ticks.size)
        haptics.press()
        assertEquals(2, rec.ticks.size)
        // The two must not feel the same — a commit answering a light gesture and a long press
        // answering a heavy one is the whole distinction.
        assertTrue("commit and press must be different ticks", rec.ticks[0] != rec.ticks[1])
    }

    @Test
    fun `completion is the only two-beat tick`() = runTest {
        val rec = Recorder()
        val haptics = KeryxHaptics(rec, enabled = true, scope = this)
        haptics.completion()
        advanceUntilIdle()
        assertEquals("Completion is a soft double", 2, rec.ticks.size)
        assertEquals("Both beats are the same tick", rec.ticks[0], rec.ticks[1])
    }

    @Test
    fun `the beats are far enough apart to count and close enough to read as one event`() = runTest {
        val rec = Recorder()
        KeryxHaptics(rec, enabled = true, scope = this).completion()
        runCurrent()
        assertEquals("The first beat lands immediately", 1, rec.ticks.size)
        // A beat short of the gap, the second must still be waiting — otherwise the two collapse
        // into one buzz and "done" becomes indistinguishable from "you did that".
        advanceTimeBy(KeryxHaptics.COMPLETION_GAP_MS - 1)
        runCurrent()
        assertEquals("The second beat fired early", 1, rec.ticks.size)
        advanceUntilIdle()
        assertEquals(2, rec.ticks.size)
        assertTrue(
            "A gap outside this range stops reading as one event with two beats",
            KeryxHaptics.COMPLETION_GAP_MS in 40..200,
        )
    }

    @Test
    fun `nothing reaches past the vocabulary to buzz the phone directly`() {
        // The setting is honoured in exactly one place, which only works if there is exactly one
        // place. A new call site that reaches for LocalHapticFeedback itself is how the switch
        // stopped working the first time.
        val offenders = File("src/main/java/chat/keryx/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "KeryxHaptics.kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> "performHapticFeedback" in line }
                    .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "Raw haptic calls bypass Settings ▸ Interface ▸ Haptic Feedback — route them through " +
                "LocalKeryxHaptics instead:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
