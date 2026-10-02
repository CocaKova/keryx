package chat.keryx.core

import chat.keryx.core.model.HermesUpdateParser
import chat.keryx.core.model.UpdateActionStatus
import chat.keryx.core.model.UpdateObservation
import chat.keryx.core.model.UpdateOutcome
import chat.keryx.core.model.UpdatePhase
import chat.keryx.core.model.UpdatePlan
import chat.keryx.core.model.UpdatePlanner
import chat.keryx.core.model.UpdateReceipt
import chat.keryx.core.model.UpdateRoute
import chat.keryx.core.model.UpdateRun
import chat.keryx.core.model.UpdateWatch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payloads shaped as keryx-stream's `update_snapshot()`, the dashboard's
 * `check_hermes_update` / `get_action_status` / `get_update_receipt`, and a real receipt file
 * from `~/.hermes/logs/update_receipts/` (09-27, trimmed).
 */
class HermesUpdateTest {
    private fun j(s: String) = Json.parseToJsonElement(s)

    private val pluginJson = """
        {"supported": true, "reason": "", "behind": 12, "ahead": 0, "branch": "origin/main", "head": "26472756",
         "head_branch": "main", "version": "0.21.5", "command_configured": true, "label": "silas-update",
         "command_source": "configured", "checked_at": "2026-10-02T15:00:00+00:00", "checking": false,
         "check_error": "", "running": false, "probe_configured": true, "probe_label": "anchor gate",
         "probe_running": false, "probe_exit": null, "probe_output": "", "probe_at": ""}
    """.trimIndent()

    private val checkJson = """
        {"install_method": "git", "current_version": "0.21.5", "behind": 3, "update_available": true,
         "can_apply": true, "update_command": "hermes update", "message": null,
         "commits": [{"sha": "a1b2c3d", "summary": "fix: cron worker", "author": "teknium", "at": 1790940000},
                     {"sha": "e4f5a6b", "summary": "feat: receipts", "author": "x", "at": 1790930000}]}
    """.trimIndent()

    private val receiptJson = """
        {"receipt": {"schema": 1, "started_at": "2026-09-27T23:07:50.954811+00:00", "finished_at": "2026-09-27T23:08:44.157541+00:00",
          "outcome": "success", "pre_update": {"sha": "8afaab3703e336d72a72c812dd2dd249f04f166a", "short_sha": "8afaab37", "version": "0.21.5", "source": "git"},
          "post_update": {"sha": "26472756f1d8f65e714d6228f76b6816df87ea13", "short_sha": "26472756", "version": "0.21.5", "source": "git"},
          "steps": [{"name": "git_pull", "ok": true, "detail": ""}, {"name": "pm_sync", "ok": false, "detail": "lock busy"}],
          "skips": [{"name": "gateway_restart", "reason": "--no-gateway-restart: deferred, marker kept", "at": "x"}],
          "gateway_restart": {"failed_units": ["hermes-gateway.service"], "incomplete": true, "phase_error": ""}, "fleet": []},
         "summary": {"outcome": "success"}}
    """.trimIndent()

    private val plugin = HermesUpdateParser.plugin(j(pluginJson))!!
    private val check = HermesUpdateParser.check(j(checkJson))!!

    @Test
    fun plugin_readsCountCommandAndAnUnrunPreflight() {
        assertEquals(12, plugin.behind)
        assertEquals("26472756", plugin.head)
        assertTrue(plugin.operatorCommand)
        assertEquals("silas-update", plugin.label)
        assertNull(plugin.probeExit) // "not run yet" is not "passed"
        // A garbled count is unknown, never zero.
        val garbled = HermesUpdateParser.plugin(j("""{"supported": true, "behind": "lots"}"""))!!
        assertEquals(-1, garbled.behind)
        assertNull(HermesUpdateParser.plugin(j("""{"error": {"message": "nope"}}""")))
    }

    @Test
    fun check_readsTheCommitsThatWouldLand() {
        assertEquals(3, check.behind)
        assertTrue(check.canApply)
        assertEquals(listOf("fix: cron worker", "feat: receipts"), check.commits.map { it.summary })
        assertEquals(1790940000L, check.commits[0].atEpochS)
        val docker = HermesUpdateParser.check(j("""{"install_method": "docker", "current_version": "0.21.5", "behind": null,
            "update_available": false, "can_apply": false, "update_command": "", "message": "Pull the new image."}"""))!!
        assertNull(docker.behind)
        assertEquals("Pull the new image.", docker.message)
    }

