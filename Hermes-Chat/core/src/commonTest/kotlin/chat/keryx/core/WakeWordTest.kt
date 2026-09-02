package chat.keryx.core

import chat.keryx.core.model.WakeDetection
import chat.keryx.core.model.WakeFrameQueue
import chat.keryx.core.model.WakePcm
import chat.keryx.core.model.WakeProtocol
import chat.keryx.core.model.WakeReason
import chat.keryx.core.model.WakeReconcile
import chat.keryx.core.model.WakeReconcileAction
import chat.keryx.core.model.WakeStartResult
import chat.keryx.core.model.WakeStatus
import chat.keryx.core.model.WakeStopResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import chat.keryx.core.model.WakeEnergyGate
import chat.keryx.core.model.WakePipeline
import chat.keryx.core.model.WakePolicy
import chat.keryx.core.model.WakeScoreGate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the ear (2.7, harvested from Talaria's D4). Wire shapes are the LIVE
 * gateway's (0.20.2, probed 08-18: wake.start / wake.status / wake.detected verbatim), the
 * frame pipeline mirrors desktop's wake-client-capture.ts, the reconcile table is
 * resumeWakeAfterVoice's, and the on-device pipeline plumbing was verified against
 * openWakeWord in Python (same clip, same scores).
 */
class WakeWordTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- wire shapes ------------------------------------------------------------------

    @Test
    fun `wake_start success carries client capture and frame geometry`() {
        val r = WakeStartResult.from(obj("""{"started":true,"phrase":"hey hermes","provider":"openwakeword",
            "owner_surface":"gui","enabled_persisted":true,"capture":"client","sample_rate":16000,"frame_length":1280}"""))
        assertTrue(r.started)
        assertTrue(r.clientCapture)
        assertEquals(1280, r.frameLength)
        assertEquals("hey hermes", r.phrase)
        assertTrue(r.enabledPersisted)
    }

    @Test
    fun `wake_start refusal keeps reason and hint`() {
        val r = WakeStartResult.from(obj("""{"started":false,"reason":"unavailable","hint":"Wake word needs speech-to-text configured","capture":"client"}"""))
        assertFalse(r.started)
        assertEquals("unavailable", r.reason)
        assertEquals("Wake word needs speech-to-text configured", WakeReason.text(r.reason, r.hint))
    }

    @Test
    fun `wake_status parses the probed live shape`() {
        val s = WakeStatus.from(obj("""{"listening": true, "owned_by_caller": true, "owner_surface": "gui", "phrase": "hey hermes",
            "provider": "openwakeword", "configured_surface": "auto", "input_device": {"selector": "client", "name": "client capture", "hostapi": "remote"},
            "available": true, "hint": "", "enabled": true, "audio_silent": false, "capture": "client", "local_input_available": false,
            "sample_rate": 16000, "frame_length": 1280}"""))
        assertTrue(s.listening && s.ownedByCaller && s.available && s.enabled && s.clientCapture)
        assertEquals("gui", s.ownerSurface)
        assertFalse(s.audioSilent)
    }

    @Test
    fun `wake_detected payload defaults start_new_session to true and blanks profile`() {
        val d = WakeDetection.from(obj("""{"phrase": "hey hermes", "profile": null, "start_new_session": true}"""))
        assertEquals("hey hermes", d.phrase)
        assertNull(d.profile)
        assertTrue(d.startNewSession)
        assertTrue(WakeDetection.from(obj("""{"phrase":"x"}""")).startNewSession)
        assertFalse(WakeDetection.from(obj("""{"phrase":"x","start_new_session":false}""")).startNewSession)
        assertEquals("work", WakeDetection.from(obj("""{"phrase":"x","profile":"work"}""")).profile)
    }

    @Test
    fun `wake_stop not_owner still lands off`() {
        val r = WakeStopResult.from(obj("""{"stopped":false,"reason":"not_owner","disabled_persisted":false}"""))
        assertFalse(r.stopped)
        assertEquals("another surface owns the listener", WakeReason.text(r.reason, null))
    }

    @Test
    fun `reason text - hint wins, known codes translate, unknown codes pass through raw`() {
        assertEquals("some hint", WakeReason.text("disabled", " some hint "))
        assertEquals("scoped to another surface (config wake_word.surface)", WakeReason.text("disabled_for_surface", ""))
        assertEquals("brand_new_code", WakeReason.text("brand_new_code", null))
        assertEquals("", WakeReason.text(null, null))
    }

    @Test
    fun `client capture spellings match desktop`() {
        listOf("client", "remote", "external", " Client ").forEach { assertTrue(WakeProtocol.isClientCapture(it), it) }
        listOf("local", "auto", "", null).forEach { assertFalse(WakeProtocol.isClientCapture(it), "$it") }
    }

    // ---- reconcile after a voice turn --------------------------------------------------

    private fun status(enabled: Boolean, available: Boolean, listening: Boolean, mine: Boolean) =
        WakeStatus(listening = listening, ownedByCaller = mine, available = available, enabled = enabled, capture = "client")

    @Test
    fun `reconcile - config off or unavailable is the correct rest state`() {
        assertEquals(WakeReconcileAction.REST_OFF, WakeReconcile.decide(status(enabled = false, available = true, listening = false, mine = false)))
        assertEquals(WakeReconcileAction.REST_OFF, WakeReconcile.decide(status(enabled = true, available = false, listening = true, mine = true)))
    }

    @Test
    fun `reconcile - our armed lease just needs the feed reattached`() {
        assertEquals(WakeReconcileAction.REATTACH_FEED, WakeReconcile.decide(status(enabled = true, available = true, listening = true, mine = true)))
    }

    @Test
    fun `reconcile - not listening (server paused itself on detection) means arm`() {
        assertEquals(WakeReconcileAction.ARM, WakeReconcile.decide(status(enabled = true, available = true, listening = false, mine = true)))
        assertEquals(WakeReconcileAction.ARM, WakeReconcile.decide(status(enabled = true, available = true, listening = true, mine = false)))
    }

    // ---- frame pipeline ---------------------------------------------------------------

    @Test
    fun `frame queue slices arbitrary reads into engine frames and carries the remainder`() {
        val q = WakeFrameQueue(frameLength = 4, maxQueued = 100, framesPerBatch = 2)
        q.push(shortArrayOf(1, 2, 3, 4, 5, 6))          // one frame + 2 carried
        assertEquals(1, q.queued)
        q.push(shortArrayOf(7, 8, 9))                    // 5,6,7,8 → frame; 9 carried
        assertEquals(2, q.queued)
        assertContentEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8), q.nextBatch())
        assertNull(q.nextBatch())
        q.push(shortArrayOf(10, 11, 12))
        assertContentEquals(shortArrayOf(9, 10, 11, 12), q.nextBatch())
    }

    @Test
    fun `frame queue drops the OLDEST under backlog so the detector sees recent audio`() {
        val q = WakeFrameQueue(frameLength = 2, maxQueued = 3, framesPerBatch = 4)
        q.push(shortArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5))
        assertEquals(3, q.queued)
        assertEquals(2, q.dropped)
        assertContentEquals(shortArrayOf(3, 3, 4, 4, 5, 5), q.nextBatch())
    }

    @Test
    fun `a batch of default frames stays under the server's 64000-byte feed cap`() {
        val q = WakeFrameQueue()
        q.push(ShortArray(WakeProtocol.DEFAULT_FRAME * 10))
        val batch = q.nextBatch()!!
        assertEquals(WakeProtocol.DEFAULT_FRAME * WakeProtocol.FRAMES_PER_FEED, batch.size)
        assertTrue(WakePcm.toLittleEndian(batch).size <= WakeProtocol.MAX_FEED_BYTES)
    }

    @Test
    fun `pcm goes out little-endian int16`() {
        assertContentEquals(
            byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x80.toByte()),
            WakePcm.toLittleEndian(shortArrayOf(0x1234, -1, Short.MIN_VALUE)),
        )
    }

    @Test
    fun `downsample - identity at 16k, 3 to 1 box average at 48k, sane at 44100`() {
        val at16 = shortArrayOf(1, 2, 3)
        assertContentEquals(at16, WakePcm.downsampleTo16k(at16, 3, 16_000))
        val at48 = shortArrayOf(3, 6, 9, 30, 30, 30)
        assertContentEquals(shortArrayOf(6, 30), WakePcm.downsampleTo16k(at48, 6, 48_000))
        val n = 44_100
        val out = WakePcm.downsampleTo16k(ShortArray(n) { 100 }, n, 44_100)
        assertEquals(16_000, out.size)
        assertTrue(out.all { it == 100.toShort() })
        assertEquals(0, WakePcm.downsampleTo16k(at16, 3, 0).size)
    }

    // ---- battery: energy gate + policy -------------------------------------------------

    private fun frame(v: Int) = ShortArray(4) { v.toShort() }

    @Test
    fun `energy gate stays closed in silence, opens with pre-roll, hangs past dips, closes after`() {
        val g = WakeEnergyGate(prerollFrames = 2, hangFrames = 2)
        // silence: nothing sent, ring keeps the last 2 frames
        assertTrue(g.offer(frame(1), rms = 10.0, startGate = 250.0, endGate = 140.0).isEmpty())
        assertTrue(g.offer(frame(2), rms = 10.0, startGate = 250.0, endGate = 140.0).isEmpty())
        assertTrue(g.offer(frame(3), rms = 10.0, startGate = 250.0, endGate = 140.0).isEmpty())
        // speech: opens and flushes the pre-roll (the 2 frames before: 2,3) + this one; frame 1 was let go
        val opened = g.offer(frame(4), rms = 900.0, startGate = 250.0, endGate = 140.0)
        assertEquals(listOf(2, 3, 4), opened.map { it[0].toInt() })
        assertEquals(1, g.skipped)
        assertTrue(g.isOpen)
        // an inter-word dip does not close it (hang=2)
        assertEquals(1, g.offer(frame(5), rms = 50.0, startGate = 250.0, endGate = 140.0).size)
        assertTrue(g.isOpen)
        // loud again resets the hang
        g.offer(frame(6), rms = 900.0, startGate = 250.0, endGate = 140.0)
        // two quiet frames → closes on the second (still sent, then silence again)
        g.offer(frame(7), rms = 50.0, startGate = 250.0, endGate = 140.0)
        assertTrue(g.isOpen)
        assertEquals(1, g.offer(frame(8), rms = 50.0, startGate = 250.0, endGate = 140.0).size)
        assertFalse(g.isOpen)
        assertTrue(g.offer(frame(9), rms = 50.0, startGate = 250.0, endGate = 140.0).isEmpty())
    }

    @Test
    fun `energy gate rms`() {
        assertEquals(0.0, WakeEnergyGate.rms(ShortArray(0)), 0.0)
        assertEquals(100.0, WakeEnergyGate.rms(shortArrayOf(100, -100, 100, -100)), 0.001)
    }

    @Test
    fun `policy - charging, cellular and idle gates in that order, all switchable off`() {
        val p = WakePolicy(onlyWhileCharging = true, notOnCellular = true, idleHours = 4)
        fun facts(ch: Boolean, cell: Boolean, idleH: Double) =
            WakePolicy.Facts(ch, cell, (idleH * 3_600_000).toLong())
        assertNull(p.restReason(facts(true, false, 1.0)))
        assertEquals("resting — plug in to listen", p.restReason(facts(false, false, 0.0)))
        assertEquals("resting — not on mobile data", p.restReason(facts(true, true, 0.0)))
        assertTrue(p.restReason(facts(true, false, 4.0))!!.startsWith("auto-off after 4h"))
        assertNull(p.restReason(facts(true, false, 3.99)))
        val loose = WakePolicy(onlyWhileCharging = false, notOnCellular = false, idleHours = 0)
        assertNull(loose.restReason(facts(false, true, 999.0)))
    }

    // ---- on-device pipeline plumbing (math verified in Python against openWakeWord) ---------

    @Test
    fun `pipeline hands the models the right windows and warms after 16 frames`() {
        val melInputs = mutableListOf<FloatArray>()
        val embInputs = mutableListOf<FloatArray>()
        val scoreInputs = mutableListOf<FloatArray>()
        var embCounter = 0f
        val p = WakePipeline(
            melspectrogram = { raw -> melInputs += raw.copyOf(); FloatArray(8 * 32) { 10f } },   // → 3.0 after /10+2
            embed = { win -> embInputs += win.copyOf(); embCounter += 1f; FloatArray(96) { embCounter } },
            score = { f -> scoreInputs += f.copyOf(); 0.42f },
        )
        val frame = ShortArray(1280) { 7 }
        // 15 frames: not warm, no score
        repeat(15) { assertNull(p.process(frame)) }
        assertFalse(p.warm)
        // mel input is the last 1760 samples oldest-first: first call had 480 zeros then 1280×7
        assertEquals(1760, melInputs[0].size)
        assertEquals(0f, melInputs[0][0], 0f)
        assertEquals(7f, melInputs[0][479 + 1], 0f)
        // second call: fully 7s
        assertTrue(melInputs[1].all { it == 7f })
        // embedding window: 76×32; after one call the last 8 rows are 3.0 and the first 68 are the ONES seed
        assertEquals(76 * 32, embInputs[0].size)
        assertEquals(1f, embInputs[0][0], 0f)
        assertEquals(3f, embInputs[0][76 * 32 - 1], 0f)
        // 16th frame: warm, score returned, feature window = embeddings 1..16 in order
        assertEquals(0.42f, p.process(frame)!!, 0f)
        assertTrue(p.warm)
        val f = scoreInputs.single()
        assertEquals(16 * 96, f.size)
        assertEquals(1f, f[0], 0f)
        assertEquals(16f, f[15 * 96], 0f)
        // reset forgets everything
        p.reset()
        assertFalse(p.warm)
        assertNull(p.process(frame))
    }

    @Test
    fun `score gate mirrors the gateway - 3 consecutive over threshold then a cooldown`() {
        val g = WakeScoreGate(threshold = 0.6f, confirmationFrames = 3, cooldownMs = 2_000)
        assertFalse(g.offer(0.9f, 0)); assertFalse(g.offer(0.9f, 80))
        assertFalse(g.offer(0.1f, 160)) // streak broken
        assertFalse(g.offer(0.9f, 240)); assertFalse(g.offer(0.9f, 320))
        assertTrue(g.offer(0.9f, 400))  // third consecutive → fire
        // within cooldown: another 3-streak does not fire
        assertFalse(g.offer(0.9f, 480)); assertFalse(g.offer(0.9f, 560)); assertFalse(g.offer(0.9f, 640))
        // after cooldown it may fire again
        assertFalse(g.offer(0.9f, 2500)); assertFalse(g.offer(0.9f, 2580)); assertTrue(g.offer(0.9f, 2660))
    }
}
