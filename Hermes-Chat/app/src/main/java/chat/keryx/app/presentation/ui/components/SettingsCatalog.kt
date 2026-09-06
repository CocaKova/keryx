package chat.keryx.app.presentation.ui.components

/**
 * Every settings row the app has, by name (2.10) — the catalog behind Settings' own search and
 * the drawer palette's "Settings" hits. The Hermes Desktop builds this from its config schema;
 * Keryx's settings are the app's own, so the list is written here and held by a test that reads
 * the screen's anchors back (SettingsCatalogTest).
 *
 * An entry names the section it lives in on each door, because the same row can sit under
 * "Gateway" on the direct door and "Connection" on Matrix. A row that only exists on one door
 * carries the other as null and is never offered there — search must not open an empty page.
 */
data class SettingsEntry(
    /** Stable — it is what a palette hit and a deep link say. */
    val id: String,
    val title: String,
    /** Words a person might type for it that the title does not contain. */
    val keywords: List<String> = emptyList(),
    val sectionDirect: String?,
    val sectionMatrix: String?,
) {
    fun section(direct: Boolean): String? = if (direct) sectionDirect else sectionMatrix
}

object SettingsCatalog {
    val entries: List<SettingsEntry> = listOf(
        SettingsEntry("account.identity", "Account", listOf("sign out", "logout", "who"), "Account", "Account"),
        SettingsEntry("account.transport", "Transport", listOf("matrix", "direct", "gateway", "switch door"), "Account", "Account"),
        SettingsEntry("agent.telemetry", "Show telemetry", listOf("check-ins", "footer", "cron"), "Agent", "Agent"),
        SettingsEntry("agent.alerts", "Mission alerts", listOf("notify", "kanban", "board"), "Agent", "Agent"),
        SettingsEntry("agent.resume", "Reopen last chat on launch", listOf("resume", "start", "fresh"), "Agent", "Agent"),
        SettingsEntry("companion.pick", "Companion", listOf("pet", "mascot", "petdex"), "Companion", "Companion"),
        SettingsEntry("connection.gateway", "Gateway address", listOf("url", "host", "dashboard"), "Gateway", null),
        SettingsEntry("connection.insecure", "Allow self-signed certificates", listOf("tls", "ssl", "https", "cert"), "Gateway", "Connection"),
        SettingsEntry("connection.homeserver", "Homeserver URL", listOf("matrix", "synapse"), null, "Connection"),
        SettingsEntry("connection.heralds", "Hermes Agent Matrix IDs", listOf("heralds", "agents", "council", "colour"), null, "Connection"),
        SettingsEntry("connection.push", "Push notifications", listOf("ntfy", "unifiedpush", "notify"), null, "Connection"),
        SettingsEntry("connection.reauth", "Re-authenticate", listOf("login", "password"), null, "Connection"),
        SettingsEntry("link.toggle", "Hermes Link", listOf("streaming", "sse", "side-channel", "api server"), "Gateway", "Hermes Link"),
        SettingsEntry("link.url", "Gateway URL", listOf("8642", "api key", "test link"), "Gateway", "Hermes Link"),
        SettingsEntry("voice.stt", "Voice dictation", listOf("mic", "microphone", "stt", "transcribe", "whisper", "parakeet"), "Voice", "Voice"),
        SettingsEntry("voice.tts", "Voice replies", listOf("tts", "speak", "read aloud", "speech", "call"), "Voice", "Voice"),
        SettingsEntry("appearance.theme", "Theme", listOf("dark", "light", "system", "mode"), "Appearance", "Appearance"),
        SettingsEntry("appearance.bubbles", "Bubble style", listOf("gilded", "solid", "gradient", "glass"), "Appearance", "Appearance"),
        SettingsEntry("appearance.text", "Text size", listOf("font", "scale", "large", "small"), "Appearance", "Appearance"),
        SettingsEntry("appearance.accent", "Accent colours", listOf("colour", "color", "hue", "accent 2", "gradient"), "Appearance", "Appearance"),
        SettingsEntry("appearance.haptics", "Haptic feedback", listOf("vibrate", "vibration"), "Appearance", "Appearance"),
        SettingsEntry("appearance.loading", "Loading animation", listOf("caduceus", "braille", "dots", "wave", "spinner"), "Appearance", "Appearance"),
        SettingsEntry("privacy.lock", "Biometric app lock", listOf("fingerprint", "face", "pin"), "Privacy & Security", "Privacy & Security"),
        SettingsEntry("privacy.e2ee", "End-to-end encryption", listOf("e2ee", "encrypt", "keys"), null, "Privacy & Security"),
        SettingsEntry("privacy.senses", "Senses", listOf("battery", "time", "place", "location", "context"), "Privacy & Security", "Privacy & Security"),
        SettingsEntry("sessions.archived", "Archived sessions", listOf("archive", "hidden", "restore"), "Sessions", null),
        SettingsEntry("sessions.prune", "Prune sessions", listOf("delete old", "clean up", "sweep"), "Sessions", null),
        SettingsEntry("about.crash", "Crash log", listOf("diagnostics", "bug", "report", "share"), "About", "About"),
        SettingsEntry("about.version", "Version", listOf("about", "build", "keryx", "hermes"), "About", "About"),
    )

    /** Entries offered on this door whose title or keywords carry [query]. */
    fun search(query: String, direct: Boolean): List<SettingsEntry> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return entries.filter { e ->
            e.section(direct) != null &&
                (e.title.contains(q, ignoreCase = true) || e.keywords.any { it.contains(q, ignoreCase = true) })
        }
    }
}
