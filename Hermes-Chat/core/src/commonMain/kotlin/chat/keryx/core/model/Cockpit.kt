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
    val headline: String get() = headline()

    /**
     * The banner line while the agent compacts. [typicalSeconds] is how long this brain's
     * compactions have taken before: a compaction is ONE summarizing call with no progress of
     * its own to report, so "usually ~40 s" is the honest version of a progress bar.
     *
     * 3.0: it says what is happening in plain words. "Compressing context (~92k tokens)" was the
     * one line a person waits on, and it spoke in tokens; the size is the chat-memory sheet's
     * small print now. [fallbackTokens] is kept for callers and no longer printed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun headline(fallbackTokens: Long? = null, typicalSeconds: Int? = null): String = when {
        isCompacting -> {
            val base = COMPACTING_LINE
            typicalSeconds?.takeIf { it > 0 }?.let { "$base · usually ~${duration(it)}" } ?: base
        }
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
         *
         * A warning or error is never progress, whatever its tag. The gateway re-tags any
         * lifecycle line containing "compress" as `compacting`, which catches the agent's
         * session-start notice ("⚠ Compression model … Auto-lowered this session's threshold …
         * so compression can run"). Taken at its tag, every new session opened on "Compressing
         * context" for a compaction that never ran, and no `ready` came to clear it.
         */
        fun of(kind: String, text: String): SessionStatus {
            val t = text.trim()
            if (kind == "compacting" || kind == "compressing") {
                if (t.startsWith("⚠") || t.startsWith("❌")) return SessionStatus("lifecycle", t)
            }
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

        private fun duration(seconds: Int): String = when {
            seconds < 90 -> "$seconds s"
            else -> "${(seconds + 30) / 60} min"
        }

        /** "92k", "2.5k", "1.2M": a token count at a glance. */
        fun tokenCount(n: Long): String = when {
            n >= 1_000_000 -> "${tenths(n, 1_000_000)}M"
            n >= 10_000 -> "${(n + 500) / 1000}k"
            n >= 1_000 -> "${tenths(n, 1000)}k"
            else -> n.toString()
        }

        /** What the banner says while the agent compacts (3.0). */
        const val COMPACTING_LINE = "Summarizing older messages so the chat can keep going"
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
    /** Batch clarify only: which question of the batch this card shows (`q0`, `q1`, …); the
     *  answer is locked per question (`clarify.lock`) and the last lock resolves the request. */
    val questionId: String = "",
    /** Batch clarify only: 1-based position and size, for the "2 of 4" line. 0 = not a batch. */
    val ordinal: Int = 0,
    val total: Int = 0,
)

enum class BlockingKind {
    /** `clarify` — the agent is asking you a question mid-task. */
    CLARIFY,

    /** `sudo` — a terminal command needs the host's sudo password. */
    SUDO,

    /** `secret` — a skill wants a credential stored in the gateway's env. */
    SECRET;

