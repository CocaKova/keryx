package chat.keryx.core

import chat.keryx.core.model.MemoryEntry
import chat.keryx.core.model.MemoryParser
import chat.keryx.core.model.MemorySearch
import chat.keryx.core.model.MemorySource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payloads shaped exactly as `hermes_cli/web_routers/ops.py` (`GET /api/memory`) and
 * `agent/learning_graph.py` (`GET /api/learning/graph`) build them.
 */
class AgentMemoryTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    private val graph = """
        {"nodes": [
          {"id": "deploy-flow", "label": "deploy-flow", "kind": "skill", "category": "ops"},
          {"id": "memory:memory:0:aaaaaaaaaaaa", "label": "Spark 1 runs the brain", "kind": "memory", "memorySource": "memory"},
          {"id": "memory:memory:1:bbbbbbbbbbbb", "label": "Never gradle on Spark 1", "kind": "memory", "memorySource": "memory"},
          {"id": "memory:profile:2:cccccccccccc", "label": "Ada, Europe/London", "kind": "memory", "memorySource": "profile"}
        ],
        "edges": [],
        "memory": [
          {"source": "memory", "timestamp": 1727000000, "title": "Spark 1 runs the brain", "body": "Spark 1 runs the brain\nvLLM on :8000", "fingerprint": "aaaaaaaaaaaa"},
          {"source": "memory", "timestamp": 1727000001, "title": "Never gradle on Spark 1", "body": "Never gradle on Spark 1\nbuild on VIRDARA", "fingerprint": "bbbbbbbbbbbb"},
          {"source": "profile", "timestamp": 1727000500, "title": "Ada, Europe/London", "body": "Ada, Europe/London\nprefers short answers", "fingerprint": "cccccccccccc"}
        ],
        "stats": {"memory_nodes": 3}}
    """.trimIndent()

    @Test
    fun entries_readBothFilesWithTheGraphsOwnIds() {
        val e = MemoryParser.entries(j(graph))!!
        assertEquals(3, e.size)
        assertEquals(listOf(MemorySource.NOTES, MemorySource.NOTES, MemorySource.PROFILE), e.map { it.source })
        assertEquals("memory:profile:2:cccccccccccc", e[2].id)
        assertEquals("Ada, Europe/London", e[2].title)
        assertEquals(1727000500L, e[2].timestamp)
        assertFalse(e[0].maybeClipped)
    }

    @Test
    fun entries_mintIdsLikeTheServerWhenNodesDontLineUp() {
        // No memory nodes in the payload: the id is minted the way memory_node_id does it —
        // position in the combined list, then the fingerprint.
        val noNodes = graph.replace(Regex("\"nodes\": \\[[^\\]]*\\],"), "\"nodes\": [],")
        val e = MemoryParser.entries(j(noNodes))!!
        assertEquals("memory:memory:1:bbbbbbbbbbbb", e[1].id)
        assertEquals("memory:profile:2:cccccccccccc", e[2].id)
    }

    @Test
    fun entries_legacyCardsWithoutFingerprintGetThePositionalId() {
        val legacy = """{"nodes": [], "memory": [{"source": "profile", "title": "t", "body": "t\nb"}]}"""
        assertEquals("memory:profile:0", MemoryParser.entries(j(legacy))!!.single().id)
    }

    @Test
    fun entries_dropUnknownSourcesAndRejectNonGraphs() {
        val odd = """{"memory": [{"source": "elsewhere", "title": "x", "body": "x"}, {"source": "memory", "title": "y", "body": "y"}]}"""
        assertEquals(listOf("y"), MemoryParser.entries(j(odd))!!.map { it.title })
        assertNull(MemoryParser.entries(j("""{"nodes": []}""")))
        assertNull(MemoryParser.entries(j("[]")))
    }

    @Test
    fun aBodyAtTheCapMayBeClipped() {
        val long = "x".repeat(MemoryParser.BODY_CAP)
        val e = MemoryEntry("memory:memory:0:f", MemorySource.NOTES, "x", long, null)
        assertTrue(e.maybeClipped)
    }

    @Test
    fun status_readsProviderAndFileSizes() {
        val st = MemoryParser.status(j("""
            {"active": "gbrain",
             "providers": [{"name": "gbrain", "description": "brain", "available": true, "configured": true, "status": "ready", "setup": {}},
                           {"name": "honcho", "description": "", "available": false, "configured": false, "status": "needs_config"}],
             "builtin_files": {"memory": 2150, "user": 0}}
        """.trimIndent()))!!
        assertEquals("gbrain", st.active)
        assertEquals("ready", st.activeProvider?.status)
        assertEquals(2150L, st.notesBytes)
        assertEquals(0L, st.profileBytes)
        assertNull(MemoryParser.status(j("""{"something": "else"}""")))
    }

    @Test
    fun nodeContent_isTheFullEntryAndMutationsCarryTheServersSentence() {
        assertEquals("full\ntext", MemoryParser.nodeContent(j("""{"ok": true, "kind": "memory", "id": "m", "label": "full", "content": "full\ntext"}""")))
        assertNull(MemoryParser.nodeContent(j("""{"ok": false, "message": "not found"}""")))
        assertEquals("updated memory in USER.md", MemoryParser.mutationMessage(j("""{"ok": true, "message": "updated memory in USER.md"}""")))
    }

    @Test
    fun search_needsEveryTermInAnyCase() {
        val e = MemoryParser.entries(j(graph))!!
        assertEquals(3, MemorySearch.filter(e, "  ").size)
        assertEquals(listOf("Never gradle on Spark 1"), MemorySearch.filter(e, "spark VIRDARA").map { it.title })
        assertEquals(2, MemorySearch.filter(e, "spark").size)
        assertTrue(MemorySearch.filter(e, "spark chicago").isEmpty())
        // The body counts, not only the title.
        assertEquals(listOf("Ada, Europe/London"), MemorySearch.filter(e, "SHORT answers").map { it.title })
    }

    @Test
    fun humanBytes_speaksTheDashboardsUnits() {
        assertEquals("0 B", MemorySearch.humanBytes(0))
        assertEquals("940 B", MemorySearch.humanBytes(940))
        assertEquals("2.1 KB", MemorySearch.humanBytes(2150))
        assertEquals("1.0 MB", MemorySearch.humanBytes(1024L * 1024))
    }
}
