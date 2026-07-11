package chat.keryx.app.data.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal client for any OpenAI-compatible text-to-speech endpoint
 * (`POST /v1/audio/speech`, JSON in, audio bytes out).
 *
 * Deliberately provider-agnostic, like [SttClient]: a self-hosted server (Kokoro,
 * openedai-speech, LocalAI), OpenAI, or Groq all speak this shape. The user configures a base
 * URL and, when the provider requires them, a bearer key, a voice, and a model name.
 */
class TtsClient(
    baseUrl: String,
    private val apiKey: String = "",
    allowInsecure: Boolean = false,
) {
    // Accept a bare host, a /v1 base, or the full path — users paste all three.
    private val endpoint: String = baseUrl.trimEnd('/').let {
        when {
            it.endsWith("/audio/speech") -> it
            it.endsWith("/v1") -> "$it/audio/speech"
            else -> "$it/v1/audio/speech"
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // Synthesis is compute-bound on the server; give slow boxes room.
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

    /** Blocking; call from an IO dispatcher. Writes the synthesized mp3 into [into] and returns
     *  it, or throws with a readable reason. */
    fun synthesize(text: String, voice: String = "", model: String = "", into: File): File {
        val payload = buildJsonObject {
            put("input", clamp(text))
            put("response_format", "mp3")
            // Only when set — providers differ on which fields they require vs reject.
            if (voice.isNotBlank()) put("voice", voice)
            if (model.isNotBlank()) put("model", model)
        }
        val request = Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty()
                throw IllegalStateException("TTS server answered HTTP ${resp.code}: ${raw.take(200)}")
            }
            val body = resp.body ?: throw IllegalStateException("TTS reply had no audio body")
            body.byteStream().use { audio -> into.outputStream().use { audio.copyTo(it) } }
        }
        return into
    }

    private companion object {
        // OpenAI-compatible servers reject inputs past 4096 chars; cut at a sentence when we can.
        const val MAX_INPUT = 4000

        fun clamp(text: String): String {
            if (text.length <= MAX_INPUT) return text
            val head = text.take(MAX_INPUT)
            val cut = head.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            return if (cut > MAX_INPUT / 2) head.take(cut + 1) else head
        }
    }
}