    @Test
    fun receipt_readsFailuresSkipsAndRestartTrouble() {
        val r = HermesUpdateParser.receipt(j(receiptJson))!!
        assertEquals("success", r.outcome)
        assertEquals("26472756f1d8f65e714d6228f76b6816df87ea13", r.postSha)
        assertEquals(listOf("pm_sync — lock busy"), r.failedSteps)
        assertEquals(listOf("gateway_restart — --no-gateway-restart: deferred, marker kept"), r.skips)
        assertEquals(listOf("unit hermes-gateway.service didn't come back", "the restart phase didn't finish"), r.restartProblems)
        // The compact summary the action status carries.
        val s = HermesUpdateParser.receipt(j("""{"outcome": "failed", "started_at": "t1", "finished_at": "t2", "pre_sha": "a", "post_sha": "b", "post_version": "0.21.6", "fleet_states": []}"""))!!
        assertEquals("failed", s.outcome)
        assertEquals("0.21.6", s.postVersion)
        assertNull(HermesUpdateParser.receipt(j("""{"detail": "No update receipt found"}""")))
    }

    @Test
    fun action_andStartAnswers() {
        val a = HermesUpdateParser.action(j("""{"name": "hermes-update", "running": true, "exit_code": null, "pid": 4, "lines": ["Fetching…", "Pulling…"]}"""))!!
        assertTrue(a.running)
        assertEquals(listOf("Fetching…", "Pulling…"), a.lines)
        val refused = HermesUpdateParser.startAnswer(j("""{"ok": false, "pid": null, "name": "hermes-update", "error": "docker_update_unsupported", "message": "Pull the image.", "update_command": ""}"""))!!
        assertFalse(refused.ok)
        assertEquals("Pull the image.", refused.message)
        assertTrue(HermesUpdateParser.startAnswer(j("""{"ok": true, "pid": 9, "name": "hermes-update", "already_running": true}"""))!!.alreadyRunning)
    }

    @Test
    fun plan_theOperatorsWrapperWinsOverStockUpdate() {
        assertEquals(UpdatePlan(UpdateRoute.PLUGIN, "silas-update", true), UpdatePlanner.plan(plugin, check))
        val stockPlugin = plugin.copy(commandSource = "default", label = "hermes update")
        assertEquals(UpdatePlan(UpdateRoute.DASHBOARD, "hermes update", false), UpdatePlanner.plan(stockPlugin, check))
        // No dashboard (the Matrix door): the plugin's default command still updates.
        assertEquals(UpdatePlan(UpdateRoute.PLUGIN, "hermes update", false), UpdatePlanner.plan(stockPlugin, null))
        // Docker: nobody can, and the reason is the dashboard's own.
        val docker = check.copy(canApply = false, message = "Pull the new image.")
        assertNull(UpdatePlanner.plan(null, docker))
        assertEquals("Pull the new image.", UpdatePlanner.noPlanReason(null, docker))
        assertNull(UpdatePlanner.plan(plugin.copy(commandConfigured = false), null))
    }

    @Test
    fun behind_prefersTheFreshCountAndNeverCallsUnknownCurrent() {
        assertEquals(3, UpdatePlanner.behind(plugin, check)!!.count)
        val local = UpdatePlanner.behind(plugin, null)!!
        assertEquals(12, local.count)
        assertTrue(local.fromLocalRefs)
        assertEquals("12 commits behind", UpdatePlanner.behindLine(local))
        assertEquals("Behind by an unknown number of commits", UpdatePlanner.behindLine(local.copy(count = -1)))
        assertEquals("Couldn't reach the update source", UpdatePlanner.behindLine(UpdatePlanner.behind(null, check.copy(behind = null))))
        assertEquals("Up to date", UpdatePlanner.behindLine(local.copy(count = 0)))
        assertFalse(UpdatePlanner.worthOffering(local.copy(count = 0)))
        assertTrue(UpdatePlanner.worthOffering(local.copy(count = -1)))
    }

    // --- the watch ----------------------------------------------------------------------------

    private val oldReceipt = "2026-09-27T23:07:50+00:00"
    private val dashRun = UpdateRun(UpdatePlan(UpdateRoute.DASHBOARD, "hermes update", false), 0L, oldReceipt, "26472756")
    private val wrapperRun = UpdateRun(UpdatePlan(UpdateRoute.PLUGIN, "silas-update", true), 0L, oldReceipt, "26472756")

    private fun receipt(outcome: String, started: String = "2026-10-02T15:10:00+00:00", post: String = "99887766aabbccdd") =
        UpdateReceipt(outcome, started, "2026-10-02T15:12:00+00:00", "26472756ffff", post, "0.21.5", "0.21.6")

