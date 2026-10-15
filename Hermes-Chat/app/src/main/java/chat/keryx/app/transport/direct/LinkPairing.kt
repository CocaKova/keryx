package chat.keryx.app.transport.direct

import chat.keryx.core.pair.PairingLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The phone's half of pairing (3.0): trade the scanned one-time code for a Hermes Link token of
 * this phone's own at `POST <link>/keryx/pair/claim` (keryx-stream 0.6). The token goes into the
 * sealed Link key like a typed one; the gateway's master key never reaches the phone.
 *
 * Runs after the gateway sign-in succeeded, so a code is only spent by a phone that could sign in.
 */
object LinkPairing {
    private val json = Json { ignoreUnknownKeys = true }

    /** The token, or a failure whose message the sign-in can show as is. */
    suspend fun claim(pairing: PairingLink, deviceName: String, allowInsecure: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("code", JsonPrimitive(pairing.code))
                put("name", JsonPrimitive(deviceName))
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(pairing.link + "/keryx/pair/claim").post(body).build()
            runCatching {
                client(allowInsecure).newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val said = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    when (resp.code) {
                        200 -> parsed?.get("token")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: error("Hermes Link's answer had no token.")
                        // An older keryx-stream has no claim route: its relay asks for a bearer (401)
                        // or the API server behind it has no such path (404/405).
                        401, 404, 405 -> error(
                            "The gateway's keryx-stream can't pair yet. Update it to 0.6 and restart the gateway.",
                        )
                        else -> error(said ?: "Hermes Link answered HTTP ${resp.code}.")
                    }
                }
            }.recoverCatching { e ->
                if (e is IllegalStateException) throw e
                error("Hermes Link didn't answer at ${pairing.link}.")
            }
        }

    private fun client(allowInsecure: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // A claim is answered by the Link itself: following a redirect would hand the code to
        // whoever the redirect names.
        .followRedirects(false)
        .followSslRedirects(false)
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
}
