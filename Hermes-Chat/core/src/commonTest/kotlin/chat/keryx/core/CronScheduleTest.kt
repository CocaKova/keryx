package chat.keryx.core

import chat.keryx.core.model.CronHumanize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `schedule` has one contract — "unparseable → the raw string (never lie, never hide)" — and
 * it is the Runs door's job cards that depend on it: one line per card, drawn inside a
 * composable, so anything this throws takes the whole surface with it rather than one row.
 */
class CronScheduleTest {

    @Test
    fun `an interval too big to be a number falls back to the raw expression`() {
        // The interval group is `(\d+)`, unbounded — and the old `.toLong()` threw
        // NumberFormatException instead of falling back.
        val absurd = "every 99999999999999999999m"
        assertEquals(absurd, CronHumanize.schedule(absurd))
        // A zero-length interval is not a schedule either; it used to read "every 0 days".
        assertEquals("every 0m", CronHumanize.schedule("every 0m"))
    }

    @Test
    fun `the intervals people actually schedule still read as words`() {
        assertEquals("every 30 min", CronHumanize.schedule("every 30m"))
        assertEquals("hourly", CronHumanize.schedule("every 60m"))
        assertEquals("every 6 h", CronHumanize.schedule("every 360m"))
        assertEquals("daily", CronHumanize.schedule("every 1440m"))
        assertEquals("every 14 days", CronHumanize.schedule("every 20160m"))
    }
}
