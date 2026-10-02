package chat.keryx.app

import androidx.compose.ui.unit.TextUnit
import chat.keryx.app.presentation.ui.components.KeryxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The type scale (2.16) holds only if nobody types a size at a call site again. This test reads
 * the app's own sources: a `fontSize = 12.sp` literal anywhere outside the Material theme fails
 * it, with the file and line, so the fix is to pick a [KeryxType] step (or add one there).
 */
class TypeScaleTest {

    private fun sourceRoot(): File {
        val here = File(System.getProperty("user.dir") ?: ".")
        return listOf(File(here, "src/main/java"), File(here, "app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("app sources not found from ${here.absolutePath}")
    }

    @Test
    fun `the scale ascends and nothing sits under the 11 sp floor`() {
        val steps: List<TextUnit> = KeryxType.steps
        assertEquals(11f, KeryxType.micro.value, 0f)
        steps.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b.value > a.value) }
        assertTrue(steps.all { it.value >= 11f })
    }

    @Test
    fun `no fontSize literal outside the theme`() {
        val literal = Regex("""\bfontSize\s*=\s*[0-9]+(\.[0-9]+)?\.sp\b""")
        val offenders = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Type.kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (literal.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("fontSize literals (use KeryxType):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
