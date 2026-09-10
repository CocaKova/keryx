package chat.keryx.core.protocol

/**
 * A session's pulse as the gateway's session row reports it — enough to tell whether someone
 * OTHER than this phone is advancing the conversation.
 *
 * One gateway serves many doors (this app, the browser extension, the terminal, cron). A turn
 * that came in through another door runs in the gateway process and lands its rows in the
 * session store, but nothing pushes those rows at a phone that has the room open: the phone's
 * live events come from the runtime it attached, which is idle for a turn it did not start.
 * The 2026-09-07 symptom was notifications arriving for a session whose open room never moved.
 */
data class SessionPulse(
    /** Rows persisted for the session, every role. Grows per row as a turn proceeds. */
    val messageCount: Long,
    /** Epoch millis of the newest activity the gateway recorded. */
    val lastActiveMs: Long,
    /** The gateway stamped an end reason — nothing more is coming until someone speaks. */
    val ended: Boolean,
    /**
     * The gateway's mid-turn activity label is set (`last_activity_description`, e.g.
     * "running tool terminal"). It is stamped while a turn runs, heartbeat-refreshed at least
     * every 60s, and cleared when the turn exits — so a silent tool call still reads as work.
     */
    val working: Boolean = false,
)

/**
 * The follow decision, kept pure so it can be pinned by tests: given what the phone last saw
 * and what the gateway now reports, re-read the transcript or hold.
 *
 * Two rules matter more than the polling:
 *  - A turn THIS phone is running is not foreign. While the room is busy locally the count is
 *    ignored, and the first quiet pulse afterwards re-baselines instead of re-reading — the
 *    local runtime already rendered those rows, and a re-read would only churn the timeline.
 *  - A count that went DOWN (undo, in-place compaction) is a new baseline, never a re-read;
 *    the code paths that shrink a session re-read on their own.
 */
object ForeignFollow {
    /** The session moved within the last few minutes, or just grew: poll like a tail. */
    const val HOT_POLL_MS = 3_000L
    /** No pulse yet (first tick, or the fetch failed): try again soon, not aggressively. */
    const val WARM_POLL_MS = 8_000L
    /** Ended, or quiet for a while: a slow heartbeat so a revived session is still noticed. */
    const val COLD_POLL_MS = 20_000L
    /** How recent the gateway's last activity must be to count as "moving". */
    const val RECENT_MS = 5 * 60_000L

    data class State(
        /** The row count the phone's transcript reflects; negative = not yet baselined. */
        val seenCount: Long = -1L,
        /** The next quiet pulse must become the baseline rather than trigger a re-read. */
        val rebaseline: Boolean = true,
    )

    enum class Action { HOLD, REBASELINE, REHYDRATE }

    fun step(state: State, pulse: SessionPulse?, busyLocally: Boolean): Pair<State, Action> {
        if (busyLocally) return state.copy(rebaseline = true) to Action.HOLD
        if (pulse == null) return state to Action.HOLD
        if (state.rebaseline || state.seenCount < 0L || pulse.messageCount < state.seenCount) {
            return State(seenCount = pulse.messageCount, rebaseline = false) to Action.REBASELINE
        }
        if (pulse.messageCount > state.seenCount) {
            return State(seenCount = pulse.messageCount, rebaseline = false) to Action.REHYDRATE
        }
        return state to Action.HOLD
    }

    /**
     * Someone is working the session RIGHT NOW, as far as the gateway's row can say: the
     * mid-turn label is set, the session has not ended, and the heartbeat is not stale. The
     * staleness bound is what stops a label left behind by a crashed run from lighting a
     * banner forever — the gateway clears the label on every turn exit it lives to see, but
     * a process that died mid-tool never gets to.
     */
    fun isLive(pulse: SessionPulse?, nowMs: Long): Boolean =
        pulse != null && pulse.working && !pulse.ended && nowMs - pulse.lastActiveMs < RECENT_MS

    fun nextDelayMs(pulse: SessionPulse?, nowMs: Long, action: Action): Long = when {
        action == Action.REHYDRATE -> HOT_POLL_MS
        pulse == null -> WARM_POLL_MS
        pulse.ended -> COLD_POLL_MS
        isLive(pulse, nowMs) -> HOT_POLL_MS
        nowMs - pulse.lastActiveMs < RECENT_MS -> HOT_POLL_MS
        else -> COLD_POLL_MS
    }
}