    @Test
    fun watch_showsTheLogWhileItRunsAndRestartingWhenTheGatewayIsDown() {
        val live = UpdateWatch.phase(dashRun, UpdateObservation(1_000, true, UpdateActionStatus(true, null, listOf("Pulling…"), null)), emptyList())
        assertEquals(UpdatePhase.Working(listOf("Pulling…"), restarting = false), live)
        val down = UpdateWatch.phase(dashRun, UpdateObservation(60_000, reachable = false), listOf("Pulling…"))
        assertEquals(UpdatePhase.Working(listOf("Pulling…"), restarting = true), down)
    }

    @Test
    fun watch_aNewReceiptIsTheVerdict() {
        val done = UpdateWatch.phase(dashRun, UpdateObservation(90_000, true, receipt = receipt("success")), emptyList())
        assertIs<UpdatePhase.Finished>(done)
        assertEquals(UpdateOutcome.SUCCESS, done.outcome)
        assertEquals("Updated 26472756 → 99887766 (0.21.6).", done.detail)
        val failed = UpdateWatch.phase(dashRun, UpdateObservation(90_000, true, receipt = receipt("failed")), emptyList())
        assertEquals(UpdateOutcome.FAILED, (failed as UpdatePhase.Finished).outcome)
        val refused = UpdateWatch.phase(dashRun, UpdateObservation(90_000, true, receipt = receipt("refused")), emptyList())
        assertEquals(UpdateOutcome.REFUSED, (refused as UpdatePhase.Finished).outcome)
    }

    @Test
    fun watch_theOldReceiptAndAStaleExitCodeAreNotThisRun() {
        // After the dashboard restarted, its status reads exit 0 off the PREVIOUS run's receipt.
        val stale = UpdateActionStatus(false, 0, emptyList(), receipt("success", started = oldReceipt))
        val p = UpdateWatch.phase(dashRun, UpdateObservation(30_000, true, stale, receipt("success", started = oldReceipt)), listOf("x"))
        assertIs<UpdatePhase.Working>(p)
        // A real exit with no receipt yet is still a verdict.
        val exited = UpdateWatch.phase(dashRun, UpdateObservation(30_000, true, UpdateActionStatus(false, 1, listOf("boom"), null)), emptyList())
        assertEquals(UpdateOutcome.FAILED, (exited as UpdatePhase.Finished).outcome)
    }

    @Test
    fun watch_aWrapperThatRolledBackIsNotASuccess() {
        val rolled = UpdateWatch.phase(
            wrapperRun,
            UpdateObservation(120_000, true, receipt = receipt("success"), pluginHead = "26472756"),
            emptyList(),
        )
        assertEquals(UpdateOutcome.ROLLED_BACK, (rolled as UpdatePhase.Finished).outcome)
        val kept = UpdateWatch.phase(
            wrapperRun,
            UpdateObservation(120_000, true, receipt = receipt("success"), pluginHead = "99887766"),
            emptyList(),
        )
        assertEquals(UpdateOutcome.SUCCESS, (kept as UpdatePhase.Finished).outcome)
        assertTrue("may still be verifying" in kept.detail)
    }

    @Test
    fun watch_withoutAReceiptAMovedHeadIsOnlyEvidence() {
        val moved = UpdateWatch.phase(wrapperRun, UpdateObservation(200_000, true, pluginHead = "99887766"), emptyList())
        assertEquals(UpdateOutcome.UNVERIFIED, (moved as UpdatePhase.Finished).outcome)
        assertTrue("26472756 to 99887766" in moved.detail)
    }

    @Test
    fun watch_givesUpHonestlyPastTheCeiling() {
        val p = UpdateWatch.phase(dashRun, UpdateObservation(UpdateWatch.CEILING_MS + 1, true), listOf("x"))
        assertEquals(UpdatePhase.Silent, p)
    }

    @Test
    fun text_readsPythonStampsAndSaysHowLongAgo() {
        val stamp = "2026-09-27T23:07:50.954811+00:00"
        val ms = chat.keryx.core.model.UpdateText.epochMs(stamp)!!
        assertEquals(1790550470954L, ms)
        assertEquals("just now", chat.keryx.core.model.UpdateText.ago(stamp, ms + 30_000))
        assertEquals("12 min ago", chat.keryx.core.model.UpdateText.ago(stamp, ms + 12 * 60_000))
        assertEquals("3 h ago", chat.keryx.core.model.UpdateText.ago(stamp, ms + 3 * 3_600_000))
        assertEquals("2 d ago", chat.keryx.core.model.UpdateText.ago("2026-09-27T23:07:50+00:00", ms + 2 * 86_400_000))
        assertNull(chat.keryx.core.model.UpdateText.ago("", ms))
        assertNull(chat.keryx.core.model.UpdateText.ago("yesterday", ms))
        assertEquals(UpdateOutcome.FAILED, chat.keryx.core.model.UpdateText.receiptOutcome("failed"))
        assertNull(chat.keryx.core.model.UpdateText.receiptOutcome("running"))
    }
}
