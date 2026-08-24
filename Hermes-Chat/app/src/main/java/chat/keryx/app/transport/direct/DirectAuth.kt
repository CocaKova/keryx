package chat.keryx.app.transport.direct

import chat.keryx.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The direct door's credentials, both dialects:
 *
 *  - **token** — the legacy `?token=` / `X-Hermes-Session-Token` session token, accepted by
 *    loopback / ungated gateways. Unchanged; [settings.directApiKey] is the credential.
 *  - **native** — a GATED gateway (`auth_required: true`, hermes ≥0.20.5). The dashboard
 *    unconditionally rejects the legacy token there, by design: REST wants
 *    `Authorization: Bearer <access_token>` and `/api/ws` wants a single-use 30s
 *    `?ticket=` minted at `POST /api/auth/ws-ticket`. Sign-in is RFC 8252 exactly as
 *    Hermes Desktop does it: our own PKCE pair, the SYSTEM browser at
 *    `/auth/native/authorize` (the gateway brokers a password login through its /login
 *    form), a loopback listener ON THE PHONE catching `?code=`, and
 *    `POST /auth/native/token` exchanging code + verifier for a 12h access / 30d refresh
 *    pair — which [bearer] rotates transparently at `/auth/native/refresh`.
 *
 * The refresh pair lives sealed in prefs (TokenVault, like every other credential). All
 * REST callers share one refresh mutex so a burst of 401s rotates once, not N times.
 */
