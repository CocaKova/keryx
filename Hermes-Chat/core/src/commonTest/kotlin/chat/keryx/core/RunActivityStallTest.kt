package chat.keryx.core

import chat.keryx.core.model.RunActivities
import chat.keryx.core.model.RunActivity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client seals a run the gateway never will — a turn that dies with only a `❌` status line,
 * no `error`, no `message.complete` (a local model's rejected reasoning effort). The decision is
 * [RunActivities.isStalled]; these pin the exemptions that keep it off a turn that is merely quiet.
 */
class RunActivityStallTest {

    private val WINDOW = 240_000L
    private val now = 1_000_000L
    private fun run(running: List<RunActivity.RunningTool> = emptyList()) =
        RunActivity(sessionId = "s", startedAt = 0L, running = running)
    private val oneTool = listOf(RunActivity.RunningTool("t1", "terminal", "nmap"))

    @Test
    fun deadTurn_noToolNoAskPastWindow_isStalled() {
        val lastFrame = now - WINDOW - 1
        assertTrue(RunActivities.isStalled(run(), now, lastFrame, WINDOW, awaitingUser = false))
    }

    @Test
    fun longToolCall_isExempt() {
        // A tool still out is legitimately silent for minutes — never seal it.
        val lastFrame = now - WINDOW - 60_000
        assertFalse(RunActivities.isStalled(run(oneTool), now, lastFrame, WINDOW, awaitingUser = false))
    }

    @Test
    fun awaitingUser_isExempt() {
        // A bare approval gate with no tool out: waiting on a person, not stalled.
        val lastFrame = now - WINDOW - 60_000
        assertFalse(RunActivities.isStalled(run(), now, lastFrame, WINDOW, awaitingUser = true))
    }

    @Test
    fun recentFrame_withinWindow_isNotStalled() {
        // A long think keeps streaming reasoning deltas, which refresh the frame stamp.
        val lastFrame = now - (WINDOW / 2)
        assertFalse(RunActivities.isStalled(run(), now, lastFrame, WINDOW, awaitingUser = false))
    }

    @Test
    fun exactlyAtWindow_isNotStalled() {
        // Strictly greater-than, so the boundary tick does not seal.
        assertFalse(RunActivities.isStalled(run(), now, now - WINDOW, WINDOW, awaitingUser = false))
    }
}
