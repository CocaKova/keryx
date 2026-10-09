package chat.keryx.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The tool names Hermes puts on the wire, brought to the one name Keryx draws.
 *
 * Two things moved under us (2.19):
 *  - Hermes renamed five tools on 2026-08-29 (`model_tools._LEGACY_TOOL_ALIASES`): `todo` →
 *    `todo_list`, `cronjob` → `cronjob_manage`, `process` → `process_manage`, `tour` →
 *    `gui_tour`, `tip` → `show_tip`. Old sessions still hold the old names, so both have to
 *    read the same; [canonical] folds the old one onto the new.
 *  - A stock config hides several tools (`session_search`, `image_generate`, `todo_list`,
 *    `process_manage`, `cronjob_manage`, `computer_use`, …) behind one `tool_call` tool. The
 *    live frame then says `name: "tool_call"` and carries `labels[]` naming the real tool; a
 *    stored row keeps the bridge name with the real call in its arguments. [unwrap] peels
 *    either back to the inner call, so the grammar sees `cronjob_manage`, not "tool call".
 */
object ToolWire {

    /** Hermes' own alias table, old name → current name. */
    val LEGACY_ALIASES: Map<String, String> = mapOf(
        "todo" to "todo_list",
        "cronjob" to "cronjob_manage",
        "process" to "process_manage",
        "tour" to "gui_tour",
        "tip" to "show_tip",
    )

    /** The tool that carries a deferred call (tools/tool_labels.py `BRIDGE_TOOL_NAMES`). */
    const val BRIDGE = "tool_call"

    fun canonical(name: String): String = LEGACY_ALIASES[name] ?: name

    /** A call as Keryx should draw it: the real tool, its own arguments. */
    data class Call(val name: String, val args: JsonObject?)

    /**
     * Peel the `tool_call` bridge. Anything else passes through with its name made canonical.
     * A bridge wrapping several calls is drawn as its first — the run is still one row on the
     * wire, and the first call is the one the label leads with.
     */
    fun unwrap(name: String, args: JsonObject?, labels: JsonArray? = null): Call {
        if (name != BRIDGE) return Call(canonical(name), args)
        val inner = firstCall(args)
        val innerName = inner?.str("name")
            ?: labels?.firstOrNull()?.let { (it as? JsonObject)?.str("name") }
        if (innerName.isNullOrBlank()) return Call(name, args)
        return Call(canonical(innerName), innerArgs(inner?.get("arguments")))
    }

    /** `{"calls":[{name, arguments}, …]}` or a single `{name, arguments}`. */
    private fun firstCall(args: JsonObject?): JsonObject? {
        args ?: return null
        val calls = args["calls"] as? JsonArray
        return if (calls != null) calls.firstOrNull() as? JsonObject else args
    }

    /** The inner arguments, as an object or as a JSON string some models emit instead. */
    private fun innerArgs(el: JsonElement?): JsonObject? = when (el) {
        is JsonObject -> el
        is JsonPrimitive -> el.contentOrNull?.let {
            runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        else -> null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
