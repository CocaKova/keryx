package chat.keryx.core.model

/** The gateway WebSocket's live condition — THE connection truth for the whole app.
 *  (The chat transport is the WS; if it's up, Hermes is reachable, full stop.) */
enum class LinkState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}
