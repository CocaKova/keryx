package chat.keryx.app.presentation

import chat.keryx.app.data.remote.HermesStreamClient.HubJob
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.core.model.CronJobDraft
import chat.keryx.core.model.CronJobForm
import chat.keryx.core.model.DeliveryTarget
import chat.keryx.core.protocol.CronBlueprint
import chat.keryx.core.protocol.CronBlueprints
import chat.keryx.core.protocol.CronHistoryRun
import chat.keryx.core.protocol.CronRestParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Runs editor's wire half (2.16): one place that creates and edits scheduled jobs, for the
 * Runs page and the Hub's Jobs spoke alike.
 *
 * Two surfaces serve jobs and they are not equal. Hermes Link (`/api/jobs`, the gateway's
 * api_server) creates and patches name, schedule, prompt and delivery — nothing else. The
 * dashboard (`/api/cron/…`, the direct door's REST leg) also takes a model, answers each job's
 * run history (script fires included — the session list never had those), lists the places a
 * report can go, and serves the blueprint catalog. So: the dashboard where the door has one and
 * its routes answer; Link otherwise. Every dashboard route is probed by being used — a 404, or a
 * body that is not the route's (an older dashboard's SPA page), hides its affordance quietly;
 * there is no error toast for a feature the gateway simply does not have.
 */
class RunsEditorDelegate(
    deps: GatewayDeps,
    private val hub: HubDelegate,
    /** The direct door's dashboard REST client; null on the Matrix door or while dialing. */
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val client = deps.client
    private val toast = deps.toast

    /** What this gateway's dashboard offers the editor. Nulls = not offered (hide, don't say). */
    data class Extras(
        /** The gateway's delivery targets; null = no list (the picker keeps what it can name). */
        val targets: List<DeliveryTarget>? = null,
        /** The blueprint catalog; null or empty = no "start from" row. */
        val blueprints: List<CronBlueprint>? = null,
        /** The dashboard's cron routes answer: saves go there, and a model can be pinned. */
        val dashboard: Boolean = false,
    )

    private val _extras = MutableStateFlow(Extras())
    val extras: StateFlow<Extras> = _extras.asStateFlow()
    private var probedAt = 0L
    private var probedFor: GatewayRest? = null

    /**
     * Ask the dashboard what it offers, at most every [PROBE_TTL_MS] per client (a gateway
     * switch or re-dial builds a new client, which re-probes). Two GETs, once per editor visit
     * at most — never polled.
     */
    fun probe(force: Boolean = false) {
        val r = rest()
        if (r == null) {
            _extras.value = Extras()
            probedFor = null
            return
        }
        val now = System.currentTimeMillis()
        if (!force && r === probedFor && now - probedAt < PROBE_TTL_MS) return
        probedFor = r
        probedAt = now
        scope.launch {
            val targets = r.call("GET", "/api/cron/delivery-targets").getOrNull()
                ?.let(CronRestParser::deliveryTargets)
            val blueprints = r.call("GET", "/api/cron/blueprints").getOrNull()
                ?.let(CronRestParser::blueprints)
            _extras.value = Extras(targets = targets, blueprints = blueprints, dashboard = targets != null)
        }
    }

    /**
     * A job's run history, newest first: agent sessions AND script-only fires. Null = the
     * dashboard has no history route here (the card's own run list still stands).
     */
    suspend fun history(jobId: String, limit: Int = 20): List<CronHistoryRun>? {
        val r = rest() ?: return null
        return r.call("GET", "/api/cron/jobs/${enc(jobId)}/runs?limit=$limit").getOrNull()
            ?.let(CronRestParser::runs)
    }

    /**
     * Create ([original] null) or edit a job. [onDone] gets true when the gateway agreed — the
     * sheet closes; false keeps it open with the form as typed, the gateway's reason toasted.
     */
    fun save(original: HubJob?, draft: CronJobDraft, onDone: (Boolean) -> Unit) {
        val before = original?.let { draftOf(it) }
        val fields = CronJobForm.changes(before, draft)
        if (original != null && fields.isEmpty()) {
            onDone(true)
            return
        }
        val r = rest()
        val useDashboard = r != null && _extras.value.dashboard
        val link = client()
        if (!useDashboard && link == null) {
            toast("Hermes Link is off — enable it in Settings")
            onDone(false)
            return
        }
        scope.launch {
            val result: Result<Unit> = if (useDashboard) {
                val body = buildJsonObject {
                    if (original == null) {
                        fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    } else {
                        put("updates", buildJsonObject {
                            // The dashboard clears an override with an explicit empty value.
                            fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        })
                    }
                }.toString()
                if (original == null) r!!.call("POST", "/api/cron/jobs", body).map { }
                else r!!.call("PUT", "/api/cron/jobs/${enc(original.id)}", body).map { }
            } else {
                // Link takes no model: a model change on this door is not offered by the form,
                // and anything else outside the api_server's whitelist is left out here too.
                val linkFields = fields.filterKeys { it in LINK_FIELDS }
                if (original == null) {
                    link!!.jobCreate(
                        fields["name"].orEmpty(), fields["schedule"].orEmpty(),
                        fields["prompt"].orEmpty(), fields["deliver"] ?: "local",
                    )
                } else if (linkFields.isEmpty()) {
                    Result.success(Unit)
                } else {
                    link!!.jobPatch(original.id, linkFields)
                }
            }
            result
                .onSuccess {
                    toast(if (original == null) "Job scheduled" else "Job updated")
                    hub.refreshJobs(); hub.refreshCron()
                    onDone(true)
                }
                .onFailure {
                    toast("Couldn't save: ${CronRestParser.errorDetail(it.message).take(120)}")
                    onDone(false)
                }
        }
    }

    /** Fill a blueprint and schedule it — the gateway builds the job (prompt, schedule, skills). */
    fun createFromBlueprint(bp: CronBlueprint, values: Map<String, String>, onDone: (Boolean) -> Unit) {
        val r = rest() ?: run { onDone(false); return }
        val body = buildJsonObject {
            put("blueprint", bp.key)
            put("values", buildJsonObject {
                CronBlueprints.payload(bp, values).forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        }.toString()
        scope.launch {
            r.call("POST", "/api/cron/blueprints/instantiate", body)
                .onSuccess {
                    toast("${bp.title} scheduled")
                    hub.refreshJobs(); hub.refreshCron()
                    onDone(true)
                }
                .onFailure {
                    toast("Couldn't schedule: ${CronRestParser.errorDetail(it.message).take(120)}")
                    onDone(false)
                }
        }
    }

    companion object {
        /** Re-ask the dashboard what it offers after this long — targets change when a platform
         *  is connected, not by the minute. */
        const val PROBE_TTL_MS = 10 * 60_000L

        /** What `PATCH /api/jobs/{id}` accepts of the editor's fields (`_UPDATE_ALLOWED_FIELDS`). */
        private val LINK_FIELDS = setOf("name", "schedule", "prompt", "deliver")

        /** A job row as the editor holds it — the baseline an edit is measured against. */
        fun draftOf(job: HubJob): CronJobDraft = CronJobDraft(
            name = job.name,
            prompt = job.prompt,
            schedule = job.scheduleDisplay,
            deliver = job.deliver.ifBlank { "local" },
            model = job.model,
            provider = job.provider,
            scriptOnly = job.scriptOnly,
        )

        private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
