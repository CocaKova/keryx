package chat.keryx.app

import chat.keryx.core.model.TodoPlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire shape is todo_tool.py's exact return: {"todos":[…], "summary":{…}}. */
class TodoPlanTest {

    private val live = """
        {"todos": [
            {"id": "1", "content": "Probe the wire", "status": "completed"},
            {"id": "2", "content": "Build the parser", "status": "in_progress"},
            {"id": "3", "content": "Pin the strip", "status": "pending"},
            {"id": "4", "content": "Skip the demo", "status": "cancelled"}
        ], "summary": {"total": 4, "pending": 1, "in_progress": 1, "completed": 1, "cancelled": 1}}
    """.trimIndent()

    @Test
    fun `the tool result parses whole`() {
        val plan = TodoPlanParser.parse(live)!!
        assertEquals(4, plan.total)
        assertEquals(2, plan.done) // completed + cancelled both count as knocked out
        assertEquals("Build the parser", plan.active?.content)
        assertFalse(plan.allDone)
    }

    @Test
    fun `all knocked out reads as done`() {
        val plan = TodoPlanParser.parse(
            """{"todos":[{"id":"1","content":"a","status":"completed"},
                {"id":"2","content":"b","status":"cancelled"}]}""",
        )!!
        assertTrue(plan.allDone)
        assertNull(plan.active)
    }

    @Test
    fun `an emptied list is a plan with no items, not a parse failure`() {
        val plan = TodoPlanParser.parse("""{"todos":[]}""")!!
        assertEquals(0, plan.total)
        assertFalse(plan.allDone) // nothing to be done OF — the strip hides instead
    }

    @Test
    fun `tool errors and junk never become a plan`() {
        assertNull(TodoPlanParser.parse("""{"error": "TodoStore not initialized"}"""))
        assertNull(TodoPlanParser.parse("not json"))
        assertNull(TodoPlanParser.parse(""))
    }

    @Test
    fun `the compaction re-injection header is machinery, not the human`() {
        assertTrue(
            TodoPlanParser.isTodoInjection(
                "[Your active task list was preserved across context compression]\n1. [ ] Probe",
            ),
        )
        assertFalse(TodoPlanParser.isTodoInjection("[CONTEXT COMPACTION — REFERENCE ONLY] …"))
        assertFalse(TodoPlanParser.isTodoInjection("Your active task list…"))
    }
}
