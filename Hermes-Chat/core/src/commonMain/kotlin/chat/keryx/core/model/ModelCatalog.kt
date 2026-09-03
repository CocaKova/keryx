package chat.keryx.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The gateway's model catalog — `model.options` over RPC, `/api/model/options` over REST,
 * one payload shape on every door. [model]/[provider] are what answers right now (the
 * session's overlay when the call carried one, else the config's default).
 *
 * `is_current` can be true on MORE than one provider row at once (a saved-but-dead
 * `configured-current` row alongside the user-config row that actually serves) — so the
 * "current" question is asked of the model name, never of a single flag.
 */
data class ModelCatalog(
    val model: String,
    val provider: String,
    val providers: List<ModelProvider>,
) {
    /** Providers a phone can actually route to: authenticated, with at least one model. */
    val usable: List<ModelProvider>
        get() = providers.filter { it.authenticated && it.models.isNotEmpty() }

    fun isCurrent(choice: ModelChoice): Boolean = choice.name == model

    companion object {
        fun parse(o: JsonObject): ModelCatalog = ModelCatalog(
            model = o.str("model"),
            provider = o.str("provider"),
            providers = (o["providers"] as? JsonArray)?.mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                val slug = p.str("slug").ifBlank { return@mapNotNull null }
                val caps = p["capabilities"] as? JsonObject
                val pricing = p["pricing"] as? JsonObject
                val featured = (p["featured_models"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                val unavailable = (p["unavailable_models"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                ModelProvider(
                    slug = slug,
                    name = p.str("name").ifBlank { slug },
                    isCurrent = p.bool("is_current"),
                    authenticated = p.bool("authenticated"),
                    source = p.str("source"),
                    warning = p.str("warning"),
                    isUserDefined = p.bool("is_user_defined"),
                    apiUrl = p.str("api_url"),
                    authType = p.str("auth_type"),
                    totalModels = (p["total_models"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    freeTier = p.bool("free_tier"),
                    models = (p["models"] as? JsonArray)?.mapNotNull { m ->
                        val name = (m as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val c = caps?.get(name) as? JsonObject
                        ModelChoice(
                            name = name,
                            provider = slug,
                            fast = c?.bool("fast") ?: false,
                            reasoning = c?.bool("reasoning") ?: false,
                            canDisableReasoning = (c?.get("can_disable_reasoning") as? JsonPrimitive)?.booleanOrNull,
                            pricing = (pricing?.get(name) as? JsonObject)?.let(ModelPricing::parse),
                            featured = name in featured,
                            unavailable = name in unavailable,
                        )
                    } ?: emptyList(),
                )
            } ?: emptyList(),
        )

        internal fun JsonObject.str(k: String): String =
            (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

        internal fun JsonObject.bool(k: String): Boolean =
            (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    }
}

data class ModelProvider(
    val slug: String,
    val name: String,
    val isCurrent: Boolean,
    val authenticated: Boolean,
    /** `canonical` / `hermes` / `built-in` / `user-config` / `configured-current` / `virtual`. */
    val source: String,
    val warning: String,
    val models: List<ModelChoice>,
    /** A `providers:` config entry (a user's own endpoint) rather than a registry login. */
    val isUserDefined: Boolean = false,
    /** The endpoint a user-defined row talks to — the one fact that says "this machine". */
    val apiUrl: String = "",
    val authType: String = "",
    /** The row's full count when [models] is a shortlist of it. */
    val totalModels: Int = 0,
    /** Nous only: the account is free-tier, so [ModelChoice.unavailable] rows can't be picked. */
    val freeTier: Boolean = false,
)

data class ModelChoice(
    val name: String,
    val provider: String,
    val fast: Boolean,
    val reasoning: Boolean,
    /** Null = the catalog didn't say; false = reasoning is mandatory on this route. */
    val canDisableReasoning: Boolean? = null,
    val pricing: ModelPricing? = null,
    /** On an aggregator row: one of the lab's newest few (the gateway's shortlist). */
    val featured: Boolean = false,
    /** A paid route the current (free-tier) account cannot pick. */
    val unavailable: Boolean = false,
) {
    /** The vendor segment of a `vendor/model` id, or "" on a single-namespace provider. */
    val lab: String get() = name.substringBefore('/', "")

    /** The id without its vendor prefix — what a person calls the model. */
    val shortName: String get() = name.substringAfter('/')
}

/**
 * Per-million-token prices as the gateway pre-formats them (`"$3.00"`, `"free"`, `"?"`), so
 * every client shows the CLI picker's numbers. [free] = input and output both cost nothing.
 * Sale chrome ([discountPercent], [wasInput]/[wasOutput]) is Nous-Portal-only by design.
 */
data class ModelPricing(
    val input: String,
    val output: String,
    val cache: String? = null,
    val free: Boolean = false,
    val discountPercent: Int? = null,
    val wasInput: String? = null,
    val wasOutput: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): ModelPricing = with(ModelCatalog.Companion) {
            ModelPricing(
                input = o.str("input"),
                output = o.str("output"),
                cache = (o["cache"] as? JsonPrimitive)?.contentOrNull,
                free = o.bool("free"),
                discountPercent = (o["discount_percent"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt(),
                wasInput = (o["was_input"] as? JsonPrimitive)?.contentOrNull,
                wasOutput = (o["was_output"] as? JsonPrimitive)?.contentOrNull,
            )
        }

        /**
         * A price for a phone row: `"$3.00"` → `"$3"`, `"$0.15"` stays, `"$0.0018"` stays,
         * `"free"`/`"?"` pass through. The gateway keeps two decimals so a CLI column aligns;
         * a chip has no column to align.
         */
        fun compact(price: String): String {
            if (!price.startsWith("$")) return price
            val n = price.drop(1)
            if (!n.contains('.')) return price
            val trimmed = n.trimEnd('0').trimEnd('.')
            return "$" + trimmed.ifEmpty { "0" }
        }
    }
}

/**
 * What `config.set {key: "model"}` answered. Three honest outcomes: applied (scope says how
 * far), deferred (a turn is in flight — it lands at the next turn start), or a confirmation
 * the gateway wants before it will spend on an expensive model.
 */
data class ModelSwitchOutcome(
    val model: String,
    val scope: String,
    val deferred: Boolean,
    val confirmRequired: Boolean,
    val message: String,
) {
    companion object {
        fun parse(o: JsonObject): ModelSwitchOutcome {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
            return ModelSwitchOutcome(
                model = s("value"),
                scope = s("scope").ifBlank { "session" },
                deferred = b("deferred"),
                confirmRequired = b("confirm_required"),
                message = s("confirm_message").ifBlank { s("warning") },
            )
        }
    }
}
