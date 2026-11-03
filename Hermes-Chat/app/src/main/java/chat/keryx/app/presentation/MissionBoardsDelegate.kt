package chat.keryx.app.presentation

import chat.keryx.app.KeryxText
import chat.keryx.app.R
import chat.keryx.app.transport.direct.GatewayRest
import chat.keryx.core.protocol.CronRestParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One board as the switcher shows it. [isCurrent] = the gateway's own current board. */
data class MissionBoardInfo(
    val slug: String,
    val name: String,
    val total: Int,
    val isCurrent: Boolean,
)

/** The pure half of the boards (3.0): read the dashboard's answer, and name a new board. */
object MissionBoards {
    /** `GET /api/plugins/kanban/boards` → the boards, or null when the body is not that. */
    fun parse(body: String): List<MissionBoardInfo>? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val rows = root["boards"] as? JsonArray ?: return null
        val current = (root["current"] as? JsonPrimitive)?.contentOrNull
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val slug = (o["slug"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: slug
            val total = (o["total"] as? JsonPrimitive)?.intOrNull ?: 0
            val isCurrent = (o["is_current"] as? JsonPrimitive)?.booleanOrNull ?: (slug == current)
            MissionBoardInfo(slug, name, total, isCurrent)
        }
    }

    /**
     * A slug for a board named [name]: lowercase letters, digits and hyphens, 1–64 long, never
     * starting with a hyphen (the gateway's rule), and not one of [taken]: "Ops" twice is ops, ops-2.
     * Null when the name has nothing a slug can keep (all emoji, say) — the caller falls back.
     */
    fun slugFor(name: String, taken: Set<String>): String? {
        val base = name.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(56)
            .trim('-')
            .ifBlank { return null }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }
}

/**
 * Missions boards (3.0): which boards the gateway has, which one this install looks at, and a
 * new one. The choice is Keryx's own, kept per gateway ([chat.keryx.app.domain.repository.SettingsRepository.missionBoard]);
 * the gateway's current board, which the CLI and the desktop follow, is never moved. Reads and
 * writes go through the dashboard's kanban API, so a gateway without it simply has one board.
 */
class MissionBoardsDelegate(
    private val deps: GatewayDeps,
    private val missions: MissionsDelegate,
    /** The direct door's dashboard REST client; null on the Matrix door. */
    private val rest: () -> GatewayRest?,
) {
    private val scope = deps.scope
    private val settings = deps.settings

    private val _boards = MutableStateFlow<List<MissionBoardInfo>>(emptyList())
    /** Every board the gateway has; empty when it cannot say (no dashboard, an older gateway). */
    val boards: StateFlow<List<MissionBoardInfo>> = _boards.asStateFlow()

    private val _chosen = MutableStateFlow(settings.missionBoard)
    /** The slug this install chose, or null for the gateway's current board. */
    val chosen: StateFlow<String?> = _chosen.asStateFlow()

    fun refresh() {
        // A gateway switch moves the ledger under us: read the choice again.
        _chosen.value = settings.missionBoard
        val r = rest() ?: run { _boards.value = emptyList(); return }
        scope.launch {
            val list = r.call("GET", "/api/plugins/kanban/boards").getOrNull()?.let(MissionBoards::parse)
            _boards.value = list.orEmpty()
            // A chosen board that was archived or deleted elsewhere falls back to the current one.
            val pick = _chosen.value
            if (list != null && pick != null && list.none { it.slug == pick }) choose(null)
        }
    }

    /** The board on screen, by name, for the header: null when there is only one to speak of. */
    fun shownName(boards: List<MissionBoardInfo>, chosen: String?): String? {
        if (boards.size < 2 && chosen == null) return null
        val on = boards.firstOrNull { it.slug == chosen } ?: boards.firstOrNull { it.isCurrent }
        return on?.name
    }

    /** Look at [slug] (null = the gateway's current board) from now on, on this gateway. */
    fun choose(slug: String?) {
        val current = _boards.value.firstOrNull { it.isCurrent }?.slug
        val pick = slug?.takeIf { it != current }
        settings.missionBoard = pick
        _chosen.value = pick
        missions.boardSwitched()
    }

    /** Make a board named [name] and look at it. [onDone] true = the gateway made it. */
    fun create(name: String, onDone: (Boolean) -> Unit) {
        val r = rest() ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty()) return onDone(false)
        val slug = MissionBoards.slugFor(clean, _boards.value.map { it.slug }.toSet())
            ?: "board-${(_boards.value.size + 1)}"
        val payload = buildJsonObject {
            put("slug", JsonPrimitive(slug))
            put("name", JsonPrimitive(clean))
        }.toString()
        scope.launch {
            val res = r.call("POST", "/api/plugins/kanban/boards", payload)
            if (res.isFailure) {
                deps.toast(
                    KeryxText.get(
                        R.string.work_create_failed,
                        CronRestParser.errorDetail(res.exceptionOrNull()?.message).take(100),
                    ),
                )
                onDone(false)
                return@launch
            }
            _boards.value = _boards.value + MissionBoardInfo(slug, clean, 0, isCurrent = false)
            choose(slug)
            onDone(true)
            refresh()
        }
    }
}
