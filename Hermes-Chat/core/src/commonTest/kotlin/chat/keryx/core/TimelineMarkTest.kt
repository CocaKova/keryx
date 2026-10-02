package chat.keryx.core

import chat.keryx.core.model.DisplayKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.TimelineMark
import chat.keryx.core.model.TimelineMarks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * System rows drawn as dividers (2.16). The texts below are the gateway's own, copied from live
 * `state.db` rows on 2026-10-02 — the model-switch marker, a background process's exit, a skill
 * invocation, a cron brief — so a reworded upstream string breaks a test here, not the timeline.
 */
class TimelineMarkTest {

    private val switchToFlash = "[System: The active model for this chat has changed to qwen3.8-flash-next " +
        "via provider custom. From this point forward, use this runtime metadata when answering " +
        "questions about what model/provider is active.]"
    private val switchToGrok = "[System: The active model for this chat has changed to grok-4.5 " +
        "via provider xai-oauth. From this point forward, use this runtime metadata when answering " +
        "questions about what model/provider is active.]"

    @Test
    fun `a model switch names the model it switched to`() {
        val mark = TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToFlash)
        assertEquals("model → qwen3.8-flash-next", mark.label)
        assertEquals(DisplayKind.MODEL_SWITCH, mark.kind)
        // The label says everything the marker does; there is nothing to open.
        assertNull(mark.detail)
        assertEquals("qwen3.8-flash-next" to "custom", TimelineMarks.modelSwitch(switchToFlash))
    }

    @Test
    fun `the marker is recognised without a display_kind too`() {
        // A gateway older than the column still writes the same words.
        assertEquals("model → grok-4.5", TimelineMarks.of(null, switchToGrok).label)
        assertEquals(DisplayKind.MODEL_SWITCH, TimelineMarks.of(null, switchToGrok).kind)
    }

    @Test
    fun `a provider-less marker still names the model`() {
        val bare = "[System: The active model for this chat has changed to qwen3.8-27b. From this point forward, …]"
        assertEquals("qwen3.8-27b" to null, TimelineMarks.modelSwitch(bare))
    }

    @Test
    fun `a switch the gateway classed but did not word keeps its text behind the tap`() {
        // Live state.db 81894: a real question stored under display_kind model_switch. The
        // divider must not swallow it unread.
        val q = "What model are you running as right now? One line."
        val mark = TimelineMarks.of(DisplayKind.MODEL_SWITCH, q)
        assertEquals("model changed", mark.label)
        assertEquals(q, mark.detail)
    }

    @Test
    fun `personality markers say set or cleared`() {
        val set = "[System: The user has changed the assistant's personality. From this point forward, " +
            "adopt the following persona and respond accordingly: You are a pirate.]"
        val cleared = "[System: The user has cleared the personality overlay. From this point forward, " +
            "respond in your normal default style.]"
        TimelineMarks.of(DisplayKind.PERSONALITY_SWITCH, set).let {
            assertEquals("personality changed", it.label)
            assertEquals("You are a pirate.", it.detail)
        }
        TimelineMarks.of(DisplayKind.PERSONALITY_SWITCH, cleared).let {
            assertEquals("personality cleared", it.label)
            assertNull(it.detail)
        }
    }

    @Test
    fun `the gateway's own one-line summary is the label when it sent one`() {
        val body = "[IMPORTANT: Background process proc_dc14c4428850 exited (exit code 1).\nCommand: python3 x.py"
        val mark = TimelineMarks.of(
            DisplayKind.PROCESS_COMPLETE, body,
            displayText = "Background Process Failed (exit 1): python3 x.py",
        )
        assertEquals("Background Process Failed (exit 1): python3 x.py", mark.label)
        assertEquals(body, mark.detail)
    }

    @Test
    fun `canned phrases cover the kinds that have one`() {
        assertEquals("resumed interrupted turn", TimelineMarks.of(DisplayKind.AUTO_CONTINUE, "[System note: …]").label)
        assertEquals(
            "background process finished",
            TimelineMarks.of(DisplayKind.PROCESS_COMPLETE, "[IMPORTANT: Background process …]").label,
        )
    }

    @Test
    fun `a note reads as its first sentence`() {
        val note = "[IMPORTANT: Background process proc_6b5fbe8b0721 completed normally (exit code 0).\n" +
            "Command: systemctl --user start silas-brain-sync.service\nOutput:\n]"
        val mark = TimelineMarks.of(null, note)
        assertEquals("Background process proc_6b5fbe8b0721 completed normally (exit code 0)", mark.label)
        assertEquals(TimelineMarks.NOTE, mark.kind)
        assertEquals(note.trim(), mark.detail)
        val cron = "[IMPORTANT: You are running as a scheduled cron job. DELIVERY: Your final response will be " +
            "automatically delivered to the user — do NOT use send_message]"
        assertEquals("You are running as a scheduled cron job", TimelineMarks.of(null, cron).label)
    }

    @Test
    fun `a skill invocation names the skill, not its body`() {
        val skill = "[IMPORTANT: The user has invoked the \"intel-brief\" skill, indicating they want you to " +
            "follow its instructions. The full skill content is loaded below.]\n\n---\nname: intel-brief"
        val mark = TimelineMarks.of(null, skill)
        assertEquals("skill · intel-brief", mark.label)
        assertEquals(skill, mark.detail)
    }

    @Test
    fun `bracketed headers lose their brackets`() {
        assertEquals("ASYNC DELEGATION COMPLETE — deleg_3", TimelineMarks.headline("[ASYNC DELEGATION COMPLETE — deleg_3]\nA background…"))
        assertEquals("background watch · the build finished", TimelineMarks.headline("[background watch] the build finished"))
        assertEquals("system note", TimelineMarks.headline("   "))
    }

    @Test
    fun `a long first line is capped on one line`() {
        val label = TimelineMarks.headline("[System note: " + "word ".repeat(60) + "]")
        assertTrue(label.length <= TimelineMarks.LABEL_MAX)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun `a todo re-injection is the plan carried over`() {
        val mark = TimelineMarks.of(null, "[Your active task list was preserved across context compression]\n- [ ] one")
        assertEquals("task list carried over", mark.label)
    }

    // ── collapse ────────────────────────────────────────────────────────────────────────────

    private fun msg(id: String, mark: TimelineMark? = null, sender: SenderType = SenderType.SYSTEM) =
        Message(id = id, roomId = "r", sender = sender, content = mark?.label ?: id, timestamp = 0L, mark = mark)

    @Test
    fun `the same mark twice in a row is one divider, and the newest survives`() {
        // Live state.db 91015/91026: two identical switches to qwen3.8-27b, nothing between.
        val a = msg("1", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToFlash))
        val b = msg("2", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToFlash))
        assertEquals(listOf("2"), TimelineMarks.collapse(listOf(a, b)).map { it.id })
    }

    @Test
    fun `a switch nobody spoke under is overridden by the next`() {
        // Live 92365→92366: grok-4.5, then two seconds later flash-next. Only the last is true.
        val grok = msg("1", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToGrok))
        val flash = msg("2", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToFlash))
        val out = TimelineMarks.collapse(listOf(grok, flash))
        assertEquals(listOf("2"), out.map { it.id })
        assertEquals("model → qwen3.8-flash-next", out[0].mark?.label)
    }

    @Test
    fun `anything between two marks keeps both`() {
        val grok = msg("1", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToGrok))
        val said = msg("2", sender = SenderType.HERMES)
        val flash = msg("3", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToFlash))
        assertEquals(listOf("1", "2", "3"), TimelineMarks.collapse(listOf(grok, said, flash)).map { it.id })
    }

    @Test
    fun `different events side by side both stand`() {
        val switch = msg("1", TimelineMarks.of(DisplayKind.MODEL_SWITCH, switchToGrok))
        val resumed = msg("2", TimelineMarks.of(DisplayKind.AUTO_CONTINUE, "[System note: …]"))
        val note1 = msg("3", TimelineMarks.of(null, "[System note: one thing.]"))
        val note2 = msg("4", TimelineMarks.of(null, "[System note: another thing.]"))
        assertEquals(listOf("1", "2", "3", "4"), TimelineMarks.collapse(listOf(switch, resumed, note1, note2)).map { it.id })
    }
}
