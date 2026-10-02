package chat.keryx.core.protocol

import chat.keryx.core.model.DeliveryTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One run of one job, from the dashboard's `GET /api/cron/jobs/{id}/runs` (newest first). */
data class CronHistoryRun(
    val id: String,
    val title: String,
    val preview: String,
    /** Epoch millis. */
    val startedAt: Long,
    val endedAt: Long?,
    /** Still going: no end, and active in the last five minutes (the server's own rule). */
    val live: Boolean,
    val messageCount: Int,
    /**
     * A script-only fire (`source: cron_output`): an output document, no transcript to open.
     * These are the runs the session list never had — a `no_agent` job showed "no runs yet"
     * forever on the Runs page, however often it fired.
     */
    val scriptOutput: Boolean,
    /** The fire is known to have failed (the execution ledger's verdict on a script fire). */
    val failed: Boolean,
)

/** One slot of a blueprint form (`cron/blueprint_catalog.py` `blueprint_form_schema`). */
data class BlueprintField(
    val name: String,
    /** `time` (HH:MM, 24 h) · `enum` · `text` · `weekdays` (everyday / weekdays / weekends). */
    val type: String,
    val label: String,
    val default: String?,
    val options: List<String>,
    val optional: Boolean,
    /** False = [options] are suggestions (the deliver slot): any value is accepted. */
    val strict: Boolean,
    val help: String,
)

/** A ready-made job the gateway can fill and schedule (`GET /api/cron/blueprints`). */
data class CronBlueprint(
    val key: String,
    val title: String,
    val description: String,
    val category: String,
    /** The schedule in the gateway's words, e.g. "Every day at 08:00". */
    val scheduleHuman: String,
    val fields: List<BlueprintField>,
)

/**
 * The dashboard's cron routes (`hermes_cli/web_routers/cron.py`) → domain. Every reader answers
 * null when the body is not the shape it expects — an older dashboard without the route, or the
 * SPA's index.html served for a path the API does not know — so the caller hides the
 * affordance instead of drawing an empty one or looping on an error.
 */
object CronRestParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `{"targets": [{id, name, home_target_set, home_env_var}, …]}`. */
    fun deliveryTargets(body: String): List<DeliveryTarget>? {
        val arr = obj(body)?.get("targets") as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            DeliveryTarget(
                id = id,
                name = o.str("name")?.ifBlank { null } ?: id,
                homeSet = o.bool("home_target_set") ?: true,
            )
        }
    }

    /** `{"runs": [...], "limit": n}` — session rows and script-output rows, newest first. */
    fun runs(body: String): List<CronHistoryRun>? {
        val arr = obj(body)?.get("runs") as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.ifBlank { null } ?: return@mapNotNull null
            val script = o.str("source") == "cron_output" || id.startsWith("cron_output:")
            val title = o.str("title").orEmpty()
            val endReason = o.str("end_reason").orEmpty().lowercase()
            CronHistoryRun(
                id = id,
                title = title,
                preview = o.str("preview").orEmpty(),
                startedAt = o.epochMs("started_at") ?: 0L,
                endedAt = o.epochMs("ended_at"),
                live = o.bool("is_active") ?: false,
                messageCount = o.num("message_count")?.toInt() ?: 0,
                scriptOutput = script,
                // Script rows carry the ledger's verdict as their title's first word
                // ("FAILED · <error>"); a session row only says so through its end reason.
                failed = (script && title.startsWith("FAILED")) || endReason == "error" || endReason == "failed",
            )
        }
    }

    /** `{"blueprints": [{key, title, description, category, fields, scheduleHuman, …}]}`. */
    fun blueprints(body: String): List<CronBlueprint>? {
        val arr = obj(body)?.get("blueprints") as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.str("key")?.ifBlank { null } ?: return@mapNotNull null
            CronBlueprint(
                key = key,
                title = o.str("title")?.ifBlank { null } ?: key,
                description = o.str("description").orEmpty(),
                category = o.str("category").orEmpty(),
                scheduleHuman = o.str("scheduleHuman").orEmpty(),
                fields = (o["fields"] as? JsonArray)?.mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    BlueprintField(
                        name = fo.str("name")?.ifBlank { null } ?: return@mapNotNull null,
                        type = fo.str("type") ?: "text",
                        label = fo.str("label").orEmpty(),
                        default = fo.str("default"),
                        options = (fo["options"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                        optional = fo.bool("optional") ?: false,
                        strict = fo.bool("strict") ?: true,
                        help = fo.str("help").orEmpty(),
                    )
                }.orEmpty(),
            )
        }
    }

    /**
     * The server's own sentence out of a failed call's message. The dashboard REST client
     * reports `HTTP 400 for /api/cron/jobs — {"detail":"Invalid schedule …"}`; FastAPI puts the
     * reason in `detail` (a string, or a validation list whose first `msg` is the useful bit).
     * Falls back to the message as it came.
     */
    fun errorDetail(message: String?): String {
        val m = message.orEmpty()
        val body = m.substringAfter(" — ", "")
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        when (val d = o?.get("detail") ?: o?.get("error")) {
            is JsonPrimitive -> d.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            is JsonArray -> ((d.firstOrNull() as? JsonObject)?.get("msg") as? JsonPrimitive)
                ?.contentOrNull?.let { return it }
            is JsonObject -> (d["message"] as? JsonPrimitive)?.contentOrNull?.let { return it }
            else -> Unit
        }
        return m
    }

    /** The REST client's failure for a route the server does not have. */
    fun isMissingRoute(message: String?): Boolean =
        message.orEmpty().let { it.startsWith("HTTP 404 ") || it.startsWith("HTTP 405 ") }

    private fun obj(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.epochMs(k: String): Long? = num(k)?.let { (it * 1000).toLong() }
}

/**
 * The blueprint form's pure half: what a field starts at, what is wrong with a filled form, and
 * what the instantiate call sends. The gateway validates again (`fill_blueprint`) and its 422
 * names the field; this keeps the obvious ones from costing a round trip.
 */
object CronBlueprints {
    private val TIME = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

    fun initialValues(bp: CronBlueprint): Map<String, String> =
        bp.fields.associate { it.name to (it.default ?: "") }

    fun problem(bp: CronBlueprint, values: Map<String, String>): String? {
        for (f in bp.fields) {
            val v = values[f.name]?.trim().orEmpty()
            if (v.isEmpty()) {
                if (f.optional) continue
                return "${f.label.ifBlank { f.name }} is needed"
            }
            if (f.type == "time" && !TIME.matches(v)) return "${f.label.ifBlank { f.name }}: a 24-hour time like 08:00"
            if (f.type == "enum" && f.strict && f.options.isNotEmpty() && v !in f.options) {
                return "${f.label.ifBlank { f.name }}: one of ${f.options.joinToString(", ")}"
            }
        }
        return null
    }

    /** The `values` map the instantiate call sends: known slots only, blanks left out. */
    fun payload(bp: CronBlueprint, values: Map<String, String>): Map<String, String> =
        bp.fields.mapNotNull { f -> values[f.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { f.name to it } }.toMap()
}
