package chat.keryx.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import chat.keryx.app.presentation.CallSentenceChunker
import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.presentation.TtsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Call (1.22): a half-duplex voice conversation with the agent, built entirely from parts
 * that already exist — [CallAudio] ears → the configured STT endpoint → the room's normal
 * [ChatViewModel.sendMessage] (so the exchange lands in Matrix history, memory ingestion and
 * all) → the tier-1 stream tapped into [CallSentenceChunker] → the configured TTS endpoint,
 * sentence by sentence, so the agent starts *talking* about a second after it starts answering.
 *
 * Half-duplex on purpose: the mic is closed while the agent speaks (a phone speaker feeding its
 * own mic is how echo loops are born); tapping the orb interrupts playback and reopens the mic.
 * One synthesized sentence is always prefetched while the previous one plays, so gaps between
 * sentences are playback-sized, not synthesis-sized.
 */
class CallController(
    private val context: Context,
    private val viewModel: ChatViewModel,
) {
    enum class Phase { LISTENING, TRANSCRIBING, THINKING, SPEAKING, MUTED, ENDED }

    data class Ui(
        val phase: Phase = Phase.LISTENING,
        /** Last transcribed user utterance. */
        val heard: String = "",
        /** The sentence currently being spoken. */
        val speaking: String = "",
        val error: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val exchanges: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = CallAudio()
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /** Mic level for the orb (0..1). */
    val micLevel: StateFlow<Float> = audio.level

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var muted = false
    private var player: MediaPlayer? = null
    private var loopJob: Job? = null
    private var turnJob: Job? = null
    private var synthSeq = 0

    fun start() {
        if (loopJob != null) return
        requestFocus()
        loopJob = scope.launch { mainLoop() }
    }

    fun setMuted(m: Boolean) {
        muted = m
        if (m) interrupt()
    }

    fun isMuted(): Boolean = muted

    /** Stop the agent mid-sentence and go back to listening. The rest of its answer still
     *  lands in the room as text — the call just stops reading it aloud. */
    fun interrupt() {
        turnJob?.cancel()
    }

    fun end() {
        _ui.update { it.copy(phase = Phase.ENDED) }
        scope.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        viewModel.callTurnTap = null
        abandonFocus()
    }

    private suspend fun mainLoop() {
        while (scope.isActive) {
            if (muted) {
                _ui.update { it.copy(phase = Phase.MUTED) }
                while (muted && scope.isActive) delay(120)
                continue
            }
            _ui.update { it.copy(phase = Phase.LISTENING) }
            val take = audio.captureUtterance(context)
            if (take == null) {
                // Non-take (noise-only window, cancelled listen, or a failed mic open). The
                // failed-open case would otherwise re-enter capture as a hot spin.
                delay(150)
                continue
            }
            if (muted || !scope.isActive) { take.delete(); continue }
            _ui.update { it.copy(phase = Phase.TRANSCRIBING) }
            val heard = runCatching { transcribe(take) }
                .onFailure { e ->
                    _ui.update { it.copy(error = "hearing failed: ${e.message?.take(80)}") }
                    delay(1_200) // don't spin a hot loop against a dead endpoint
                }
                .getOrNull()?.trim().orEmpty()
            if (heard.isBlank()) continue
            _ui.update {
                it.copy(phase = Phase.THINKING, heard = heard, speaking = "", error = null,
                    exchanges = it.exchanges + 1)
            }
            val turn = scope.launch { runTurn(heard) }
            turnJob = turn
            turn.join()
            turnJob = null
        }
    }

    /** One exchange: send the utterance into the room, voice the streamed reply. */
    private suspend fun runTurn(userText: String) = coroutineScope {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val chunker = CallSentenceChunker()
        var streamedAny = false
        viewModel.callTurnTap = object : ChatViewModel.CallTurnTap {
            override fun onDelta(text: String) {
                streamedAny = true
                chunker.feed(text).forEach { sentences.trySend(it) }
            }

            override fun onTurnEnd(finalText: String?) {
                // Nothing streamed (pure tier-2 turn) → voice the committed body instead.
                if (!streamedAny && !finalText.isNullOrBlank()) {
                    chunker.feed(finalText).forEach { sentences.trySend(it) }
                }
                chunker.flush()?.let { sentences.trySend(it) }
                sentences.close()
            }

            override fun onTurnFailed() {
                chunker.flush()?.let { sentences.trySend(it) }
                sentences.close()
            }
        }
        try {
            viewModel.sendMessage(userText)
            // Synthesis runs one sentence ahead of playback; capacity 1 = exactly one prefetch.
            val ready = Channel<Pair<File, String>>(1)
            val synth = launch {
                for (sentence in sentences) {
                    val speakable = TtsText.speakable(sentence)
                    if (speakable.isBlank()) continue
                    val f = File(context.cacheDir, "call_tts_${synthSeq++}.mp3")
                    val ok = runCatching { synthesize(speakable, f) }.isSuccess
                    if (ok) ready.send(f to speakable) else f.delete()
                }
                ready.close()
            }
            // Watchdog: every stream path closes the channel, but a wedged gateway must not
            // hold the call hostage — fall back to listening and let the text land in chat.
            withTimeoutOrNull(TURN_WATCHDOG_MS) {
                for ((file, text) in ready) {
                    _ui.update { it.copy(phase = Phase.SPEAKING, speaking = text) }
                    try {
                        play(file)
                    } finally {
                        file.delete()
                    }
                }
                synth.join()
            } ?: synth.cancel()
        } finally {
            viewModel.callTurnTap = null
        }
    }

    private suspend fun transcribe(wav: File): String = suspendCancellableCoroutine { cont ->
        viewModel.transcribe(wav) { result ->
            if (cont.isActive) result.fold(
                { cont.resume(it) },
                { cont.resumeWithException(it) },
            )
        }
    }

    private suspend fun synthesize(text: String, into: File): File = suspendCancellableCoroutine { cont ->
        viewModel.synthesizeSpeech(text, into) { result ->
            if (cont.isActive) result.fold(
                { cont.resume(it) },
                { cont.resumeWithException(it) },
            )
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer()
        player = mp
        fun finish() {
            if (player === mp) player = null
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(Unit)
        }
        mp.setAudioAttributes(speechAttributes)
        mp.setOnCompletionListener { finish() }
        mp.setOnErrorListener { _, _, _ -> finish(); true }
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            finish()
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(speechAttributes)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        focusRequest = null
    }

    private companion object {
        /** A tool-heavy turn can stream nothing for a long time — generous, but not infinite. */
        const val TURN_WATCHDOG_MS = 5 * 60_000L
    }
}
