package chat.keryx.core

import chat.keryx.core.model.CronDelivery
import chat.keryx.core.model.CronJobDraft
import chat.keryx.core.model.CronJobForm
import chat.keryx.core.model.CronScheduleForm
import chat.keryx.core.model.DeliveryOption
import chat.keryx.core.model.DeliveryTarget
import chat.keryx.core.model.ScheduleDraft
import chat.keryx.core.model.ScheduleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Runs editor's pure half, against the schedules and delivery values on Jonny's live
 * gateway (10-02: 24 jobs — `0 7 * * *`, `every 30m`, `every 20160m`, `0 9 3 * *`, `7 8-21 * * *`,
 * `matrix:!dAfUtHvghPuyaprEuB:silas.local`, bare `matrix`, `origin`, `local`).
 */
class CronEditorTest {

    // --- Schedule round trip ---------------------------------------------------------------

    @Test
    fun `every schedule shape the picker writes reads back as itself`() {
        val live = listOf(
            "0 7 * * *", "15 7 * * *", "55 6 * * *", "every 30m", "every 10m", "every 20160m",
            "0 19 * * 0", "30 9 * * 0", "0 10 * * 6", "0 9 3 * *", "15 7 * * 1-5", "0 8 * * 0,6", "0 9 * * 1,3,5",
        )
        for (s in live) assertEquals(s, CronScheduleForm.build(CronScheduleForm.parse(s)), "round trip of $s")
    }

    @Test
    fun `shapes are recognised as the picker's own kinds`() {
        assertEquals(ScheduleDraft(ScheduleKind.DAILY, hour = 7, minute = 0), CronScheduleForm.parse("0 7 * * *"))
        assertEquals(ScheduleKind.WEEKDAYS, CronScheduleForm.parse("15 7 * * 1-5").kind)
        val weekly = CronScheduleForm.parse("0 19 * * 0")
        assertEquals(ScheduleKind.WEEKLY, weekly.kind)
        assertEquals(setOf(0), weekly.days)
        val monthly = CronScheduleForm.parse("0 9 3 * *")
        assertEquals(ScheduleKind.MONTHLY, monthly.kind)
        assertEquals(3, monthly.dayOfMonth)
        val every = CronScheduleForm.parse("every 20160m")
        assertEquals(ScheduleKind.INTERVAL, every.kind)
        assertEquals(20160, every.everyMinutes)
    }

    @Test
    fun `anything the picker cannot draw stays custom and verbatim`() {
        // An hour range, a dated one-off, a step, a month, zero-padded fields, a one-shot.
        for (s in listOf(
            "7 8-21 * * *", "0 9 27 9 *", "*/30 * * * *", "07 7 * * *", "once at 2026-10-03 08:00",
            "every monday 9am", "0 7 * * 7", "0 7 * * 5,1", "every 0m",
        )) {
            val d = CronScheduleForm.parse(s)
            assertEquals(ScheduleKind.CUSTOM, d.kind, s)
            assertEquals(s, CronScheduleForm.build(d), s)
        }
    }

    @Test
    fun `Monday to Friday picked by hand is written the way the gateway shows weekdays`() {
        val d = ScheduleDraft(ScheduleKind.WEEKLY, hour = 7, minute = 15, days = setOf(5, 1, 2, 4, 3))
        assertEquals("15 7 * * 1-5", CronScheduleForm.build(d))
        assertEquals(ScheduleKind.WEEKDAYS, CronScheduleForm.parse(CronScheduleForm.build(d)).kind)
    }

    @Test
    fun `switching kinds keeps the time the user set`() {
        val daily = CronScheduleForm.parse("30 6 * * *")
        assertEquals("30 6 * * 2", CronScheduleForm.build(daily.copy(kind = ScheduleKind.WEEKLY, days = setOf(2))))
        assertEquals("30 6 15 * *", CronScheduleForm.build(daily.copy(kind = ScheduleKind.MONTHLY, dayOfMonth = 15)))
    }

