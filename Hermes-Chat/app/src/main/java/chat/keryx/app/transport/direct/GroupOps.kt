package chat.keryx.app.transport.direct

import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.GroupApproval
import chat.keryx.core.model.GroupChats
import chat.keryx.core.model.GroupRoom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Group chats (2.18) over the gateway's `groups.*` (split out of [DirectTransport] in 3.0). */
internal class GroupOps(private val gw: GatewayCalls) {
    private suspend fun groups(method: String, params: JsonObject, timeoutMs: Long = 30_000): JsonObject =
        gw.request(method, params, timeoutMs = timeoutMs)

    suspend fun rooms(): Result<List<GroupRoom>> = runCatching {
        GroupChats.rooms(groups("groups.list", buildJsonObject { put("limit", JsonPrimitive(100)) }))
    }

    suspend fun create(name: String, bots: List<BotProfile>): Result<GroupRoom> = runCatching {
        val res = groups("groups.create", buildJsonObject {
            put("room_id", JsonPrimitive(GroupChats.newId("room")))
            put("name", JsonPrimitive(name.trim()))
            put("members", JsonArray(GroupChats.membersFor(bots)))
        })
        (res["room"] as? JsonObject)?.let(GroupChats::room) ?: error("create returned no room")
    }

    suspend fun state(roomId: String) = runCatching {
        val res = groups("groups.state", buildJsonObject { put("room_id", JsonPrimitive(roomId)) })
        val room = (res["room"] as? JsonObject)?.let(GroupChats::room) ?: error("no such room")
        room to GroupChats.driver(res)
    }

    suspend fun log(roomId: String, sinceSeq: Long, limit: Int) = runCatching {
        GroupChats.page(groups("groups.log", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("since_seq", JsonPrimitive(sinceSeq))
            put("limit", JsonPrimitive(limit))
        }))
    }

    suspend fun send(roomId: String, text: String, threadId: String, clientEventId: String): Result<Unit> = runCatching {
        groups("groups.send", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("event_id", JsonPrimitive(clientEventId))
            put("payload", buildJsonObject {
                put("text", JsonPrimitive(text))
                put("thread_id", JsonPrimitive(threadId))
            })
        })
        Unit
    }

    suspend fun rename(roomId: String, name: String): Result<Unit> = runCatching {
        groups("groups.rename", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("event_id", JsonPrimitive(GroupChats.newId("rename")))
            put("name", JsonPrimitive(name.trim()))
        })
        Unit
    }

    suspend fun stop(roomId: String): Result<Unit> = runCatching {
        groups("groups.stop", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("cancel_id", JsonPrimitive(GroupChats.newId("keryx-stop")))
        })
        Unit
    }

    suspend fun disband(roomId: String): Result<Unit> = runCatching {
        groups("groups.disband", buildJsonObject { put("room_id", JsonPrimitive(roomId)) }, timeoutMs = 60_000)
        Unit
    }

    suspend fun approve(roomId: String, approval: GroupApproval, choice: String): Result<Unit> = runCatching {
        groups("groups.approve", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("member_id", JsonPrimitive(approval.memberId))
            put("task_id", JsonPrimitive(approval.taskId))
            put("execution_generation", JsonPrimitive(approval.executionGeneration))
            put("choice", JsonPrimitive(choice))
            put("request_id", JsonPrimitive(approval.requestId))
        })
        Unit
    }

    suspend fun retry(roomId: String, taskId: String): Result<Unit> = runCatching {
        groups("groups.retry", buildJsonObject {
            put("room_id", JsonPrimitive(roomId))
            put("task_id", JsonPrimitive(taskId))
        })
        Unit
    }
}
