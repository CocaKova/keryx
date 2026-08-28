package chat.keryx.app

import chat.keryx.core.protocol.ProjectsParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures captured live from a running gateway (8a4483c-era) on 2026-08-15 —
 *  the wire truth, not hand-written ideals. */
class ProjectsParserTest {

    private fun fixture(name: String) = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource(name)!!.readText()
    )

    @Test
    fun catalog_parsesExplicitProjects() {
        val cat = ProjectsParser.parseCatalog(fixture("projects_list_live.json"))!!
        assertEquals(1, cat.projects.size)
        val p = cat.projects[0]
        assertEquals("p_0588f8bd", p.id)
        assertEquals("palworld-server-setup", p.slug)
        assertEquals("Palworld Server Setup", p.name)
        assertFalse(p.archived)
        assertTrue(p.createdAtMs > 1_700_000_000_000L) // seconds → millis
        // Folder-less project: not a valid move target.
        assertNull(p.anchorPath)
    }

    @Test
    fun tree_parsesNodesCountsAndScopedIds() {
        val tree = ProjectsParser.parseTree(fixture("projects_tree_live.json"))!!
        assertTrue(tree.scopedSessionIds.isNotEmpty())

        val home = tree.projects.single { it.isNoProject }
        assertEquals("Home", home.label)
        assertTrue(home.sessionCount > 0)
        assertTrue(home.previewSessions.isNotEmpty())
        // Preview rows map onto the drawer's own summaries: named, timestamped in millis.
        val s = home.previewSessions.first()
        assertTrue(s.name.isNotBlank())
        assertTrue(s.timestamp > 1_700_000_000_000L)

        val auto = tree.projects.first { it.isAuto }
        assertTrue(auto.path != null)
        assertTrue(auto.repos.isNotEmpty())
    }

    @Test
    fun drillIn_flattensLanesNewestFirst() {
        val payload = fixture("project_sessions_live.json")
        val node = ProjectsParser.parseTreeNode(
            (payload as kotlinx.serialization.json.JsonObject)["project"]
        )!!
        val flat = node.flatSessions()
        assertTrue(flat.isNotEmpty())
        assertEquals(flat.map { it.first.timestamp }.sortedDescending(), flat.map { it.first.timestamp })
        // Every flattened row carries its lane label for the section caption.
        assertTrue(flat.all { it.second.isNotBlank() })
    }

    @Test
    fun anchorPath_prefersPrimaryThenFirstFolder() {
        fun proj(primary: String?, folders: String) = ProjectsParser.parseProject(
            Json.parseToJsonElement(
                """{"id":"p_x","slug":"x","name":"X","primary_path":${primary?.let { "\"$it\"" } ?: "null"},
                    "archived":false,"created_at":1,"folders":$folders}"""
            )
        )!!
        assertEquals("/a", proj("/a", """[{"path":"/b","is_primary":false}]""").anchorPath)
        assertEquals("/b", proj(null, """[{"path":"/c","is_primary":false},{"path":"/b","is_primary":true}]""").anchorPath)
        assertEquals("/c", proj(null, """[{"path":"/c","is_primary":false}]""").anchorPath)
        assertNull(proj(null, "[]").anchorPath)
    }

    @Test
    fun malformedNodes_dropNeverCrash() {
        val tree = ProjectsParser.parseTree(
            Json.parseToJsonElement("""{"projects":[{"label":"no id"},42,{"id":"ok","repos":"not-a-list"}],"active_id":null}""")
        )!!
        assertEquals(listOf("ok"), tree.projects.map { it.id })
        assertNull(ProjectsParser.parseTree(buildJsonObject { }))
    }
}
