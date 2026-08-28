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
                ModelProvider(
                    slug = slug,
                    name = p.str("name").ifBlank { slug },
                    isCurrent = p.bool("is_current"),
                    authenticated = p.bool("authenticated"),
                    source = p.str("source"),
                    warning = p.str("warning"),
                    models = (p["models"] as? JsonArray)?.mapNotNull { m ->
                        val name = (m as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val c = caps?.get(name) as? JsonObject
                        ModelChoice(
                            name = name,
                            provider = slug,
                            fast = c?.bool("fast") ?: false,
                            reasoning = c?.bool("reasoning") ?: false,
                        )
                    } ?: emptyList(),
                )
            } ?: emptyList(),
        )

        private fun JsonObject.str(k: String): String =
            (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun JsonObject.bool(k: String): Boolean =
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
)

data class ModelChoice(
    val name: String,
    val provider: String,
    val fast: Boolean,
    val reasoning: Boolean,
)

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
