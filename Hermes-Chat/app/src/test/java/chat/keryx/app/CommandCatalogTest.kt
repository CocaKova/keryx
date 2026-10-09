package chat.keryx.app

import chat.keryx.app.data.remote.HermesStreamClient
import chat.keryx.app.transport.direct.CommandCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogTest {
    private val res = Json.parseToJsonElement(
        """
        {"pairs": [["/model", "Switch model (usage: /model [name])"], ["/new", "Start fresh"],
                   ["/deploy-notes", "Write release notes from the git log"]],
         "canon": {"/model": "/model", "/m": "/model", "/new": "/new"},
         "categories": [{"name": "Session", "pairs": [["/model", "x"], ["/new", "y"]]}],
         "skills": {"/deploy-notes": {"usage": 3, "origin": "agent"}}}
        """,
    ).jsonObject

    @Test fun `skills come through with their description and a tag`() {
        val rows = CommandCatalog.parse(res).associateBy { it.cmd }
        val skill = rows.getValue("/deploy-notes")
        assertEquals("Write release notes from the git log", skill.description)
        assertEquals(CommandCatalog.SKILL, skill.category)
        assertTrue(skill.argsHint.isNotBlank()) // picking it fills the composer
    }

    @Test fun `usage becomes the argument hint and aliases come from canon`() {
        val model = CommandCatalog.parse(res).first { it.cmd == "/model" }
        assertEquals("Switch model", model.description)
        assertEquals("[name]", model.argsHint)
        assertEquals("Session", model.category)
        assertEquals(listOf("/m"), model.aliases)
        assertEquals("", CommandCatalog.parse(res).first { it.cmd == "/new" }.argsHint)
    }

    @Test fun `the plugin list fills gaps but the catalog leads`() {
        val catalog = CommandCatalog.parse(res)
        val plugin = listOf(
            HermesStreamClient.GatewayCommand("/new", "plugin words", "Session", "", listOf("/reset")),
            HermesStreamClient.GatewayCommand("/keryx", "Plugin command", "Plugin", "", emptyList()),
        )
        val merged = HermesStreamClient.mergeCommands(catalog, plugin).associateBy { it.cmd }
        assertEquals("Start fresh", merged.getValue("/new").description)
        assertEquals(listOf("/reset"), merged.getValue("/new").aliases)
        assertTrue("/keryx" in merged)
        assertTrue("/deploy-notes" in merged)
    }
}
