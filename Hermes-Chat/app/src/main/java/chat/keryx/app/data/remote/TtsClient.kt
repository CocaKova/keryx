package chat.keryx.app.data.remote

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.InputStream
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
        .apply { if (allowInsecure) trustEverything() }
        .build()

    // The streaming client fails FAST: a live PCM stream delivers a chunk well under a second
    // apart, and its headers arrive the moment the server picks the request up. Twenty quiet
    // seconds means the server is wedged or queued behind something — the Call must learn that
    // now, not two minutes later while the orb pretends to think.
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun OkHttpClient.Builder.trustEverything() {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
        hostnameVerifier { _, _ -> true }
    }


    /** A live PCM reply: 16-bit little-endian mono at [sampleRate]. Close it to stop the server. */
    class PcmStream(val sampleRate: Int, private val response: okhttp3.Response) : Closeable {
        val input: InputStream = response.body?.byteStream() ?: InputStream.nullInputStream()
        override fun close() = response.close()
    }

    /**
     * Blocking. Asks for raw PCM so playback can start on the first bytes. Returns null when the
     * server answered with something else (it ignored `response_format` — a plain mp3-only
     * server), in which case the caller falls back to [synthesize]. Throws on HTTP/transport errors.
     */
    fun openStream(text: String, voice: String = "", model: String = ""): PcmStream? {
        val payload = buildJsonObject {
            put("input", clamp(text))
            put("response_format", "pcm")
            if (voice.isNotBlank()) put("voice", voice)
            if (model.isNotBlank()) put("model", model)
        }
        val request = Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = streamClient.newCall(request).execute()
        if (!resp.isSuccessful) {
            val raw = resp.body?.string().orEmpty()
            resp.close()
            throw IllegalStateException("TTS server answered HTTP ${resp.code}: ${raw.take(200)}")
        }
        val type = resp.header("Content-Type").orEmpty().lowercase()
        val rateHeader = resp.header("X-Sample-Rate")?.trim()?.toIntOrNull()
        val isPcm = type.startsWith("audio/pcm") || type.startsWith("audio/l16") ||
            type.startsWith("application/octet-stream") || rateHeader != null
        if (!isPcm) {
            resp.close()
            return null
        }
        return PcmStream(rateHeader ?: DEFAULT_PCM_RATE, resp)
    }

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
        /** OpenAI's `pcm` format is 24 kHz; servers that say otherwise send X-Sample-Rate. */
        const val DEFAULT_PCM_RATE = 24_000

        fun clamp(text: String): String {
            if (text.length <= MAX_INPUT) return text
            val head = text.take(MAX_INPUT)
            val cut = head.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            return if (cut > MAX_INPUT / 2) head.take(cut + 1) else head
        }
    }
}
