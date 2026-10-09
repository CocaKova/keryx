package chat.keryx.app.transport.direct

import chat.keryx.app.data.remote.HermesStreamClient.GatewayCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads a `commands.catalog` answer into palette rows (2.19.1). Pure, so a test can hold it. */
internal object CommandCatalog {
    const val SKILL = "Skill"

    fun parse(res: JsonObject): List<GatewayCommand> {
        val categoryOf = HashMap<String, String>()
        (res["categories"] as? JsonArray)?.forEach { c ->
            val cat = c as? JsonObject ?: return@forEach
            val name = (cat["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (cat["pairs"] as? JsonArray)?.forEach { p ->
                ((p as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.let { categoryOf[it] = name }
            }
        }
        val skills = (res["skills"] as? JsonObject)?.keys.orEmpty()
        val aliasesOf = HashMap<String, MutableList<String>>()
        (res["canon"] as? JsonObject)?.forEach { (alias, target) ->
            val to = (target as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (!alias.equals(to, ignoreCase = true)) aliasesOf.getOrPut(to) { mutableListOf() } += alias
        }
        val seen = HashSet<String>()
        return (res["pairs"] as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonArray ?: return@mapNotNull null
            val cmd = (row.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!seen.add(cmd)) return@mapNotNull null
            val desc = (row.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val usage = Regex("""\(usage:\s*([^)]*)\)""").find(desc)?.groupValues?.get(1)?.trim()
            val isSkill = cmd in skills
            GatewayCommand(
                cmd = cmd,
                description = desc.substringBefore(" (usage:").trim(),
                category = if (isSkill) SKILL else categoryOf[cmd].orEmpty(),
                // A skill takes what you want done; picking one fills the composer to say it.
                argsHint = usage?.substringAfter(' ', "")?.trim()?.ifBlank { null }
                    ?: if (isSkill) "[what to do]" else if (usage != null) "…" else "",
                aliases = aliasesOf[cmd].orEmpty(),
            )
        }
    }
}
