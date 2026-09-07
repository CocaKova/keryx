package chat.keryx.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Plays 16-bit mono PCM as it arrives, so a voice reply starts within a fraction of a second
 * of the server's first bytes instead of after the whole clip has downloaded.
 *
 * Learned on a real phone (Galaxy S26, 2026-09-05), in order:
 *
 * 0. **Feed the track what music players feed it: 48 kHz stereo, a small standard buffer, the
 *    default start threshold.** A 24 kHz mono track with a custom threshold played its first
 *    cushion and then refused every further byte with an empty buffer (head == written,
 *    underruns 1, still "playing"). The server's PCM is upsampled here ([PcmUpsampler]) instead
 *    of by the framework's resampler.
 *
 * 1. **A streaming track does not start until its buffer holds the start threshold, and the
 *    default threshold is the WHOLE buffer.** With a two-second buffer, a 0.6 s "Yes." never
 *    reached it: AudioFlinger showed the track started, routed to the speaker, frames-ready 0,
 *    server position 0, gain -inf — never mixed, never heard. So the threshold is set explicitly
 *    to the cushion ([PREBUFFER_MS]) on API 31+, and below that the buffer itself is sized so the
 *    cushion fills it.
 * 2. **Never write to the track before `play()`.** A blocking `write()` into a not-yet-started
 *    track can park in the HAL. The opening cushion is held in a plain byte buffer here; `play()`
 *    is called first, then the cushion is written, then everything else.
 * 3. **No pause/refill games.** A streaming track that runs dry plays silence and resumes when
 *    data returns — a short gap, not a crash. Pausing would put writes back into a not-playing
 *    track (rule 2 again).
 * 4. **The cushion is per sentence, not per track.** The opening cushion protected the first
 *    sentence only; every later one was written straight in, and at ~0.93x real time (the GB10
 *    with the brain decoding beside it) each drained the lead a little until the track ran dry
 *    mid-answer — the micro-stutters of 09-05. Now every sentence's head is held ([beginSentence])
 *    until the lead — audio still queued in the track plus the held bytes — covers that
 *    sentence's projected shortfall, and [endSentence] releases whatever is left. When the
 *    server keeps up the hold is a single chunk; when it falls behind, one deliberate pause
 *    before the sentence replaces a dozen stutters inside it.
 *    A sentence can also collapse AFTER its head passed the check — the brain starting a
 *    tool call mid-sentence drops the server to 0.4x — so when the track runs dry while the
 *    sentence is still arriving, the player re-holds and rebuilds its lead. The arithmetic
 *    is [SentenceLead]: the rate is measured across the whole sentence (a 250 ms window read
 *    a bursty server as faster than it was), and a sentence that ran dry stops trusting its
 *    length estimate — each dry assumes more audio is coming and holds longer, so a slow
 *    stretch costs a few deliberate pauses, not one chop every 400 ms (the 22:38 call of
 *    09-05: eleven "ran dry" in one sentence).
 * 5. **The meter reads the play head, not the write.** Bytes go in up to seconds before they are
 *    heard, so a level taken at write time would light the orb ahead of the voice. Each 20 ms of
 *    input is scored as it is written and filed by position; [levelNow] looks up the slot the
 *    play head is in, so the picture moves with the sound leaving the speaker.
 *
 * All calls are safe from any thread; [write] blocks while the track's buffer is full.
 */
