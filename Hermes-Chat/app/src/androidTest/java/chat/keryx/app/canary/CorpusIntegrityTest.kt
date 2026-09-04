package chat.keryx.app.canary

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The corpus is generated, so it can silently generate nothing, and a green gate over an empty
 * set is worse than no gate at all. This is the check on the check.
 */
@RunWith(AndroidJUnit4::class)
class CorpusIntegrityTest {

    @Test fun corpusCoversTheGeneratedSurface() {
        assertTrue(
            "fence corpus collapsed (${RenderCorpus.fences.size}) — CodeHighlighting.knownTags empty?",
            RenderCorpus.fences.size >= 10,
        )
        assertTrue("corpus collapsed: ${RenderCorpus.all.size} cases", RenderCorpus.all.size >= 30)
        assertTrue("every case needs a name", RenderCorpus.all.all { it.name.isNotBlank() })
    }
}
