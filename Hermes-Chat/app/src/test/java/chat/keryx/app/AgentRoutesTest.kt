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

    @Test
    fun `the mcp catalog reads what an entry would run and what it asks for`() {
        val entries = AgentParser.mcpCatalog(obj("""
            {"entries":[
              {"name":"linear","description":"Issues","connector_slug":null,"source":"optional-mcps",
               "transport":"http","auth_type":"oauth","required_env":[],"command":null,"args":[],
               "url":"https://mcp.linear.app/mcp","install_url":null,"install_ref":null,"bootstrap":[],
               "default_enabled":null,"post_install":"","suggest":null,"needs_install":false,
               "installed":true,"enabled":true},
              {"name":"Brave","description":"Search","transport":"stdio","auth_type":"env",
               "required_env":[{"name":"BRAVE_API_KEY","prompt":"Brave API key","required":true}],
               "command":"npx","args":["-y","brave-mcp"],"url":null,"install_url":"https://g/x.git",
               "bootstrap":["npm ci"],"needs_install":true,"installed":false,"enabled":false}
            ],"diagnostics":[]}
        """))!!
        // A to Z, case-blind.
        assertEquals(listOf("Brave", "linear"), entries.map { it.name })
        val brave = entries[0]
        assertEquals("npx -y brave-mcp", brave.target)
        assertEquals("BRAVE_API_KEY", brave.requiredEnv.single().name)
        assertEquals("Brave API key", brave.requiredEnv.single().prompt)
        assertTrue(brave.needsInstall)
        assertFalse(brave.installed)
        assertEquals("oauth", entries[1].authType)
        assertTrue(entries[1].installed)
        assertNull(AgentParser.mcpCatalog(obj("""{"detail":"x"}""")))
    }

    @Test
    fun `an mcp test answers tools or the reason it could not connect`() {
        val ok = AgentParser.mcpProbe(obj("""
            {"ok":true,"tools":[{"name":"search","description":"Find","schema_chars":120},{"name":"fetch","description":""}],
             "prompts":2,"resources":0}
        """))!!
        assertTrue(ok.ok)
        assertEquals(listOf("search", "fetch"), ok.tools.map { it.first })
        assertEquals(2, ok.prompts)
        val failed = AgentParser.mcpProbe(obj("""{"ok":false,"error":"Connection refused","tools":[]}"""))!!
        assertFalse(failed.ok)
        assertEquals("Connection refused", failed.error)
        assertTrue(AgentParser.mcpInstallBackground(obj("""{"ok":true,"name":"x","background":true,"action":"a"}""")))
        assertFalse(AgentParser.mcpInstallBackground(obj("""{"ok":true,"name":"x","background":false}""")))
    }
}
