package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Hermes update from the phone (2.16). Two servers know about updates and each knows something
 * the other doesn't:
 *
 *  - the keryx-stream plugin (`/keryx/update`, on the Hermes Link base): the checkout's own head
 *    and branch, the count against refs already on disk, and the OPERATOR's update command and
 *    preflight from config.yaml (`keryx.update.command` / `.probe`) — a wrapper that snapshots,
 *    verifies and rolls back, which a patched or watched install must use instead of a bare
 *    `hermes update`;
 *  - the dashboard (`/api/hermes/update*`, on the direct door): stock `hermes update`, a check
 *    that names the commits that would land, a live log, and the receipt every update run
 *    writes — the durable record of how it went, which survives the update restarting the very
 *    server you asked.
 *
 * Either may be missing (an older plugin, an older Hermes, the Matrix door has no dashboard);
 * each missing one hides its part of the panel.
 */

/** `GET /keryx/update` — local-only, never fetches. */
data class PluginUpdateStatus(
    val supported: Boolean,
    val reason: String,
    /** -1 = the count can't be trusted (shallow clone, no remote ref) — "unknown", never 0. */
    val behind: Int,
    val ahead: Int,
    val branch: String,
    val head: String,
    val headBranch: String,
    val version: String,
    val commandConfigured: Boolean,
    /** What the command is CALLED; the command itself never leaves the gateway. */
    val label: String,
    /** "configured" = the operator's wrapper; "default" = Hermes' own `hermes update`. */
    val commandSource: String,
    /** When the refs were last fetched — the age of [behind], not of this read. */
    val checkedAt: String,
    val checking: Boolean,
    val checkError: String,
    val probeConfigured: Boolean,
    val probeLabel: String,
    val probeRunning: Boolean,
    /** Null until a preflight has run in this gateway's life: "not run" is not "passed". */
    val probeExit: Int?,
    val probeOutput: String,
    val probeAt: String,
) {
    val operatorCommand: Boolean get() = commandConfigured && commandSource == "configured"
}

data class UpdateCommit(val sha: String, val summary: String, val author: String, val atEpochS: Long)

/** `GET /api/hermes/update/check`. */
data class UpdateCheck(
    val installMethod: String,
    val currentVersion: String,
    /** null = the check couldn't run; -1 = behind by an unknown count; 0 = latest. */
    val behind: Int?,
    val updateAvailable: Boolean,
    /** Only a git install updates in place; docker/nix/apt get [message] instead. */
    val canApply: Boolean,
    val updateCommand: String,
    val message: String?,
    val commits: List<UpdateCommit>,
)

/** One update run's receipt — the full one, or the summary the action status carries. */
data class UpdateReceipt(
    /** running · success · partial · failed · refused. */
    val outcome: String,
    val startedAt: String,
    val finishedAt: String?,
    val preSha: String,
    val postSha: String,
    val preVersion: String,
    val postVersion: String,
    /** "name — detail" of each step that did not succeed. */
    val failedSteps: List<String> = emptyList(),
    /** "name — reason" of each step the run chose to skip. */
    val skips: List<String> = emptyList(),
    /** What went wrong bringing the gateways back, in the receipt's words. */
    val restartProblems: List<String> = emptyList(),
) {
    val finished: Boolean get() = outcome != "running" && outcome.isNotBlank()
}

/** `GET /api/actions/hermes-update/status`. */
data class UpdateActionStatus(
    val running: Boolean,
    val exitCode: Int?,
    val lines: List<String>,
    val receipt: UpdateReceipt?,
)

/** `POST /api/hermes/update`: started (or already running), or refused with the reason. */
data class UpdateStartAnswer(val ok: Boolean, val alreadyRunning: Boolean, val message: String)

object HermesUpdateParser {

    fun plugin(el: JsonElement): PluginUpdateStatus? {
        val o = el as? JsonObject ?: return null
        if (o["supported"] == null && o["behind"] == null) return null
        return PluginUpdateStatus(
            supported = o.bool("supported"),
            reason = o.str("reason"),
            // Absent or garbled → -1 ("unknown"), never 0 ("up to date").
            behind = o.int("behind") ?: -1,
            ahead = o.int("ahead") ?: 0,
            branch = o.str("branch"),
            head = o.str("head"),
            headBranch = o.str("head_branch"),
            version = o.str("version"),
            commandConfigured = o.bool("command_configured"),
            label = o.str("label"),
            commandSource = o.str("command_source"),
            checkedAt = o.str("checked_at"),
            checking = o.bool("checking"),
            checkError = o.str("check_error"),
            probeConfigured = o.bool("probe_configured"),
            probeLabel = o.str("probe_label"),
            probeRunning = o.bool("probe_running"),
            probeExit = o.int("probe_exit"),
            probeOutput = o.str("probe_output"),
            probeAt = o.str("probe_at"),
        )
    }

