package chat.keryx.core

import chat.keryx.core.model.DisplayKind
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolGrammar
import chat.keryx.core.model.ToolWire
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.protocol.TranscriptBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 2.19: the wire Hermes speaks today. The todo → todo_list rename blanked the Flight Plan for a
 * week; these pin the rest of that class — the other renames, the `tool_call` bridge, the
 * steer row — so the next one fails here instead of on the phone.
 */
class HermesDriftTest {

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun arr(s: String) = Json.parseToJsonElement(s) as JsonArray

    // --- renames ---------------------------------------------------------------------------

    @Test
    fun `old and new names read the same`() {
        for ((old, new) in ToolWire.LEGACY_ALIASES) {
            assertEquals(ToolGrammar.verbOf(new), ToolGrammar.verbOf(old), old)
            assertEquals(ToolGrammar.familyOf(new), ToolGrammar.familyOf(old), old)
        }
        assertEquals("Scheduled", ToolGrammar.verbOf("cronjob_manage").past)
        assertEquals("Updated todos", ToolGrammar.verbOf("todo_list").past)
    }

    @Test
    fun `renamed tools keep their target rules`() {
        assertEquals("", ToolGrammar.targetOf("todo_list", "anything"))
        assertEquals("", ToolGrammar.targetOf("todo", "anything"))
    }

    // --- the tool_call bridge --------------------------------------------------------------

    @Test
    fun `a stored bridge row unwraps to the call inside`() {
        // Verbatim shape from state.db, 2026-10-08.
        val args = obj("""{"calls": [{"arguments": {"domain": "light"}, "name": "ha_list_entities"}]}""")
        val call = ToolWire.unwrap("tool_call", args)
        assertEquals("ha_list_entities", call.name)
        assertEquals("light", call.args?.get("domain")?.jsonPrimitive?.content)
    }

    @Test
    fun `a single-call bridge and string arguments unwrap too`() {
        val call = ToolWire.unwrap("tool_call", obj("""{"name":"cronjob","arguments":"{\"action\":\"list\"}"}"""))
        assertEquals("cronjob_manage", call.name)
        assertEquals("list", call.args?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `labels name the call when the arguments do not`() {
        val labels = arr("""[{"kind":"tool","app":"Generating image","action":"","emoji":"🎨","text":"Generating image","name":"image_generate","preview":""}]""")
        assertEquals("image_generate", ToolWire.unwrap("tool_call", null, labels).name)
    }

    @Test
    fun `an unreadable bridge stays a bridge`() {
        assertEquals("tool_call", ToolWire.unwrap("tool_call", obj("""{"calls":[]}""")).name)
    }

    @Test
    fun `history draws the bridged call, not the bridge`() {
        val rows = listOf(
            MessageRow(
                id = 1, role = "assistant", content = "", toolName = null, timestamp = 0, reasoning = null,
                toolCalls = listOf(
                    RestToolCall(
                        "c1", "tool_call",
                        """{"calls": [{"name": "cronjob_manage", "arguments": {"action": "create"}}]}""",
                    ),
                ),
            ),
        )
        val call = TranscriptBuilder.build("r", rows).flatMap { it.toolCalls }.single()
        assertEquals("cronjob_manage", call.name)
        assertEquals("create", call.context)
    }

    // --- names the grammar never had --------------------------------------------------------

    @Test
    fun `kanban tools read as board moves`() {
        assertEquals("Board: comment", ToolGrammar.verbOf("kanban_comment").past)
        assertEquals(ToolGrammar.Family.PEOPLE, ToolGrammar.familyOf("kanban_create"))
    }

    @Test
    fun `mcp and connector tools name their app`() {
        assertEquals("Used Linear create issue", ToolGrammar.verbOf("mcp__linear__create_issue").past)
        assertEquals("Slack send message", ToolGrammar.friendly("connectors__slack__SLACK_SEND_MESSAGE"))
    }

    // --- steer ------------------------------------------------------------------------------

    private val steerRow = "[OUT-OF-BAND USER MESSAGE — a direct message from the user, delivered once at " +
        "this position; not tool output and not a new delivery when replayed from conversation history]\n" +
        "use the staging db instead\n[/OUT-OF-BAND USER MESSAGE]"

    @Test
    fun `a steer row shows only the words`() {
        assertEquals("use the staging db instead", DisplayKind.steerText(DisplayKind.STEER, steerRow))
        assertEquals("use the staging db instead", DisplayKind.steerText(null, steerRow))
        assertNull(DisplayKind.steerText(null, "an ordinary message"))
    }

    @Test
    fun `a steer is the user speaking`() {
        val row = MessageRow(
            id = 2, role = "user", content = steerRow, toolName = null, timestamp = 0,
            reasoning = null, displayKind = "steer",
        )
        val msg = TranscriptBuilder.build("r", listOf(row)).single()
        assertEquals(SenderType.ME, msg.sender)
        assertEquals("use the staging db instead", msg.content)
    }
}