class DirectAuth(
    private val settings: SettingsRepository,
    allowInsecure: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    val nativeMode: Boolean get() = settings.directAuthMode == MODE_NATIVE

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    // ---- REST credential -----------------------------------------------------------------

    /**
     * The header for one REST request: `Authorization: Bearer` (native, refreshed when the
     * access token is inside its expiry margin) or the legacy session-token header. Null =
     * nothing configured; the caller sends unauthenticated (public probes stay public).
     */
    suspend fun header(base: String): Pair<String, String>? = when {
        nativeMode -> bearer(base)?.let { "Authorization" to "Bearer $it" }
        settings.directApiKey.isNotBlank() -> "X-Hermes-Session-Token" to settings.directApiKey
        else -> null
    }

    /** Native access token, rotating via the refresh token when expired ([force] skips the
     *  clock check — the 401-retry path, where the server has already voted). */
    suspend fun bearer(base: String, force: Boolean = false): String? {
        val access = settings.directAccessToken
        val fresh = access.isNotBlank() &&
            System.currentTimeMillis() / 1000 < settings.directTokenExpiresAt - EXPIRY_MARGIN_S
        if (fresh && !force) return access
        return refreshLock.withLock {
            // Another caller may have rotated while we queued on the lock.
            val again = settings.directAccessToken
            if (!force && again.isNotBlank() &&
                System.currentTimeMillis() / 1000 < settings.directTokenExpiresAt - EXPIRY_MARGIN_S
            ) return@withLock again
            val rt = settings.directRefreshToken
            if (rt.isBlank()) return@withLock null
            val body = buildJsonObject { put("refresh_token", JsonPrimitive(rt)) }.toString()
            val resp = post(base.trimEnd('/') + "/auth/native/refresh", body) ?: return@withLock null
            storeTokens(resp)
            settings.directAccessToken.ifBlank { null }
        }
    }

    // ---- WS credential -------------------------------------------------------------------

    /**
     * The pre-encoded query for ONE WS connect attempt. Native mode mints a fresh single-use
     * ticket every time (they burn on use and expire in 30s — exactly the server's expected
     * pattern); token mode returns the stable legacy query. Throws when a ticket can't be
     * minted — the reconnect loop treats that as a failed attempt and backs off.
     */
    suspend fun wsCredentialQuery(base: String): String {
        if (!nativeMode) return "token=" + URLEncoder.encode(settings.directApiKey, "UTF-8")
        val at = bearer(base) ?: throw IllegalStateException("gateway sign-in expired")
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/api/auth/ws-ticket")
            .header("Authorization", "Bearer $at")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val raw = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 401) {
                    // The access token died between the bearer() check and the mint —
                    // rotate once and retry the mint before giving up.
                    val rotated = bearer(base, force = true)
                        ?: throw IllegalStateException("gateway sign-in expired")
                    return@use client.newCall(
                        req.newBuilder().header("Authorization", "Bearer $rotated").build()
                    ).execute().use { r2 ->
                        if (!r2.isSuccessful) throw IllegalStateException("ticket mint failed: HTTP ${r2.code}")
                        r2.body?.string().orEmpty()
                    }
                }
                if (!resp.isSuccessful) throw IllegalStateException("ticket mint failed: HTTP ${resp.code}")
                body
            }
        }
        val ticket = json.parseToJsonElement(raw).jsonObject["ticket"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("ticket mint reply had no ticket")
        return "ticket=" + URLEncoder.encode(ticket, "UTF-8")
    }

    // ---- browser sign-in (RFC 8252) ------------------------------------------------------

    /** Everything the UI needs to run one sign-in: the URL to open in the browser and the
     *  suspending wait that resolves when the loopback redirect lands + tokens are stored. */
    class PendingLogin internal constructor(
        val authorizeUrl: String,
        internal val server: ServerSocket,
        internal val verifier: String,
        internal val state: String,
    )

    /**
     * Open the loopback listener and build the authorize URL. Separate from [awaitLogin] so
     * the UI can fire the browser intent between the two. The listener binds a random port
     * on 127.0.0.1 — RFC 8252 §7.3 loopback-literal, the only redirect shape the gateway
     * accepts — and lives until [awaitLogin] returns or times out.
     */
    suspend fun beginLogin(base: String): PendingLogin = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(32)
        val challenge = pkceChallenge(verifier)
        val state = randomUrlSafe(16)
        // The bind is an IO op (StrictMode kills it on Main) — hence the suspend + IO hop.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val redirect = "http://127.0.0.1:${server.localPort}/callback"
        val url = base.trimEnd('/') + "/auth/native/authorize" +
            "?code_challenge=" + URLEncoder.encode(challenge, "UTF-8") +
            "&code_challenge_method=S256" +
            "&redirect_uri=" + URLEncoder.encode(redirect, "UTF-8") +
            "&state=" + URLEncoder.encode(state, "UTF-8")
        PendingLogin(url, server, verifier, state)
    }

    /**
     * Wait for the browser to bounce back to the loopback listener, then exchange the code
     * (+ our PKCE verifier) for the token pair and seal it into settings. Returns the
     * signed-in user id. Throws on timeout, state mismatch, or a failed exchange; the
     * listener is closed on every path.
     */
    suspend fun awaitLogin(base: String, pending: PendingLogin, timeoutMs: Long = LOGIN_TIMEOUT_MS): String =
        withContext(Dispatchers.IO) {
            val query = try {
                withTimeout(timeoutMs) {
                    runInterruptible {
                        pending.server.use { server ->
                            server.soTimeout = timeoutMs.toInt()
                            server.accept().use { sock ->
                                val line = sock.getInputStream().bufferedReader().readRequestLine()
                                sock.getOutputStream().write(CALLBACK_PAGE.toByteArray())
                                sock.getOutputStream().flush()
                                line.substringAfter("GET ", "").substringBefore(" ")
                                    .substringAfter("?", "")
                            }
                        }
                    }
                }
            } finally {
                runCatching { pending.server.close() }
            }
            val params = callbackParams(query)
            val code = params["code"] ?: throw IllegalStateException("browser returned no code")
            if (params["state"] != pending.state) throw IllegalStateException("state mismatch")
            val body = buildJsonObject {
                put("code", JsonPrimitive(code))
                put("code_verifier", JsonPrimitive(pending.verifier))
            }.toString()
            val resp = post(base.trimEnd('/') + "/auth/native/token", body)
                ?: throw IllegalStateException("token exchange failed")
            storeTokens(resp)
            resp["user_id"]?.jsonPrimitive?.contentOrNull ?: ""
        }

    fun clearTokens() {
        settings.directAccessToken = ""
        settings.directRefreshToken = ""
        settings.directTokenExpiresAt = 0L
    }

    // ---- internals -----------------------------------------------------------------------

    private fun storeTokens(o: kotlinx.serialization.json.JsonObject) {
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return
        val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: ""
        val exp = o["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L
        settings.commitDirectNativeTokens(access, refresh, exp)
    }

    /** POST json, parse json; null on non-2xx or malformed reply (callers decide severity). */
    private suspend fun post(url: String, body: String): kotlinx.serialization.json.JsonObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                }
            }.getOrNull()
        }

    private fun randomUrlSafe(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            .let { android.util.Base64.encodeToString(it, B64_FLAGS) }

    private fun BufferedReader.readRequestLine(): String = readLine().orEmpty()

    companion object {
        const val MODE_TOKEN = "token"
        const val MODE_NATIVE = "native"

        /** RFC 7636 S256: base64url-no-pad of SHA256 over the verifier ASCII bytes. */
        internal fun pkceChallenge(verifier: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
                .let { android.util.Base64.encodeToString(it, B64_FLAGS) }

        /** The loopback redirect query, decoded; tolerates blank segments. */
        internal fun callbackParams(query: String): Map<String, String> =
            query.split("&").filter { it.isNotBlank() }.associate {
                it.substringBefore("=") to
                    java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
            }
        private const val B64_FLAGS =
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        /** Rotate this far before the server's exp — one slow request must not straddle it. */
        private const val EXPIRY_MARGIN_S = 60L
        /** The whole interactive login window (the server's pending TTL is 10 min). */
        private const val LOGIN_TIMEOUT_MS = 600_000L
        private val CALLBACK_PAGE = """
            HTTP/1.1 200 OK
            Content-Type: text/html; charset=utf-8
            Connection: close

            <!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
            <body style="font-family:sans-serif;display:grid;place-items:center;height:90vh;background:#111;color:#eee">
            <div style="text-align:center"><h2>Signed in ✓</h2><p>Return to Keryx.</p></div>
        """.trimIndent().replace("\n", "\r\n")
    }
}