    fun check(el: JsonElement): UpdateCheck? {
        val o = el as? JsonObject ?: return null
        if (o["install_method"] == null && o["current_version"] == null) return null
        return UpdateCheck(
            installMethod = o.str("install_method"),
            currentVersion = o.str("current_version"),
            behind = o.int("behind"),
            updateAvailable = o.bool("update_available"),
            canApply = o.bool("can_apply"),
            updateCommand = o.str("update_command"),
            message = o.str("message").takeIf { it.isNotBlank() },
            commits = (o["commits"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                UpdateCommit(
                    sha = co.str("sha"),
                    summary = co.str("summary"),
                    author = co.str("author"),
                    atEpochS = (co["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            },
        )
    }

    /**
     * A receipt in any of the three shapes the dashboard serves: the receipt route's
     * `{receipt, summary}`, a bare full receipt, or the compact summary (action status).
     */
    fun receipt(el: JsonElement?): UpdateReceipt? {
        val top = el as? JsonObject ?: return null
        val full = (top["receipt"] as? JsonObject) ?: top.takeIf { it["pre_update"] is JsonObject }
        if (full != null) {
            val pre = full["pre_update"] as? JsonObject
            val post = full["post_update"] as? JsonObject
            val restart = full["gateway_restart"] as? JsonObject
            return UpdateReceipt(
                outcome = full.str("outcome"),
                startedAt = full.str("started_at"),
                finishedAt = full.str("finished_at").takeIf { it.isNotBlank() },
                preSha = pre?.str("sha").orEmpty(),
                postSha = post?.str("sha").orEmpty(),
                preVersion = pre?.str("version").orEmpty(),
                postVersion = post?.str("version").orEmpty(),
                failedSteps = (full["steps"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    if ((so["ok"] as? JsonPrimitive)?.booleanOrNull != false) return@mapNotNull null
                    so.str("name") + so.str("detail").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                },
                skips = (full["skips"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val so = s as? JsonObject ?: return@mapNotNull null
                    so.str("name") + so.str("reason").takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                },
                restartProblems = buildList {
                    if (restart != null) {
                        (restart["failed_units"] as? JsonArray).orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .forEach { add("unit $it didn't come back") }
                        restart.str("phase_error").takeIf { it.isNotBlank() }?.let { add(it) }
                        if (restart.bool("incomplete")) add("the restart phase didn't finish")
                    }
                },
            )
        }
        if (top["outcome"] == null) return null
        return UpdateReceipt(
            outcome = top.str("outcome"),
            startedAt = top.str("started_at"),
            finishedAt = top.str("finished_at").takeIf { it.isNotBlank() },
            preSha = top.str("pre_sha"),
            postSha = top.str("post_sha"),
            preVersion = "",
            postVersion = top.str("post_version"),
        )
    }

    fun action(el: JsonElement): UpdateActionStatus? {
        val o = el as? JsonObject ?: return null
        if (o["running"] == null) return null
        return UpdateActionStatus(
            running = o.bool("running"),
            exitCode = o.int("exit_code"),
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            receipt = receipt(o["receipt"]),
        )
    }

    fun startAnswer(el: JsonElement): UpdateStartAnswer? {
        val o = el as? JsonObject ?: return null
        val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return UpdateStartAnswer(
            ok = ok,
            alreadyRunning = o.bool("already_running"),
            message = o.str("message").ifBlank { o.str("error") },
        )
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/** Which server runs the update. */
enum class UpdateRoute { DASHBOARD, PLUGIN }

/** What the Update button would do — said in full in the confirm before it does it. */
data class UpdatePlan(
    val route: UpdateRoute,
    /** The command's name as the gateway tells it ("hermes update", "silas-update"). */
    val command: String,
    /** The operator's own wrapper from config.yaml rather than stock `hermes update`. */
    val operatorWrapper: Boolean,
)

/** How far behind, and how much to believe it. */
data class BehindReading(
    /** Null = nobody could count; -1 = behind by an unknown number; 0 = current. */
    val count: Int?,
    /** Counted against refs already on the host (the plugin) — as old as [checkedAt]. */
    val fromLocalRefs: Boolean,
    val checkedAt: String,
)

object UpdatePlanner {

    /**
     * The button's route: the dashboard's in-place `hermes update` (receipts and a live log),
     * else the plugin's stock default command. Null = no way to update from here.
     */
    fun plan(plugin: PluginUpdateStatus?, check: UpdateCheck?): UpdatePlan? = when {
        // Vanilla only (Jonny, 2026-10-09): the button runs Hermes' own `hermes update`, never
        // an operator's wrapper from config.yaml (`keryx.update.command`). People who install
        // Keryx don't have anyone's private tooling, and the button must mean the same thing for them.
        check != null && check.canApply -> UpdatePlan(UpdateRoute.DASHBOARD, "hermes update", false)
        plugin != null && plugin.commandConfigured && plugin.commandSource == "default" ->
            UpdatePlan(UpdateRoute.PLUGIN, plugin.label.ifBlank { "hermes update" }, false)
        else -> null
    }

    /** Why there's no button, in the servers' own words where they gave some. */
    fun noPlanReason(plugin: PluginUpdateStatus?, check: UpdateCheck?): String =
        check?.message?.takeIf { check.canApply.not() }
            ?: plugin?.reason?.takeIf { it.isNotBlank() }
            ?: "This gateway doesn't offer an update from the phone — update it on the host."

    /**
     * The best count either server has. The dashboard's when it is a number (fresh from the
     * update source). When it only knows "behind, count unknown" (-1: the source names a newer
     * commit but not the distance — 977 commits on 2026-10-09) or couldn't reach the source
     * at all, the plugin's count against the host's refs fills in. A local 0 never overrides
     * the dashboard's -1, though: the dashboard has seen upstream move, so the local refs are stale.
     */
    fun behind(plugin: PluginUpdateStatus?, check: UpdateCheck?): BehindReading? {
        val dash = check?.behind
        val p = plugin?.takeIf { it.supported }
        val local = p?.behind
        val localReading = p?.let { BehindReading(it.behind, fromLocalRefs = true, checkedAt = it.checkedAt) }
        return when {
            dash != null && dash >= 0 -> BehindReading(dash, fromLocalRefs = false, checkedAt = "")
            dash == -1 && local != null && local > 0 -> localReading
            dash == -1 -> BehindReading(-1, fromLocalRefs = false, checkedAt = "")
            local != null && local >= 0 -> localReading
            localReading != null -> localReading
            check != null -> BehindReading(null, fromLocalRefs = false, checkedAt = "")
            else -> null
        }
    }

    /** One line for the count. A count nobody could take is never "up to date". */
    fun behindLine(b: BehindReading?): String = when (val n = b?.count) {
        null -> "Couldn't reach the update source"
        -1 -> "Behind by an unknown number of commits"
        0 -> "Up to date"
        1 -> "1 commit behind"
        else -> "$n commits behind"
    }

    /** Whether to offer the button at all: not when the count is a known zero. */
    fun worthOffering(b: BehindReading?): Boolean = b?.count != 0
}

enum class UpdateOutcome { SUCCESS, PARTIAL, FAILED, REFUSED, ROLLED_BACK, UNVERIFIED }

/** One update this phone started, and what was true just before it. */
data class UpdateRun(
    val plan: UpdatePlan,
    val startedAtMs: Long,
    /**
     * `started_at` of the newest receipt before this run: a receipt with any other stamp is this
     * run's — no comparing the phone's clock to the server's.
     */
    val baselineReceipt: String?,
    /** The checkout's short head before the run (plugin), to see a wrapper roll back. */
    val baselineHead: String?,
)

sealed interface UpdatePhase {
    /** Under way. [restarting] = the gateway isn't answering, which mid-update is the update. */
    data class Working(val lines: List<String>, val restarting: Boolean) : UpdatePhase
    data class Finished(val outcome: UpdateOutcome, val detail: String, val receipt: UpdateReceipt?) : UpdatePhase
    /** Past the ceiling with no verdict from anywhere. */
    data object Silent : UpdatePhase
}

/** What one poll saw. Nulls are "couldn't read it", not "it said no". */
data class UpdateObservation(
    val nowMs: Long,
    val reachable: Boolean,
    val action: UpdateActionStatus? = null,
    val receipt: UpdateReceipt? = null,
    val pluginHead: String? = null,
)

object UpdateWatch {
    /** A verified wrapper on a slow box takes minutes; past this, say so instead of spinning. */
    const val CEILING_MS = 20 * 60_000L

    fun phase(run: UpdateRun, obs: UpdateObservation, lastLines: List<String>): UpdatePhase {
        val receipt = obs.receipt?.takeIf { it.finished && it.startedAt.isNotBlank() && it.startedAt != run.baselineReceipt }
        if (receipt != null) return finishedFrom(run, receipt, obs.pluginHead)

        val action = obs.action
        if (action != null) {
            if (action.running) return UpdatePhase.Working(action.lines.ifEmpty { lastLines }, restarting = false)
            // An exit code after the dashboard restarted is read from the newest receipt or log
            // marker — which is the PREVIOUS run's until this one writes its own. Trust it only
            // when it can't be that.
            val stale = action.receipt?.startedAt?.let { it == run.baselineReceipt } ?: false
            val code = action.exitCode
            if (code != null && !stale) {
                return if (code == 0) UpdatePhase.Finished(UpdateOutcome.SUCCESS, "Finished cleanly.", null)
                else UpdatePhase.Finished(UpdateOutcome.FAILED, "Exited with code $code.", null)
            }
        }
        if (!obs.reachable) return UpdatePhase.Working(lastLines, restarting = true)

        // No receipt to read (the Matrix door has no dashboard): the checkout moving is the
        // only evidence there is — said as exactly that, never as "updated".
        val head = obs.pluginHead
        if (run.plan.route == UpdateRoute.PLUGIN && !head.isNullOrBlank() && !run.baselineHead.isNullOrBlank() &&
            head != run.baselineHead && obs.receipt == null
        ) {
            return UpdatePhase.Finished(
                UpdateOutcome.UNVERIFIED,
                "The host moved from ${run.baselineHead} to $head. Keryx can't read the update's receipt " +
                    "over this door; ${run.plan.command}'s own log on the host has the verdict.",
                null,
            )
        }
        if (obs.nowMs - run.startedAtMs > CEILING_MS) return UpdatePhase.Silent
        return UpdatePhase.Working(action?.lines?.ifEmpty { null } ?: lastLines, restarting = false)
    }

    private fun finishedFrom(run: UpdateRun, r: UpdateReceipt, head: String?): UpdatePhase.Finished {
        val to = r.postVersion.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val pre = r.preSha.take(8)
        val post = r.postSha.take(8)
        return when (r.outcome) {
            "success" -> {
                // A wrapper that verifies after Hermes updates can undo it: the receipt says
                // success, the checkout says otherwise. The checkout is what's running.
                if (run.plan.operatorWrapper && !head.isNullOrBlank() && r.postSha.isNotBlank() &&
                    !r.postSha.startsWith(head)
                ) {
                    UpdatePhase.Finished(
                        UpdateOutcome.ROLLED_BACK,
                        "Hermes reached $post, but the host is at $head now — ${run.plan.command} " +
                            "rolled it back. Its log on the host says why.",
                        r,
                    )
                } else if (pre.isNotBlank() && pre == post) {
                    UpdatePhase.Finished(UpdateOutcome.SUCCESS, "Nothing new to install — still at $post$to.", r)
                } else {
                    UpdatePhase.Finished(
                        UpdateOutcome.SUCCESS,
                        (if (pre.isNotBlank() && post.isNotBlank()) "Updated $pre → $post$to." else "Updated$to.") +
                            if (run.plan.operatorWrapper) " ${run.plan.command} may still be verifying." else "",
                        r,
                    )
                }
            }
            "partial" -> UpdatePhase.Finished(UpdateOutcome.PARTIAL, "Updated, but not every step went through.", r)
            "refused" -> UpdatePhase.Finished(UpdateOutcome.REFUSED, "Hermes refused to update. Nothing changed.", r)
            "failed" -> UpdatePhase.Finished(UpdateOutcome.FAILED, "The update failed.", r)
            else -> UpdatePhase.Finished(UpdateOutcome.UNVERIFIED, "The receipt says “${r.outcome}”.", r)
        }
    }
}

/** The panel's words for times and verdicts. */
object UpdateText {
    /** The servers stamp Python `isoformat()` ("…+00:00", microseconds and all). */
    fun epochMs(iso: String): Long? {
        val s = iso.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching { kotlinx.datetime.Instant.parse(s).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { kotlinx.datetime.Instant.parse(s.removeSuffix("+00:00") + "Z").toEpochMilliseconds() }.getOrNull()
    }

    /** "just now" · "12 min ago" · "3 h ago" · "4 d ago"; null when the stamp can't be read. */
    fun ago(iso: String, nowMs: Long): String? = epochMs(iso)?.let { agoMs(it, nowMs) }

    fun agoMs(ms: Long, nowMs: Long): String {
        val mins = ((nowMs - ms) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            else -> "${mins / (60 * 24)} d ago"
        }
    }

    fun outcomeTitle(o: UpdateOutcome): String = when (o) {
        UpdateOutcome.SUCCESS -> "Updated"
        UpdateOutcome.PARTIAL -> "Partly updated"
        UpdateOutcome.FAILED -> "The update failed"
        UpdateOutcome.REFUSED -> "Not started"
        UpdateOutcome.ROLLED_BACK -> "Rolled back"
        UpdateOutcome.UNVERIFIED -> "Finished, unverified"
    }

    /** A receipt's outcome word → the same vocabulary, for the "Last update" card. */
    fun receiptOutcome(outcome: String): UpdateOutcome? = when (outcome) {
        "success" -> UpdateOutcome.SUCCESS
        "partial" -> UpdateOutcome.PARTIAL
        "failed" -> UpdateOutcome.FAILED
        "refused" -> UpdateOutcome.REFUSED
        else -> null
    }
}
