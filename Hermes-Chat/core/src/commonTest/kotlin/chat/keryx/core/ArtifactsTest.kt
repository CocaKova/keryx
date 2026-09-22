package chat.keryx.core

import chat.keryx.core.model.Artifacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A bare `/…/name.html` path in the agent's prose earns an "Open" chip; nothing else does (2.13). */
class ArtifactsTest {

    @Test
    fun html_and_htm_are_artifacts_case_insensitively() {
        assertTrue(Artifacts.isArtifactPath("/home/sy/out/mock-v1.html"))
        assertTrue(Artifacts.isArtifactPath("/tmp/index.HTM"))
        assertTrue(Artifacts.isArtifactPath("/tmp/page.html?v=2#top"))
        assertFalse(Artifacts.isArtifactPath("/tmp/report.pdf"))
        assertFalse(Artifacts.isArtifactPath("/tmp/page.html.bak"))
        assertFalse(Artifacts.isArtifactPath("page"))
    }

    @Test
    fun bare_path_in_prose_is_found() {
        assertEquals(
            listOf("/home/sy/out/salt-creek-mock-v1.html"),
            Artifacts.findArtifactPaths("Saved the mock to /home/sy/out/salt-creek-mock-v1.html for you."),
        )
    }

    @Test
    fun quoted_backticked_and_bracketed_paths_are_found() {
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("wrote `/a/b.html`"))
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("wrote \"/a/b.html\""))
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("wrote (/a/b.html)"))
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("[the mock](/a/b.html)"))
    }

    @Test
    fun trailing_punctuation_closes_the_path() {
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("open /a/b.html."))
        assertEquals(listOf("/a/b.html"), Artifacts.findArtifactPaths("open /a/b.html, then"))
        assertEquals(listOf("/a/b.htm"), Artifacts.findArtifactPaths("open /a/b.htm; then"))
    }

    @Test
    fun urls_are_not_artifact_paths() {
        assertEquals(emptyList(), Artifacts.findArtifactPaths("see https://example.com/page.html"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("see http://host:8080/x/page.htm now"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("file:///home/sy/page.html"))
    }

    @Test
    fun relative_paths_and_longer_extensions_are_not_found() {
        assertEquals(emptyList(), Artifacts.findArtifactPaths("in ./out/page.html"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("in ../out/page.html"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("in out/page.html"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("backup at /a/b.html.bak"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("a dir /a/b.html/assets"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths("/a/b.htmlx"))
    }

    @Test
    fun paths_are_deduped_in_first_seen_order() {
        val text = "Draft: /o/one.html and /o/two.html — then /o/one.html again."
        assertEquals(listOf("/o/one.html", "/o/two.html"), Artifacts.findArtifactPaths(text))
    }

    @Test
    fun plain_text_costs_nothing_and_finds_nothing() {
        assertEquals(emptyList(), Artifacts.findArtifactPaths("no pages here, just words"))
        assertEquals(emptyList(), Artifacts.findArtifactPaths(""))
    }

    @Test
    fun name_is_the_last_segment() {
        assertEquals("mock-v1.html", Artifacts.nameOf("/home/sy/out/mock-v1.html"))
    }
}
