package chat.keryx.app.transport.direct

import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.BlockingKind
import chat.keryx.core.model.BlockingRequest
import chat.keryx.core.model.ShadePendingEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What the agent is waiting on the user for, per stored session: a command approval, or a
 * blocking question (clarify / sudo / secret). Split out of [DirectTransport] in 3.0.
 *
 * Since hermes ebe8cda8 (2026-09-13) a clarify / approval / sudo / secret is a JSON-RPC
 * request FROM the gateway (`{id:"srq-…", method, params}`), answered with a response frame
 * carrying that id, and the backend only sends them to a client that said
 * `client.capabilities {server_requests:true}` — otherwise the tool fails in the same
 * millisecond it is called. Keryx 2.13.5 and earlier did neither: it parsed only integer
 * ids, so the frame was dropped, and it never advertised, so the gateway never waited.
 * Sy's 09-24 session asked three times and got three empty answers, each back in 20 ms.
 */
internal class PendingAsks(private val rpc: () -> GatewayRpc?) {
    companion object {
        val LEGACY_EVENTS = setOf(
            "clarify.request", "sudo.request", "secret.request",
            "clarify.expire", "sudo.expire", "secret.expire", "approval.request",
        )
    }

    private val approvalFlows = ConcurrentHashMap<String, MutableStateFlow<ApprovalRequest?>>()
    private val blockingFlows = ConcurrentHashMap<String, MutableStateFlow<BlockingRequest?>>()

    /** Open batch clarifies, by request id: a batch is shown one question at a time. */
    private val batches = ConcurrentHashMap<String, ClarifyBatch>()

    // --- the shade's merged view -------------------------------------------------------
    // One map across all sessions so the notification layer never has to know which
    // per-session flows exist. Every write to the approval/blocking flows goes through
    // setApproval/setBlocking so the two views cannot drift; rotation moves entries to the
    // continuation id (responding to the DEAD parent id after a compaction fork would
    // resume a session the gateway forgot — the 0.5.2 lesson, now load-bearing here too).
    private val _shade = MutableStateFlow<Map<String, ShadePendingEntry>>(emptyMap())

    private fun approvalFlow(storedId: String) = approvalFlows.getOrPut(storedId) { MutableStateFlow(null) }
    private fun blockingFlow(storedId: String) = blockingFlows.getOrPut(storedId) { MutableStateFlow(null) }

    fun approval(storedId: String): StateFlow<ApprovalRequest?> = approvalFlow(storedId)
    fun blocking(storedId: String): StateFlow<BlockingRequest?> = blockingFlow(storedId)
    fun shade(): StateFlow<Map<String, ShadePendingEntry>> = _shade

    private fun updateShade(storedId: String, fn: (ShadePendingEntry) -> ShadePendingEntry) {
        _shade.value = _shade.value.toMutableMap().apply {
            val next = fn(this[storedId] ?: ShadePendingEntry())
            if (next.isEmpty) remove(storedId) else put(storedId, next)
        }
    }

    fun setApproval(storedId: String, value: ApprovalRequest?) {
        // The blanket clear runs on EVERY turn event (deltas included) — skip the map churn
        // when nothing changes, which is almost always.
        if (approvalFlow(storedId).value == value) return
        approvalFlow(storedId).value = value
        updateShade(storedId) { it.copy(approval = value) }
    }

    fun setBlocking(storedId: String, value: BlockingRequest?) {
        if (blockingFlow(storedId).value == value) return
        blockingFlow(storedId).value = value
        updateShade(storedId) { it.copy(blocking = value) }
    }

    /** `approval.cancelled`: every pending approval of [stored] was dropped, so its card goes
     *  whatever its id; [ids] catch a card filed under a stored id we have since rotated away from. */
    fun approvalsCancelled(stored: String?, ids: Set<String>) {
        for ((sid, flow) in approvalFlows) {
            val rid = flow.value?.requestId ?: continue
            if (sid == stored || rid in ids) setApproval(sid, null)
        }
    }