    /** Wire name. Current gateways ask as a JSON-RPC request with this `method` and take a
     *  response frame back; older ones emit `<wire>.request` and take `<wire>.respond`. */
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
    /** The server→client request id (`srq-…`) when the gateway asked as a JSON-RPC request;
     *  blank on the older `approval.request` event. Rides along on `approval.respond` so the
     *  gateway settles exactly this entry, and names the card a `request.cancel` withdraws. */
    val requestId: String = "",
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
    /**
     * The token count at which the gateway auto-compacts this session (`usage.compact_at`),
     * or 0 when it has not said. NOT a fraction of [contextMax]: per-model floors and the
     * summarizer's own window move it — measured 136,500 and then 182,000 on one brain in one
     * day, against a 327,680 window. Measured against the window, the ring read ~60% at the
     * moment a session compacted, which is why compaction always looked like it came from
     * nowhere (2.13.11).
     */
    val compactAt: Long = 0L,
    // ---- run numbers (2.16), folded by [SessionUsage.fold]. Null = the gateway never said,
    // which is not the same as zero: a gauge that reads 0 tok/s is a claim nobody made.
    /** Output tokens per second over the last calls (`usage.avg_tps`). */
    val avgTps: Double? = null,
    /** Prompt tokens served from the provider's prefix cache, % (`usage.cache_hit_pct`). */
    val cacheHitPct: Int? = null,
    /** Mean seconds per model call over the last calls (`usage.avg_latency_s`). */
    val avgLatencyS: Double? = null,
    /** Compactions this session's agent has run (`usage.compressions`). */
    val compressions: Int? = null,
    /** Cumulative session tokens: reasoning, output, input (`usage.reasoning/output/input`). */
    val reasoningTokens: Long? = null,
    val outputTokens: Long? = null,
    val inputTokens: Long? = null,
    /** Model calls this session (`usage.calls`). */
    val apiCalls: Int? = null,
    /** Spend in US dollars, when the provider prices it (`usage.cost_usd`). */
    val costUsd: Double? = null,
    /** Phone clock (epoch ms) of the last usage reading that carried [outputTokens]: with the
     *  raw cumulative count, the pair a real tokens-per-second figure is measured from. */
    val usageAtMs: Long? = null,
) {
    /**
     * The context window as (used, max) — or null until the gateway has said both. The gateway
     * itself only sends the pair from a real current-window reading (never a cumulative total,
     * never a post-compaction placeholder), so a half-reading here is "unknown", not "empty".
     */
    val contextGauge: Pair<Long, Long>?
        get() = if (contextUsed > 0L && contextMax > 0L) contextUsed to contextMax else null

    /** The trigger, when the gateway named one that fits inside the window it named. */
    val compactionTrigger: Long?
        get() = compactAt.takeIf { it > 0L && (contextMax <= 0L || it <= contextMax) }

    /**
     * A reading for a DARK gauge only (2.11.5). The ring is fed by `session.info` and
     * `message.complete`, which arrive at turn end — so a room opened in a fresh process, or
     * after a gateway restart, sat dark until the next turn completed. A seed (the resume ack's
     * usage, or the gateway's anchored `session.context_breakdown` figure) lights it; a gauge a
     * completed turn already lit keeps its own reading, and a half-reading is still no reading.
     */
    fun seedGauge(used: Long, max: Long, percent: Int = 0, model: String = "", compactAt: Long = 0L): SessionMeta =
        if (contextGauge != null || used <= 0L || max <= 0L) {
            // A lit gauge keeps its reading, but a trigger it never had is still news.
            if (this.compactAt <= 0L && compactAt > 0L) copy(compactAt = compactAt) else this
        } else copy(
            contextUsed = used, contextMax = max,
            contextPercent = if (percent > 0) percent else (used * 100 / max).toInt(),
            model = this.model.ifBlank { model },
            compactAt = if (compactAt > 0L) compactAt else this.compactAt,
        )
}

/**
 * How far a session is toward its next auto-compaction (2.13.11) — the ring's reading when the
 * gateway names its trigger. [fraction] is used/trigger, clamped to 0..1: a full ring means
 * "the next call compacts", not "the model's window is full". [left] is tokens to go (0 once
 * past the trigger, which happens: the check runs before the NEXT call, so a turn can overshoot).
 */
data class CompactionGauge(val used: Long, val trigger: Long) {
    val fraction: Float get() = if (trigger <= 0L) 0f else (used.toFloat() / trigger.toFloat()).coerceIn(0f, 1f)
    val left: Long get() = (trigger - used).coerceAtLeast(0L)

