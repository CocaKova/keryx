package chat.keryx.app.transport.demo

import chat.keryx.core.demo.DemoPages
import chat.keryx.core.demo.DemoScript
import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.BotProfile
import chat.keryx.core.model.BotRosterSnapshot
import chat.keryx.core.model.Message
import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.SessionMeta
import chat.keryx.core.model.TodoPlan
import chat.keryx.core.model.TypingState
import chat.keryx.core.transport.AgentInstruments
import chat.keryx.core.transport.ChatTransport
import chat.keryx.core.transport.GatewayCapabilities
import chat.keryx.core.transport.MatrixCapabilities
import chat.keryx.core.transport.SessionMetaSource
import chat.keryx.core.transport.SessionSearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The demo gateway (3.0 herald): the third door, for someone with no Hermes yet. It answers
 * from [DemoScript] on the phone, no network anywhere, and wears the direct door's shape (a
 * gateway, no Matrix) so the screen it draws is the one a real gateway draws and the tour can
 * point at all of it.
 *
 * What only a real agent can do fails in words that say so, never quietly: every [DemoOnly]
 * message is a sentence a toast can show as it is.
 *
 * One session is live: the importer's agent is mid-job with its plan up, stopped on an approval.
 * The visitor's answer plays the rest of the script, row by row, through the same instruments a
 * real gateway feeds ([AgentInstruments]): the plan ticks off, and the job ends on a page.
 */
class DemoTransport(private val scope: CoroutineScope) : ChatTransport, GatewayCapabilities, SessionMetaSource, AgentInstruments {

    /** What the demo can't do, in a sentence that says what would. */
    class DemoOnly(what: String) : Exception("Connect your gateway to $what.")

    private val bootedAt = System.currentTimeMillis()

    private val rooms = MutableStateFlow(DemoScript.rooms(bootedAt))
    private val transcripts = HashMap<String, MutableStateFlow<List<Message>>>()
    private val agentTyping = HashMap<String, MutableStateFlow<Boolean>>()
    private val busy = MutableStateFlow(setOf(DemoScript.RUNNING))
    private var serial = 0L

    /** Each session's script so far: its stored rows, then whatever the visitor's answers played. */
    private val scripted = HashMap<String, List<DemoScript.Stamped>>()

    /** The ids the script drew last time, so a redraw keeps what the visitor typed in between. */
    private val drawnIds = HashMap<String, Set<String>>()

    private val plans = HashMap<String, MutableStateFlow<TodoPlan?>>()
    private val approvals = HashMap<String, MutableStateFlow<ApprovalRequest?>>()

    private fun scriptOf(id: String): List<DemoScript.Stamped> = synchronized(scripted) {
        scripted.getOrPut(id) { DemoScript.stamped(id, bootedAt) }
    }

    private fun transcript(id: String) = synchronized(transcripts) {
        transcripts.getOrPut(id) {
            val built = DemoScript.build(id, scriptOf(id))
            synchronized(drawnIds) { drawnIds[id] = built.mapTo(HashSet()) { it.id } }
            MutableStateFlow(built)
        }
    }

    private fun planOf(id: String) = synchronized(plans) {
        plans.getOrPut(id) { MutableStateFlow(DemoScript.planOf(scriptOf(id).map { it.row })) }
    }

    private fun approvalOf(id: String) = synchronized(approvals) {
        approvals.getOrPut(id) { MutableStateFlow(DemoScript.sessions.firstOrNull { it.id == id }?.ask?.request) }
    }

    /** Draws [rows] as the session's script, keeping the visitor's own lines where they fell. */
    private fun redraw(id: String, rows: List<DemoScript.Stamped>, streaming: Boolean = false) {
        val built = DemoScript.build(id, rows).let { msgs ->
            if (!streaming || msgs.isEmpty()) msgs else msgs.dropLast(1) + msgs.last().copy(isStreaming = true)
        }
        val flow = transcript(id)
        val old = synchronized(drawnIds) { drawnIds.put(id, built.mapTo(HashSet()) { it.id }).orEmpty() }
        flow.value = (built + flow.value.filter { it.id !in old && built.none { b -> b.id == it.id } })
            .sortedBy { it.timestamp }
    }

