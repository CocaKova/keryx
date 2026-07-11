package chat.keryx.app

import chat.keryx.app.presentation.TtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the voice reads: prose only — never reasoning, tool chrome, telemetry, or markdown syntax. */
class TtsTextTest {

    @Test
    fun plainProse_passesThrough() {
        assertEquals(
            "The deploy finished cleanly with all checks green.",
            TtsText.speakable("The deploy finished cleanly with all checks green."),
        )
    }

    @Test
    fun reasoningAndToolLines_neverSpoken() {
        val content = "🧠 memory: \"User: Jonny\"\n" +
            "🧠 reasoning about the plan\n" +
            "⚙️ terminal: `ls -la /tmp`\n" +
            "All three snapshots restore without errors."
        val spoken = TtsText.speakable(content)
        assertEquals("All three snapshots restore without errors.", spoken)
    }

    @Test
    fun runtimeFooter_neverSpoken() {
        val spoken = TtsText.speakable("Here's your answer.\n\nOrnith-1.0-35B · 42% · ~/workspace/keryx")
        assertEquals("Here's your answer.", spoken)
    }

    @Test
    fun fencedCode_becomesSpokenPlaceholder() {
        val content = "Run this:\n```bash\nrm -rf build/\n./gradlew assembleDebug\n```\nThen reinstall."
        val spoken = TtsText.speakable(content)
        assertEquals("Run this: Code block. Then reinstall.", spoken)
        assertFalse(spoken.contains("gradlew"))
    }

    @Test
    fun unclosedFence_stillSwallowedToPlaceholder() {
        // Streams can commit with a healed-open fence; the voice must not read the code tail.
        val spoken = TtsText.speakable("Sure:\n```python\nprint('hi')")
        assertEquals("Sure: Code block.", spoken)
    }

    @Test
    fun markdownSyntax_flattened() {
        val content = "## Result\n" +
            "> quoted wisdom\n" +
            "- **bold point** with *emphasis* and `inline()` code\n" +
            "1. see [the docs](https://example.com/docs)\n" +
            "---\n" +
            "Done."
        val spoken = TtsText.speakable(content)
        assertEquals("Result quoted wisdom bold point with emphasis and inline() code see the docs Done.", spoken)
    }

    @Test
    fun tableAndCiteRefs_silent() {
        val content = "Totals below⁽¹⁾ hold up.\n\n| Col A | Col B |\n|-------|-------|\n| 1 | 2 |"
        val spoken = TtsText.speakable(content)
        assertEquals("Totals below hold up.", spoken)
    }

    @Test
    fun pureTelemetry_blank() {
        assertEquals("", TtsText.speakable("qwen3.5-122b · 42% · ~/workspace/keryx"))
        assertEquals("", TtsText.speakable(""))
    }

    @Test
    fun mixedRealReply_cleanProse() {
        val content = "Here's what I did:\n" +
            "⚙️ tool_a: \"x\"\n" +
            "All done — the fix is in **`run.py`** now.\n\n" +
            "Ornith-1.0-35B · 42% · ~/workspace/keryx"
        val spoken = TtsText.speakable(content)
        assertEquals("Here's what I did: All done — the fix is in run.py now.", spoken)
    }

    @Test
    fun underscoresInsideIdentifiers_survive() {
        // snake_case is prose here, not emphasis — a voice saying "brain recall" beats "brain-underscore-recall",
        // but stripping the underscore mid-word would corrupt what the user hears as an identifier.
        val spoken = TtsText.speakable("Use brain_recall for that.")
        assertTrue(spoken.contains("brain_recall"))
    }
}