class PcmPlayer(val sampleRate: Int, attributes: AudioAttributes) {
    /** The track's own rate — the device's native 48 kHz, stereo. Input is upsampled to it. */
    private val trackRate = TRACK_RATE
    private val upsampler = PcmUpsampler(sampleRate, trackRate)
    /** Bytes per second of TRACK audio (stereo 16-bit). */
    private val trackBytesPerSecond = trackRate * 4
    /** Bytes per second of INPUT audio (mono 16-bit) — the cushion is measured in input bytes. */
    private val bytesPerSecond = sampleRate * 2
    private val cushionBytes = (PREBUFFER_MS * bytesPerSecond / 1000).toInt()
    private val minBufferBytes =
        AudioTrack.getMinBufferSize(trackRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    // Small and standard, like a music player's: it fills from the first cushion write, so the
    // default start threshold (= the buffer) is met at once. Pacing lives in push(), not here.
    private val bufferBytes = max(minBufferBytes * 2, trackBytesPerSecond / 5) // ≥ 200 ms
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(trackRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()
        .also {
            android.util.Log.w(TAG, "track created ${trackRate}Hz stereo buffer=${it.bufferSizeInFrames}f " +
                "(min ${minBufferBytes}B) state=${it.state}; input ${sampleRate}Hz mono")
        }

    private val cushion = ByteArrayOutputStream()
    /** Wall time of the current sentence's first byte — the rate's clock. */
    private var firstByteAt = 0L
    /** Bytes are being held back: before playback starts, and again at the head of every later
     *  sentence until the track holds enough lead for it (rule 4). */
    @Volatile private var holding = true
    /** Estimated length of the sentence now arriving (ms); 0 = unknown. */
    @Volatile private var expectedMs = 0L
    private var sentenceNo = 0
    /** Input ms of the current sentence already released to the track. */
    private var sentenceReleasedMs = 0L
    /** Times the current sentence has run the track dry (rule 4, second paragraph). */
    private var sentenceDries = 0
    @Volatile private var sentenceOpen = false
    @Volatile private var framesWritten = 0L
    @Volatile private var playing = false
    @Volatile private var stopped = false

    /** Fires once, on the writing thread, when sound actually starts. */
    var onStarted: (() -> Unit)? = null

    // Rule 5: one level per LEVEL_SLOT_MS of input, filed by input position.
    private val samplesPerSlot = sampleRate * LEVEL_SLOT_MS / 1000
    private val levels = FloatArray(LEVEL_SLOTS)
    @Volatile private var meteredSamples = 0L
    private var slotAcc = 0.0
    private var slotN = 0

    /** The voice as it leaves the speaker right now, 0..1 (log-scaled like the mic level). */
    fun levelNow(): Float {
        if (!playing || stopped) return 0f
        val inputPos = head() * sampleRate / trackRate
        if (inputPos >= meteredSamples) return 0f
        return levels[((inputPos / samplesPerSlot) % LEVEL_SLOTS).toInt()]
    }

    private fun noteLevels(data: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len - 1
        while (i < end) {
            val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            slotAcc += v.toDouble() * v
            slotN++
            if (slotN >= samplesPerSlot) commitSlot()
            i += 2
        }
    }

    private fun commitSlot() {
        val rms = kotlin.math.sqrt(slotAcc / slotN)
        val db = 20 * kotlin.math.log10(max(1.0, rms))
        val norm = ((db - 40) / 40).coerceIn(0.0, 1.0).toFloat()
        levels[((meteredSamples / samplesPerSlot) % LEVEL_SLOTS).toInt()] = norm
        meteredSamples += slotN
        slotAcc = 0.0
        slotN = 0
    }

    val isReleased: Boolean get() = track.state == AudioTrack.STATE_UNINITIALIZED

    /**
     * A new sentence is about to arrive; [expectedMs] is its rough length (0 = unknown). Anything
     * still held from the previous sentence is released first.
     */
    fun beginSentence(expectedMs: Long) {
        if (holding && cushion.size() > 0 && playing) flushCushion()
        cushion.reset()
        this.expectedMs = expectedMs
        sentenceNo++
        sentenceReleasedMs = 0L
        sentenceDries = 0
        sentenceOpen = true
        holding = true
        firstByteAt = 0L
    }

    /** The sentence's last byte has arrived: whatever is still held plays now. */
    fun endSentence() {
        sentenceOpen = false
        if (stopped || !holding) return
        holding = false
        if (cushion.size() == 0) return
        if (!playing && !start("sentence end")) return
        flushCushion()
    }

    /** Queue [len] bytes of PCM. Returns false once [stop] has been called or the track is dead. */
    fun write(data: ByteArray, off: Int, len: Int): Boolean {
        if (stopped) return false
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            android.util.Log.w(TAG, "track not initialized (state=${track.state}) — dropping audio")
            return false
        }
        if (!holding && playing && sentenceOpen && queuedMs() < DRY_MS) {
            // Ran dry mid-sentence (rule 4, second paragraph): re-hold and rebuild the lead.
            sentenceDries++
            android.util.Log.w(TAG, "sentence $sentenceNo ran dry after ${sentenceReleasedMs}ms " +
                "(dry #$sentenceDries) — rebuilding lead")
            holding = true
            cushion.reset()
        }
        if (holding) {
            // The sentence's first byte stamps the clock for the whole sentence; a re-hold
            // does not restart it (a bursty server read as 1.2× over a 250 ms window).
            if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
            cushion.write(data, off, len)
            val haveMs = cushion.size() * 1000L / bytesPerSecond
            // Lead = what the track still has to play + what we are holding. Before the first
            // word there is no queue, so the opening cushion is the whole lead.
            val queuedMs = if (playing) queuedMs() else 0L
            val base = if (playing) LEAD_MS else PREBUFFER_MS
            val lead = haveMs + queuedMs
            if (lead < base) return true
            // Adaptive part: the server runs at about real time, but when the GPU is shared (the
            // brain decoding while Sy speaks) it falls behind — one sentence rendered at 2.3x
            // slower than real time on 09-05 and the track ran dry. Arrival rate so far = audio
            // ms per wall ms across the sentence; under 1, hold enough lead to cover the
            // projected remainder ([SentenceLead]).
            val elapsed = max(1L, System.currentTimeMillis() - firstByteAt)
            val rate = (sentenceReleasedMs + haveMs).toDouble() / elapsed
            val remainingMs = max(0L, expectedMs - sentenceReleasedMs - haveMs)
            val neededMs = SentenceLead.neededMs(base, rate, remainingMs, sentenceDries, MAX_PREBUFFER_MS)
            if (lead < neededMs) return true
            val rateText = String.format(java.util.Locale.US, "%.2f", rate)
            if (!playing) {
                if (!start("cushion ${haveMs}ms rate=$rateText needed=${neededMs}ms")) return false
            } else if (haveMs > base) {
                android.util.Log.w(TAG, "sentence $sentenceNo held ${haveMs}ms (queued ${queuedMs}ms, " +
                    "rate=$rateText, needed=${neededMs}ms, dries=$sentenceDries)")
            }
            holding = false
            return flushCushion()
        }
        sentenceReleasedMs += len * 1000L / bytesPerSecond
        noteLevels(data, off, len)
        val up = upsampler.convert(data, off, len)
        return push(up, 0, up.size)
    }

    /** Let queued audio finish, then stop. Returns when the last sample has played (or on [stop]).
     *  Terminal: write nothing after this — release the player. */
    fun drain() {
        if (stopped) return
        if (holding && cushion.size() > 0) {
            if (!playing && !start("drain")) return
            holding = false
            flushCushion()
        }
        if (!playing) return
        val deadline = System.currentTimeMillis() + (framesWritten * 1000 / trackRate) + 3_000
        while (!stopped && System.currentTimeMillis() < deadline) {
            if (head() >= framesWritten) break
            Thread.sleep(40)
        }
        android.util.Log.w(TAG, "drained: played=${head() * 1000 / trackRate}ms of ${framesWritten * 1000 / trackRate}ms")
        if (!stopped) runCatching { track.stop() }
    }

    /** Silence now. Unblocks a pending [write]. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    fun release() {
        stop()
        runCatching { track.release() }
    }

    // --- internals --------------------------------------------------------------------------

    private fun head(): Long = track.playbackHeadPosition.toLong().and(0xFFFFFFFFL)

    /** Audio written but not yet played, in ms of track time. */
    private fun queuedMs(): Long = max(0L, framesWritten - head()) * 1000 / trackRate

    private fun start(why: String): Boolean {
        try {
            track.play()
        } catch (e: IllegalStateException) {
            android.util.Log.w(TAG, "play() refused (state=${track.state}, playState=${track.playState}): $e")
            return false
        }
        playing = true
        android.util.Log.w(TAG, "play ($why) track=${trackRate}Hz session=${track.audioSessionId} " +
            "route=${runCatching { track.routedDevice?.productName }.getOrNull()}")
        onStarted?.let { cb -> onStarted = null; cb() }
        return true
    }

    private fun flushCushion(): Boolean {
        val bytes = cushion.toByteArray()
        cushion.reset()
        sentenceReleasedMs += bytes.size * 1000L / bytesPerSecond
        noteLevels(bytes, 0, bytes.size)
        val up = upsampler.convert(bytes, 0, bytes.size)
        return push(up, 0, up.size)
    }

    private var writes = 0

    /**
     * Non-blocking writes, paced by us — the way ExoPlayer feeds an AudioTrack. A WRITE_BLOCKING
     * call into this track parked in the HAL twice on the S26 with a mostly empty buffer; with
     * non-blocking writes the worst the track can do is take nothing, which we see and log.
     */
    private fun push(data: ByteArray, off: Int, len: Int): Boolean {
        var written = 0
        var lastProgress = System.currentTimeMillis()
        var stallLogged = false
        while (written < len && !stopped) {
            val t0 = System.nanoTime()
            val n = track.write(data, off + written, len - written, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) {
                android.util.Log.w(TAG, "write returned $n (playState=${track.playState})")
                return false
            }
            if (n == 0) {
                val quiet = System.currentTimeMillis() - lastProgress
                if (quiet > 1_500 && !stallLogged) {
                    stallLogged = true
                    android.util.Log.w(TAG, "track taking no data for ${quiet}ms: head=${head()} written=$framesWritten " +
                        "playState=${track.playState} underruns=${track.underrunCount} bufferFrames=${track.bufferSizeInFrames}")
                }
                Thread.sleep(10)
                continue
            }
            written += n
            framesWritten += n / 4
            lastProgress = System.currentTimeMillis()
            if (writes < 3) {
                android.util.Log.w(TAG, "write #${writes + 1}: ${n}B in ${(System.nanoTime() - t0) / 1_000_000}ms " +
                    "head=${head()} written=$framesWritten playState=${track.playState}")
            }
            writes++
        }
        return !stopped
    }

    companion object {
        private const val TAG = "KeryxCall"
        const val PREBUFFER_MS = 400L
        /** Lead a later sentence must have before its bytes go in; a gap this short between
         *  sentences reads as a breath, not a stall. */
        const val LEAD_MS = 250L
        /** Under this much queued audio mid-sentence, the track is about to run dry. */
        const val DRY_MS = 60L
        /** Never hold more than this before the first word, however slow the stream. */
        const val MAX_PREBUFFER_MS = 2_500L
        const val TRACK_RATE = 48_000
        const val LEVEL_SLOT_MS = 20
        /** 1024 slots × 20 ms = 20 s of lead, far past the 2.5 s cushion cap. */
        const val LEVEL_SLOTS = 1024
    }
}
