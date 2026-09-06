package chat.keryx.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voice: dictation (universal OpenAI-compatible STT) and spoken replies (TTS). */
class VoiceDelegate(deps: GatewayDeps) {
    private val scope = deps.scope
    private val settings = deps.settings

    // --- Voice dictation (universal OpenAI-compatible STT) ---
    private val _sttUrl = MutableStateFlow(settings.sttUrl)
    val sttUrl: StateFlow<String> = _sttUrl.asStateFlow()

    private val _sttApiKey = MutableStateFlow(settings.sttApiKey)
    val sttApiKey: StateFlow<String> = _sttApiKey.asStateFlow()

    private val _sttModel = MutableStateFlow(settings.sttModel)
    val sttModel: StateFlow<String> = _sttModel.asStateFlow()

    // --- Voice replies (TTS) ---
    private val _ttsAutoSpeak = MutableStateFlow(settings.ttsAutoSpeak)
    val ttsAutoSpeak: StateFlow<Boolean> = _ttsAutoSpeak.asStateFlow()

    private val _ttsUrl = MutableStateFlow(settings.ttsUrl)
    val ttsUrl: StateFlow<String> = _ttsUrl.asStateFlow()

    private val _ttsApiKey = MutableStateFlow(settings.ttsApiKey)
    val ttsApiKey: StateFlow<String> = _ttsApiKey.asStateFlow()

    private val _ttsVoice = MutableStateFlow(settings.ttsVoice)
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    private val _ttsModel = MutableStateFlow(settings.ttsModel)
    val ttsModel: StateFlow<String> = _ttsModel.asStateFlow()


    fun setSttUrl(url: String) {
        _sttUrl.value = url
        settings.sttUrl = url
    }

    fun setSttApiKey(key: String) {
        _sttApiKey.value = key
        settings.sttApiKey = key
    }

    fun setSttModel(model: String) {
        _sttModel.value = model
        settings.sttModel = model
    }

    /** Uploads a finished dictation take to the configured STT endpoint. The recording is
     *  deleted afterwards either way; [onResult] fires on the main thread. */
    fun transcribe(audio: java.io.File, onResult: (Result<String>) -> Unit) {
        val url = _sttUrl.value.trim()
        if (url.isBlank()) {
            audio.delete()
            onResult(Result.failure(IllegalStateException("No STT endpoint configured")))
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    chat.keryx.app.data.remote.SttClient(url, _sttApiKey.value.trim(), settings.allowInsecure)
                        .transcribe(audio, _sttModel.value.trim())
                }
            }
            audio.delete()
            onResult(result)
        }
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        _ttsAutoSpeak.value = enabled
        settings.ttsAutoSpeak = enabled
    }

    fun setTtsUrl(url: String) {
        _ttsUrl.value = url
        settings.ttsUrl = url
        ttsStreams = null
    }

    /** Whether the configured server streams PCM: null = not asked yet, learned on first use. */
    @Volatile private var ttsStreams: Boolean? = null

    private fun ttsClient(): chat.keryx.app.data.remote.TtsClient? {
        val url = _ttsUrl.value.trim()
        if (url.isBlank()) return null
        return chat.keryx.app.data.remote.TtsClient(url, _ttsApiKey.value.trim(), settings.allowInsecure)
    }

    /**
     * Blocking (worker thread). Opens a live PCM stream for [text], or returns null when the
     * server does not stream — remembered so later calls skip straight to [synthesizeBlocking].
     * Throws when no endpoint is configured or the server errors.
     */
    fun openSpeechStream(text: String): chat.keryx.app.data.remote.TtsClient.PcmStream? {
        val client = ttsClient() ?: throw IllegalStateException("No TTS endpoint configured")
        if (ttsStreams == false) return null
        val stream = client.openStream(text, _ttsVoice.value.trim(), _ttsModel.value.trim())
        ttsStreams = stream != null
        return stream
    }

    /** Blocking (worker thread) mp3 synthesis into [into]. */
    fun synthesizeBlocking(text: String, into: java.io.File): java.io.File {
        val client = ttsClient() ?: throw IllegalStateException("No TTS endpoint configured")
        return client.synthesize(text, _ttsVoice.value.trim(), _ttsModel.value.trim(), into)
    }

    fun setTtsApiKey(key: String) {
        _ttsApiKey.value = key
        settings.ttsApiKey = key
    }

    fun setTtsVoice(voice: String) {
        _ttsVoice.value = voice
        settings.ttsVoice = voice
    }

    fun setTtsModel(model: String) {
        _ttsModel.value = model
        settings.ttsModel = model
    }

    /** Synthesizes [text] on the configured `/v1/audio/speech` endpoint into [into]. The caller
     *  owns the file (and plays/deletes it); [onResult] fires on the main thread. */
    fun synthesizeSpeech(text: String, into: java.io.File, onResult: (Result<java.io.File>) -> Unit) {
        val url = _ttsUrl.value.trim()
        if (url.isBlank()) {
            onResult(Result.failure(IllegalStateException("No TTS endpoint configured")))
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    chat.keryx.app.data.remote.TtsClient(url, _ttsApiKey.value.trim(), settings.allowInsecure)
                        .synthesize(text, _ttsVoice.value.trim(), _ttsModel.value.trim(), into)
                }
            }
            onResult(result)
        }
    }


    /**
     * Blocking (worker thread). Reaches both voice endpoints (`GET …/v1/models`, 4 s) and returns
     * a one-line problem for the Call screen, or null when both answer. Any HTTP status counts
     * as reachable — a 404 from a minimal server still proves the host and port are right.
     */
    fun probe(): String? {
        fun reach(label: String, base: String): String? {
            val url = base.trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" } + "/models"
            return try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4_000; conn.readTimeout = 4_000
                conn.responseCode; conn.disconnect(); null
            } catch (e: Exception) {
                "$label unreachable at $base (${e.javaClass.simpleName.removeSuffix("Exception")})"
            }
        }
        val stt = _sttUrl.value.trim(); val tts = _ttsUrl.value.trim()
        if (stt.isBlank()) return "no STT endpoint set"
        if (tts.isBlank()) return "no TTS endpoint set"
        return reach("ears (STT)", stt) ?: reach("voice (TTS)", tts)
    }

    /** Both voice endpoints are configured — the Call has ears and a mouth. */
    fun callReady(): Boolean = _sttUrl.value.isNotBlank() && _ttsUrl.value.isNotBlank()
}
