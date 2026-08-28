package chat.keryx.core.protocol

import chat.keryx.core.model.FolderPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `complete.path` in its `@folder:` mode — the stock gateway's only directories-only
 * listing, and therefore the fuel for picking a project's folder instead of typing it.
 *
 * Two facts about the wire, both verified live against 0.20.2 on 2026-08-17:
 *
 * - `text` is REBASED on the gateway's own completion cwd (`~/workspace/keryx` comes back
 *   as `@folder:workspace/keryx/`), so it is not a path anyone else can use. Only `display`
 *   is a name, and the caller owns the prefix it hangs under.
 * - the server stops emitting at 30 items and says nothing about having stopped, so a full
 *   page is reported as [FolderPage.truncated] rather than passed off as the whole folder.
 */
object FolderCompletionParser {

    /** The server's hard completion cap. */
    const val ITEM_CAP = 30

    fun parse(payload: JsonElement?): FolderPage {
        val items = ((payload as? JsonObject)?.get("items") as? JsonArray) ?: return FolderPage.EMPTY
        val names = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // A file that merely fuzzy-matched is not a folder; only `meta: "dir"` is.
            if ((o["meta"] as? JsonPrimitive)?.contentOrNull != "dir") return@mapNotNull null
            (o["display"] as? JsonPrimitive)?.contentOrNull
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
        }
        return FolderPage(names = names, truncated = items.size >= ITEM_CAP)
    }
}
