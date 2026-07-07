package chat.keryx.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal client for any OpenAI-compatible speech-to-text endpoint
 * (`POST /v1/audio/transcriptions`, multipart, answers `{"text": …}`).
 *
 * Deliberately provider-agnostic: a self-hosted server (Orpheus/Parakeet,
 * faster-whisper, Speaches), OpenAI, or Groq all speak this shape. The user
 * configures a base URL and, when the provider requires them, a bearer key
 * and a model name.
 */
class SttClient(
    baseUrl: String,
    private val apiKey: String = "",
    allowInsecure: Boolean = false,
) {
    // Accept a bare host, a /v1 base, or the full path — users paste all three.
    private val endpoint: String = baseUrl.trimEnd('/').let {
        when {
            it.endsWith("/audio/transcriptions") -> it
            it.endsWith("/v1") -> "$it/audio/transcriptions"
            else -> "$it/v1/audio/transcriptions"
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // Transcription is compute-bound on the server; give slow boxes room.
        .readTimeout(120, TimeUnit.SECONDS)
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

    /** Blocking; call from an IO dispatcher. Returns the transcript or throws with a readable reason. */
    fun transcribe(audio: File, model: String = ""): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            .apply { if (model.isNotBlank()) addFormDataPart("model", model) }
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("STT server answered HTTP ${resp.code}: ${raw.take(200)}")
            }
            val text = runCatching {
                json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return text ?: throw IllegalStateException("STT reply had no text field: ${raw.take(200)}")
        }
    }
}
