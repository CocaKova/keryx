package chat.keryx.app.domain.repository

interface SettingsRepository {
    var homeserverUrl: String
    var matrixToken: String
    /** Agent MXIDs Keryx renders as heralds. One id, or several (comma / newline separated) —
     *  the first is the primary herald and keeps the user's own theme accents (2.3 Council). */
    var agentMatrixId: String
    /** Per-herald accent overrides: MXID localpart -> "#RRGGBB". Empty = derived from the palette. */
    var heraldAccents: Map<String, String>
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

    /**
     * Which transport carries the chat: "matrix" (the herald's home) or "direct" (straight
     * to a hermes-agent gateway — no homeserver anywhere). Chosen on the login screen;
     * the process builds its transport from this at startup.
     */
    var transportMode: String

    /** The direct door's session flag: a validated gateway credential is a login. */
    var directLoggedIn: Boolean

    /** The last Matrix username that signed in — a login-form prefill, nothing more. The
     *  password is never stored anywhere: the session token in Trixnity's store is the
     *  durable credential, and it survives every transport toggle on its own. */
    var lastMatrixUsername: String

    /**
     * ⚠️ The direct transport's dashboard address — NOT [gatewayUrl]. Two different services
     * share one host: [gatewayUrl] is Hermes Link (the keryx_stream payload on the messaging
     * gateway), this is the dashboard's WS/REST surface (`hermes dashboard`). Conflating them
     * broke the Hub the moment the direct door lit it (device-caught 2026-08-21: /health
     * against the dashboard answers the SPA's index.html), and a direct login was silently
     * clobbering a Matrix user's Hermes Link URL.
     */
    var directGatewayUrl: String

    /** The dashboard session token the direct transport authenticates with. */
    var directApiKey: String

    /**
     * Persist a door decision SYNCHRONOUSLY (commit, not apply) — the process relaunches the
     * moment this returns, and an async apply() loses that race: the relaunch comes up with
     * default settings and the login screen again (device-caught on the first direct-door
     * walk, 2026-08-21). One commit at the end also flushes any apply()s already queued on
     * the same prefs file. Null [gatewayUrl]/[gatewayApiKey] leave those keys untouched.
     */
    fun commitTransportDoor(gatewayUrl: String?, gatewayApiKey: String?, mode: String, directLoggedIn: Boolean)

    /**
     * The TOGGLE, not a logout: flip which door the next process life boots through, touching
     * neither credential set. The Matrix session stays in Trixnity's store, the sealed direct
     * token stays in prefs — whichever spine comes up resumes what it holds. Synchronous for
     * the same relaunch-race reason as [commitTransportDoor].
     */
    fun commitTransportMode(mode: String)

    /** Trixnity's store exists on disk. ⚠️ Only the NEGATIVE is proof: any matrix-mode boot
     *  creates the (possibly empty) store, so true means "maybe signed in" and the toggle's
     *  hint words it that way; false means the switch definitely lands on the login screen. */
    val matrixSessionOnFile: Boolean
    /** Master switch for the SSE side-channel; off = always use the Matrix fallback tier. */
    var sideChannelEnabled: Boolean
    /** True when the user actually wired a Hermes gateway (explicit URL or API key) — the
     *  structural "this install talks to an agent" signal. [gatewayUrl] can't serve: it
     *  auto-derives from the homeserver host, so it's non-blank for pure-Matrix users too. */
    val gatewayConfigured: Boolean

    // --- Real push (UnifiedPush) ---
    /** Master switch: register with a UnifiedPush distributor + a Matrix pusher. Default off —
     *  the in-process sync notifications remain the fallback tier. */
    var pushEnabled: Boolean
    /** Base URL of the Matrix push gateway (e.g. a self-hosted ntfy server — it serves
     *  /_matrix/push/v1/notify natively). Blank = pusher can't be registered. */
    var pushGatewayUrl: String
    /** The distributor-issued endpoint currently registered as our pushkey ("" = none). */
    var pushEndpoint: String
    /** Private ntfy topic for built-in (distributor-less) push; minted once by PushManager.
     *  Random because a default ntfy server's only auth is topic-name secrecy. */
    var builtinPushTopic: String
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

    // --- Voice replies (TTS) ---
    /** Speak each finished agent reply in the open chat automatically. Off by default. */
    var ttsAutoSpeak: Boolean
    /** Base URL of any OpenAI-compatible `/v1/audio/speech` server (Kokoro, openedai-speech,
     *  LocalAI, OpenAI…). Bare host, `/v1` base, or full path all accepted. Blank = the
     *  device's built-in voice — spoken replies never require a server. */
    var ttsUrl: String
    /** Optional bearer key for the TTS endpoint. */
    var ttsApiKey: String
    /** Optional voice name (e.g. "alloy", "af_sky"); sent only when non-blank. */
    var ttsVoice: String
    /** Optional model name (e.g. "tts-1", "kokoro"); sent only when non-blank. */
    var ttsModel: String

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
