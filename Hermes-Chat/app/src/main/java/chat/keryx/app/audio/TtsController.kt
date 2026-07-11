package chat.keryx.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Speaks agent replies aloud. Two engines behind one state machine:
 *
 * - **System** — the device's built-in [TextToSpeech]; needs no server, no config, no network.
 * - **File** — plays an mp3 a `/v1/audio/speech` server synthesized (the caller fetches it via
 *   `TtsClient`; this class only owns playback).
 *
 * Exactly one thing speaks at a time: starting either engine silences the other. All state
 * transitions land on the main thread — [TextToSpeech] progress callbacks arrive on a binder
 * thread and are marshalled here.
 */
class TtsController(
    private val context: Context,
    private val onError: (String) -> Unit = {},
) {
    enum class Phase { IDLE, PREPARING, SPEAKING }
    data class State(val messageId: String? = null, val phase: Phase = Phase.IDLE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Invalidates in-flight work (HTTP synthesis, async TTS init) after a stop: results carrying
    // a stale generation are dropped instead of speaking over whatever came next.
    private var generation = 0

    // --- System engine ------------------------------------------------------------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Utterance queued while the engine's async init is still in flight. */
    private var pendingUtterance: Triple<String, String, Int>? = null // (messageId, text, generation)
    private var lastUtteranceId = ""

    // --- File engine --------------------------------------------------------------------------

    private var player: MediaPlayer? = null
    private var playingFile: File? = null

    /** Speak [text] with the device's built-in voice. Silences anything currently speaking. */
    fun speakSystem(messageId: String, text: String) {
        stop()
        val gen = ++generation
        _state.value = State(messageId, Phase.PREPARING)
        val engine = tts
        when {
            engine != null && ttsReady -> speakNow(engine, messageId, text, gen)
            engine != null -> pendingUtterance = Triple(messageId, text, gen)
            else -> {
                pendingUtterance = Triple(messageId, text, gen)
                tts = TextToSpeech(context) { status ->
                    main.post {
                        if (status == TextToSpeech.SUCCESS) {
                            ttsReady = true
                            tts?.setOnUtteranceProgressListener(progressListener)
                            pendingUtterance?.let { (id, body, g) ->
                                pendingUtterance = null
                                if (g == generation) tts?.let { speakNow(it, id, body, g) }
                            }
                        } else {
                            pendingUtterance = null
                            _state.value = State()
                            onError("No speech engine on this device")
                        }
                    }
                }
            }
        }
    }

    /** Marks [messageId] as fetching server speech. Returns the token [playFile] must echo. */
    fun prepare(messageId: String): Int {
        stop()
        _state.value = State(messageId, Phase.PREPARING)
        return ++generation
    }

    /** Play a synthesized file. Ignored when [generation] is stale (user stopped meanwhile). */
    fun playFile(messageId: String, generation: Int, file: File) {
        if (generation != this.generation) {
            file.delete()
            return
        }
        if (!requestFocus()) {
            file.delete()
            _state.value = State()
            onError("Audio unavailable")
            return
        }
        val mp = MediaPlayer()
        mp.setAudioAttributes(playbackAttributes)
        mp.setOnCompletionListener { main.post { stopFilePlayback() } }
        mp.setOnErrorListener { _, _, _ ->
            main.post { stopFilePlayback(); onError("Speech playback failed") }
            true
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            runCatching { mp.release() }
            file.delete()
            abandonFocus()
            _state.value = State()
            onError("Speech playback failed: ${e.message}")
            return
        }
        player = mp
        playingFile = file
        _state.value = State(messageId, Phase.SPEAKING)
    }

    /** Silences both engines and abandons audio focus. Safe to call at any time. */
    fun stop() {
        generation++
        pendingUtterance = null
        runCatching { tts?.stop() }
        stopFilePlayback()
    }

    /** [stop] plus engine teardown; call when the hosting screen leaves for good. */
    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun speakNow(engine: TextToSpeech, messageId: String, text: String, gen: Int) {
        if (text.isBlank()) {
            _state.value = State()
            return
        }
        if (!requestFocus()) {
            _state.value = State()
            onError("Audio unavailable")
            return
        }
        // Long replies exceed the engine's per-utterance cap; chunk and mark done on the last one.
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val chunks = text.chunked(max)
        chunks.forEachIndexed { index, chunk ->
            val utteranceId = "keryx_tts_${gen}_$index"
            if (index == chunks.lastIndex) lastUtteranceId = utteranceId
            engine.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                utteranceId,
            )
        }
        _state.value = State(messageId, Phase.SPEAKING)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (utteranceId == lastUtteranceId) main.post {
                if (_state.value.phase == Phase.SPEAKING) {
                    abandonFocus()
                    _state.value = State()
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { abandonFocus(); _state.value = State() }
        }
    }

    private fun stopFilePlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingFile?.delete()
        playingFile = null
        abandonFocus()
        _state.value = State()
    }

    // --- Audio focus --------------------------------------------------------------------------

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Polite guest: a phone call or another player silences us; we never barge back in.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            main.post { stop() }
        }
    }

    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        }
    }
}