    private fun typingOf(id: String) = synchronized(agentTyping) {
        agentTyping.getOrPut(id) { MutableStateFlow(false) }
    }

    private fun nextId(prefix: String) = synchronized(this) { "$prefix-${++serial}" }

    override fun isLoggedIn(): Flow<Boolean> = flowOf(true)
    override fun currentUserId(): Flow<String?> = flowOf("demo")
    /** Bot chats are rows the roster publishes (they never show in the drawer's list). */
    private val botRows = MutableStateFlow<List<RoomProfile>>(emptyList())

    override fun getRooms(): Flow<List<RoomProfile>> =
        combine(rooms, botRows) { sessions, bots -> sessions + bots.filter { b -> sessions.none { it.id == b.id } } }

    override fun getMessages(roomId: String, limit: Int): Flow<List<Message>> =
        transcript(roomId).map { it.takeLast(limit) }

    override suspend fun messagesAround(roomId: String, eventId: String, before: Int, after: Int): List<Message> {
        val all = transcript(roomId).value
        val at = all.indexOfFirst { it.id == eventId }.takeIf { it >= 0 } ?: return emptyList()
        return all.subList((at - before).coerceAtLeast(0), (at + after + 1).coerceAtMost(all.size))
    }

    /** Whatever is typed lands, and the demo agent answers it in a streamed reply. */
    override suspend fun sendMessage(roomId: String, content: String) {
        val flow = transcript(roomId)
        val now = System.currentTimeMillis()
        flow.value = flow.value + Message(nextId("me"), roomId, SenderType.ME, content, now)
        touch(roomId, now)
        scope.launch { answer(roomId, content) }
    }

    override suspend fun sendReply(roomId: String, content: String, replyToEventId: String) =
        sendMessage(roomId, content)

    private suspend fun answer(roomId: String, prompt: String) {
        val flow = transcript(roomId)
        val typing = typingOf(roomId)
        // A job already running there keeps running after the aside.
        val wasBusy = roomId in busy.value
        busy.value = busy.value + roomId
        typing.value = true
        delay(THINK_MS)
        val words = DemoScript.replyTo(prompt).split(' ')
        val id = nextId("reply")
        val at = System.currentTimeMillis()
        val said = StringBuilder()
        for ((i, word) in words.withIndex()) {
            if (i > 0) said.append(' ')
            said.append(word)
            val live = Message(id, roomId, SenderType.HERMES, said.toString(), at, senderName = DemoScript.AGENT, isStreaming = i < words.lastIndex)
            flow.value = flow.value.filterNot { it.id == id } + live
            delay(WORD_MS)
        }
        typing.value = false
        if (!wasBusy) busy.value = busy.value - roomId
        touch(roomId, System.currentTimeMillis())
    }

    // ---- the agent's instruments: the live job's plan and the approval it stopped on --------

    override fun todoPlan(sessionId: String): Flow<TodoPlan?> = planOf(sessionId)

    override fun pendingApproval(sessionId: String): Flow<ApprovalRequest?> = approvalOf(sessionId)

    /** The visitor's answer: the card goes, and the job carries on the way that answer leads. */
    override suspend fun respondApproval(sessionId: String, choice: String, expectedRequestId: String?): Result<Boolean> {
        val ask = DemoScript.sessions.firstOrNull { it.id == sessionId }?.ask ?: return Result.success(false)
        val card = approvalOf(sessionId)
        val open = card.value ?: return Result.success(false)
        if (!card.compareAndSet(open, null)) return Result.success(false)
        scope.launch { play(sessionId, ask.after(choice)) }
        return Result.success(true)
    }

