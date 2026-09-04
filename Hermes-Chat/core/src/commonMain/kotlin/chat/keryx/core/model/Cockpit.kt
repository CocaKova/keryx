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

    /** The token count the line names ("~123,456 tokens", "~92,000 tok"), when it names one. */
    val tokens: Long? get() = TOKENS.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()

    /** What the working banner says while this holds: the state, and the size of the job. */
    val headline: String get() = when {
        isCompacting -> tokens?.let { "Compressing context (~${compact(it)} tokens)" } ?: "Compressing context"
        else -> text
    }

    companion object {
        private val TOKENS = Regex("~\\s*([\\d,]+)\\s*tok")

        /**
         * A status off either wire, classified. The direct door re-tags only the one line that
         * carries `COMPACTION_STATUS_MARKER` ("Compacting context"); the pre-API / preflight /
         * retry / idle lines reach it still tagged `lifecycle`, and the side-channel classifies
         * on the gateway. Either way the app decides by the line itself when the tag is generic:
         * every routine compression template the agent emits opens with one of three glyphs.
         */
        fun of(kind: String, text: String): SessionStatus {
            val t = text.trim()
            val k = if (kind == "lifecycle" || kind == "status") {
                if (t.startsWith("📦") || t.startsWith("🗜") || t.startsWith("💤") ||
                    t.contains("Compacting context")
                ) "compacting" else kind
            } else kind
            return SessionStatus(k, t)
        }

        // No String.format: this is commonMain.
        private fun tenths(n: Long, unit: Long): String {
            val t = (n * 10 + unit / 2) / unit
            return if (t % 10 == 0L) "${t / 10}" else "${t / 10}.${t % 10}"
        }

        private fun compact(n: Long): String = when {
            n >= 1_000_000 -> "${tenths(n, 1_000_000)}M"
            n >= 10_000 -> "${(n + 500) / 1000}k"
            n >= 1_000 -> "${tenths(n, 1000)}k"
            else -> n.toString()
        }
    }
}

/**
 * How long the working banner keeps holding when the last thing the gateway said was that it
 * is compacting.
 *
 * A compaction is announced ONCE, at the moment it starts, and then runs for as long as the
 * summary model takes — up to three passes on a very large session, and there is no beat in
 * between to renew a timer with. Answering that single announcement with the ordinary
 * no-reply window is therefore a claim nobody made: 2.8.2 armed four minutes against a
 * compaction that ran for twelve, so the room sat there looking idle, with no banner and no
 * reply, for the eight minutes the gateway was still working.
 *
 * So the hold is open-ended. [CEILING_MS] is not an estimate of how long compaction takes and
 * must never be read as one — it is the point past which silence is better explained by a lost
 * "done" than by work still happening, so a dropped stream cannot strand the banner forever.
 */
object CompactionHold {
    const val CEILING_MS = 1_800_000L

    /** True while a compaction started at [since] should still veto the quiet timer at [now]. */
    fun holds(since: Long?, now: Long): Boolean = since != null && now - since < CEILING_MS

    /** Milliseconds left on the hold, or 0 once it has lapsed (or was never armed). */
    fun remaining(since: Long?, now: Long): Long =
        if (since == null) 0L else (since + CEILING_MS - now).coerceAtLeast(0L)
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
) {
    /**
     * The context window as (used, max) — or null until the gateway has said both. The gateway
     * itself only sends the pair from a real current-window reading (never a cumulative total,
     * never a post-compaction placeholder), so a half-reading here is "unknown", not "empty".
     */
    val contextGauge: Pair<Long, Long>?
        get() = if (contextUsed > 0L && contextMax > 0L) contextUsed to contextMax else null
}
