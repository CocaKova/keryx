package chat.keryx.app.presentation.ui.components

import androidx.annotation.StringRes
import chat.keryx.app.R

/**
 * Every settings row the app has, by name (2.10) — the catalog behind Settings' own search and
 * the drawer palette's "Settings" hits. The Hermes Desktop builds this from its config schema;
 * Keryx's settings are the app's own, so the list is written here.
 *
 * An enum, not a list of strings, on purpose: a row's anchor on the screen is
 * `SettingsAnchor(SettingsRow.X)` — a name the compiler checks — so a row cannot be anchored
 * without being searchable. The reverse (an entry nobody anchored) is held by
 * SettingsCatalogTest, which reads the screen's source back.
 *
 * An entry names the section it lives in on each door, because the same row can sit under
 * "Gateway" on the direct door and "Connection" on Matrix. A row that only exists on one door
 * carries the other as null and is never offered there — search must not open an empty page.
 */
enum class SettingsRow(
    /** The title as shown, in the app's language. [title] stays English: search reads both. */
    @StringRes val label: Int,
    val title: String,
    /** Words a person might type for it that the title does not contain. */
    val keywords: List<String>,
    val sectionDirect: String?,
    val sectionMatrix: String?,
) {
    ACCOUNT_IDENTITY(R.string.settings_row_account_identity, "Account", listOf("sign out", "logout", "who"), "Account", "Account"),
    ACCOUNT_TRANSPORT(R.string.settings_row_account_transport, "Transport", listOf("matrix", "direct", "gateway", "switch door"), "Account", "Account"),
    AGENT_TELEMETRY(R.string.settings_row_agent_telemetry, "Show telemetry", listOf("check-ins", "footer", "cron"), "Agent", "Agent"),
    AGENT_ALERTS(R.string.settings_row_agent_alerts, "Mission alerts", listOf("notify", "kanban", "board"), "Agent", "Agent"),
    AGENT_RESUME(R.string.settings_row_agent_resume, "Reopen last chat on launch", listOf("resume", "start", "fresh"), "Agent", "Agent"),
    AGENT_STARTERS(R.string.settings_row_agent_starters, "Suggestions in empty chats", listOf("starter", "prompts", "suggestions", "reminders", "fresh page", "ai"), "Agent", "Agent"),
    AGENT_STICKY_MODEL(R.string.settings_row_agent_sticky_model, "New sessions use the last model picked", listOf("default model", "sticky", "brain", "new session"), "Agent", null),
    COMPANION_PICK(R.string.settings_row_companion_pick, "Companion", listOf("pet", "mascot", "petdex"), "Companion", "Companion"),
    CONNECTION_GATEWAY(R.string.settings_row_connection_gateway, "Gateways", listOf("url", "host", "dashboard", "connections", "add gateway", "remote", "instances", "switch", "fleet", "primary"), "Gateway", null),
    CONNECTION_FLEET_RESUME(R.string.settings_row_connection_fleet_resume, "At startup, return to the last-used gateway", listOf("startup", "primary", "resume", "cold start"), "Gateway", null),
    CONNECTION_INSECURE(R.string.settings_row_connection_insecure, "Allow self-signed certificates", listOf("tls", "ssl", "https", "cert"), "Gateway", "Connection"),
    CONNECTION_HOMESERVER(R.string.settings_row_connection_homeserver, "Homeserver URL", listOf("matrix", "synapse"), null, "Connection"),
    CONNECTION_HERALDS(R.string.settings_row_connection_heralds, "Hermes Agent Matrix IDs", listOf("heralds", "agents", "council", "colour"), null, "Connection"),
    CONNECTION_PUSH(R.string.settings_row_connection_push, "Push notifications", listOf("ntfy", "unifiedpush", "notify"), null, "Connection"),
    CONNECTION_REAUTH(R.string.settings_row_connection_reauth, "Re-authenticate", listOf("login", "password"), null, "Connection"),
    LINK_TOGGLE(R.string.settings_row_link_toggle, "Hermes Link", listOf("streaming", "sse", "side-channel", "api server"), "Gateway", "Hermes Link"),
    LINK_URL(R.string.settings_row_link_url, "Gateway URL", listOf("8642", "api key", "test link"), "Gateway", "Hermes Link"),
    VOICE_STT(R.string.settings_row_voice_stt, "Voice dictation", listOf("mic", "microphone", "stt", "transcribe", "whisper", "parakeet"), "Voice", "Voice"),
    VOICE_TTS(R.string.settings_row_voice_tts, "Voice replies", listOf("tts", "speak", "read aloud", "speech", "call"), "Voice", "Voice"),
    APPEARANCE_THEME(R.string.settings_row_appearance_theme, "Theme", listOf("dark", "light", "system", "mode"), "Appearance", "Appearance"),
    APPEARANCE_BUBBLES(R.string.settings_row_appearance_bubbles, "Bubble style", listOf("gilded", "solid", "gradient", "glass"), "Appearance", "Appearance"),
    APPEARANCE_TEXT(R.string.settings_row_appearance_text, "Text size", listOf("font", "scale", "large", "small"), "Appearance", "Appearance"),
    APPEARANCE_ACCENT(R.string.settings_row_appearance_accent, "Accent colours", listOf("colour", "color", "hue", "accent 2", "gradient"), "Appearance", "Appearance"),
    APPEARANCE_HAPTICS(R.string.settings_row_appearance_haptics, "Haptic feedback", listOf("vibrate", "vibration"), "Appearance", "Appearance"),
    APPEARANCE_LOADING(R.string.settings_row_appearance_loading, "Loading animation", listOf("caduceus", "braille", "dots", "wave", "spinner"), "Appearance", "Appearance"),
    APPEARANCE_TRANSITION(R.string.settings_row_appearance_transition, "Room transition", listOf("transition", "animation", "switch", "dissolve", "passage", "weave", "bloom", "rise", "motion", "speed", "slow", "fast"), "Appearance", "Appearance"),
    APP_LANGUAGE(R.string.p2_settings_language, "Language", listOf("locale", "translate", "translation", "english", "phone language"), "Language", "Language"),
    PRIVACY_LOCK(R.string.settings_row_privacy_lock, "Biometric app lock", listOf("fingerprint", "face", "pin"), "Privacy & Security", "Privacy & Security"),
    PRIVACY_E2EE(R.string.settings_row_privacy_e2ee, "End-to-end encryption", listOf("e2ee", "encrypt", "keys"), null, "Privacy & Security"),
    PRIVACY_SENSES(R.string.settings_row_privacy_senses, "Senses", listOf("battery", "time", "place", "location", "context"), "Privacy & Security", "Privacy & Security"),
    SESSIONS_ARCHIVED(R.string.settings_row_sessions_archived, "Archived sessions", listOf("archive", "hidden", "restore"), "Sessions", null),
    SESSIONS_PRUNE(R.string.settings_row_sessions_prune, "Prune sessions", listOf("delete old", "clean up", "sweep"), "Sessions", null),
    ABOUT_TOUR(R.string.settings_row_about_tour, "Take the tour", listOf("tutorial", "help", "guide", "walkthrough", "how", "getting started", "hints", "tips"), "About", "About"),
    ABOUT_CRASH(R.string.settings_row_about_crash, "Crash log", listOf("diagnostics", "bug", "report", "share"), "About", "About"),
    ABOUT_VERSION(R.string.settings_row_about_version, "Version", listOf("about", "build", "keryx", "hermes"), "About", "About"),
    ;

    /** Stable — it is what a palette hit and a deep link say. */
    val id: String get() = name

    fun section(direct: Boolean): String? = if (direct) sectionDirect else sectionMatrix
}

