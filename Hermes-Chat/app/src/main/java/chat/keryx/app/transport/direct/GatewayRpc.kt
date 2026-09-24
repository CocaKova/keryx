package chat.keryx.app.transport.direct

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The Keryx transport spine: JSON-RPC 2.0 over the gateway's `WS /api/ws` — the same
 * dispatcher surface Hermes Desktop and the TUI use. See TALARIA-PROTOCOL.md §3.
 *
 * One socket carries everything conversational: `prompt.submit`, streaming deltas, tool
 * events, approvals, reactions, wake word. Requests are matched to responses by id (slow
 * handlers respond out of order); everything else arrives as `method:"event"` frames and
 * is fanned out through [events].
 *
 * Auth, both dialects via [credentialQuery]: token mode (`?token=`, stable — ungated /
 * loopback gateways) and ticket mode (`?ticket=`, minted fresh per attempt by [DirectAuth]
 * — GATED gateways, hermes ≥0.20.5, where the legacy token is rejected by design).
 *
 * Deliberately NO Origin header is sent (OkHttp adds none): the stock WS guard admits
 * absent Origins unconditionally, so the WebView-shell 403 saga cannot happen here. The
 * Host header OkHttp derives from the dialed URL, which must therefore match the
 * gateway's bound host exactly (stock `_is_accepted_host` rule).
 */
