package chat.keryx.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import chat.keryx.app.domain.repository.SettingsRepository
import java.net.URI

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    companion object {
        /** The one settings file. Shared with [chat.keryx.app.presentation.ui.components.relaunchApp],
         *  which must flush this file's pending apply() writes before it kills the process. */
        const val PREFS_FILE = "hermes_settings"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

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
        get() = prefs.getString("last_room_id", null)
        set(value) = prefs.edit().putString("last_room_id", value).apply()

    override var recentCommands: List<String>
        // Stored as a newline-joined string to keep order (SharedPreferences sets are unordered).
        get() = prefs.getString("recent_commands", "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("recent_commands", value.joinToString("\n")).apply()

    override var batteryPromptShown: Boolean
        get() = prefs.getBoolean("battery_prompt_shown", false)
        set(value) = prefs.edit().putBoolean("battery_prompt_shown", value).apply()

    override var gatewayUrl: String
        get() = (prefs.getString("gateway_url", "") ?: "").ifBlank { defaultGatewayUrl() }
        set(value) = prefs.edit().putString("gateway_url", value).apply()

    override var transportMode: String
        get() = prefs.getString("transport_mode", "matrix") ?: "matrix"
        set(value) = prefs.edit().putString("transport_mode", value).apply()

    override var directLoggedIn: Boolean
        get() = prefs.getBoolean("direct_logged_in", false)
        set(value) = prefs.edit().putBoolean("direct_logged_in", value).apply()

    override var cronBaseline: Long
        get() = prefs.getLong("cron_baseline", 0L)
        set(value) = prefs.edit().putLong("cron_baseline", value).apply()

    override var cronSeenIds: Set<String>
        get() = prefs.getStringSet("cron_seen_ids", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("cron_seen_ids", value).apply()

    // A String, not a StringSet: order is the pin order and a StringSet forgets it.
    override var pinnedCronJobs: List<String>
        get() = prefs.getString("pinned_cron_jobs", "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("pinned_cron_jobs", value.joinToString("\u001F")).apply()

    override var botSeenAt: Map<String, Long>
        get() = prefs.getString("bot_seen_at", "")!!.split('\u001E').filter { it.isNotBlank() }
            .mapNotNull { e -> e.split('\u001F').takeIf { it.size == 2 }?.let { (n, t) -> t.toLongOrNull()?.let { n to it } } }
            .toMap()
        set(value) = prefs.edit().putString(
            "bot_seen_at", value.entries.joinToString("\u001E") { (n, t) -> "$n\u001F$t" },
        ).apply()

    override var pinnedBots: List<String>
        get() = prefs.getString("pinned_bots", "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("pinned_bots", value.joinToString("\u001F")).apply()

    override var recentModels: List<String>
        get() = prefs.getString("recent_models", "")!!.split('\u001F').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("recent_models", value.joinToString("\u001F")).apply()

    override var temporarySessionIds: Set<String>
        get() = prefs.getStringSet("temporary_session_ids", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("temporary_session_ids", value).apply()

    override var lastMatrixUsername: String
        get() = prefs.getString("last_matrix_username", "") ?: ""
        set(value) = prefs.edit().putString("last_matrix_username", value).apply()

    override var directGatewayUrl: String
        get() = prefs.getString("direct_gateway_url", "") ?: ""
        set(value) = prefs.edit().putString("direct_gateway_url", value).apply()

    override var directApiKey: String
        get() = sealedGet("direct_api_key")
        set(value) = sealedPut("direct_api_key", value)

    override var directAuthMode: String
        get() = prefs.getString("direct_auth_mode", "token") ?: "token"
        set(value) = prefs.edit().putString("direct_auth_mode", value).apply()

    override var directAccessToken: String
        get() = sealedGet("direct_access_token")
        set(value) = sealedPut("direct_access_token", value)

    override var directRefreshToken: String
        get() = sealedGet("direct_refresh_token")
        set(value) = sealedPut("direct_refresh_token", value)

    override var directTokenExpiresAt: Long
        get() = prefs.getLong("direct_token_expires_at", 0L)
        set(value) = prefs.edit().putLong("direct_token_expires_at", value).apply()

    @Suppress("ApplySharedPref") // synchronous ON PURPOSE — see the interface doc.
    override fun commitDirectNativeTokens(access: String, refresh: String, expiresAtSeconds: Long) {
        prefs.edit()
            .putString("direct_access_token", chat.keryx.app.data.local.TokenVault.seal(access))
            .putString("direct_refresh_token", chat.keryx.app.data.local.TokenVault.seal(refresh))
            .putLong("direct_token_expires_at", expiresAtSeconds)
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
            gatewayUrl?.let { putString("direct_gateway_url", it) }
            gatewayApiKey?.let { putString("direct_api_key", chat.keryx.app.data.local.TokenVault.seal(it)) }
            putString("transport_mode", mode)
            putBoolean("direct_logged_in", directLoggedIn)
        }.commit()
    }

    override var gatewayApiKey: String
        get() = sealedGet("gateway_api_key")
        set(value) = sealedPut("gateway_api_key", value)

    override var sideChannelEnabled: Boolean
        get() = prefs.getBoolean("side_channel_enabled", true)
        set(value) = prefs.edit().putBoolean("side_channel_enabled", value).apply()

    override val gatewayConfigured: Boolean
        // Raw pref, not the [gatewayUrl] getter — that one falls back to a derived default.
        get() = !(prefs.getString("gateway_url", "") ?: "").isBlank() || gatewayApiKey.isNotBlank()

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
        get() = prefs.getLong("mission_events_cursor", -1L)
        set(value) = prefs.edit().putLong("mission_events_cursor", value).apply()

    // Hub snapshots live in their own prefs file: they're whole gateway responses (the sessions
    // list runs tens of KB) and shouldn't bloat every hermes_settings load.
    private val hubCache: SharedPreferences =
        context.getSharedPreferences("keryx_hub_cache", Context.MODE_PRIVATE)

    override fun hubSnapshot(path: String): String? =
        hubCache.getString(path, null)

    override fun putHubSnapshot(path: String, json: String) {
        hubCache.edit().putString(path, json).apply()
    }

    // Drafts are tiny strings keyed per room; empty text removes the key so prefs never
    // accumulate stale entries for rooms the user finished typing in.
    override fun getDraft(roomId: String): String =
        prefs.getString("draft_$roomId", "") ?: ""

    override fun setDraft(roomId: String, text: String) {
        if (text.isBlank()) prefs.edit().remove("draft_$roomId").apply()
        else prefs.edit().putString("draft_$roomId", text).apply()
    }

    private fun defaultGatewayUrl(): String {
        val host = runCatching { URI(homeserverUrl).host }
            .getOrNull()
            ?: homeserverUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore(':')
                .trim()
        return if (host.isBlank()) "" else "http://$host:8642"
    }
}