    /** The backend withdrew request [rid]: only the card wearing THAT id comes down. */
    fun withdraw(storedId: String, rid: String) {
        batches.remove(rid)
        if (blockingFlow(storedId).value?.requestId == rid) setBlocking(storedId, null)
        if (approvalFlow(storedId).value?.requestId == rid) setApproval(storedId, null)
    }

    /** A compaction forked [old] into [newStored]: the cards and the shade entry follow the fork. */
    fun rotate(old: String, newStored: String) {
        approvalFlows[old]?.let { approvalFlows.putIfAbsent(newStored, it) }
        blockingFlows[old]?.let { blockingFlows.putIfAbsent(newStored, it) }
        _shade.value[old]?.let { entry ->
            _shade.value = _shade.value.toMutableMap().apply { remove(old); put(newStored, entry) }
        }
    }

    /** The gateway was left (logout): nothing it asked is answerable any more. */
    fun clear() {
        _shade.value = emptyMap()
        approvalFlows.values.forEach { it.value = null }
        blockingFlows.values.forEach { it.value = null }
    }

    /**
     * The ask as an EVENT, from gateways before hermes ebe8cda8 (2026-09-13); answered with
     * `<kind>.respond` (see [respondBlocking]). Unlike approvals these carry a request_id and
     * can expire server-side, so they clear on their own `.expire` twin (or when the turn
     * moves on) — never on the blanket message.* and tool.* rule, which would wipe the card
     * mid-question.
     */
    fun onLegacyEvent(type: String, storedId: String, p: JsonObject?) {
        fun pStr(key: String) = (p?.get(key) as? JsonPrimitive)?.contentOrNull
        fun pList(key: String) = (p?.get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        when (type) {
            "clarify.request" -> setBlocking(
                storedId,
                BlockingRequest(
                    kind = BlockingKind.CLARIFY,
                    requestId = pStr("request_id") ?: "",
                    prompt = pStr("question") ?: "",
                    choices = pList("choices") ?: emptyList(),
                    multiSelect = pStr("multi_select") == "true",
                ),
            )
            "sudo.request" -> setBlocking(
                storedId,
                BlockingRequest(
                    kind = BlockingKind.SUDO,
                    requestId = pStr("request_id") ?: "",
                    prompt = "A command on the host needs your sudo password.",
                ),
            )
            "secret.request" -> setBlocking(
                storedId,
                BlockingRequest(
                    kind = BlockingKind.SECRET,
                    requestId = pStr("request_id") ?: "",
                    prompt = pStr("prompt") ?: "",
                    envVar = pStr("env_var") ?: "",
                ),
            )
            "clarify.expire", "sudo.expire", "secret.expire" -> {
                val rid = pStr("request_id")
                // Only the request that actually expired: a late twin must not clear a
                // newer question the agent has already asked in its place.
                if (rid != null && blockingFlow(storedId).value?.requestId == rid) setBlocking(storedId, null)
            }
            "approval.request" -> setApproval(
                storedId,
                ApprovalRequest(
                    command = pStr("command") ?: "",
                    description = pStr("description") ?: "",
                    choices = pList("choices")?.ifEmpty { null } ?: listOf("once", "deny"),
                ),
            )
        }
    }

    /** A request the gateway asked about [storedId] (already resolved from its live id). */
    fun onServerRequest(req: GatewayRpc.ServerRequest, storedId: String) {
        val p = req.params
        fun pStr(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull
        fun pList(key: String) = (p[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        when (req.method) {
            "clarify" -> {
                val batch = ClarifyBatch.fromParams(req.id, p)
                if (batch != null) {
                    val first = batch.current()
                    if (first == null) {
                        // Every question already locked (a replay after our own last lock raced
                        // the disconnect). Nothing left to show; the server resolves it.
                        batches.remove(req.id)
                    } else {
                        batches[req.id] = batch
                        setBlocking(storedId, first)
                    }
                } else {
                    val question = pStr("question").orEmpty()
                    if (question.isBlank()) { rpc()?.declineRequest(req.id, "empty clarify", GatewayRpc.INVALID_PARAMS); return }
                    val choices = pList("choices")
                    setBlocking(
                        storedId,
                        BlockingRequest(
                            kind = BlockingKind.CLARIFY,
                            requestId = req.id,
                            prompt = question,
                            choices = choices,
                            multiSelect = pStr("multi_select") == "true" && choices.isNotEmpty(),
                        ),
                    )
                }
            }
            "sudo" -> setBlocking(
                storedId,
                BlockingRequest(
                    kind = BlockingKind.SUDO,
                    requestId = req.id,
                    prompt = "A command on the host needs your sudo password.",
                ),
            )
            "secret" -> setBlocking(
                storedId,
                BlockingRequest(
                    kind = BlockingKind.SECRET,
                    requestId = req.id,
                    prompt = pStr("prompt").orEmpty(),
                    envVar = pStr("env_var").orEmpty(),
                ),
            )
            "approval" -> setApproval(
                storedId,
                ApprovalRequest(
                    command = pStr("command").orEmpty(),
                    description = pStr("description").orEmpty(),
                    choices = pList("choices").ifEmpty { listOf("once", "deny") },
                    requestId = req.id,
                ),
            )
            // Desktop-only bridges (terminal.read, preview.act, vault prompts, the tour): no
            // surface for them on a phone. Decline as "not shown here" — a Desktop window on
            // the same session still answers, and with none attached the tool fails at once.
            else -> rpc()?.declineRequest(req.id)
        }
    }

    /**
     * Answer whatever the agent is blocked on. A current gateway asked as a JSON-RPC request
     * (`srq-…` id): the answer is a response frame with that id — `{answer}` for one clarify
     * question, `{value}` for sudo / secret — or, for a batch clarify, one `clarify.lock` per
     * question, the last of which resolves the request. An older gateway asked with a
     * `<kind>.request` event and takes `<kind>.respond`, keyed off its own `request_id`.
     *
     * An empty [answer] is the wire's "skipped" either way, so Skip is a blank answer rather
     * than a silent drop. Answering late is safe: a settled request is ignored (`expired`).
     */
    suspend fun respondBlocking(
        sessionId: String,
        requestId: String,
        kind: BlockingKind,
        answer: String,
    ): Result<Unit> = runCatching {
        val rpc = rpc() ?: error("gateway not connected")
        val batch = batches[requestId]
        when {
            batch != null -> {
                val qid = batch.current()?.questionId ?: error("batch already answered")
                val res = rpc.request("clarify.lock", buildJsonObject {
                    put("request_id", JsonPrimitive(requestId))
                    put("question_id", JsonPrimitive(qid))
                    put("answer", JsonPrimitive(answer))
                })
                batch.lock(qid, answer)
                if ((res["status"] as? JsonPrimitive)?.contentOrNull == "expired") {
                    batches.remove(requestId)
                    setBlocking(sessionId, null)
                    return@runCatching
                }
                (res["remaining"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.let(batch::syncRemaining)
                val next = batch.current()
                if (next == null) batches.remove(requestId)
                setBlocking(sessionId, next)
            }
            requestId.startsWith("srq-") -> {
                val key = if (kind == BlockingKind.CLARIFY) "answer" else "value"
                if (!rpc.respondToRequest(requestId, buildJsonObject { put(key, JsonPrimitive(answer)) })) {
                    error("gateway socket send failed")
                }
                if (blockingFlow(sessionId).value?.requestId == requestId) setBlocking(sessionId, null)
            }
            else -> {
                rpc.request("${kind.wire}.respond", buildJsonObject {
                    put("request_id", JsonPrimitive(requestId))
                    put(kind.answerKey, JsonPrimitive(answer))
                })
                if (blockingFlow(sessionId).value?.requestId == requestId) setBlocking(sessionId, null)
            }
        }
    }
}
