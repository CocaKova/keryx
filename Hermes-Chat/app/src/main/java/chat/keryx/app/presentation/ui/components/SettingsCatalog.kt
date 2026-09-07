package chat.keryx.app.presentation.ui.components

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
    val title: String,
    /** Words a person might type for it that the title does not contain. */
    val keywords: List<String>,
    val sectionDirect: String?,
    val sectionMatrix: String?,
) {
    ACCOUNT_IDENTITY("Account", listOf("sign out", "logout", "who"), "Account", "Account"),
    ACCOUNT_TRANSPORT("Transport", listOf("matrix", "direct", "gateway", "switch door"), "Account", "Account"),
    AGENT_TELEMETRY("Show telemetry", listOf("check-ins", "footer", "cron"), "Agent", "Agent"),
    AGENT_ALERTS("Mission alerts", listOf("notify", "kanban", "board"), "Agent", "Agent"),
    AGENT_RESUME("Reopen last chat on launch", listOf("resume", "start", "fresh"), "Agent", "Agent"),
    AGENT_STICKY_MODEL("New sessions use the last model picked", listOf("default model", "sticky", "brain", "new session"), "Agent", null),
    COMPANION_PICK("Companion", listOf("pet", "mascot", "petdex"), "Companion", "Companion"),
    CONNECTION_GATEWAY("Gateways", listOf("url", "host", "dashboard", "connections", "add gateway", "remote", "instances", "switch", "fleet", "primary"), "Gateway", null),
    CONNECTION_FLEET_RESUME("At startup, return to the last-used gateway", listOf("startup", "primary", "resume", "cold start"), "Gateway", null),
    CONNECTION_INSECURE("Allow self-signed certificates", listOf("tls", "ssl", "https", "cert"), "Gateway", "Connection"),
    CONNECTION_HOMESERVER("Homeserver URL", listOf("matrix", "synapse"), null, "Connection"),
    CONNECTION_HERALDS("Hermes Agent Matrix IDs", listOf("heralds", "agents", "council", "colour"), null, "Connection"),
    CONNECTION_PUSH("Push notifications", listOf("ntfy", "unifiedpush", "notify"), null, "Connection"),
    CONNECTION_REAUTH("Re-authenticate", listOf("login", "password"), null, "Connection"),
    LINK_TOGGLE("Hermes Link", listOf("streaming", "sse", "side-channel", "api server"), "Gateway", "Hermes Link"),
    LINK_URL("Gateway URL", listOf("8642", "api key", "test link"), "Gateway", "Hermes Link"),
    VOICE_STT("Voice dictation", listOf("mic", "microphone", "stt", "transcribe", "whisper", "parakeet"), "Voice", "Voice"),
    VOICE_TTS("Voice replies", listOf("tts", "speak", "read aloud", "speech", "call"), "Voice", "Voice"),
    APPEARANCE_THEME("Theme", listOf("dark", "light", "system", "mode"), "Appearance", "Appearance"),
    APPEARANCE_BUBBLES("Bubble style", listOf("gilded", "solid", "gradient", "glass"), "Appearance", "Appearance"),
    APPEARANCE_TEXT("Text size", listOf("font", "scale", "large", "small"), "Appearance", "Appearance"),
    APPEARANCE_ACCENT("Accent colours", listOf("colour", "color", "hue", "accent 2", "gradient"), "Appearance", "Appearance"),
    APPEARANCE_HAPTICS("Haptic feedback", listOf("vibrate", "vibration"), "Appearance", "Appearance"),
    APPEARANCE_LOADING("Loading animation", listOf("caduceus", "braille", "dots", "wave", "spinner"), "Appearance", "Appearance"),
    PRIVACY_LOCK("Biometric app lock", listOf("fingerprint", "face", "pin"), "Privacy & Security", "Privacy & Security"),
    PRIVACY_E2EE("End-to-end encryption", listOf("e2ee", "encrypt", "keys"), null, "Privacy & Security"),
    PRIVACY_SENSES("Senses", listOf("battery", "time", "place", "location", "context"), "Privacy & Security", "Privacy & Security"),
    SESSIONS_ARCHIVED("Archived sessions", listOf("archive", "hidden", "restore"), "Sessions", null),
    SESSIONS_PRUNE("Prune sessions", listOf("delete old", "clean up", "sweep"), "Sessions", null),
    ABOUT_CRASH("Crash log", listOf("diagnostics", "bug", "report", "share"), "About", "About"),
    ABOUT_VERSION("Version", listOf("about", "build", "keryx", "hermes"), "About", "About"),
    ;

    /** Stable — it is what a palette hit and a deep link say. */
    val id: String get() = name

    fun section(direct: Boolean): String? = if (direct) sectionDirect else sectionMatrix
}

object SettingsCatalog {
    val entries: List<SettingsRow> = SettingsRow.entries

    fun byId(id: String): SettingsRow? = entries.firstOrNull { it.id == id }

    /** Entries offered on this door whose title or keywords carry [query]. */
    fun search(query: String, direct: Boolean): List<SettingsRow> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return entries.filter { e ->
            e.section(direct) != null &&
                (e.title.contains(q, ignoreCase = true) || e.keywords.any { it.contains(q, ignoreCase = true) })
        }
    }
}
