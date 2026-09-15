package chat.keryx.app

import chat.keryx.app.presentation.ui.components.isTelemetryMessage
import chat.keryx.app.presentation.ui.components.showsTelemetryRow
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Show telemetry" off hides plumbing (the runtime footer, check-ins) and nothing else. The
 * post-turn self-improvement review is telemetry-shaped — it must be, to reach the parser with
 * agent chrome on — but it is the agent's own learning record and, on the direct door, exists
 * only as an event: hidden once is lost. It shows under either setting (device-caught 2026-09-15).
 */
class TelemetryRowGateTest {

    private fun agent(content: String) =
        Message(id = "m", roomId = "r", sender = SenderType.HERMES, content = content, timestamp = 1L)

    private val review = agent("💾 Self-improvement review: 📝 Skill 'keryx-integration' patched: \"old\" → \"new\" · Memory ➕ hooks fire per API call")
    private val footer = agent("GLM-5.3-Flash-EXL3 · 42% · ~/workspace")
    private val checkin = agent("⏳ Working — iteration 5/90")

    @Test
    fun everyTelemetryRowShowsWhenTheToggleIsOn() {
        for (m in listOf(review, footer, checkin)) {
            assertTrue("classified as telemetry: ${m.content}", isTelemetryMessage(m))
            assertTrue(showsTelemetryRow(m, showTelemetry = true))
        }
    }

    @Test
    fun theToggleOffHidesPlumbingButNeverTheReview() {
        assertFalse(showsTelemetryRow(footer, showTelemetry = false))
        assertFalse(showsTelemetryRow(checkin, showTelemetry = false))
        assertTrue(showsTelemetryRow(review, showTelemetry = false))
    }
}
