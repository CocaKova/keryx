package chat.keryx.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The run numbers in the gateway's usage payload (2.16), which arrived on every `session.info`,
 * every `message.complete` and every mid-turn `session.usage` tick — and which nothing read. The
 * gateway computes them the way the CLI status bar does (hermes `tui_gateway/server.py`
 * `_get_usage`): cache hit from cache-read over prompt tokens, speed and latency from the last
 * ten calls, and it OMITS a number it has no data for rather than sending a zero.
 */
object SessionUsage {

    /**
     * Fold one `usage` object into [meta]. A key that is absent (or null) keeps what was known —
     * the three carriers send different subsets, and a provider with no cache reads sends no hit
     * rate at all. A key that is present is the gateway's word, zero included. [nowMs] stamps a
     * reading that carried the output counter.
     */
    fun fold(meta: SessionMeta, usage: JsonObject?, nowMs: Long): SessionMeta {
        usage ?: return meta
        fun num(key: String): Double? =
            (usage[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val output = num("output")?.toLong()
        return meta.copy(
            avgTps = num("avg_tps") ?: meta.avgTps,
            cacheHitPct = num("cache_hit_pct")?.toInt() ?: meta.cacheHitPct,
            avgLatencyS = num("avg_latency_s") ?: meta.avgLatencyS,
            compressions = num("compressions")?.toInt() ?: meta.compressions,
            reasoningTokens = num("reasoning")?.toLong() ?: meta.reasoningTokens,
            outputTokens = output ?: meta.outputTokens,
            inputTokens = num("input")?.toLong() ?: meta.inputTokens,
            apiCalls = num("calls")?.toInt() ?: meta.apiCalls,
            costUsd = num("cost_usd") ?: meta.costUsd,
            usageAtMs = if (output != null) nowMs else meta.usageAtMs,
        )
    }

    /** "prefix cache 94% · 41 tok/s avg · 2 compactions · 1.2 s/call" — what is known, or null. */
    fun headline(meta: SessionMeta): String? = listOfNotNull(
        meta.cacheHitPct?.let { "prefix cache $it%" },
        meta.avgTps?.takeIf { it > 0.0 }?.let { "${rate(it)} tok/s avg" },
        meta.compressions?.takeIf { it > 0 }?.let { if (it == 1) "1 compaction" else "$it compactions" },
        meta.avgLatencyS?.takeIf { it > 0.0 }?.let { "${tenths(it)} s/call" },
    ).joinToString(" · ").ifEmpty { null }

    /** "12 calls · 1.2M in · 41k out · 9.1k reasoning · $0.42" — the session's totals, or null. */
    fun ledger(meta: SessionMeta): String? {
        val calls = meta.apiCalls ?: 0
        if (calls <= 0 && (meta.inputTokens ?: 0L) <= 0L && (meta.outputTokens ?: 0L) <= 0L) return null
        return listOfNotNull(
            meta.apiCalls?.let { if (it == 1) "1 call" else "$it calls" },
            meta.inputTokens?.let { "${tokens(it)} in" },
            meta.outputTokens?.let { "${tokens(it)} out" },
            meta.reasoningTokens?.takeIf { it > 0L }?.let { "${tokens(it)} reasoning" },
            meta.costUsd?.takeIf { it > 0.0 }?.let(::dollars),
        ).joinToString(" · ")
    }

    // No String.format: this is commonMain.

    /** One decimal under ten, whole numbers above: "7.5", "41". */
    internal fun rate(v: Double): String = if (v < 10.0) tenths(v) else kotlin.math.round(v).toLong().toString()

    internal fun tenths(v: Double): String {
        val t = kotlin.math.round(v * 10.0).toLong()
        return if (t % 10 == 0L) "${t / 10}" else "${t / 10}.${t % 10}"
    }

    internal fun tokens(n: Long): String = when {
        n >= 1_000_000 -> "${tenths(n / 1_000_000.0)}M"
        n >= 10_000 -> "${(n + 500) / 1000}k"
        n >= 1_000 -> "${tenths(n / 1000.0)}k"
        else -> n.toString()
    }

    internal fun dollars(v: Double): String {
        val cents = kotlin.math.round(v * 100.0).toLong()
        if (cents < 1) return "<$0.01"
        val c = cents % 100
        return "$${cents / 100}.${if (c < 10) "0$c" else "$c"}"
    }
}
