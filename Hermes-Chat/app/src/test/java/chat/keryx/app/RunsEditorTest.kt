package chat.keryx.app

import chat.keryx.app.data.remote.HubJson
import chat.keryx.app.presentation.RunsEditorDelegate
import chat.keryx.core.model.CronJobForm
import chat.keryx.core.model.CronScheduleForm
import chat.keryx.core.model.ScheduleKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's baseline: a job row as Hermes Link serves it (`GET /api/jobs`) becomes the draft
 * an edit is measured against. Opening a job and saving without touching it must send nothing —
 * above all not a schedule the gateway only knows how to DISPLAY.
 */
class RunsEditorTest {

    private val jobs = HubJson.jobs(
        Json.parseToJsonElement(
            """
            {"jobs":[
              {"id":"b1","name":"Daily Brief","prompt":"Brief me.","enabled":true,"state":"scheduled",
               "schedule":{"kind":"cron","expr":"0 7 * * *","display":"0 7 * * *"},"schedule_display":"0 7 * * *",
               "deliver":"matrix:!dAfUtHvghPuyaprEuB:silas.local","model":"qwen3.8-flash-next","provider":"custom"},
              {"id":"o1","name":"Reminder","prompt":"Ping.","enabled":true,"state":"scheduled",
               "schedule":{"kind":"once","run_at":"2026-10-03T08:00:00-05:00","display":"once at 2026-10-03 08:00"},
               "schedule_display":"once at 2026-10-03 08:00","deliver":null,"model":null,"provider":null},
              {"id":"w1","name":"gbrain watchdog","prompt":"","no_agent":true,"enabled":true,
               "schedule_display":"every 30m","deliver":"local"}
            ]}
            """.trimIndent(),
        ).jsonObject,
    )

    @Test
    fun `a job row carries its model pin into the editor`() {
        val d = RunsEditorDelegate.draftOf(jobs[0])
        assertEquals("qwen3.8-flash-next", d.model)
        assertEquals("custom", d.provider)
        assertEquals("matrix:!dAfUtHvghPuyaprEuB:silas.local", d.deliver)
    }

    @Test
    fun `opening a job and saving untouched sends nothing`() {
        for (job in jobs) {
            val before = RunsEditorDelegate.draftOf(job)
            // What the sheet rebuilds from its picker on open.
            val reopened = before.copy(schedule = CronScheduleForm.build(CronScheduleForm.parse(before.schedule)))
            assertEquals(job.name, emptyMap<String, String>(), CronJobForm.changes(before, reopened))
        }
    }

    @Test
    fun `a one-off opens as custom and a missing deliver reads as save-only`() {
        val once = RunsEditorDelegate.draftOf(jobs[1])
        assertEquals(ScheduleKind.CUSTOM, CronScheduleForm.parse(once.schedule).kind)
        assertEquals("local", once.deliver)
        assertEquals("", once.model)
    }

    @Test
    fun `a script job needs no prompt to save`() {
        val script = RunsEditorDelegate.draftOf(jobs[2])
        assertTrue(script.scriptOnly)
        assertEquals(null, CronJobForm.problem(script))
    }
}