/**
 * A section's name as shown. The section strings themselves ("Account", "Privacy & Security"…)
 * are the screen's page keys and stay English; this is only their face. Null for an unknown key.
 */
@StringRes
fun settingsSectionLabel(key: String?): Int? = when (key) {
    "Account" -> R.string.settings_section_account
    "Agent" -> R.string.settings_section_agent
    "Companion" -> R.string.settings_section_companion
    "Gateway" -> R.string.settings_section_gateway
    "Connection" -> R.string.settings_section_connection
    "Hermes Link" -> R.string.settings_section_hermes_link
    "Voice" -> R.string.settings_section_voice
    "Appearance" -> R.string.settings_section_appearance
    "Language" -> R.string.p2_settings_language
    "Privacy & Security" -> R.string.settings_section_privacy
    "Sessions" -> R.string.settings_section_sessions
    "About" -> R.string.settings_section_about
    else -> null
}

object SettingsCatalog {
    val entries: List<SettingsRow> = SettingsRow.entries

    fun byId(id: String): SettingsRow? = entries.firstOrNull { it.id == id }

    /**
     * Entries offered on this door whose title or keywords carry [query]. [shown] gives a row's
     * title in the app's language, so a translated title finds it too; the English title and
     * keywords always do.
     */
    fun search(query: String, direct: Boolean, shown: ((SettingsRow) -> String)? = null): List<SettingsRow> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return entries.filter { e ->
            e.section(direct) != null &&
                (e.title.contains(q, ignoreCase = true) ||
                    shown?.invoke(e)?.contains(q, ignoreCase = true) == true ||
                    e.keywords.any { it.contains(q, ignoreCase = true) })
        }
    }
}