    /** Plays [rows] into [id] as an agent would: a beat of work, then the row; prose streams. */
    private suspend fun play(id: String, rows: List<DemoScript.Row>) {
        val typing = typingOf(id)
        busy.value = busy.value + id
        for (row in rows) {
            typing.value = true
            delay(STEP_MS)
            val at = System.currentTimeMillis()
            val done = scriptOf(id)
            val prose = (row as? DemoScript.Row.Agent)?.text.orEmpty()
                .lines().filterNot { chat.keryx.core.model.MediaTags.hasTag(it) }.joinToString("\n").trim()
            if (row is DemoScript.Row.Agent && prose.isNotEmpty()) {
                val words = prose.split(' ')
                for (n in STREAM_CHUNK until words.size step STREAM_CHUNK) {
                    redraw(id, done + DemoScript.Stamped(row.copy(text = words.take(n).joinToString(" "), calls = emptyList()), at), streaming = true)
                    delay(WORD_MS * STREAM_CHUNK)
                }
            }
            val next = done + DemoScript.Stamped(row, at)
            synchronized(scripted) { scripted[id] = next }
            redraw(id, next)
            DemoScript.planOf(next.map { it.row })?.let { planOf(id).value = it }
        }
        typing.value = false
        busy.value = busy.value - id
        rooms.value = rooms.value.map { if (it.id == id) it.copy(isActive = false) else it }
        touch(id, System.currentTimeMillis())
    }

    private fun touch(roomId: String, at: Long) {
        rooms.value = rooms.value.map { if (it.id == roomId) it.copy(timestamp = at, unread = false) else it }
            .sortedByDescending { it.timestamp }
    }

    /** The pages the demo's agent wrote; nothing else here has bytes. */
    override suspend fun mediaBytes(roomId: String, eventId: String): ByteArray? =
        transcript(roomId).value.firstOrNull { it.id == eventId }?.mediaUrl
            ?.let(DemoPages::page)?.encodeToByteArray()

    override suspend fun sendAttachment(roomId: String, bytes: ByteArray, fileName: String, contentType: String, caption: String?) {
        throw DemoOnly("send files")
    }

    override suspend fun markRead(roomId: String, eventId: String) {
        rooms.value = rooms.value.map { if (it.id == roomId) it.copy(unread = false) else it }
    }

    override suspend fun react(roomId: String, eventId: String, emoji: String) = Unit
    override fun reactionsFlow(roomId: String, eventId: String): Flow<List<MessageReaction>> = flowOf(emptyList())

    override fun typing(roomId: String): Flow<TypingState> = typingOf(roomId).map { TypingState(agentTyping = it) }
    override suspend fun setTyping(roomId: String, typing: Boolean) = Unit
    override suspend fun logout() = Unit

    override val matrix: MatrixCapabilities? = null
    override val gateway: GatewayCapabilities = this

    // ---- the gateway's runtime facts: the composer's model pill and ring --------------------

    override fun sessionMeta(sessionId: String): Flow<SessionMeta> =
        flowOf(SessionMeta(model = MODEL, contextUsed = 18_400, contextMax = 200_000))

    // ---- session lifecycle: real on the phone, so the drawer's verbs all work ---------------

    override suspend fun createSession(title: String?, profile: BotProfile?): Result<String> {
        val id = nextId("demo-new")
        val now = System.currentTimeMillis()
        rooms.value = listOf(RoomProfile(id, title?.takeIf { it.isNotBlank() } ?: "New session", RoomType.DIRECT_MESSAGE, now)) + rooms.value
        transcript(id).value = emptyList()
        return Result.success(id)
    }

    override fun sessionProfile(sessionId: String): String? = null

    override suspend fun renameSession(sessionId: String, title: String): Result<Unit> {
        rooms.value = rooms.value.map { if (it.id == sessionId) it.copy(name = title) else it }
        return Result.success(Unit)
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        rooms.value = rooms.value.filterNot { it.id == sessionId }
        return Result.success(Unit)
    }

    private val archived = MutableStateFlow<List<RoomProfile>>(emptyList())

    override suspend fun archiveSession(sessionId: String): Result<Unit> {
        val row = rooms.value.firstOrNull { it.id == sessionId } ?: return Result.success(Unit)
        rooms.value = rooms.value - row
        archived.value = listOf(row) + archived.value
        return Result.success(Unit)
    }

    override suspend fun unarchiveSession(sessionId: String): Result<Unit> {
        val row = archived.value.firstOrNull { it.id == sessionId } ?: return Result.success(Unit)
        archived.value = archived.value - row
        rooms.value = (rooms.value + row).sortedByDescending { it.timestamp }
        return Result.success(Unit)
    }

