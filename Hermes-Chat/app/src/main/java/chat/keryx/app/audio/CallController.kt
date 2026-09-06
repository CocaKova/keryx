package chat.keryx.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import kotlinx.coroutines.withContext
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
 *
 * Speech is streamed: each sentence's PCM is played by one [PcmPlayer] as the bytes arrive, so
 * the first word lands a few hundred milliseconds after the server starts, and synthesis of the
 * next sentence runs while the current one plays, so gaps between sentences are playback-sized,
 * not synthesis-sized. Servers that cannot stream PCM get the old per-sentence mp3 path.
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

    private val _voiceLevel = MutableStateFlow(-1f)
    /** The agent's voice as it leaves the speaker (0..1) while a streamed sentence plays;
     *  -1 = no meter (the mp3 fallback, or nothing playing). */
    val voiceLevel: StateFlow<Float> = _voiceLevel

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    @Volatile private var muted = false
    private var player: MediaPlayer? = null
    @Volatile private var pcm: PcmPlayer? = null
    private var loopJob: Job? = null
    private var turnJob: Job? = null
    private var synthSeq = 0

    fun start() {
        if (loopJob != null) return
        requestFocus()
        log("call start")
        // Ears and voice are checked the moment the call opens — a dead endpoint is said out
        // loud on the call screen instead of being discovered as silence a minute later.
        scope.launch {
            val problem = withContext(Dispatchers.IO) { viewModel.voice.probe() }
            if (problem != null) {
                log("probe: $problem")
                _ui.update { it.copy(error = problem) }
            } else log("probe: ears + voice reachable")
        }
        loopJob = scope.launch { mainLoop() }
    }

    // Log.w, not .i: the release R8 rules strip Log.i/d/v — this trace exists FOR release phones.
    private fun log(msg: String) = android.util.Log.w("KeryxCall", msg)

    fun setMuted(m: Boolean) {
        muted = m
        if (m) {
            // Mute closes the take at the tap and KEEPS it. On 09-05 a mute tapped mid-sentence
            // let the capture run to its natural end, then the loop deleted 12 s of speech
            // without a word — "he couldn't hear me". What was said while the mic was open is
            // the user's; mute only means "nothing more from now".
            audio.finishTake()
            interrupt()
        }
    }

    fun isMuted(): Boolean = muted

    /** Stop the agent mid-sentence and go back to listening. The rest of its answer still
     *  lands in the room as text — the call just stops reading it aloud. */
    fun interrupt() {
        log("interrupt")
        pcm?.stop() // unblocks a write() parked in the audio HAL; cancellation alone cannot
        turnJob?.cancel()
    }

    fun end() {
        _ui.update { it.copy(phase = Phase.ENDED) }
        scope.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        pcm?.release()
        pcm = null
        viewModel.callTurnTap = null
        abandonFocus()
    }

    /** One sentence's sound, either streaming PCM or a finished mp3. */
    private sealed interface SentenceAudio {
        val text: String
        class Pcm(override val text: String, val sampleRate: Int, val chunks: Channel<ByteArray>) : SentenceAudio
        class Mp3(override val text: String, val file: File) : SentenceAudio
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
                if (!scope.isActive) break
                // Non-take (noise-only window, cancelled listen, or a failed mic open). The
                // failed-open case would otherwise re-enter capture as a hot spin.
                delay(150)
                continue
            }
            if (!scope.isActive) { take.delete(); break }
            if (muted) log("muted after speech began — the take still goes to STT")
            _ui.update { it.copy(phase = Phase.TRANSCRIBING) }
            log("take ${take.length() / 32_000.0}s → STT")
            val heard = runCatching { transcribe(take) }
                .onFailure { e ->
                    log("STT failed: $e")
                    _ui.update { it.copy(error = "hearing failed: ${e.message?.take(80)}") }
                    delay(1_200) // don't spin a hot loop against a dead endpoint
                }
                .getOrNull()?.trim().orEmpty()
            log("heard: \"${heard.take(80)}\"")
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
        var deltas = 0
        viewModel.callTurnTap = object : ChatViewModel.CallTurnTap {
            override fun onDelta(text: String) {
                if (deltas++ == 0) log("first delta")
                streamedAny = true
                chunker.feed(text).forEach { sentences.trySend(it) }
            }

            override fun onTurnEnd(finalText: String?) {
                log("turn end: deltas=$deltas finalText=${finalText?.length ?: 0} chars")
                // Nothing streamed (pure tier-2 turn) → voice the committed body instead.
                if (!streamedAny && !finalText.isNullOrBlank()) {
                    chunker.feed(finalText).forEach { sentences.trySend(it) }
                }
                chunker.flush()?.let { sentences.trySend(it) }
                sentences.close()
            }

            override fun onTurnFailed() {
                log("turn FAILED")
                _ui.update { it.copy(error = "the agent's turn failed") }
                chunker.flush()?.let { sentences.trySend(it) }
                sentences.close()
            }
        }
        try {
            log("send: link=${viewModel.linkState()} room=${viewModel.currentRoomId()}")
            // The marker tells the agent this was SAID and will be read aloud (SILAS is taught
            // it in wiki_context.py: answer first, talk plain, tools after a sentence). The
            // phone strips it from bubbles like every other ⟦keryx:…⟧ marker.
            viewModel.sendMessage("$userText $VOICE_MARKER")
            // Synthesis runs ahead of playback: capacity 1 = one finished sentence waiting plus the
            // one being synthesized. A streaming sentence is handed over as soon as it OPENS, so
            // the first one plays while it is still being generated.
            val ready = Channel<SentenceAudio>(1)
            val synth = launch(Dispatchers.IO) {
                for (sentence in sentences) {
                    val speakable = TtsText.speakable(sentence)
                    if (speakable.isBlank()) continue
                    val opened = runCatching { viewModel.voice.openSpeechStream(speakable) }
                    opened.exceptionOrNull()?.let { e ->
                        log("TTS open failed: $e")
                        _ui.update { it.copy(error = "voice failed: ${e.message?.take(80)}") }
                    }
                    val stream = opened.getOrNull()
                    if (stream != null) {
                        log("TTS stream open (${stream.sampleRate} Hz) for ${speakable.length} chars")
                        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
                        ready.send(SentenceAudio.Pcm(speakable, stream.sampleRate, chunks))
                        try {
                            stream.use { st ->
                                val buf = ByteArray(8192)
                                while (isActive) {
                                    val n = st.input.read(buf)
                                    if (n < 0) break
                                    if (n > 0) chunks.send(buf.copyOf(n))
                                }
                            }
                        } finally {
                            chunks.close()
                        }
                    } else {
                        val f = File(context.cacheDir, "call_tts_${synthSeq++}.mp3")
                        val r = runCatching { viewModel.voice.synthesizeBlocking(speakable, f) }
                        r.exceptionOrNull()?.let { e ->
                            log("TTS mp3 failed: $e")
                            _ui.update { it.copy(error = "voice failed: ${e.message?.take(80)}") }
                        }
                        if (r.isSuccess) ready.send(SentenceAudio.Mp3(speakable, f)) else f.delete()
                    }
                }
                ready.close()
            }
            // The meter: the orb and the bars ride the sound actually leaving the speaker.
            val meter = launch {
                while (isActive) {
                    val p = pcm
                    _voiceLevel.value = if (p != null && !p.isReleased) p.levelNow() else -1f
                    delay(33)
                }
            }
            // Watchdog: every stream path closes the channel, but a wedged gateway must not
            // hold the call hostage — fall back to listening and let the text land in chat.
            withTimeoutOrNull(TURN_WATCHDOG_MS) {
                for (audio in ready) {
                    _ui.update { it.copy(phase = Phase.SPEAKING, speaking = audio.text) }
                    when (audio) {
                        is SentenceAudio.Pcm -> playPcm(audio)
                        is SentenceAudio.Mp3 -> try { play(audio.file) } finally { audio.file.delete() }
                    }
                }
                pcm?.let { withContext(Dispatchers.IO) { it.drain() } }
                synth.join()
            } ?: run {
                log("turn watchdog fired — no end signal in ${TURN_WATCHDOG_MS / 1000}s")
                _ui.update { it.copy(error = "no reply from the gateway") }
                synth.cancel()
            }
            meter.cancel()
        } finally {
            _voiceLevel.value = -1f
            // Cancellation (interrupt / call end) leaves withTimeoutOrNull by exception, so the
            // player is cleaned up HERE, not after it — or the next turn inherits a dead track.
            pcm?.release()
            pcm = null
            viewModel.callTurnTap = null
        }
    }

    private suspend fun transcribe(wav: File): String = suspendCancellableCoroutine { cont ->
        viewModel.voice.transcribe(wav) { result ->
            if (cont.isActive) result.fold(
                { cont.resume(it) },
                { cont.resumeWithException(it) },
            )
        }
    }

    /** Feed one sentence's PCM into the call's shared track, creating it (or re-creating it on a
     *  sample-rate change) as needed. Sentences flow into one continuous track — no drain between
     *  them, so there is no re-buffering gap; the turn drains once at the end. Cancellation stops
     *  the sound at once. */
    private suspend fun playPcm(audio: SentenceAudio.Pcm) = withContext(Dispatchers.IO) {
        var track = pcm
        if (track == null || track.isReleased || track.sampleRate != audio.sampleRate) {
            track?.release()
            track = PcmPlayer(audio.sampleRate, speechAttributes)
            pcm = track
        }
        // Rough length of what is coming (Sy runs ~52 ms per character): sizes this sentence's
        // lead when the server is falling behind real time (PcmPlayer rule 4).
        track.beginSentence(audio.text.length * MS_PER_CHAR)
        var bytes = 0
        var refused = false
        try {
            for (chunk in audio.chunks) {
                if (!isActive) break
                if (!track.write(chunk, 0, chunk.size)) { refused = true; log("player refused audio after $bytes bytes"); break }
                bytes += chunk.size
            }
            if (isActive && !refused) track.endSentence()
            log("sentence queued: ${bytes / 48}ms of PCM")
        } finally {
            // A stopped track (interrupt) or a cancelled turn must not be the track the NEXT
            // sentence finds: release it and let the next one build a fresh player.
            if (!isActive || refused) {
                track.release()
                if (pcm === track) pcm = null
            }
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

    // USAGE_MEDIA on purpose: it is the media volume the user actually turned up, and the same
    // routing the per-message speak button has always used. USAGE_ASSISTANT lands on a separate
    // "assistant" stream on some OEMs (Samsung), which can sit silent with media volume at full.
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(speechAttributes)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private companion object {
        /** A tool-heavy turn can stream nothing for a long time — generous, but not infinite. */
        const val TURN_WATCHDOG_MS = 5 * 60_000L
        /** Rides at the end of every spoken utterance; stripped by the parser and the echo. */
        const val VOICE_MARKER = "⟦keryx:voice⟧"
        /** Sy's measured pace on 09-05: 56 chars = 2.96 s, 69 = 3.76 s, 103 = 4.9 s. */
        const val MS_PER_CHAR = 52L
    }
}
