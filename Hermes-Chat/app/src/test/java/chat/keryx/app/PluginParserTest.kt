package chat.keryx.app

import chat.keryx.app.transport.direct.PluginParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.0 Plugins: the dashboard's plugin hub and an MCP sign-in, read the way the gateway sends them. */
class PluginParserTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    @Test fun readsTheHubRows() {
        val rows = PluginParser.hub(j("""{"plugins":[
            {"name":"keryx-stream","version":"0.7.1","source":"user","runtime_status":"enabled","can_remove":true,"auth_required":false,"auth_command":"x"},
            {"name":"gbrain","source":"user","runtime_status":"inactive","can_remove":true},
            {"name":"matrix-platform","source":"bundled","runtime_status":"enabled","auth_required":true,"auth_command":"hermes auth matrix"},
            {"version":"no name"}
        ],"orphan_dashboard_plugins":[]}"""))!!
        assertEquals(listOf("keryx-stream", "gbrain", "matrix-platform"), rows.map { it.name })
        assertTrue(rows[0].enabled && rows[0].canRemove && !rows[0].bundled)
        assertNull("a sign-in hint only when the plugin says it needs one", rows[0].authCommand)
        assertFalse(rows[1].enabled)
        assertTrue(rows[2].bundled)
        assertEquals("hermes auth matrix", rows[2].authCommand)
    }

    @Test fun notTheHubShapeIsNull() {
        assertNull(PluginParser.hub(j("""{"detail":"nope"}""")))
    }

    @Test fun readsAnOAuthFlow() {
        val f = PluginParser.authFlow(j("""{"flow_id":"abc","server_name":"notion","status":"authorization_required","authorization_url":"https://x/auth","error":null}"""))!!
        assertEquals("https://x/auth", f.authorizationUrl)
        assertFalse(f.done)
        assertTrue(PluginParser.authFlow(j("""{"flow_id":"abc","status":"approved"}"""))!!.done)
        assertNull(PluginParser.authFlow(j("""{"status":"error"}""")))
    }

    @Test fun readsAnInstallAnswer() {
        val r = PluginParser.install(j("""{"ok":true,"plugin_name":"weather","restart_required":true,"missing_env":["WEATHER_KEY"],"warnings":[]}"""))!!
        assertEquals("weather", r.name)
        assertTrue(r.restartRequired)
        assertEquals(listOf("WEATHER_KEY"), r.missingEnv)
    }
}
