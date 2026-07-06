package chat.keryx.app

import chat.keryx.app.data.remote.HubJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Agent Hub parsers against fixture JSON shaped exactly like the live gateway's answers
 * (hermes-agent 0.18.0, captured 2026-07-06). Synthetic values — the shapes are what's under test,
 * plus the fail-soft rule: missing/retyped fields must degrade to blanks, never throw.
 */
class HubJsonTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `health detailed maps platforms sorted with error message`() {
        val h = HubJson.health(obj("""
            {"status":"ok","platform":"hermes-agent","version":"0.18.0","gateway_state":"running",
             "platforms":{
               "matrix":{"state":"connected","error_code":null,"error_message":null,"updated_at":"2026-07-06T21:25:53.287797+00:00"},
               "homeassistant":{"state":"retrying","error_code":null,"error_message":"failed to reconnect","updated_at":"2026-07-06T22:44:29.070425+00:00"}
             }}
        """))
        assertEquals("running", h.gatewayState)
        assertEquals("0.18.0", h.version)
        assertEquals(listOf("homeassistant", "matrix"), h.platforms.map { it.name })
        assertEquals("failed to reconnect", h.platforms[0].errorMessage)
        assertEquals("connected", h.platforms[1].state)
        assertEquals("", h.platforms[1].errorMessage) // null → blank, not "null"
    }

    @Test
    fun `jobs map schedule and repeat with nullable run fields`() {
        val jobs = HubJson.jobs(obj("""
            {"jobs":[{"id":"00ab82a24317","name":"Calendar","prompt":"…","enabled":true,"state":"scheduled",
              "schedule":{"kind":"cron","expr":"0 7 * * *","display":"0 7 * * *"},"schedule_display":"0 7 * * *",
              "repeat":{"times":null,"completed":44},
              "next_run_at":"2026-07-07T07:00:00-05:00","last_run_at":"2026-07-06T07:04:30.847514-05:00",
              "last_status":"ok","last_error":null,"deliver":"matrix:!room:silas.local"},
             {"id":"x","name":"Sparse"}]}
        """))
        assertEquals(2, jobs.size)
        assertEquals("0 7 * * *", jobs[0].scheduleDisplay)
        assertEquals(44, jobs[0].repeatCompleted)
        assertTrue(jobs[0].enabled)
        assertEquals("ok", jobs[0].lastStatus)
        assertNull(jobs[0].lastError)
        // Sparse job: everything degrades quietly.
        assertEquals("Sparse", jobs[1].name)
        assertNull(jobs[1].nextRunAt)
        assertEquals(0, jobs[1].repeatCompleted)
    }

    @Test
    fun `sessions map counts tokens and epoch floats`() {
        val s = HubJson.sessions(obj("""
            {"object":"list","data":[{"id":"20260706_174914_96c35f","source":"cli","model":"qwen3.6-35b",
              "title":null,"started_at":1783376590.7765987,"ended_at":null,"message_count":12,
              "tool_call_count":3,"input_tokens":3067201,"output_tokens":9283,"api_call_count":40,
              "last_active":1783378154.8821032,"preview":"hello"}]}
        """)).single()
        assertEquals("qwen3.6-35b", s.model)
        assertNull(s.title)
        assertEquals(3067201L, s.inputTokens)
        assertEquals(40, s.apiCallCount)
        assertNull(s.endedAt)
        assertTrue(s.lastActive > 1.78e9)
    }

    @Test
    fun `messages map role content and tool call count`() {
        val msgs = HubJson.messages(obj("""
            {"object":"list","session_id":"s","data":[
              {"role":"user","content":"do the thing","timestamp":1783376590.0},
              {"role":"assistant","content":"","tool_calls":[{"id":"a"},{"id":"b"}]},
              {"role":"tool","content":"ok","tool_name":"terminal"}]}
        """))
        assertEquals(3, msgs.size)
        assertEquals("do the thing", msgs[0].content)
        assertEquals(2, msgs[1].toolCallCount)
        assertEquals("terminal", msgs[2].toolName)
    }

    @Test
    fun `skills and toolsets map their lists`() {
        val skills = HubJson.skills(obj(
            """{"object":"list","data":[{"name":"android-devops","description":"Build Android on Linux","category":null}]}"""
        ))
        assertEquals("android-devops", skills.single().name)

        val ts = HubJson.toolsets(obj("""
            {"object":"list","platform":"api_server","data":[
              {"name":"web","label":"🔍 Web","description":"web_search, web_extract",
               "enabled":true,"configured":true,"tools":["web_extract","web_search"]}]}
        """)).single()
        assertTrue(ts.enabled)
        assertEquals(listOf("web_extract", "web_search"), ts.tools)
    }

    @Test
    fun `iso timestamps parse with and without fractional seconds`() {
        assertNotNull(HubJson.isoToMillis("2026-07-07T07:00:00-05:00"))
        assertNotNull(HubJson.isoToMillis("2026-07-06T07:04:30.847514-05:00"))
        assertNotNull(HubJson.isoToMillis("2026-07-06T21:25:53.287797+00:00"))
        assertNull(HubJson.isoToMillis(null))
        assertNull(HubJson.isoToMillis("not a date"))
        // Offset math is real: 07:00-05:00 == 12:00Z.
        val plain = HubJson.isoToMillis("2026-07-07T12:00:00+00:00")!!
        val offset = HubJson.isoToMillis("2026-07-07T07:00:00-05:00")!!
        assertEquals(plain, offset)
    }

    @Test
    fun `event feed maps kinds and keeps the caller cursor when absent`() {
        val page = HubJson.events(obj("""
            {"events":[
              {"id":143,"task_id":"t_93d2b1a4","kind":"completed","payload":{"by":"milo"},"created_at":1783370393},
              {"id":144,"task_id":"t_2e47bdad","kind":"heartbeat","payload":{},"created_at":1783370468}],
             "cursor":144}
        """), since = 100L)
        assertEquals(2, page.events.size)
        assertEquals("completed", page.events[0].kind)
        assertEquals("t_93d2b1a4", page.events[0].taskId)
        assertEquals(144L, page.cursor)
        // No cursor in the answer → keep the caller's, never rewind to 0.
        assertEquals(100L, HubJson.events(obj("""{"events":[]}"""), since = 100L).cursor)
    }

    @Test
    fun `empty and alien payloads degrade instead of throwing`() {
        assertTrue(HubJson.jobs(obj("""{"weird":true}""")).isEmpty())
        assertTrue(HubJson.sessions(obj("""{"data":"not-a-list"}""")).isEmpty())
        val h = HubJson.health(obj("""{}"""))
        assertEquals("", h.version)
        assertTrue(h.platforms.isEmpty())
    }
}
