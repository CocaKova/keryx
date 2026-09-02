package chat.keryx.app

import chat.keryx.app.presentation.ui.components.CodeHighlighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code fence's colour is data, not layout: known languages yield in-bounds spans, unknown
 * ones yield nothing, and neither path can throw. (The composable that used to do this shipped
 * its own horizontal scroller inside ours and crashed on every `bash` fence.)
 */
class CodeHighlightingTest {
    private val bash = "export FOO=1\nfor f in *.txt; do echo \"\$f\"; done\n"

    @Test fun aKnownLanguageColoursSomething() {
        val spans = CodeHighlighting.spans(bash, "bash", darkMode = true)
        assertTrue("bash should produce at least one span", spans.isNotEmpty())
        assertTrue(spans.any { it.rgb != null })
    }

    @Test fun everySpanStaysInsideTheCode() {
        val code = "def f(x):\n    return x + 1  # comment\n"
        for (span in CodeHighlighting.spans(code, "python", darkMode = false)) {
            assertTrue(span.start >= 0)
            assertTrue(span.end <= code.length)
            assertTrue(span.start < span.end)
        }
    }

    @Test fun rgbIsOpaqueFreeSixHex() {
        for (span in CodeHighlighting.spans(bash, "bash", darkMode = true)) {
            span.rgb?.let { assertEquals(0, it ushr 24) }
        }
    }

    @Test fun unknownOrBlankLanguageIsPlainText() {
        assertEquals(emptyList<CodeHighlighting.Span>(), CodeHighlighting.spans(bash, "klingon", true))
        assertEquals(emptyList<CodeHighlighting.Span>(), CodeHighlighting.spans(bash, "", true))
        assertEquals(emptyList<CodeHighlighting.Span>(), CodeHighlighting.spans(bash, null, true))
        assertFalse(CodeHighlighting.knows("klingon"))
        assertTrue(CodeHighlighting.knows("Bash"))
    }

    @Test fun fenceTagsReachTheGrammarsTheyMean() {
        // The tokenizer matches its own enum names only; what people actually write must land.
        for (tag in listOf("bash", "sh", "shell", "ts", "tsx", "typescript", "py", "python", "js", "kt")) {
            assertTrue("$tag should be a known language", CodeHighlighting.knows(tag))
        }
        assertTrue(CodeHighlighting.spans("const x: number = 1;", "ts", true).isNotEmpty())
        assertTrue(CodeHighlighting.spans("print('hi')", "python", true).isNotEmpty())
    }

    @Test fun emptyCodeIsFine() {
        assertEquals(emptyList<CodeHighlighting.Span>(), CodeHighlighting.spans("", "bash", true))
    }
}
