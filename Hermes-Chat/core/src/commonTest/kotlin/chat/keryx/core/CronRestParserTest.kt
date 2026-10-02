package chat.keryx.core

import chat.keryx.core.protocol.BlueprintField
import chat.keryx.core.protocol.CronBlueprint
import chat.keryx.core.protocol.CronBlueprints
import chat.keryx.core.protocol.CronRestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's cron routes, in the shapes `hermes_cli/web_routers/cron.py` writes them —
 * and the shapes a gateway without them answers with, which must read as "not here", never as
 * an empty list that draws an empty picker.
 */
class CronRestParserTest {

    @Test
    fun `delivery targets keep the gateway's order and its home-channel flag`() {
        val body = """{"targets":[
            {"id":"local","name":"Local (save only)","home_target_set":true,"home_env_var":null},
            {"id":"matrix","name":"Matrix","home_target_set":true,"home_env_var":"MATRIX_HOME_ROOM"},
            {"id":"telegram","name":"Telegram","home_target_set":false,"home_env_var":"TELEGRAM_HOME_CHANNEL"},
            {"id":"bot-chat:sy","name":"Bot Chat (sy)","home_target_set":true,"home_env_var":null},
            {"name":"no id, dropped"}
        ]}"""
        val t = CronRestParser.deliveryTargets(body)!!
        assertEquals(listOf("local", "matrix", "telegram", "bot-chat:sy"), t.map { it.id })
        assertFalse(t[2].homeSet)
        assertEquals("Bot Chat (sy)", t[3].name)
    }

    @Test
    fun `a body that is not the route's answers null, not an empty list`() {
        // The SPA's index.html for a path the API does not know, and an unrelated JSON object.
        assertNull(CronRestParser.deliveryTargets("<!doctype html><html></html>"))
        assertNull(CronRestParser.deliveryTargets("""{"detail":"Not Found"}"""))
        assertNull(CronRestParser.runs("""{"jobs":[]}"""))
        assertNull(CronRestParser.blueprints(""))
        assertEquals(emptyList(), CronRestParser.runs("""{"runs":[],"limit":20}"""))
    }

    @Test
    fun `run history reads session rows and script fires alike`() {
        val body = """{"runs":[
            {"id":"cron_output:abc123:2026-10-02_07-00-01","title":"FAILED · exit 1: no such file","preview":"exit 1",
             "source":"cron_output","started_at":1790924401.0,"last_active":1790924401.0,"ended_at":1790924401.0,
             "message_count":0,"is_active":false},
            {"id":"cron_abc123_20261001_070000","title":"Daily Brief · Oct 1 07:00","preview":"# Morning",
             "source":"cron","started_at":1790838000.5,"ended_at":null,"last_active":1790838090,
             "message_count":7,"is_active":true,"end_reason":null},
            {"title":"no id"}
        ],"limit":20}"""
        val runs = CronRestParser.runs(body)!!
        assertEquals(2, runs.size)
        val script = runs[0]
        assertTrue(script.scriptOutput)
        assertTrue(script.failed)
        assertEquals(1_790_924_401_000L, script.startedAt)
        val session = runs[1]
        assertFalse(session.scriptOutput)
        assertFalse(session.failed)
        assertTrue(session.live)
        assertNull(session.endedAt)
        assertEquals(7, session.messageCount)
        assertEquals(1_790_838_000_500L, session.startedAt)
    }

    @Test
    fun `blueprints carry their form`() {
        val body = """{"blueprints":[{"key":"morning-brief","title":"Morning briefing","description":"A short daily briefing",
            "category":"daily","tags":["daily"],"schedule":"{minute} {hour} * * *","scheduleHuman":"Every day at 08:00",
            "command":"/blueprint morning-brief time=08:00 deliver=origin","appUrl":"hermes://blueprint/morning-brief",
            "fields":[
              {"name":"time","type":"time","label":"What time?","default":"08:00","options":[],"optional":false,"strict":true,"help":"24h"},
              {"name":"deliver","type":"enum","label":"Where to deliver?","default":"origin","options":["origin","local","matrix"],"optional":false,"strict":false,"help":""}
            ]}]}"""
        val bp = CronRestParser.blueprints(body)!!.single()
        assertEquals("morning-brief", bp.key)
        assertEquals("Every day at 08:00", bp.scheduleHuman)
        assertEquals(listOf("time", "deliver"), bp.fields.map { it.name })
        assertEquals(listOf("origin", "local", "matrix"), bp.fields[1].options)
        assertFalse(bp.fields[1].strict)
    }

    @Test
    fun `the server's own sentence comes out of a failed call`() {
        assertEquals(
            "Invalid schedule 'every banana'",
            CronRestParser.errorDetail("""HTTP 400 for /api/cron/jobs — {"detail":"Invalid schedule 'every banana'"}"""),
        )
        assertEquals(
            "Field required",
            CronRestParser.errorDetail("""HTTP 422 for /api/cron/jobs — {"detail":[{"loc":["body","schedule"],"msg":"Field required"}]}"""),
        )
        assertEquals("HTTP 502 for /api/cron/jobs", CronRestParser.errorDetail("HTTP 502 for /api/cron/jobs"))
        assertTrue(CronRestParser.isMissingRoute("HTTP 404 for /api/cron/delivery-targets — {\"detail\":\"Not Found\"}"))
        assertFalse(CronRestParser.isMissingRoute("HTTP 500 for /api/cron/delivery-targets"))
        assertFalse(CronRestParser.isMissingRoute(null))
    }

    private val brief = CronBlueprint(
        key = "morning-brief", title = "Morning briefing", description = "", category = "daily", scheduleHuman = "",
        fields = listOf(
            BlueprintField("time", "time", "What time?", "08:00", emptyList(), optional = false, strict = true, help = ""),
            BlueprintField("deliver", "enum", "Where to deliver?", "origin", listOf("origin", "local"), optional = false, strict = false, help = ""),
            BlueprintField("recurrence", "weekdays", "Which days?", null, listOf("everyday", "weekdays"), optional = true, strict = true, help = ""),
            BlueprintField("tone", "enum", "Tone", "calm", listOf("calm", "brisk"), optional = false, strict = true, help = ""),
        ),
    )

    @Test
    fun `a blueprint form starts at its defaults and catches the obvious before the round trip`() {
        val start = CronBlueprints.initialValues(brief)
        assertEquals("08:00", start["time"])
        assertNull(CronBlueprints.problem(brief, start))
        assertNotNull(CronBlueprints.problem(brief, start + ("time" to "8am")))
        assertNotNull(CronBlueprints.problem(brief, start + ("time" to "")))
        // A strict enum refuses a value it doesn't list; the deliver slot takes anything.
        assertNotNull(CronBlueprints.problem(brief, start + ("tone" to "loud")))
        assertNull(CronBlueprints.problem(brief, start + ("deliver" to "matrix")))
    }

    @Test
    fun `the instantiate payload sends known slots only, blanks left out`() {
        val payload = CronBlueprints.payload(brief, mapOf("time" to " 07:30 ", "recurrence" to "", "tiem" to "09:00", "tone" to "calm"))
        assertEquals(mapOf("time" to "07:30", "tone" to "calm"), payload)
    }
}
