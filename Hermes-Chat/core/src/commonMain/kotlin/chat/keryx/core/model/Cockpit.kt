package chat.keryx.core.model

/** One gateway toolset (`tools.list`): a named family of tools with an enabled flag. */
data class ToolsetInfo(
    val name: String,
    val description: String,
    val toolCount: Int,
    val enabled: Boolean,
    val tools: List<String>,
)

/**
 * A gateway lifecycle status line (`status.update`): [kind] is the machine tag
 * ("compressing", "compacting", "ready", …) and [text] the gateway's own wording,
 * which already carries the useful detail ("⠋ compressing 42 messages (~92,000 tok)…").
 */
data class SessionStatus(val kind: String, val text: String) {
    /** True while context compaction is running — the one long op worth a progress bar. */
    val isCompacting: Boolean get() = kind == "compressing" || kind == "compacting"
}

/**
 * How much of a session's transcript is actually loaded.
 *
 * The gateway pages history (500 rows max per request) and Keryx opens on the NEWEST
 * page, so a long session always starts with more behind it. [hasMore] drives the
 * "show earlier" affordance at the top of the transcript; [loading] is its in-flight state.
 */
data class HistoryState(
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    /** Rows fetched so far — the "N loaded" the affordance shows on a deep session. */
    val loaded: Int = 0,
)

/**
 * A session the gateway's full-text index matched, and the line that matched it.
 *
 * [snippet] arrives with the query term wrapped in `>>>…<<<` — the server's own highlight
 * markers, kept intact so the UI can emphasize exactly what the index matched instead of
 * re-guessing it client-side.
 */
data class SessionSearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
    /** Which side of the conversation said the matching line. */
    val role: String,
    val lastActive: Long,
    val messageCount: Long,
)

/**
 * A request that has the agent STOPPED mid-turn waiting on a human — everything except
 * tool approvals, which predate this and keep their own shape ([ApprovalRequest]).
 *
 * All three answer through `<kind>.respond {request_id, <key>}` and all three can time out
 * server-side, announcing it with a `<kind>.expire` carrying the same [requestId]
 * (tui_gateway/server.py `_block`). A blank answer is a legitimate, meaningful reply: it is
 * how the gateway spells "skipped".
 */
data class BlockingRequest(
    val kind: BlockingKind,
    val requestId: String,
    /** Clarify: the question. Secret: the gateway's prompt line. Sudo: unused. */
    val prompt: String = "",
    /** Clarify only: offered answers. Empty = free text. */
    val choices: List<String> = emptyList(),
    /** Clarify only: the gateway hints that several choices may be picked. */
    val multiSelect: Boolean = false,
    /** Secret only: the env var the value will be stored as (e.g. `OPENROUTER_API_KEY`). */
    val envVar: String = "",
)

enum class BlockingKind {
    /** `clarify.request` — the agent is asking you a question mid-task. */
    CLARIFY,

    /** `sudo.request` — a terminal command needs the host's sudo password. */
    SUDO,

    /** `secret.request` — a skill wants a credential stored in the gateway's env. */
    SECRET;

    /** Wire prefix: the event is `<wire>.request`, the answer `<wire>.respond`. */
    val wire: String
        get() = when (this) {
            CLARIFY -> "clarify"
            SUDO -> "sudo"
            SECRET -> "secret"
        }

    /** The parameter the gateway reads the answer out of (`_respond`'s `key`). */
    val answerKey: String
        get() = when (this) {
            CLARIFY -> "answer"
            SUDO -> "password"
            SECRET -> "value"
        }

    /** True when the answer is a credential: mask the field, never echo it anywhere. */
    val isSecret: Boolean get() = this != CLARIFY
}

/** A pending tool approval (`approval.request` event). [choices] come from the gateway
 *  (subsets of once/session/always/deny); [command] arrives pre-redacted. */
data class ApprovalRequest(
    val command: String,
    val description: String,
    val choices: List<String>,
)

/** One scheduled job (`cron.manage {action: list}`), fields straight off the wire. */
data class CronJob(
    val jobId: String,
    val name: String,
    val schedule: String,
    val repeat: String,
    val deliver: String,
    val nextRunAt: String,
    val lastRunAt: String,
    val lastStatus: String,
    val lastDeliveryError: String?,
    val enabled: Boolean,
    val state: String,
    val promptPreview: String,
    val skills: List<String>,
)

/** One command from the gateway's own registry (`commands.catalog`) — core plus whatever
 *  plugins/skills registered. [takesArgs] means "fill the composer" instead of auto-send. */
data class GatewayCommand(
    val cmd: String,
    val description: String,
    val takesArgs: Boolean,
    val aliases: List<String> = emptyList(),
)


/** Live per-session runtime facts, fed by `session.info` / `message.complete` usage events.
 *  contextPercent is the meter — populated only from the gateway's real gauge, never derived. */
data class SessionMeta(
    val model: String = "",
    val contextPercent: Int = 0,
    val contextUsed: Long = 0L,
    val contextMax: Long = 0L,
    /** The reasoning level this session is actually running at ("" until the gateway says).
     *  `session.info` carries it, so a level chosen from desktop or the TUI lands here too. */
    val reasoningEffort: String = "",
)