    @Test
    fun `schedule problems catch only what cannot be a schedule`() {
        assertNotNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.WEEKLY, days = emptySet())))
        assertNotNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.INTERVAL, everyMinutes = 0)))
        assertNotNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.CUSTOM, raw = "  ")))
        assertNotNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.CUSTOM, raw = "0 7 * *")))
        // The gateway's parser is the judge of everything else.
        for (ok in listOf("every monday 9am", "weekdays at 9am", "in 30m", "2h", "2026-10-03T09:00", "0 9 * * 1-5")) {
            assertNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.CUSTOM, raw = ok)), ok)
        }
        assertNull(CronScheduleForm.problem(ScheduleDraft(ScheduleKind.DAILY, hour = 23, minute = 59)))
    }

    @Test
    fun `clock text reads and writes a 24 hour time`() {
        assertEquals(7 to 5, CronScheduleForm.clock("7:05"))
        assertEquals(23 to 59, CronScheduleForm.clock(" 23:59 "))
        assertNull(CronScheduleForm.clock("24:00"))
        assertNull(CronScheduleForm.clock("7.05"))
        assertEquals("07:05", CronScheduleForm.clockText(7, 5))
    }

    // --- Delivery ---------------------------------------------------------------------------

    private val targets = listOf(
        DeliveryTarget("local", "Local (save only)", true),
        DeliveryTarget("matrix", "Matrix", true),
        DeliveryTarget("telegram", "Telegram", false),
        DeliveryTarget("bot-chat:sy", "Bot Chat (sy)", true),
    )
    private val rooms = mapOf("!dAfUtHvghPuyaprEuB:silas.local" to "Ops")

    @Test
    fun `delivery values read as words`() {
        assertEquals("Save only", CronDelivery.label("local"))
        assertEquals("Save only", CronDelivery.label(""))
        assertEquals("Where it was set up", CronDelivery.label("origin"))
        assertEquals("Matrix home channel", CronDelivery.label("matrix", targets))
        assertEquals("Bot Chat (sy)", CronDelivery.label("bot-chat:sy", targets))
        assertEquals("Matrix · Ops", CronDelivery.label("matrix:!dAfUtHvghPuyaprEuB:silas.local", targets) { rooms[it] })
        // An unknown room is shortened, never dropped; a platform the list lacks is title-cased.
        assertEquals("Matrix · !YuIrzmUh…", CronDelivery.label("matrix:!YuIrzmUhspTUENmXZq:silas.local", targets))
        assertEquals("Discord home channel", CronDelivery.label("discord"))
        assertEquals("Save only + Matrix home channel", CronDelivery.label("local, matrix", targets))
    }

    @Test
    fun `the picker lists save-only first, the gateway's targets, the room, then the job's own value`() {
        val room = DeliveryOption("matrix:!abc:server", "This chat", "Each run posts here")
        val opts = CronDelivery.options(targets, "matrix:!dAfUtHvghPuyaprEuB:silas.local", room) { rooms[it] }
        assertEquals(
            listOf("local", "matrix", "telegram", "bot-chat:sy", "matrix:!abc:server", "matrix:!dAfUtHvghPuyaprEuB:silas.local"),
            opts.map { it.value },
        )
        assertEquals("Save only", opts.first().label)
        assertTrue(opts.first { it.value == "telegram" }.warning, "no home channel = nothing would arrive")
        assertFalse(opts.first { it.value == "matrix" }.warning)
        assertEquals("Matrix · Ops", opts.last().label)
    }

    @Test
    fun `without a gateway list the picker still keeps what the job has`() {
        val opts = CronDelivery.options(null, "origin")
        assertEquals(listOf("local", "origin"), opts.map { it.value })
        // A job already on save-only is not listed twice.
        assertEquals(listOf("local"), CronDelivery.options(null, "local").map { it.value })
        assertEquals(listOf("local"), CronDelivery.options(null, null).map { it.value })
    }

    // --- Form ---------------------------------------------------------------------------------

    private val job = CronJobDraft(
        name = "Daily Brief", prompt = "Brief me.", schedule = "0 7 * * *",
        deliver = "matrix:!dAfUtHvghPuyaprEuB:silas.local", model = "qwen3.8-flash-next", provider = "custom",
    )

    @Test
    fun `the form names its first problem in reading order`() {
        assertEquals("Give it a name", CronJobForm.problem(CronJobDraft()))
        assertEquals("Say what the agent does each run", CronJobForm.problem(CronJobDraft(name = "x")))
        assertEquals("Say when it runs", CronJobForm.problem(CronJobDraft(name = "x", prompt = "y")))
        assertNull(CronJobForm.problem(job))
        assertNotNull(CronJobForm.problem(job.copy(name = "n".repeat(201))))
        assertNotNull(CronJobForm.problem(job.copy(prompt = "p".repeat(5001))))
        // A script job runs no agent: no prompt is fine.
        assertNull(CronJobForm.problem(job.copy(prompt = "", scriptOnly = true)))
    }

    @Test
    fun `a new job sends every field, leaving a blank model to the gateway`() {
        val new = CronJobDraft(name = " Watch ", prompt = "Check it.", schedule = "every 30m", deliver = "")
        assertEquals(
            mapOf("name" to "Watch", "schedule" to "every 30m", "prompt" to "Check it.", "deliver" to "local"),
            CronJobForm.changes(null, new),
        )
        assertEquals("custom", CronJobForm.changes(null, job)["provider"])
    }

    @Test
    fun `an edit sends only what changed`() {
        assertEquals(emptyMap(), CronJobForm.changes(job, job.copy(name = "Daily Brief ")))
        assertEquals(mapOf("schedule" to "15 7 * * *"), CronJobForm.changes(job, job.copy(schedule = "15 7 * * *")))
        // An untouched one-shot is never handed back as display text.
        val once = job.copy(schedule = "once at 2026-10-03 08:00")
        assertFalse("schedule" in CronJobForm.changes(once, once.copy(prompt = "Brief me, briefly.")))
    }

    @Test
    fun `a model and its provider travel together, and clearing one clears both`() {
        assertEquals(
            mapOf("model" to "", "provider" to ""),
            CronJobForm.changes(job, job.copy(model = "", provider = "custom")),
        )
        assertEquals(
            mapOf("model" to "grok-4.5", "provider" to "xai"),
            CronJobForm.changes(job, job.copy(model = "grok-4.5", provider = "xai")),
        )
    }
}