    companion object {
        /** From a (used, max) reading plus the trigger — null when either half is unknown. */
        fun of(used: Long, trigger: Long?): CompactionGauge? =
            if (used > 0L && trigger != null && trigger > 0L) CompactionGauge(used, trigger) else null

        /** The ring never drains past this while a compaction runs: the summary is not nothing. */
        const val DRAIN_FLOOR = 0.08f

        /**
         * The ring's fill while a compaction runs (2.16): [reading] drained linearly toward
         * [DRAIN_FLOOR] over [typicalSeconds] since [since] — the length this app has seen
         * compactions take. Past the typical length it holds at the floor (it is late, not done);
         * with no typical length it drains over [FALLBACK_SECONDS]. Not compacting: [reading].
         */
        fun drained(reading: Float, since: Long?, typicalSeconds: Int?, nowMs: Long): Float {
            if (since == null) return reading
            val span = ((typicalSeconds?.takeIf { it > 0 } ?: FALLBACK_SECONDS) * 1000L).toFloat()
            val p = ((nowMs - since).coerceAtLeast(0L) / span).coerceIn(0f, 1f)
            val floor = minOf(DRAIN_FLOOR, reading)
            return if (p >= 1f) floor else reading + (floor - reading) * p
        }

        const val FALLBACK_SECONDS = 60
    }
}

/**
 * The chat-memory readout (3.0) — how far the chat is from the point where the agent summarizes
 * older messages, said in replies, not tokens. The ring before this sat in the pill row from the
 * first message as a hairline arc that read like a loading spinner, and its tap said
 * "84k · 216k to compaction". Now it stays out of the way until the chat is half way to that
 * point, says "~12 left" from [SHOW_LEFT], and one tap explains it in a sentence.
 *
 * "Replies left" is an estimate from this chat's own growth per turn, and every line that uses
 * it says "about". Pure: no clock, no Compose.
 */
object ContextOutlook {
    /** Below this share of the way to the summary point the ring stays hidden. */
    const val SHOW_RING = 0.50f

    /** From this share on, the ring carries its short label. */
    const val SHOW_LEFT = 0.60f

    /** Readings kept to estimate growth per turn. */
    const val KEEP = 8

    /**
     * [readings] plus [used]: the same value again changes nothing, a lower one is a compaction
     * (or a new branch) and starts the history over, and only the last [KEEP] are kept.
     */
    fun record(readings: List<Long>, used: Long): List<Long> {
        if (used <= 0L) return readings
        val last = readings.lastOrNull()
        return when {
            last == used -> readings
            last != null && used < last -> listOf(used)
            else -> (readings + used).takeLast(KEEP)
        }
    }

    /**
     * About how many more replies fit before [left] tokens are used: [left] over the median
     * growth of the turns in [readings] (oldest first). Null with no growth to go on yet.
     */
    fun repliesLeft(left: Long, readings: List<Long>): Int? {
        val steps = readings.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
        if (steps.isEmpty()) return null
        val median = if (steps.size % 2 == 1) steps[steps.size / 2]
        else (steps[steps.size / 2 - 1] + steps[steps.size / 2]) / 2
        if (median <= 0L) return null
        return (left.coerceAtLeast(0L) / median).toInt().coerceAtMost(999)
    }

    /** Whether the ring shows at all, given how far along the way to the summary point it is. */
    fun showRing(fraction: Float, compacting: Boolean): Boolean = compacting || fraction >= SHOW_RING

    /** The ring's short label: null below [SHOW_LEFT], else "~12 left", "summary next" or "filling up". */
    fun shortLabel(fraction: Float, replies: Int?, left: Long): String? = when {
        fraction < SHOW_LEFT -> null
        left <= 0L || replies == 0 -> "summary next"
        replies == null -> "filling up"
        else -> "~$replies left"
    }

    /**
     * The sheet's sentence. [agent] names who summarizes ("the agent" when unknown); [hasTrigger]
     * = the gateway named its summary point (without it the only line is the model's window).
     */
    fun sentence(replies: Int?, left: Long, agent: String?, hasTrigger: Boolean): String {
        val who = agent?.takeIf { it.isNotBlank() } ?: "the agent"
        val then = "Then $who summarizes the older messages so the chat can keep going."
        if (!hasTrigger) return when {
            replies != null && replies > 0 -> "Room for about ${replyCount(replies)} before the chat is full."
            else -> "The chat fills up as you talk. This shows how much the model can still hold."
        }
        return when {
            left <= 0L || replies == 0 -> "The next reply starts a summary of the older messages, so the chat can keep going."
            replies == null -> "The chat fills up as you talk. $then"
            else -> "Room for about ${replyCount(replies)}. $then"
        }
    }

