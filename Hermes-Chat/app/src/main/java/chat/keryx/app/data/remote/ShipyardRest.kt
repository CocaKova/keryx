package chat.keryx.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The Shipyard's wire: the gateway's keryx payload surface (the Hermes Link base URL,
 * bearer-authed with API_SERVER_KEY) — the same server that declares `git` in the
 * capabilities probe, on BOTH doors. Deliberately NOT the direct door's REST base: the
 * tui gateway never mounts the git routes, which is how 2.6.0's first device walk got
 * an HTML page where JSON was promised (08-31). Matrix has no gateway capabilities at
 * all, so routing through the transport seam left that door silently empty.
 */
class ShipyardRest(
    baseUrl: String,
    private val apiKey: String,
    allowInsecure: Boolean = false,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    suspend fun shipyardRepos(): Result<List<chat.keryx.core.model.ShipyardRepo>> =
        get("/keryx/git/repos").mapCatching { body ->
            chat.keryx.core.protocol.ShipyardParser.parseRepos(json.parseToJsonElement(body))
                ?: error("unrecognized /keryx/git/repos payload")
        }

    suspend fun shipyardStatus(repo: String): Result<chat.keryx.core.model.ShipyardStatus?> =
        get("/keryx/git/status?path=" + enc(repo)).mapCatching { body ->
            chat.keryx.core.protocol.ShipyardParser.parseStatus(json.parseToJsonElement(body))
        }

    suspend fun shipyardReview(repo: String, scope: String): Result<chat.keryx.core.model.ShipyardReview> =
        get("/keryx/git/review/list?path=" + enc(repo) + "&scope=" + enc(scope)).mapCatching { body ->
            chat.keryx.core.protocol.ShipyardParser.parseReview(json.parseToJsonElement(body))
                ?: error("unrecognized review payload")
        }

    suspend fun shipyardDiff(repo: String, file: String, scope: String, staged: Boolean): Result<chat.keryx.core.model.ShipyardDiff> =
        get("/keryx/git/review/diff?path=" + enc(repo) + "&file=" + enc(file) + "&scope=" + enc(scope) + "&staged=" + staged)
            .mapCatching { body ->
                chat.keryx.core.protocol.ShipyardParser.parseDiff(json.parseToJsonElement(body))
                    ?: error("unrecognized diff payload")
            }

    private fun shipyardBody(repo: String, vararg extra: Pair<String, JsonElement>): String =
        buildJsonObject {
            put("path", JsonPrimitive(repo))
            extra.forEach { (k, v) -> put(k, v) }
        }.toString()

    suspend fun shipyardStage(repo: String, file: String?): Result<Unit> =
        send("POST", "/keryx/git/review/stage", shipyardBody(repo, "file" to (file?.let { JsonPrimitive(it) } ?: JsonNull))).map { }

    suspend fun shipyardUnstage(repo: String, file: String?): Result<Unit> =
        send("POST", "/keryx/git/review/unstage", shipyardBody(repo, "file" to (file?.let { JsonPrimitive(it) } ?: JsonNull))).map { }

    suspend fun shipyardCommitContext(repo: String): Result<chat.keryx.core.model.ShipyardCommitContext> =
        get("/keryx/git/review/commit-context?path=" + enc(repo)).mapCatching { body ->
            chat.keryx.core.protocol.ShipyardParser.parseCommitContext(json.parseToJsonElement(body))
                ?: error("unrecognized commit-context payload")
        }

    suspend fun shipyardCommit(repo: String, message: String, push: Boolean): Result<chat.keryx.core.model.ShipyardCommitResult> =
        send("POST", "/keryx/git/review/commit", shipyardBody(repo, "message" to JsonPrimitive(message), "push" to JsonPrimitive(push)))
            .mapCatching { body ->
                chat.keryx.core.protocol.ShipyardParser.parseCommitResult(json.parseToJsonElement(body))
                    ?: error("commit was refused")
            }

    suspend fun shipyardPush(repo: String): Result<Unit> =
        send("POST", "/keryx/git/review/push", shipyardBody(repo)).map { }

    suspend fun shipyardShipInfo(repo: String): Result<chat.keryx.core.model.ShipyardShipInfo> =
        get("/keryx/git/review/ship-info?path=" + enc(repo)).mapCatching { body ->
            chat.keryx.core.protocol.ShipyardParser.parseShipInfo(json.parseToJsonElement(body))
                ?: error("unrecognized ship-info payload")
        }

    private suspend fun get(path: String): Result<String> = send("GET", path, null)

    private suspend fun send(method: String, path: String, jsonBody: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(base + path)
                .method(method, jsonBody?.toRequestBody("application/json".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string().orEmpty()
                // Carry the server's own words into the failure — `shipyard_off` and friends
                // are diagnosed from this line by the delegate.
                if (code !in 200..299) error(
                    "HTTP $code for $path" + body.take(160).let { if (it.isBlank()) "" else " — $it" }
                )
                body
            }
        }
    }
}
