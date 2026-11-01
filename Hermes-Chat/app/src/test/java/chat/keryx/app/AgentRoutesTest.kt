package chat.keryx.app

import chat.keryx.app.presentation.ui.components.middleEllipsis
import chat.keryx.app.presentation.ui.components.soulGist
import chat.keryx.app.presentation.ui.components.soulHeading
import chat.keryx.app.transport.direct.AgentParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent page's reads (makeover): stock `hermes dashboard` shapes, as the routes answer them. */
class AgentRoutesTest {

    private fun obj(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `mcp servers read the dashboard's summary, secrets already redacted`() {
        val servers = AgentParser.mcpServers(obj("""
            {"servers":[
              {"name":"context7","transport":"http","url":"https://mcp.context7.com/mcp","command":null,
               "args":[],"env":{},"auth":null,"enabled":true,"tools":null,"source":"config","plugin":null},
              {"name":"firecrawl","transport":"stdio","url":null,"command":"/home/u/bin/firecrawl-mcp",
               "args":["--quiet"],"env":{"FIRECRAWL_API_KEY":"***"},"auth":null,"enabled":false,
               "tools":null,"source":"config","plugin":null},
              {"name":"ha","transport":"http","url":"http://h/mcp","auth":"bearer","enabled":true,
               "source":"plugin","plugin":"homeassistant"}
            ]}
        """))!!
        assertEquals(listOf("context7", "firecrawl", "ha"), servers.map { it.name })
        assertEquals("https://mcp.context7.com/mcp", servers[0].target)
        assertEquals("/home/u/bin/firecrawl-mcp --quiet", servers[1].target)
        assertEquals(1, servers[1].envCount)
        assertFalse(servers[1].enabled)
        assertEquals("homeassistant", servers[2].plugin)
        assertEquals("bearer", servers[2].auth)
    }

    @Test
    fun `a body without servers is unreadable, not empty`() {
        assertNull(AgentParser.mcpServers(obj("""{"detail":"nope"}""")))
        assertEquals(0, AgentParser.mcpServers(obj("""{"servers":[]}"""))!!.size)
    }

    @Test
    fun `the profile is the one this dashboard is scoped to`() {
        assertEquals("work", AgentParser.currentProfile(obj("""{"active":"default","current":"work"}""")))
        // "custom" is a dashboard that cannot name its home — fall back to the sticky one.
        assertEquals("default", AgentParser.currentProfile(obj("""{"active":"default","current":"custom"}""")))
    }

    @Test
    fun `soul reads content and whether the file exists`() {
        val s = AgentParser.soul("default", obj("""{"content":"# SILAS\n\nYou are SILAS.","exists":true}"""))!!
        assertTrue(s.exists)
        assertEquals("SILAS", soulHeading(s.content))
        assertEquals("You are SILAS.", soulGist(s.content))
        val none = AgentParser.soul("default", obj("""{"content":"","exists":false}"""))!!
        assertFalse(none.exists)
        assertNull(soulHeading(none.content))
    }

    @Test
    fun `a long path keeps its head and its name`() {
        val p = "/home/cocakova/workspace/tools/mcp/servers/aseprite-mcp.sh"
        val short = middleEllipsis(p, 30)
        assertEquals(30, short.length)
        assertTrue(short.startsWith("/home/coca"))
        assertTrue(short.endsWith("aseprite-mcp.sh"))
        assertEquals("short", middleEllipsis("short", 30))
    }
}
