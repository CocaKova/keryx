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
    /** Room ID of the last conversation the user had open, restored on next launch. */
    var lastRoomId: String?
    /** Slash commands the user has used most recently (most-recent first), for the command palette. */
    var recentCommands: List<String>
    /** Whether we've already asked the user to exempt the app from battery optimization (ask once). */
    var batteryPromptShown: Boolean
}
