package chat.keryx.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attention budget (2.0) is law: "Battery Saver stills every ornament." It is a promise the
 * README makes to strangers, and between 2.0 and 2.4 it quietly stopped being true — each movement
 * added a glow, a pulse or a breath that never asked whether the device was in Battery Saver, and
 * nothing failed when it didn't.
 *
 * So this test guards the invariant rather than the eight or nine places that had drifted: every
 * always-on animation in the app must sit inside a reduced-motion gate. It reads the source
 * because the thing being asserted is a property of the code, not of a value some function
 * returns — `rememberReducedMotion()` needs a live Context, and this module has no Robolectric.
 *
 * When this fails, the fix is almost never to widen the allow-list. It is to gate the animation:
 *
 *     val reduced by rememberReducedMotion()
 *     val x = if (!reduced) { rememberInfiniteTransition(...).animateFloat(...).value } else <still>
 *
 * Note the shape — the transition is created *inside* the branch, so a stilled ornament disposes
 * its frame-clock client instead of running an animation nobody can see.
 */
class AttentionBudgetTest {

    private val sourceRoot = File("src/main/java/chat/keryx/app")

    /** How far above a call site the gate may live before it stops being obviously connected. */
    private val gateWindow = 16

    /** Hand-rolled tickers sit further from their gate: it guards the whole composable body. */
    private val tickerWindow = 60

    private fun sources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the source tree is where this test thinks it is`() {
        // A misconfigured working directory would make every assertion below vacuously pass.
        assertTrue(
            "Expected Kotlin sources under ${sourceRoot.absolutePath}",
            sources().size > 50,
        )
    }

    @Test
    fun `every infinite animation sits inside a reduced-motion gate`() {
        val offenders = mutableListOf<String>()
        for (file in sources()) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                // Skip the import that gives the call site its name.
                if (!line.contains("rememberInfiniteTransition(")) return@forEachIndexed
                if (line.trimStart().startsWith("import ")) return@forEachIndexed
                val window = lines.subList(maxOf(0, i - gateWindow), i + 1)
                if (window.none { it.contains("reduced", ignoreCase = true) }) {
                    offenders += "${file.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertTrue(
            "Always-on animations with no reduced-motion gate within $gateWindow lines " +
                "— Battery Saver must still every ornament:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every frame-clock ticker sits inside a reduced-motion gate`() {
        // The other way an ornament animates here: a coroutine that drives frames itself. The
        // signal has to be the frame clock (withFrameNanos) or a sprite-cell advance — a plain
        // sub-second delay() is just as likely to be a search debounce or the call's audio loop,
        // and a guard that cries wolf about those gets widened until it guards nothing.
        // The advance itself, not any assignment to something called frameW: a cell index
        // stepping modulo a frame count is what every hand-rolled sprite loop here does.
        val spriteAdvance = Regex("""\bframe\w*\s*=\s*\(\s*frame\w*\s*\+\s*1\s*\)\s*%""")
        val offenders = mutableListOf<String>()
        for (file in sources()) {
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (line.trimStart().startsWith("import ")) return@forEachIndexed
                if (!line.contains("withFrameNanos") && !spriteAdvance.containsMatchIn(line)) {
                    return@forEachIndexed
                }
                // A wider window than the infinite-transition check: these gates sit at the top of
                // the composable and the loop they guard can be some way down its body.
                val window = lines.subList(maxOf(0, i - tickerWindow), i + 1)
                if (window.none { it.contains("reduced", ignoreCase = true) }) {
                    offenders += "${file.name}:${i + 1}  ${line.trim()}"
                }
            }
        }
        assertTrue(
            "Frame-clock or sprite tickers with no reduced-motion gate within $tickerWindow " +
                "lines:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