    override suspend fun archivedSessions(): Result<List<RoomProfile>> = Result.success(archived.value)

    override suspend fun pinSession(sessionId: String, pinned: Boolean): Result<Unit> {
        rooms.value = rooms.value.map { if (it.id == sessionId) it.copy(pinned = pinned) else it }
        return Result.success(Unit)
    }

    override suspend fun markSessionRead(sessionId: String, read: Boolean): Result<Unit> {
        rooms.value = rooms.value.map { if (it.id == sessionId) it.copy(unread = !read) else it }
        return Result.success(Unit)
    }

    override suspend fun searchSessions(query: String, limit: Int): Result<List<SessionSearchHit>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        val hits = rooms.value.mapNotNull { room ->
            val line = transcript(room.id).value.firstOrNull { it.content.contains(q, ignoreCase = true) }
                ?: return@mapNotNull room.takeIf { it.name.contains(q, ignoreCase = true) }
                    ?.let { SessionSearchHit(it.id, it.name, "", "", it.timestamp) }
            val at = line.content.indexOf(q, ignoreCase = true)
            val snippet = line.content.substring(0, at) + ">>>" + line.content.substring(at, at + q.length) + "<<<" +
                line.content.substring(at + q.length)
            SessionSearchHit(room.id, room.name, snippet.take(160), if (line.sender == SenderType.ME) "user" else "assistant", room.timestamp)
        }
        return Result.success(hits.take(limit))
    }

    override suspend fun modelOptions(sessionId: String): Result<chat.keryx.core.model.ModelCatalog> =
        Result.failure(DemoOnly("choose its model"))

    override suspend fun selectModel(
        sessionId: String, model: String, provider: String?, confirm: Boolean, global: Boolean,
    ): Result<chat.keryx.core.model.ModelSwitchOutcome> = Result.failure(DemoOnly("choose its model"))

    // ---- projects: the gateway's folders; the demo has none ---------------------------------

    override suspend fun projectsTree(): Result<chat.keryx.core.model.ProjectsTree> = Result.failure(DemoOnly("use projects"))
    override suspend fun projectSessions(projectId: String): Result<chat.keryx.core.model.ProjectTreeNode?> = Result.success(null)
    override suspend fun projectsCatalog(): Result<chat.keryx.core.model.ProjectsCatalog> = Result.failure(DemoOnly("use projects"))
    override suspend fun createProject(name: String, folderPath: String?): Result<chat.keryx.core.model.ProjectInfo> = Result.failure(DemoOnly("make a project"))
    override suspend fun deleteProject(projectId: String): Result<Unit> = Result.failure(DemoOnly("use projects"))
    override suspend fun archiveProject(projectId: String, restore: Boolean): Result<Unit> = Result.failure(DemoOnly("use projects"))
    override suspend fun listFolders(query: String): Result<chat.keryx.core.model.FolderPage> = Result.failure(DemoOnly("browse its folders"))
    override suspend fun folderExists(path: String): Result<Boolean> = Result.failure(DemoOnly("browse its folders"))
    override suspend fun moveSessionToProject(sessionId: String, cwd: String): Result<Unit> = Result.failure(DemoOnly("use projects"))
    override suspend fun createSessionIn(title: String?, cwd: String): Result<String> = createSession(title, null)
    override fun adoptSession(sessionId: String, title: String) {
        if (botRows.value.none { it.id == sessionId } && rooms.value.none { it.id == sessionId }) {
            botRows.value = botRows.value + RoomProfile(sessionId, title, RoomType.DIRECT_MESSAGE, System.currentTimeMillis(), source = "bot")
        }
    }

    // ---- bots: a small roster to look at; talking to one needs a real agent -----------------

    override suspend fun botRoster(): Result<BotRosterSnapshot> = Result.success(
        BotRosterSnapshot(bots = BOTS, protocolEnabled = true, fetchedAt = System.currentTimeMillis()),
    )

    /** A bot's chat opens on its hello: who it is, and that a real one is a profile of its own. */
    override suspend fun openBotChat(bot: BotProfile, kickoff: Boolean): Result<chat.keryx.core.model.BotChatRef> {
        val id = "demo-bot-${bot.name}"
        val flow = transcript(id)
        if (flow.value.isEmpty()) {
            val hello = "Hi, I'm ${bot.label}. On a real gateway I'm a profile of my own, with my own memory, " +
                "skills and chats, and I can message the other bots. Connect yours and I'll answer for real."
            flow.value = listOf(Message("$id-hello", id, SenderType.HERMES, hello, System.currentTimeMillis(), senderName = bot.label))
        }
        val last = flow.value.last()
        return Result.success(
            chat.keryx.core.model.BotChatRef(id, id, preview = last.content, lastActive = last.timestamp, messageCount = flow.value.size.toLong()),
        )
    }

    override suspend fun retireBotChat(bot: BotProfile): Result<Unit> = Result.failure(DemoOnly("manage bots"))
    override suspend fun botSessions(bot: BotProfile): Result<chat.keryx.core.model.BotSessions> =
        Result.success(chat.keryx.core.model.BotSessions())
    override fun adoptProfileSession(sessionId: String, profile: String?, title: String) = Unit

    override suspend fun groupRooms(): Result<List<chat.keryx.core.model.GroupRoom>> = Result.success(emptyList())
    override suspend fun createGroup(name: String, bots: List<BotProfile>): Result<chat.keryx.core.model.GroupRoom> =
        Result.failure(DemoOnly("start a group chat"))
    override suspend fun groupState(roomId: String): Result<Pair<chat.keryx.core.model.GroupRoom, chat.keryx.core.model.GroupDriver>> =
        Result.failure(DemoOnly("open group chats"))
    override suspend fun groupLog(roomId: String, sinceSeq: Long, limit: Int): Result<chat.keryx.core.model.GroupLogPage> =
        Result.failure(DemoOnly("open group chats"))
    override suspend fun sendToGroup(roomId: String, text: String, threadId: String, clientEventId: String): Result<Unit> =
        Result.failure(DemoOnly("talk in group chats"))
    override suspend fun renameGroup(roomId: String, name: String): Result<Unit> = Result.failure(DemoOnly("manage group chats"))
    override suspend fun stopGroup(roomId: String): Result<Unit> = Result.failure(DemoOnly("manage group chats"))
    override suspend fun disbandGroup(roomId: String): Result<Unit> = Result.failure(DemoOnly("manage group chats"))
    override suspend fun approveInGroup(roomId: String, approval: chat.keryx.core.model.GroupApproval, choice: String): Result<Unit> =
        Result.failure(DemoOnly("answer approvals"))
    override suspend fun retryInGroup(roomId: String, taskId: String): Result<Unit> = Result.failure(DemoOnly("manage group chats"))

    override suspend fun configureBot(name: String, meta: kotlinx.serialization.json.JsonObject?, description: String?): Result<Unit> =
        Result.failure(DemoOnly("change bots"))
    override suspend fun createBot(name: String, description: String?, cloneFrom: String?): Result<Unit> =
        Result.failure(DemoOnly("make a bot"))
    override suspend fun botAvatar(name: String): Result<ByteArray?> = Result.success(null)
    override fun publishBotRows(rows: List<RoomProfile>) {
        botRows.value = rows
    }

    override fun busySessionIds(): Flow<Set<String>> = busy

    companion object {
        const val MODE = chat.keryx.app.data.repository.SettingsRepositoryImpl.DEMO_MODE

        /** What the demo's model pill says: a stand-in for whichever model the visitor runs. */
        const val MODEL = "your-model"

        private const val THINK_MS = 900L
        private const val WORD_MS = 45L

        /** A beat of work before each played row: long enough to see the plan tick. */
        private const val STEP_MS = 1_600L

        /** Words per streamed frame of a played row. */
        private const val STREAM_CHUNK = 3

        private val BOTS = listOf(
            BotProfile("default", title = DemoScript.AGENT, description = "Your main agent. Runs the jobs, keeps the memory.", model = MODEL, isDefault = true, managed = true),
            BotProfile("scout", title = "Scout", description = "Reads the web and comes back with sources.", model = MODEL, managed = true),
            BotProfile("quill", title = "Quill", description = "Writes and edits: posts, emails, docs.", model = MODEL, managed = true),
        )
    }
}
