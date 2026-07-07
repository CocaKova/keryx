package chat.keryx.app.domain.repository

interface SettingsRepository {
    var homeserverUrl: String
    var matrixToken: String
    var agentMatrixId: String
    /** Allow self-signed / invalid TLS certs. Off by default; for local servers only. */
    var allowInsecure: Boolean
    /** Room IDs the user pinned as Quick Rooms, for fast access when there are many rooms. */
    var pinnedRoomIds: Set<String>
    var biometricLockEnabled: Boolean
    var e2eeEnabled: Boolean
    var hapticsEnabled: Boolean
    var animationStyle: String
    /** Message bubble style: "Solid" | "Gradient" | "Glass". */
    var bubbleStyle: String
    /** Message text size multiplier (1.0 = default). */
    var messageTextScale: Float
    var syncToken: String?
    var accentColorHex: String
    var accentColor2Hex: String
    /** Room ID of the last conversation the user had open, restored on next launch. */
    var lastRoomId: String?
    /** Slash commands the user has used most recently (most-recent first), for the command palette. */
    var recentCommands: List<String>
    /** Whether we've already asked the user to exempt the app from battery optimization (ask once). */
    var batteryPromptShown: Boolean

    // --- Hermes side-channel streaming (tier-1) ---
    /** Base URL of the Hermes gateway API server (e.g. http://your-gateway-host:8642). Blank = disabled. */
    var gatewayUrl: String
    /** Bearer key for the gateway API server (API_SERVER_KEY). */
    var gatewayApiKey: String
    /** Master switch for the SSE side-channel; off = always use the Matrix fallback tier. */
    var sideChannelEnabled: Boolean
    /** Show automated telemetry blocks (runtime footer, cron check-ins) in the chat. */
    var showTelemetry: Boolean

    // --- Voice dictation (universal OpenAI-compatible STT) ---
    /** Base URL of any OpenAI-compatible `/v1/audio/transcriptions` server (self-hosted, OpenAI,
     *  Groq…). Bare host, `/v1` base, or full path all accepted. Blank = mic hidden. */
    var sttUrl: String
    /** Optional bearer key for the STT endpoint. */
    var sttApiKey: String
    /** Optional model name; only needed when the provider requires one (e.g. "whisper-1"). */
    var sttModel: String

    // --- Mission alerts (background kanban-event watcher) ---
    /** Opt-in 15-minute background check that notifies on completed/blocked/given-up missions. */
    var missionAlertsEnabled: Boolean
    /** Last task_events rowid the watcher has seen; -1 = baseline on next run without notifying. */
    var missionEventsCursor: Long

    // --- Agent Hub offline cache ---
    /** Last raw JSON the gateway answered for [path], or null when uncached. */
    fun hubSnapshot(path: String): String?
    fun putHubSnapshot(path: String, json: String)

    // --- Composer drafts ---
    /** Unsent composer text for a room ("" when none). */
    fun getDraft(roomId: String): String
    /** Persist (or with "" clear) the unsent composer text for a room. */
    fun setDraft(roomId: String, text: String)
}