    private fun replyCount(n: Int): String = if (n == 1) "1 more reply" else "$n more replies"

    /** The small print: "84k of 300k before the summary · window 524k". */
    fun smallPrint(used: Long, trigger: Long, max: Long): String {
        val t = SessionStatus.tokenCount(used)
        return if (trigger > 0L) {
            "$t of ${SessionStatus.tokenCount(trigger)} before the summary" +
                (if (max > trigger) " · window ${SessionStatus.tokenCount(max)}" else "")
        } else "$t of ${SessionStatus.tokenCount(max)} the model can hold"
    }
}


/**
 * The context window, itemised (2.10): what is eating it, by category — system prompt, tools,
 * skills, memory, the conversation itself. The gateway's `session.context_breakdown`, the same
 * payload the Desktop's Context Usage popover reads. Categories arrive in the gateway's order
 * with their zero rows already dropped.
 */
data class ContextBreakdown(
    val categories: List<ContextCategory>,
    val used: Long,
    val max: Long,
    val percent: Int,
    val model: String,
) {
    val total: Long get() = categories.sumOf { it.tokens }.coerceAtLeast(1L)
}

data class ContextCategory(val id: String, val label: String, val tokens: Long)

/**
 * How long this phone has watched a brain's compactions take (2.13.11): the "usually ~40 s"
 * beside the banner. Measured on the phone, banner up to banner down, because that is the wait
 * the person actually sits through — and kept per model, because the same summary is a 40 s
 * call on one brain and minutes on another.
 */
object CompactionTimings {
    /** The shortest pause worth recording: under it the banner was a flicker, not a wait. */
    const val MIN_SECONDS = 3

    /** The longest: past it the banner outlived its compaction (a dropped `ready`), not a sample. */
    const val MAX_SECONDS = 30 * 60

    /** Samples kept per model. */
    const val KEEP = 5

    /** [samples] with [seconds] recorded, newest last, trimmed to [KEEP] — or unchanged when out of range. */
    fun record(samples: List<Int>, seconds: Int): List<Int> =
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) samples else (samples + seconds).takeLast(KEEP)

    /** The median sample, or null with none — one slow outlier must not become "usually". */
    fun typical(samples: List<Int>): Int? {
        if (samples.isEmpty()) return null
        val s = samples.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Wire form for a settings string: "41,38,52". Unparseable entries are dropped. */
    fun decode(raw: String?): List<Int> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.takeLast(KEEP)

    fun encode(samples: List<Int>): String = samples.joinToString(",")
}

/**
 * A session's compaction lineage, as the direct door sees it (2.13.11). Compaction ends a
 * session and continues it under a new id; the gateway follows that lineage itself on
 * `session.resume` and reports where it landed. These are the two decisions the transport makes
 * about it, pure so they can be pinned.
 */
object CompactionLineage {
    /**
     * The session a resume actually landed in, when it is not the one asked for — else null.
     * `session_key` first (the id the gateway's live session is keyed by), `resumed` as the
     * older name for the same thing.
     */
    fun rotatedTip(asked: String, sessionKey: String?, resumed: String?): String? =
        (sessionKey?.takeIf { it.isNotBlank() } ?: resumed?.takeIf { it.isNotBlank() })
            ?.takeIf { it != asked }

    /** Where [id] continues today through any number of compactions; a cycle stops, not spins. */
    fun follow(id: String, forward: (String) -> String?): String {
        var cur = id
        val seen = mutableSetOf(cur)
        while (true) {
            val next = forward(cur) ?: return cur
            if (!seen.add(next)) return cur
            cur = next
        }
    }
}
