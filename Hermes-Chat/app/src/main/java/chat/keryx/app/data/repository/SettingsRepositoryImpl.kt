package chat.keryx.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import chat.keryx.app.domain.repository.SettingsRepository
import java.net.URI

class SettingsRepositoryImpl(context: Context) : SettingsRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_settings", Context.MODE_PRIVATE)

    override var homeserverUrl: String
        get() = prefs.getString("homeserver_url", "") ?: ""
        set(value) = prefs.edit().putString("homeserver_url", value).apply()

    override var matrixToken: String
        get() = prefs.getString("matrix_token", "") ?: ""
        set(value) = prefs.edit().putString("matrix_token", value).apply()

    override var agentMatrixId: String
        get() = prefs.getString("agent_matrix_id", "") ?: ""
        set(value) = prefs.edit().putString("agent_matrix_id", value).apply()

    override var allowInsecure: Boolean
        get() = prefs.getBoolean("allow_insecure", false)
        set(value) = prefs.edit().putBoolean("allow_insecure", value).apply()

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
        get() = prefs.getString("animation_style", "Braille") ?: "Braille"
        set(value) = prefs.edit().putString("animation_style", value).apply()

    override var bubbleStyle: String
        get() = prefs.getString("bubble_style", "Gradient") ?: "Gradient"
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

    override var gatewayApiKey: String
        get() = prefs.getString("gateway_api_key", "") ?: ""
        set(value) = prefs.edit().putString("gateway_api_key", value).apply()

    override var sideChannelEnabled: Boolean
        get() = prefs.getBoolean("side_channel_enabled", true)
        set(value) = prefs.edit().putBoolean("side_channel_enabled", value).apply()

    override var showTelemetry: Boolean
        get() = prefs.getBoolean("show_telemetry", true)
        set(value) = prefs.edit().putBoolean("show_telemetry", value).apply()

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
