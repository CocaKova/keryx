package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A session's standing orders (2.16): the goal it is working toward, a loop re-asking on a
 * timer, a heartbeat. The gateway serves the snapshot over `session.control.read` and pushes
 * every change as `session.control.update` (hermes `tui_gateway/methods_session_control.py`
 * `_snapshot_control`); the shape is allowlisted there, so only these fields ever arrive.
 */
data class SessionControl(
    val goal: GoalControl? = null,
    val loop: LoopControl? = null,
    val heartbeat: HeartbeatControl? = null,
    /** Hash of the visible state; "" when nothing is set. Equal revisions = equal snapshots. */
    val revision: String = "",
) {
    val isEmpty: Boolean get() = goal == null && loop == null && heartbeat == null
}

data class GoalGate(
    val command: String,
    val attempts: Int = 0,
    val maxRetries: Int = 0,
    /** Null until the gate has run. */
    val lastExitCode: Int? = null,
) {
    val passed: Boolean get() = lastExitCode == 0
    val failed: Boolean get() = lastExitCode != null && lastExitCode != 0
}

data class GoalControl(
    val title: String,
    /** active | paused | done (cleared never arrives: the gateway sends no goal then). */
    val status: String,
    val turnsUsed: Int = 0,
    val maxTurns: Int = 0,
    val subgoals: List<String> = emptyList(),
    val gates: List<GoalGate> = emptyList(),
    /** done | blocked | continue | wait | skipped — the judge's last word. */
    val lastVerdict: String? = null,
    val lastReason: String? = null,
    val pausedReason: String? = null,
    /** Why it is parked, when the judge said wait. */
    val waitReason: String? = null,
) {
    val active: Boolean get() = status == "active"
    val paused: Boolean get() = status == "paused"
    val done: Boolean get() = status == "done"
}

data class LoopControl(
    val prompt: String,
    val status: String,
    val ticksFired: Int = 0,
    val maxTicks: Int? = null,
    val intervalSeconds: Double? = null,
) {
    val active: Boolean get() = status == "active"
}

data class HeartbeatControl(
    val prompt: String,
    val status: String,
    val fireCount: Int = 0,
    val intervalSeconds: Double? = null,
) {
    val active: Boolean get() = status == "active"
}

object SessionControls {

    /** The `control` object of a read reply or an update event; null when absent or malformed. */
    fun parse(control: JsonElement?): SessionControl? {
        val o = control as? JsonObject ?: return null
        return SessionControl(
            goal = (o["goal"] as? JsonObject)?.let(::goal),
            loop = (o["loop"] as? JsonObject)?.let(::loop),
            heartbeat = (o["heartbeat"] as? JsonObject)?.let(::heartbeat),
            revision = o.str("revision").orEmpty(),
        )
    }

    private fun goal(o: JsonObject): GoalControl? {
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: return null
        return GoalControl(
            title = title,
            status = o.str("status") ?: "active",
            turnsUsed = o.int("turns_used") ?: 0,
            maxTurns = o.int("max_turns") ?: 0,
            subgoals = (o["subgoals"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } },
            gates = (o["gates"] as? JsonArray).orEmpty().mapNotNull { g ->
                val go = g as? JsonObject ?: return@mapNotNull null
                GoalGate(
                    command = go.str("command") ?: return@mapNotNull null,
                    attempts = go.int("attempts") ?: 0,
                    maxRetries = go.int("max_retries") ?: 0,
                    lastExitCode = go.int("last_exit_code"),
                )
            },
            lastVerdict = o.str("last_verdict"),
            lastReason = o.str("last_reason"),
            pausedReason = o.str("paused_reason"),
            waitReason = (o["wait_barrier"] as? JsonObject)?.str("reason")?.takeIf { it.isNotBlank() },
        )
    }

    private fun loop(o: JsonObject) = LoopControl(
        prompt = o.str("prompt").orEmpty(),
        status = o.str("status") ?: "active",
        ticksFired = o.int("ticks_fired") ?: 0,
        maxTicks = o.int("max_ticks"),
        intervalSeconds = o.num("interval_seconds"),
    )

    private fun heartbeat(o: JsonObject) = HeartbeatControl(
        prompt = o.str("prompt").orEmpty(),
        status = o.str("status") ?: "active",
        fireCount = o.int("fire_count") ?: 0,
        intervalSeconds = o.num("interval_seconds"),
    )

    /**
     * The strip's one line: "◎ Ship the parser · turn 3/20 · gates 1/2 · paused". What is
     * known, in that order; the verdict stamps a finished goal.
     */
    fun headline(goal: GoalControl): String = buildList {
        add("◎ ${goal.title}")
        if (goal.maxTurns > 0) add("turn ${goal.turnsUsed}/${goal.maxTurns}")
        if (goal.gates.isNotEmpty()) add("gates ${goal.gates.count { it.passed }}/${goal.gates.size}")
        when {
            goal.done -> add("✓ done")
            goal.paused -> add("paused")
            goal.waitReason != null -> add("waiting")
            goal.lastVerdict == "blocked" -> add("blocked")
        }
    }.joinToString(" · ")

    /** The actions the strip offers for [goal]'s state: allowlisted `session.control` verbs. */
    fun actions(goal: GoalControl): List<Pair<String, String>> = buildList {
        if (goal.active) add("goal.pause" to "Pause")
        if (goal.paused) add("goal.resume" to "Resume")
        if (goal.waitReason != null) add("goal.unwait" to "Stop waiting")
        add("goal.clear" to "Clear")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun JsonObject.int(key: String): Int? = num(key)?.toInt()
}
