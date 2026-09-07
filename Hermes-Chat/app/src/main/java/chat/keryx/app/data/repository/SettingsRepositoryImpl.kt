package chat.keryx.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import chat.keryx.app.domain.repository.SettingsRepository
import java.net.URI

class SettingsRepositoryImpl(
    context: Context,
    /**
     * The fleet (2.11): a view pinned to ONE gateway's credentials and ledgers, whatever the
     * active gateway is — how a non-active gateway is tested, signed in or forgotten. Null =
     * the real repository, which follows the active gateway. Both read and write the same
     * per-file SharedPreferences singleton, so a pinned view never goes stale.
     */
    private val pinnedGatewayId: String? = null,
) : SettingsRepository {

    companion object {
        /** The one settings file. Shared with [chat.keryx.app.presentation.ui.components.relaunchApp],
         *  which must flush this file's pending apply() writes before it kills the process. */
        const val PREFS_FILE = "hermes_settings"
        private const val FLEET_KEY = "fleet_json"
        /** The active gateway id, duplicated out of the fleet JSON so every scoped read is one
         *  map lookup rather than a parse. Written only by [commitFleet]. */
        private const val FLEET_ACTIVE_KEY = "fleet_active"
        private const val FLEET_SWITCH_KEY = "fleet_switch_pending"
        private const val FLEET_LEGACY_KEY = "fleet_legacy_id"

        /**
         * Everything the phone keeps PER GATEWAY on the direct door, beyond the credentials:
         * the ledgers a gateway's own sessions, jobs and bots write. Scoped by suffixing the
         * key with the gateway id, so two gateways never read each other's read-marks — the
         * Desktop's "sessions, cron, bots, settings, files, memory are all scoped to the
         * active gateway". Matrix keeps the bare keys: there is one homeserver.
         */
        private val GATEWAY_LEDGERS = listOf(
            "last_room_id", "temporary_session_ids", "cron_baseline", "cron_seen_ids",
            "pinned_cron_jobs", "bot_seen_at", "pinned_bots", "mission_events_cursor",
            "gateway_url", "gateway_api_key",
            // A model key (`provider|model`) means nothing across gateways: the sticky model
            // pinned a fresh Ascent session to Spark 1's 27B (device-caught 2026-09-06).
            "recent_models",
        )
        private val GATEWAY_CREDENTIALS = listOf(
            "direct_api_key", "direct_auth_mode", "direct_access_token", "direct_refresh_token",
            "direct_token_expires_at", "direct_logged_in",
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    init {
        if (pinnedGatewayId == null) migrateSingleGateway()
    }

    /**
     * The gateway whose keys this instance reads: the pinned one, else the active one — and
     * only on the direct door. On Matrix the fleet may well exist (the user crossed doors)
     * but the ledgers are the homeserver's, so they stay unsuffixed.
     */
    private val gatewayScope: String
        get() = pinnedGatewayId
            ?: if ((prefs.getString("transport_mode", "matrix") ?: "matrix") == "direct")
                prefs.getString(FLEET_ACTIVE_KEY, "") ?: ""
            else ""

    /** A credential key is ALWAYS the gateway's (pinned or active) — credentials have no
     *  Matrix meaning. Falls back to the bare key only while no fleet exists at all. */
    private fun credKey(key: String): String {
        val g = pinnedGatewayId ?: (prefs.getString(FLEET_ACTIVE_KEY, "") ?: "")
        return if (g.isBlank()) key else "$key.$g"
    }

    private fun ledgerKey(key: String): String {
        val g = gatewayScope
        return if (g.isBlank()) key else "$key.$g"
    }

    /**
     * A 2.10-and-earlier install has ONE direct gateway in bare keys. Fold it into the fleet
     * as the first (primary, active) row and copy its credentials and ledgers under its id —
     * copy, not move: the bare keys are left as they were, so an older build on the same
     * phone still finds its door (the Desktop leaves its legacy settings file the same way).
     */
    private fun migrateSingleGateway() {
        if (!(prefs.getString(FLEET_KEY, "") ?: "").isBlank()) return
        val url = prefs.getString("direct_gateway_url", "") ?: ""
        if (url.isBlank()) return
        val (fleet, entry) = runCatching {
            chat.keryx.core.model.Fleet().add(url, "", ::newGatewayId)
        }.getOrElse { return }
        val all = prefs.all
        val ed = prefs.edit()
        for (key in GATEWAY_CREDENTIALS + GATEWAY_LEDGERS) {
            val v = all[key] ?: continue
            val to = "$key.${entry.id}"
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> ed.putString(to, v)
                is Boolean -> ed.putBoolean(to, v)
                is Long -> ed.putLong(to, v)
                is Int -> ed.putInt(to, v)
                is Float -> ed.putFloat(to, v)
                is Set<*> -> ed.putStringSet(to, v as Set<String>)
            }
        }
        ed.putString(FLEET_KEY, fleet.toJson()).putString(FLEET_ACTIVE_KEY, fleet.activeId)
            .putString(FLEET_LEGACY_KEY, entry.id).commit()
    }

    override val legacyGatewayId: String get() = prefs.getString(FLEET_LEGACY_KEY, "") ?: ""

    private fun newGatewayId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    // The fleet JSON is parsed once per distinct stored string: SharedPreferences hands back the
    // same String instance from its in-memory map until a write replaces it, so identity is a
    // sound cache key across every instance sharing the file.
    @Volatile private var fleetRaw: String? = null
    @Volatile private var fleetParsed: chat.keryx.core.model.Fleet = chat.keryx.core.model.Fleet()

    override var fleet: chat.keryx.core.model.Fleet
        get() {
            val raw = prefs.getString(FLEET_KEY, "") ?: ""
            if (raw !== fleetRaw) {
                fleetParsed = chat.keryx.core.model.Fleet.fromJson(raw)
                fleetRaw = raw
            }
            return fleetParsed
        }
        set(value) = prefs.edit().putString(FLEET_KEY, value.toJson()).putString(FLEET_ACTIVE_KEY, value.activeId).apply()

    @Suppress("ApplySharedPref") // synchronous ON PURPOSE — a switch relaunches on the next line.
    override fun commitFleet(fleet: chat.keryx.core.model.Fleet, switchPending: Boolean) {
        prefs.edit().putString(FLEET_KEY, fleet.toJson()).putString(FLEET_ACTIVE_KEY, fleet.activeId)
            .putBoolean(FLEET_SWITCH_KEY, switchPending).commit()
    }

    override fun consumeFleetSwitch(): Boolean {
        val pending = prefs.getBoolean(FLEET_SWITCH_KEY, false)
        if (pending) prefs.edit().putBoolean(FLEET_SWITCH_KEY, false).apply()
        return pending
    }

    override fun newGatewayIdFor(fleet: chat.keryx.core.model.Fleet): String {
        var id = newGatewayId()
        while (fleet.byId(id) != null) id = newGatewayId()
        return id
    }

    override fun forGateway(gatewayId: String): SettingsRepository = SettingsRepositoryImpl(appContext, gatewayId)

    override fun forgetGateway(gatewayId: String) {
        if (gatewayId.isBlank()) return
        val suffix = ".$gatewayId"
        prefs.edit().apply { prefs.all.keys.filter { it.endsWith(suffix) }.forEach { remove(it) } }.apply()
        hubCache.edit().apply { hubCache.all.keys.filter { it.endsWith(suffix) }.forEach { remove(it) } }.apply()
    }

    // --- sealed secrets (TokenVault: AES/GCM under an AndroidKeyStore key) ---------------
    // Every credential goes to disk sealed; a legacy plaintext value is re-written sealed
    // the first time it is read, so existing installs migrate lazily and losslessly.
    private fun sealedGet(key: String): String {
        val stored = prefs.getString(key, "") ?: ""
        if (stored.isNotBlank() && !chat.keryx.app.data.local.TokenVault.isSealed(stored)) {
            prefs.edit().putString(key, chat.keryx.app.data.local.TokenVault.seal(stored)).apply()
        }
        return chat.keryx.app.data.local.TokenVault.open(stored)
    }

    private fun sealedPut(key: String, value: String) =
        prefs.edit().putString(key, chat.keryx.app.data.local.TokenVault.seal(value)).apply()

    override var homeserverUrl: String
        get() = prefs.getString("homeserver_url", "") ?: ""
        set(value) = prefs.edit().putString("homeserver_url", value).apply()

    override var matrixToken: String
        get() = sealedGet("matrix_token")
        set(value) = sealedPut("matrix_token", value)

    override var agentMatrixId: String
        get() = prefs.getString("agent_matrix_id", "") ?: ""
        set(value) = prefs.edit().putString("agent_matrix_id", value).apply()

    override var allowInsecure: Boolean
        get() = prefs.getBoolean("allow_insecure", false)
        set(value) = prefs.edit().putBoolean("allow_insecure", value).apply()

    /** Stored as one string: "milo=#5FD3BC;theo=#B08CFF" — a map in prefs would need a
     *  StringSet dance and this is read on every bubble, so keep it cheap and ordered. */
    override var heraldAccents: Map<String, String>
        get() = (prefs.getString("herald_accents", "") ?: "")
            .split(';')
            .mapNotNull { entry ->
                val k = entry.substringBefore('=').trim().lowercase()
                val v = entry.substringAfter('=', "").trim()
                if (k.isNotEmpty() && v.startsWith("#")) k to v else null
            }
            .toMap()
        set(value) = prefs.edit().putString(
            "herald_accents",
            value.entries.joinToString(";") { "${it.key.lowercase()}=${it.value}" },
        ).apply()

    override var pinnedRoomIds: Set<String>
        // Return a copy — SharedPreferences forbids mutating the returned set.
        get() = prefs.getStringSet("pinned_room_ids", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("pinned_room_ids", value).apply()

    override var resumeLastRoom: Boolean
        get() = prefs.getBoolean("resume_last_room", true)
        set(value) = prefs.edit().putBoolean("resume_last_room", value).apply()

    override var stickyModel: Boolean
        get() = prefs.getBoolean("sticky_model", true)
        set(value) = prefs.edit().putBoolean("sticky_model", value).apply()

    override var collapsedRosterGroups: Set<String>
        get() = prefs.getStringSet("collapsed_roster_groups", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("collapsed_roster_groups", value).apply()

    override var biometricLockEnabled: Boolean
        get() = prefs.getBoolean("biometric_lock", false)
        set(value) = prefs.edit().putBoolean("biometric_lock", value).apply()

    override var e2eeEnabled: Boolean
        get() = prefs.getBoolean("e2ee_enabled", true)
        set(value) = prefs.edit().putBoolean("e2ee_enabled", value).apply()

    override var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics_enabled", true)
        set(value) = prefs.edit().putBoolean("haptics_enabled", value).apply()

    override var animationStyle: String
        get() = prefs.getString("animation_style", "Caduceus") ?: "Caduceus"
        set(value) = prefs.edit().putString("animation_style", value).apply()

    override var bubbleStyle: String
        get() = prefs.getString("bubble_style", "Gilded") ?: "Gilded"
        set(value) = prefs.edit().putString("bubble_style", value).apply()

    override var messageTextScale: Float
        get() = prefs.getFloat("message_text_scale", 1.0f)
        set(value) = prefs.edit().putFloat("message_text_scale", value).apply()

    override var syncToken: String?
        get() = prefs.getString("sync_token", null)
        set(value) = prefs.edit().putString("sync_token", value).apply()

    override var accentColorHex: String
        get() = prefs.getString("accent_color_hex", "#E55A00") ?: "#E55A00"
        set(value) = prefs.edit().putString("accent_color_hex", value).apply()
    override var accentColor2Hex: String
        get() = prefs.getString("accent_color2_hex", "#8B5CF6") ?: "#8B5CF6"
        set(value) = prefs.edit().putString("accent_color2_hex", value).apply()

    override var lastRoomId: String?
        get() = prefs.getString(ledgerKey("last_room_id"), null)
        set(value) = prefs.edit().putString(ledgerKey("last_room_id"), value).apply()

    override var recentCommands: List<String>
        // Stored as a newline-joined string to keep order (SharedPreferences sets are unordered).
        get() = prefs.getString("recent_commands", "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("recent_commands", value.joinToString("\n")).apply()

    override var batteryPromptShown: Boolean
        get() = prefs.getBoolean("battery_prompt_shown", false)
        set(value) = prefs.edit().putBoolean("battery_prompt_shown", value).apply()

    override var gatewayUrl: String
        get() = (prefs.getString(ledgerKey("gateway_url"), "") ?: "").ifBlank { defaultGatewayUrl() }
        set(value) = prefs.edit().putString(ledgerKey("gateway_url"), value).apply()

    override var transportMode: String
        get() = prefs.getString("transport_mode", "matrix") ?: "matrix"
        set(value) = prefs.edit().putString("transport_mode", value).apply()

    override var directLoggedIn: Boolean
        get() = prefs.getBoolean(credKey("direct_logged_in"), false)
        set(value) = prefs.edit().putBoolean(credKey("direct_logged_in"), value).apply()

    override var cronBaseline: Long
        get() = prefs.getLong(ledgerKey("cron_baseline"), 0L)
        set(value) = prefs.edit().putLong(ledgerKey("cron_baseline"), value).apply()

    override var cronSeenIds: Set<String>
        get() = prefs.getStringSet(ledgerKey("cron_seen_ids"), emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(ledgerKey("cron_seen_ids"), value).apply()

    // A String, not a StringSet: order is the pin order and a StringSet forgets it.
    override var pinnedCronJobs: List<String>
        get() = prefs.getString(ledgerKey("pinned_cron_jobs"), "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(ledgerKey("pinned_cron_jobs"), value.joinToString("\u001F")).apply()

    override var botSeenAt: Map<String, Long>
        get() = prefs.getString(ledgerKey("bot_seen_at"), "")!!.split('\u001E').filter { it.isNotBlank() }
            .mapNotNull { e -> e.split('\u001F').takeIf { it.size == 2 }?.let { (n, t) -> t.toLongOrNull()?.let { n to it } } }
            .toMap()
        set(value) = prefs.edit().putString(
            ledgerKey("bot_seen_at"), value.entries.joinToString("\u001E") { (n, t) -> "$n\u001F$t" },
        ).apply()

    override var pinnedBots: List<String>
        get() = prefs.getString(ledgerKey("pinned_bots"), "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(ledgerKey("pinned_bots"), value.joinToString("\u001F")).apply()

    override var recentModels: List<String>
        get() = prefs.getString(ledgerKey("recent_models"), "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(ledgerKey("recent_models"), value.joinToString("\u001F")).apply()

    override var temporarySessionIds: Set<String>
        get() = prefs.getStringSet(ledgerKey("temporary_session_ids"), emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(ledgerKey("temporary_session_ids"), value).apply()

    override var lastMatrixUsername: String
        get() = prefs.getString("last_matrix_username", "") ?: ""
        set(value) = prefs.edit().putString("last_matrix_username", value).apply()

    // The URL is the fleet row's (2.11); the bare pref is the pre-fleet install's, read only
    // while no fleet exists — and it is what [migrateSingleGateway] folds in.
    override var directGatewayUrl: String
        get() {
            val g = pinnedGatewayId ?: (prefs.getString(FLEET_ACTIVE_KEY, "") ?: "")
            return fleet.byId(g)?.url ?: (prefs.getString("direct_gateway_url", "") ?: "")
        }
        set(value) = prefs.edit().putString("direct_gateway_url", value).apply()

    override var directApiKey: String
        get() = sealedGet(credKey("direct_api_key"))
        set(value) = sealedPut(credKey("direct_api_key"), value)

    override var directAuthMode: String
        get() = prefs.getString(credKey("direct_auth_mode"), "token") ?: "token"
        set(value) = prefs.edit().putString(credKey("direct_auth_mode"), value).apply()

    override var directAccessToken: String
        get() = sealedGet(credKey("direct_access_token"))
        set(value) = sealedPut(credKey("direct_access_token"), value)

    override var directRefreshToken: String
        get() = sealedGet(credKey("direct_refresh_token"))
        set(value) = sealedPut(credKey("direct_refresh_token"), value)

    override var directTokenExpiresAt: Long
        get() = prefs.getLong(credKey("direct_token_expires_at"), 0L)
        set(value) = prefs.edit().putLong(credKey("direct_token_expires_at"), value).apply()

    @Suppress("ApplySharedPref") // synchronous ON PURPOSE — see the interface doc.
    override fun commitDirectNativeTokens(access: String, refresh: String, expiresAtSeconds: Long) {
        prefs.edit()
            .putString(credKey("direct_access_token"), chat.keryx.app.data.local.TokenVault.seal(access))
            .putString(credKey("direct_refresh_token"), chat.keryx.app.data.local.TokenVault.seal(refresh))
            .putLong(credKey("direct_token_expires_at"), expiresAtSeconds)
            .commit()
    }

    @Suppress("ApplySharedPref") // synchronous ON PURPOSE — see the interface doc.
    override fun commitTransportMode(mode: String) {
        prefs.edit().putString("transport_mode", mode).commit()
    }

    override val matrixSessionOnFile: Boolean
        get() = appContext.getDatabasePath("trixnity.db").exists()

    @Suppress("ApplySharedPref") // synchronous ON PURPOSE — see the interface doc.
    override fun commitTransportDoor(
        gatewayUrl: String?,
        gatewayApiKey: String?,
        mode: String,
        directLoggedIn: Boolean,
    ) {
        prefs.edit().apply {
            // The DIRECT keys, never gateway_url — that one belongs to Hermes Link and a
            // door crossing must not clobber it (they are different services; see the
            // interface doc on directGatewayUrl).
            // The URL lives on the fleet row (2.11); the bare key is kept current for an older
            // build on the same phone, nothing on this build reads it once a fleet exists.
            gatewayUrl?.let { putString("direct_gateway_url", it) }
            gatewayApiKey?.let { putString(credKey("direct_api_key"), chat.keryx.app.data.local.TokenVault.seal(it)) }
            putString("transport_mode", mode)
            putBoolean(credKey("direct_logged_in"), directLoggedIn)
        }.commit()
    }

    // Hermes Link's key is per gateway (2.11). A gateway row that has none yet — just added, or
    // migrated in — inherits the fleet's: the primary's key first, else any gateway's. One
    // operator, one API_SERVER_KEY across the fleet is the common shape; without this the Hub
    // and the composer footer (which only renders once the caps probe answers over the link)
    // stay dark on a new gateway until the key is typed. Writing the field pins the row's own.
    override var gatewayApiKey: String
        get() = sealedGet(ledgerKey("gateway_api_key")).ifBlank { inheritedLinkKey() }
        set(value) = sealedPut(ledgerKey("gateway_api_key"), value)

    private fun inheritedLinkKey(): String {
        val scope = gatewayScope
        if (scope.isBlank()) return ""
        val f = fleet
        val order = listOfNotNull(f.primaryId.takeIf { it.isNotBlank() }) + f.gateways.map { it.id }
        for (id in order.distinct()) {
            if (id == scope) continue
            val k = sealedGet("gateway_api_key.$id")
            if (k.isNotBlank()) return k
        }
        return ""
    }

    override var sideChannelEnabled: Boolean
        get() = prefs.getBoolean("side_channel_enabled", true)
        set(value) = prefs.edit().putBoolean("side_channel_enabled", value).apply()

    override val gatewayConfigured: Boolean
        // Raw pref, not the [gatewayUrl] getter — that one falls back to a derived default.
        get() = !(prefs.getString(ledgerKey("gateway_url"), "") ?: "").isBlank() || gatewayApiKey.isNotBlank()

    override var pushEnabled: Boolean
        get() = prefs.getBoolean("push_enabled", false)
        set(value) = prefs.edit().putBoolean("push_enabled", value).apply()

    override var pushGatewayUrl: String
        get() = prefs.getString("push_gateway_url", "") ?: ""
        set(value) = prefs.edit().putString("push_gateway_url", value).apply()

    override var pushEndpoint: String
        get() = prefs.getString("push_endpoint", "") ?: ""
        set(value) = prefs.edit().putString("push_endpoint", value).apply()

    override var builtinPushTopic: String
        get() = prefs.getString("builtin_push_topic", "") ?: ""
        set(value) = prefs.edit().putString("builtin_push_topic", value).apply()

    override var showTelemetry: Boolean
        get() = prefs.getBoolean("show_telemetry", true)
        set(value) = prefs.edit().putBoolean("show_telemetry", value).apply()

    override var sttUrl: String
        get() = prefs.getString("stt_url", "") ?: ""
        set(value) = prefs.edit().putString("stt_url", value).apply()

    override var sttApiKey: String
        get() = sealedGet("stt_api_key")
        set(value) = sealedPut("stt_api_key", value)

    override var sttModel: String
        get() = prefs.getString("stt_model", "") ?: ""
        set(value) = prefs.edit().putString("stt_model", value).apply()

    override var ttsAutoSpeak: Boolean
        get() = prefs.getBoolean("tts_auto_speak", false)
        set(value) = prefs.edit().putBoolean("tts_auto_speak", value).apply()

    override var ttsUrl: String
        get() = prefs.getString("tts_url", "") ?: ""
        set(value) = prefs.edit().putString("tts_url", value).apply()

    override var ttsApiKey: String
        get() = sealedGet("tts_api_key")
        set(value) = sealedPut("tts_api_key", value)

    override var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(value) = prefs.edit().putString("tts_voice", value).apply()

    override var ttsModel: String
        get() = prefs.getString("tts_model", "") ?: ""
        set(value) = prefs.edit().putString("tts_model", value).apply()

    override var missionAlertsEnabled: Boolean
        get() = prefs.getBoolean("mission_alerts", false)
        set(value) = prefs.edit().putBoolean("mission_alerts", value).apply()

    override var missionEventsCursor: Long
        get() = prefs.getLong(ledgerKey("mission_events_cursor"), -1L)
        set(value) = prefs.edit().putLong(ledgerKey("mission_events_cursor"), value).apply()

    // Hub snapshots live in their own prefs file: they're whole gateway responses (the sessions
    // list runs tens of KB) and shouldn't bloat every hermes_settings load.
    private val hubCache: SharedPreferences =
        context.getSharedPreferences("keryx_hub_cache", Context.MODE_PRIVATE)

    // Keyed per gateway (2.11): a path is the same on every gateway, its answer is not.
    override fun hubSnapshot(path: String): String? =
        hubCache.getString(ledgerKey(path), null)

    override fun putHubSnapshot(path: String, json: String) {
        hubCache.edit().putString(ledgerKey(path), json).apply()
    }

    // Drafts are tiny strings keyed per room; empty text removes the key so prefs never
    // accumulate stale entries for rooms the user finished typing in.
    override fun getDraft(roomId: String): String =
        prefs.getString("draft_$roomId", "") ?: ""

    override fun setDraft(roomId: String, text: String) {
        if (text.isBlank()) prefs.edit().remove("draft_$roomId").apply()
        else prefs.edit().putString("draft_$roomId", text).apply()
    }

    // Hermes Link's default host: the homeserver on Matrix, the active gateway's host on the
    // direct door (2.11) — a second gateway's Hub would otherwise stay dark until the link
    // URL was typed by hand. Port 8642 is the gateway's API server default on both.
    private fun defaultGatewayUrl(): String {
        val source = if (gatewayScope.isNotBlank()) directGatewayUrl else homeserverUrl
        val host = runCatching { URI(source).host }
            .getOrNull()
            ?: source
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore(':')
                .trim()
        return if (host.isBlank()) "" else "http://$host:8642"
    }
}
