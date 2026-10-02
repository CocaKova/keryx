package chat.keryx.core

import chat.keryx.core.model.SessionMeta
import chat.keryx.core.model.SessionUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The usage payload's run numbers (2.16) — shapes from hermes `tui_gateway/server.py` `_get_usage`. */
class SessionUsageTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val tick = obj("""
        {"model":"qwen3.8-flash-next","input":1234567,"output":41230,"reasoning":9120,
         "prompt":1300000,"completion":50350,"total":1350350,"calls":12,"compressions":2,
         "context_used":84000,"context_max":327680,"context_percent":26,
         "cache_hit_pct":94,"avg_latency_s":1.2,"avg_tps":41.3,"active_subagents":0}
    """)

    @Test
    fun everyRunNumberIsRead() {
        val m = SessionUsage.fold(SessionMeta(), tick, nowMs = 1_000L)
        assertEquals(41.3, m.avgTps)
        assertEquals(94, m.cacheHitPct)
        assertEquals(1.2, m.avgLatencyS)
        assertEquals(2, m.compressions)
        assertEquals(9_120L, m.reasoningTokens)
        assertEquals(41_230L, m.outputTokens)
        assertEquals(1_234_567L, m.inputTokens)
        assertEquals(12, m.apiCalls)
        assertNull(m.costUsd) // a local brain prices nothing; the key never came
        assertEquals(1_000L, m.usageAtMs)
    }

    @Test
    fun anAbsentNumberIsUnknownNeverZero() {
        // Codex reports no latency and a cold cache sends no hit rate: the gateway omits both.
        val m = SessionUsage.fold(SessionMeta(), obj("""{"input":10,"output":5,"calls":1}"""), 7L)
        assertNull(m.avgTps)
        assertNull(m.cacheHitPct)
        assertNull(m.avgLatencyS)
        assertNull(m.compressions)
        assertNull(m.reasoningTokens)
        assertEquals("1 call · 10 in · 5 out", SessionUsage.ledger(m))
        assertNull(SessionUsage.headline(m))
    }

    @Test
    fun aLaterSubsetKeepsWhatWasKnown() {
        val first = SessionUsage.fold(SessionMeta(), tick, 1_000L)
        val later = SessionUsage.fold(first, obj("""{"output":41500,"cost_usd":0.4213,"cache_hit_pct":null}"""), 2_000L)
        assertEquals(94, later.cacheHitPct)
        assertEquals(41.3, later.avgTps)
        assertEquals(41_500L, later.outputTokens)
        assertEquals(2_000L, later.usageAtMs)
        assertEquals(0.4213, later.costUsd)
        // A payload without the counter is not a usage reading: the stamp stays where it was.
        val noCounter = SessionUsage.fold(later, obj("""{"avg_tps":40.0}"""), 3_000L)
        assertEquals(2_000L, noCounter.usageAtMs)
        assertEquals(40.0, noCounter.avgTps)
    }

    @Test
    fun aZeroTheGatewaySentIsKept() {
        val m = SessionUsage.fold(SessionMeta(), obj("""{"calls":0,"input":0,"output":0,"compressions":0}"""), 1L)
        assertEquals(0, m.compressions)
        assertEquals(0L, m.outputTokens)
        // Nothing has happened yet: no line rather than a row of zeros.
        assertNull(SessionUsage.ledger(m))
        assertNull(SessionUsage.headline(m))
    }

    @Test
    fun noUsageObjectChangesNothing() {
        val m = SessionMeta(model = "x", avgTps = 3.0)
        assertEquals(m, SessionUsage.fold(m, null, 5L))
    }

    @Test
    fun theSheetLineReadsLikeTheStatusBar() {
        val m = SessionUsage.fold(SessionMeta(), tick, 1L)
        assertEquals("prefix cache 94% · 41 tok/s avg · 2 compactions · 1.2 s/call", SessionUsage.headline(m))
        assertEquals("12 calls · 1.2M in · 41k out · 9.1k reasoning", SessionUsage.ledger(m))
        val slow = m.copy(avgTps = 7.46, compressions = 1, avgLatencyS = 2.0, costUsd = 0.004)
        assertEquals("prefix cache 94% · 7.5 tok/s avg · 1 compaction · 2 s/call", SessionUsage.headline(slow))
        assertEquals("12 calls · 1.2M in · 41k out · 9.1k reasoning · <$0.01", SessionUsage.ledger(slow))
    }

    @Test
    fun dollarsAndTokensFormat() {
        assertEquals("$0.42", SessionUsage.dollars(0.4213))
        assertEquals("$12.05", SessionUsage.dollars(12.049))
        assertEquals("<$0.01", SessionUsage.dollars(0.001))
        assertEquals("999", SessionUsage.tokens(999))
        assertEquals("1.5k", SessionUsage.tokens(1_500))
        assertEquals("41k", SessionUsage.tokens(41_230))
        assertEquals("2M", SessionUsage.tokens(2_000_000))
    }
}
