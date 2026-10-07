package chat.keryx.core

import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.GroupChats
import chat.keryx.core.model.GroupLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupChatsTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val room = GroupChats.room(obj("""
        {"room_id":"room-1","name":"Team","authority_gateway_id":"install:x","authority_epoch":1,
         "revision":1,"created_at":1790000000.5,"updated_at":1790000100.25,"latest_seq":7,
         "members":[{"member_id":"juno","profile":"juno","handle":"juno","display_name":"Juno"},
                    {"member_id":"milo","profile":"milo","handle":"milo"}]}
    """))!!

    private fun ev(seq: Int, kind: String, payload: String, actor: String = "gateway") = GroupChats.event(obj(
        """{"room_id":"room-1","seq":$seq,"event_id":"e$seq","kind":"$kind","actor":{"kind":"$actor","id":"a"},
            "payload":$payload,"created_at":1790000000.0}"""
    ))!!

    @Test fun roomParsesMembersAndSecondsBecomeMillis() {
        assertEquals(listOf("Juno", "Milo"), room.members.map { it.label })
        assertEquals(1790000100250L, room.updatedAt)
        assertEquals(7L, room.latestSeq)
    }

    @Test fun transcriptShowsWordsAndFailuresButNotMachinery() {
        val events = listOf(
            ev(1, "room.created", "{}", "system"),
            ev(2, "message.user", """{"text":"hi all","thread_id":"t1"}""", "user"),
            ev(3, "turn.started", """{"member_id":"juno","task_id":"k1","thread_id":"t1"}"""),
            ev(4, "message.member", """{"member_id":"juno","task_id":"k1","thread_id":"t1","text":"Hello!"}""", "member"),
            ev(5, "message.member", """{"member_id":"milo","task_id":"k2","thread_id":"t1","text":"[SILENT]"}""", "member"),
            ev(6, "turn.failed", """{"member_id":"milo","task_id":"k2","thread_id":"t1","error":"bad key\nTraceback"}"""),
            ev(7, "room.activity", """{"status":"idle"}"""),
        )
        val lines = GroupChats.lines(events, room)
        assertEquals(4, lines.size)
        assertTrue(lines[1] is GroupLine.Mine)
        assertEquals("Juno", (lines[2] as GroupLine.Member).name)
        assertEquals("Milo hit an error — bad key", (lines[3] as GroupLine.Note).text)
        assertEquals("Juno: Hello!", GroupChats.preview(lines))
    }

    @Test fun aMemberIsWorkingUntilItsTurnEnds() {
        val started = listOf(
            ev(1, "turn.started", """{"member_id":"juno","task_id":"k1"}"""),
            ev(2, "turn.started", """{"member_id":"milo","task_id":"k2"}"""),
        )
        assertEquals(setOf("juno", "milo"), GroupChats.working(started))
        val settled = started + ev(3, "turn.settled", """{"member_id":"juno","task_id":"k1","passed":true}""")
        assertEquals(setOf("milo"), GroupChats.working(settled))
    }

    // The real 10-06 juno+milo room, trimmed: the gateway never logs turn.started, so a room is
    // "thinking" from the user's message until the room.activity that names it.
    private fun real(seq: Int, id: String, kind: String, payload: String) = GroupChats.event(obj(
        """{"room_id":"room-pmtxstukmrtlaf5cqdob","seq":$seq,"event_id":"$id","kind":"$kind",
            "actor":{"kind":"gateway","id":"install:aa49"},"payload":$payload,"created_at":1791338029.17517}"""
    ))!!
    private val ask = "user:89a1d3deda1e1480355743ee972ccc9412d9aece304fae6f65bf84896ffcdbad"

    @Test fun aRoomIsThinkingUntilTheGatewayClosesTheDiscussion() {
        val asked = listOf(
            real(1, ask, "message.user", """{"text":"@juno can you send a test ping to milo?","thread_id":"t"}"""),
        )
        val mid = asked + listOf(
            real(2, "dmessage:ae5e", "message.member",
                """{"discussion_event_id":"$ask","member_id":"juno","round_index":0,"task_id":"dtask:ae5e","text":"Hey @milo","thread_id":"t"}"""),
            real(3, "dterminal:ae5e", "turn.settled",
                """{"discussion_event_id":"$ask","member_id":"juno","passed":false,"round_index":0,"task_id":"dtask:ae5e","thread_id":"t"}"""),
        )
        val done = mid + real(10, "dactivity:$ask:max_rounds", "room.activity",
            """{"discussion_event_id":"$ask","reason_code":"max_rounds","status":"bounded","thread_id":"t"}""")
        assertTrue(GroupChats.working(mid).isEmpty()) // why the old indicator never showed
        assertTrue(GroupChats.discussionOpen(asked))
        assertTrue(GroupChats.discussionOpen(mid))
        assertTrue(!GroupChats.discussionOpen(done))
        assertTrue(!GroupChats.discussionOpen(mid + real(11, "stop", "room.stop_requested", "{}")))
        assertTrue(!GroupChats.discussionOpen(emptyList()))
    }

    @Test fun approvalsAndRetriesComeFromTheDriverStatus() {
        val d = GroupChats.driver(obj("""
            {"room":{},"driver_status":{"running":true,"working":true,"blocked":false,"counts":{},
             "pending_actions":[{"kind":"retry","task_id":"k9"},
               {"kind":"approval","member_id":"juno","task_id":"k1","execution_generation":2,
                "request_id":"r1","approval":{"command":"rm -rf build","choices":["once","deny"]}}],
             "peer_routes":[]}}
        """))
        assertEquals(listOf("k9"), d.retries)
        assertEquals("rm -rf build", d.approvals.single().description)
        assertEquals(2, d.approvals.single().executionGeneration)
    }

    @Test fun membersCarryTheBotHandleAndIdsAreGatewaySafe() {
        val m = GroupChats.membersFor(listOf(BotProfile(name = "default", isDefault = true), BotProfile(name = "juno", title = "Inbox Bot")))
        assertEquals("hermes", m[0]["handle"]!!.jsonPrimitive.content)
        assertEquals("Inbox Bot", m[1]["display_name"]!!.jsonPrimitive.content)
        assertTrue(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$").matches(GroupChats.newId("room")))
        assertTrue(GroupChats.canCreate(2) && GroupChats.canCreate(6) && !GroupChats.canCreate(1) && !GroupChats.canCreate(7))
    }
}
