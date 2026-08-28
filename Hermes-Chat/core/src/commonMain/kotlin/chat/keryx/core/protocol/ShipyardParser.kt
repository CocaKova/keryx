package chat.keryx.core.protocol

import chat.keryx.core.model.ShipyardCommitContext
import chat.keryx.core.model.ShipyardCommitResult
import chat.keryx.core.model.ShipyardDiff
import chat.keryx.core.model.ShipyardFile
import chat.keryx.core.model.ShipyardRepo
import chat.keryx.core.model.ShipyardReview
import chat.keryx.core.model.ShipyardShipInfo
import chat.keryx.core.model.ShipyardStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `/keryx/git/…` payloads → domain. Pure; tested against payloads captured off the gateway
 * module itself (the wire truth). A malformed row is dropped, never a crash.
 */
object ShipyardParser {

    fun parseRepos(res: JsonElement): List<ShipyardRepo>? {
        val o = res as? JsonObject ?: return null
        val rows = o["repos"] as? JsonArray ?: return null
        return rows.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            ShipyardRepo(
                path = r.str("path") ?: return@mapNotNull null,
                label = r.str("label") ?: r.str("path")!!.substringAfterLast('/'),
                source = r.str("source") ?: "discovered",
                branch = r.str("branch"),
            )
        }
    }

    /** `{path, status: {...}|null}` — null status = not a repo any more. */
    fun parseStatus(res: JsonElement): ShipyardStatus? {
        val o = res as? JsonObject ?: return null
        val s = (o["status"] ?: o) as? JsonObject ?: return null
        if (s["files"] == null && s["branch"] == null) return null
        return ShipyardStatus(
            branch = s.str("branch"),
            defaultBranch = s.str("defaultBranch"),
            detached = s.bool("detached"),
            ahead = s.int("ahead"),
            behind = s.int("behind"),
            staged = s.int("staged"),
            unstaged = s.int("unstaged"),
            untracked = s.int("untracked"),
            conflicted = s.int("conflicted"),
            changed = s.int("changed"),
            added = s.int("added"),
            removed = s.int("removed"),
        )
    }

    fun parseReview(res: JsonElement): ShipyardReview? {
        val o = res as? JsonObject ?: return null
        val rows = o["files"] as? JsonArray ?: return null
        val files = rows.mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            ShipyardFile(
                path = f.str("path") ?: return@mapNotNull null,
                added = f.int("added"),
                removed = f.int("removed"),
                status = f.str("status") ?: "M",
                staged = f.bool("staged"),
            )
        }
        return ShipyardReview(scope = o.str("scope") ?: "uncommitted", base = o.str("base"), files = files)
    }

    fun parseDiff(res: JsonElement): ShipyardDiff? {
        val o = res as? JsonObject ?: return null
        return ShipyardDiff(
            file = o.str("file") ?: "",
            diff = o.str("diff") ?: return null,
            clipped = o.bool("clipped"),
            omittedLines = o.int("omittedLines"),
            totalLines = o.int("totalLines"),
        )
    }

    fun parseCommitContext(res: JsonElement): ShipyardCommitContext? {
        val o = res as? JsonObject ?: return null
        return ShipyardCommitContext(
            diff = o.str("diff") ?: "",
            recentSubjects = (o.str("recent") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun parseCommitResult(res: JsonElement): ShipyardCommitResult? {
        val o = res as? JsonObject ?: return null
        if (!o.bool("ok")) return null
        return ShipyardCommitResult(sha = o.str("sha"), pushed = o.bool("pushed"))
    }

    fun parseShipInfo(res: JsonElement): ShipyardShipInfo? {
        val o = res as? JsonObject ?: return null
        val pr = o["pr"] as? JsonObject
        return ShipyardShipInfo(
            ghReady = o.bool("ghReady"),
            prUrl = pr?.str("url"),
            prState = pr?.str("state"),
            prNumber = pr?.get("number")?.let { (it as? JsonPrimitive)?.intOrNull },
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key]?.takeIf { it !is JsonNull } as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0
}