class GatewayRpc(
    baseUrl: String,
    /** Mints the pre-encoded credential query for ONE connect attempt ("token=…" or
     *  "ticket=…"). A supplier, not a string: gated gateways issue single-use 30s tickets,
     *  so every attempt — first dial and every reconnect — needs a fresh mint. */
    private val credentialQuery: suspend () -> String,
    allowInsecure: Boolean = false,
) {
    sealed interface ConnState {
        data object Disconnected : ConnState
        data object Connecting : ConnState
        /** `gateway.ready` arrived; [skin] is the server's branding payload (colors, name). */
        data class Ready(val skin: JsonObject?) : ConnState
        /** Terminal for this attempt; the reconnect loop decides what happens next.
         *  [wsCloseCode] 4401 = credential rejected, 4403 = Host/Origin boundary. */
        data class Failed(val reason: String, val wsCloseCode: Int? = null) : ConnState
    }

    /** One `method:"event"` frame: `params.type` + `params.session_id` + `params.payload`. */
    data class GatewayEvent(val type: String, val sessionId: String, val payload: JsonObject?)

    /**
     * One server→client request: the backend asking THIS client a question (`clarify`,
     * `approval`, `sudo`, `secret`, …) and blocking until a response frame carrying [id] comes
     * back — the agent's turn is stopped inside a tool call until then. The id is a string
     * (`srq-<12 hex>`) so it never collides with our own integer request ids; that is how the
     * two directions are told apart on one socket (hermes `tui_gateway/server_requests.py`).
     */
    data class ServerRequest(val id: String, val method: String, val params: JsonObject)

    /** What one inbound frame is. Pure, so the split is testable without a socket. */
    sealed interface Inbound {
        data class Event(val event: GatewayEvent) : Inbound
        data class Request(val request: ServerRequest) : Inbound
        /** A response to one of OUR requests, keyed by the integer id we minted. */
        data class Response(val id: Long, val result: JsonObject?, val error: JsonObject?) : Inbound
        /** A notification we don't model, or a frame with neither id nor method. */
        data object Ignored : Inbound
    }

    class RpcException(val code: Int, message: String) : Exception("rpc $code: $message")

    private val json = Json { ignoreUnknownKeys = true }
    private val wsBase: String = baseUrl.trimEnd('/')
        .replaceFirst("http://", "ws://")
        .replaceFirst("https://", "wss://") + "/api/ws"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        // The JSON-RPC protocol has no app-level ping; WS-level pings keep NATs open and
        // detect dead sockets (the gateway itself pings every 20s on non-loopback binds).
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply {
            if (allowInsecure) {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    // extraBufferCapacity absorbs the 33ms-coalesced delta bursts without suspending the
    // socket reader; DROP_OLDEST is wrong here (a lost delta corrupts the transcript) so
    // we buffer generously and let backpressure suspend emit() only in pathological cases.
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    // Questions the backend is waiting on. Rare and never bursty; the buffer only has to cover a
    // reconnect replay of every open request at once.
    private val _serverRequests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    private val nextId = AtomicLong(1)
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    @Volatile private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var wantConnected = false
    /** Did the CURRENT attempt reach `gateway.ready`? Decides whether backoff resets. */
    @Volatile private var reachedReady = false
    @Volatile private var scope: CoroutineScope? = null

    /**
     * A signal that the outside world changed and waiting out the backoff is pointless —
     * the network came back, or the user asked. CONFLATED: many kicks collapse into one
     * early retry, and a stale one costs a single extra connection attempt.
     */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED,
    )

    /**
     * Reconnect NOW instead of sleeping out the remaining backoff.
     *
     * The backoff exists for a gateway that is down; it is exactly wrong for a phone whose
     * route just came back (VPN up, wifi joined, plane mode off). Without this the app sits
     * on a 30 s timer while the gateway is reachable — which reads as "the app is broken,
     * reopen it", and reopening only helps because a fresh process starts at 1 s again.
     */
    fun retryNow() {
        if (wantConnected) wake.trySend(Unit)
    }

    /** Open the socket and keep it open (exponential backoff reconnect) until [close]. */
    fun connect(scope: CoroutineScope) {
        this.scope = scope
        wantConnected = true
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var backoffMs = 1_000L
            while (wantConnected) {
                _state.value = ConnState.Connecting
                reachedReady = false
                // Mint this attempt's credential. A mint failure is an attempt failure: back
                // off and retry — EXCEPT a dead sign-in (refresh token rejected), which no
                // retry can heal; surface it as 4401 so the one re-onboard path serves both
                // dialects.
                val query = try {
                    credentialQuery()
                } catch (e: Exception) {
                    val dead = e.message?.contains("sign-in expired") == true
                    _state.value = ConnState.Failed(e.message ?: "credential unavailable", if (dead) 4401 else null)
                    if (dead) break
                    withTimeoutOrNull(backoffMs) { wake.receive() }
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    continue
                }
                val closed = CompletableDeferred<Unit>()
                openSocket("$wsBase?$query", closed)
                // Wait for this socket's lifetime to end, then back off and retry.
                closed.await()
                if (!wantConnected) break
                val st = _state.value
                // Credential rejection won't fix itself — stop and let the UI re-onboard.
                // WIRE FACT (measured through tailscale-serve against hermes 0.20.5): a
                // pre-accept ws.close(4401/4403) surfaces to the client as a plain HTTP 403
                // upgrade reject — the 4xx01 close codes only exist when the server accepts
                // first. So the terminal set is all four: 401/403 (upgrade reject, via
                // onFailure's response code) and 4401/4403 (post-accept close).
                if (st is ConnState.Failed && st.wsCloseCode in TERMINAL_CREDENTIAL_CODES) break
                // Reset on a socket that actually WORKED, so a long-lived connection that
                // finally drops retries promptly instead of inheriting an ancient backoff.
                // (Testing `_state` here instead — as this once did — can never be true: by
                // this line the socket has closed, so the state is Failed. The backoff only
                // ever grew, pinned at 30 s after a few failures, and an overnight outage
                // guaranteed the worst case for the morning's first launch.)
                if (reachedReady) backoffMs = 1_000L
                // Sleep out the backoff — unless something tells us the world changed.
                withTimeoutOrNull(backoffMs) { wake.receive() }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun close() {
        wantConnected = false
        reconnectJob?.cancel()
        socket?.close(1000, "client closing")
        socket = null
        failAllPending("connection closed")
        _state.value = ConnState.Disconnected
    }

    private fun openSocket(url: String, closed: CompletableDeferred<Unit>) {
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let(::route)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value !is ConnState.Failed) {
                    _state.value = ConnState.Failed(reason.ifBlank { "closed $code" }, code)
                }
                failAllPending("socket closed: $code $reason")
                closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                // A rejected upgrade (4401/4403 close-before-accept) surfaces here with the
                // HTTP response; map it so onboarding can give a targeted hint.
                val code = response?.code
                _state.value = ConnState.Failed(t.message ?: "connect failed", code)
                failAllPending("socket failure: ${t.message}")
                closed.complete(Unit)
            }
        })
    }

    private fun route(frame: JsonObject) {
        when (val inbound = classify(frame)) {
            is Inbound.Event -> {
                val ev = inbound.event
                if (ev.type == "gateway.ready") {
                    reachedReady = true
                    _state.value = ConnState.Ready(ev.payload?.get("skin") as? JsonObject)
                    // Say so on EVERY fresh socket: the backend keeps the flag per connection,
                    // and without it every clarify/approval/sudo for our sessions fails in the
                    // same millisecond it is asked (the wire pinned on 09-24: three clarify calls,
                    // each back empty in 20 ms). An older backend answers -32601; ignored.
                    advertiseCapabilities()
                }
                _events.tryEmit(ev)
            }
            is Inbound.Request -> _serverRequests.tryEmit(inbound.request)
            is Inbound.Response -> {
                val waiter = pending.remove(inbound.id) ?: return
                val error = inbound.error
                if (error != null) {
                    val code = error["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1
                    val msg = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
                    waiter.completeExceptionally(RpcException(code, msg))
                } else {
                    waiter.complete(inbound.result ?: JsonObject(emptyMap()))
                }
            }
            Inbound.Ignored -> Unit
        }
    }

    /**
     * Tell the backend this connection answers server→client requests. Fire-and-forget: the
     * ack lists the request methods it may send, which we don't need, and a failure only means
     * an older backend that still speaks `*.request` events — those are handled as before.
     */
    private fun advertiseCapabilities() {
        scope?.launch {
            runCatching {
                request("client.capabilities", buildJsonObject { put("server_requests", JsonPrimitive(true)) })
            }
        }
    }

    /** Answer a [ServerRequest] with [result]. False when the socket is gone (the backend will
     *  time the request out on its own; a reconnect replays it). */
    fun respondToRequest(id: String, result: JsonObject): Boolean {
        val ws = socket ?: return false
        return ws.send(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("result", result)
        }.toString())
    }

    /** Refuse a [ServerRequest] this app has no surface for. `-32601` is the JSON-RPC
     *  method-not-found code, which the backend reads as "no handler here" and fails the
     *  tool fast instead of waiting out the deadline. */
    fun declineRequest(id: String, message: String = "not supported by this client"): Boolean {
        val ws = socket ?: return false
        return ws.send(buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("error", buildJsonObject {
                put("code", JsonPrimitive(-32601))
                put("message", JsonPrimitive(message))
            })
        }.toString())
    }

    /**
     * Issue one JSON-RPC request. Throws [RpcException] on an error response, or a timeout /
     * IllegalState when the socket can't carry it. `prompt.submit` acks fast (`status:
     * streaming`) — the turn itself arrives via [events], so the default timeout is fine.
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 30_000,
    ): JsonObject {
        val ws = socket ?: throw IllegalStateException("gateway socket not connected")
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = waiter
        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        if (!ws.send(frame.toString())) {
            pending.remove(id)
            throw IllegalStateException("gateway socket send failed")
        }
        return try {
            withTimeout(timeoutMs) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun failAllPending(reason: String) {
        val err = IllegalStateException(reason)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    companion object {
        fun tokenQuery(token: String): String = "token=" + URLEncoder.encode(token, "UTF-8")

        /**
         * Which of the three things one frame is. JSON-RPC is peer-to-peer on this socket:
         * `method:"event"` is a notification; a `method` with a STRING `id` is the backend
         * asking us (`srq-…`, [ServerRequest]); an integer `id` with no method is the answer to
         * something we sent. Before 2.13.6 the string-id case fell through the integer parse
         * and was dropped on the floor — the backend then waited on an answer that could
         * never come, and with no capability handshake, didn't even wait.
         */
        fun classify(frame: JsonObject): Inbound {
            val method = frame["method"]?.jsonPrimitive?.contentOrNull
            val idPrim = frame["id"] as? JsonPrimitive
            if (method == "event") {
                val params = frame["params"]?.jsonObject ?: return Inbound.Ignored
                val type = params["type"]?.jsonPrimitive?.contentOrNull ?: return Inbound.Ignored
                val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull ?: ""
                return Inbound.Event(GatewayEvent(type, sessionId, params["payload"] as? JsonObject))
            }
            if (method != null) {
                if (idPrim == null || !idPrim.isString) return Inbound.Ignored
                return Inbound.Request(
                    ServerRequest(idPrim.content, method, frame["params"] as? JsonObject ?: JsonObject(emptyMap())),
                )
            }
            val id = idPrim?.longOrNull ?: return Inbound.Ignored
            return Inbound.Response(id, frame["result"] as? JsonObject, frame["error"] as? JsonObject)
        }

        /** Failure codes no retry can heal: the credential (or Host boundary) is rejected
         *  as configured. HTTP 401/403 = upgrade rejected before accept (what a gated
         *  gateway actually sends on the wire); 4401/4403 = the server accepted then
         *  closed. The reconnect loop stops on these and the transport re-onboards. */
        val TERMINAL_CREDENTIAL_CODES = setOf(401, 403, 4401, 4403)
    }
}
