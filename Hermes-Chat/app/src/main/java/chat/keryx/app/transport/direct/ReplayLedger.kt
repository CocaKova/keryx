package chat.keryx.app.transport.direct

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Seamless reconnect (2.16): what the direct door remembers about each live session's event
 * stream so a socket drop costs nothing but the drop.
 *
 * Before this, Wi-Fi→LTE in the middle of a turn re-read the whole transcript the moment the new
 * socket came up — the frames sent while the phone was away were simply gone, so the only honest
 * repair was the REST page. And the page raced the stream it was repairing: rows the agent had
 * persisted mid-turn came back as history while the overlay still drew them live, a ghost beside
 * the reply until the next re-read.
 *
 * The gateway stamps every session event with a per-session `seq` and keeps the last few hundred
 * in a ring (hermes `tui_gateway/event_replay.py`). So this ledger keeps the highest seq the
 * phone has applied per live session; on a fresh socket the session is resumed, the missed
 * frames are asked for by number (`session.events.since`), and they go through the normal event
 * pump as if they had never been missed. A transcript reload is kept for the cases where the
 * ring cannot vouch for the gap: it overflowed, the gateway restarted, or it is too old to have
 * a ring at all.
 *
 * Not thread-safe by itself: DirectTransport serialises every call under its pump lock, the same
 * lock its event pump applies frames under, so a frame is never applied twice or out of order.
 */
internal class ReplayLedger {

    /** The gateway process the watermarks belong to (`gateway.ready`'s `replay_epoch`). */
    var epoch: String? = null
        private set

    /** Bumped on every fresh socket; a catch-up started under an older one must not touch state. */
    private var generation = 0L

    /** Live sid → the highest seq applied for it. */
    private val lastSeen = HashMap<String, Long>()

    /** Live sids catching up → the frames the new socket delivered meanwhile, in arrival order. */
    private val held = HashMap<String, MutableList<GatewayRpc.GatewayEvent>>()

    /**
     * A fresh socket said `gateway.ready`. Watermarks survive only when it is the same gateway
     * process that numbered them: a restart counts every session from 1 again, and a watermark of
     * 97 against a new 1..5 would read "nothing missed" forever. Any catch-up still holding frames
     * from the previous socket is void — its frames come back in this socket's replay.
     * Returns the new generation.
     */
    fun onReady(readyEpoch: String?): Long {
        if (readyEpoch == null || readyEpoch != epoch) lastSeen.clear()
        epoch = readyEpoch
        held.clear()
        return ++generation
    }

    fun isCurrent(gen: Long): Boolean = gen == generation

    fun watermark(sid: String): Long? = lastSeen[sid]

    /** Whether [sid] can be caught up by replay at all: a gateway with a ring, and a seq seen. */
    fun canReplay(sid: String): Boolean = epoch != null && lastSeen.containsKey(sid)

    /** From now until [release] / [abandon], frames for [sid] wait instead of applying. */
    fun hold(sid: String) {
        held[sid] = mutableListOf()
    }

    fun isHeld(sid: String): Boolean = held.containsKey(sid)

    /**
     * One frame off the socket: the frames to apply now (none while its session is held).
     *
     * A live frame is never dropped for its number. The socket delivers each frame once, in
     * order, so duplicates only exist at the replay seam; and the gateway can legitimately count
     * a session from 1 again mid-life (its ring evicts the oldest of 64 sessions whole, counter
     * included). The newest frame always sets the watermark.
     */
    fun live(ev: GatewayRpc.GatewayEvent): List<GatewayRpc.GatewayEvent> {
        held[ev.sessionId]?.let { waiting -> waiting += ev; return emptyList() }
        note(ev)
        return listOf(ev)
    }

    /**
     * The catch-up for [sid] landed: the replayed frames plus whatever arrived live meanwhile,
     * merged in seq order with nothing applied twice. Ends the hold. Empty when [gen] is stale.
     */
    fun release(
        sid: String,
        gen: Long,
        replayed: List<GatewayRpc.GatewayEvent>,
    ): List<GatewayRpc.GatewayEvent> {
        if (gen != generation) return emptyList()
        val waiting = held.remove(sid) ?: return emptyList()
        return merge(lastSeen[sid] ?: 0L, replayed, waiting).onEach(::note)
    }

    /**
     * The catch-up for [sid] gave up (the transcript is being re-read instead): the frames that
     * arrived meanwhile apply as they came, and the old watermark goes — whatever comes next
     * numbers the session from here. Empty when [gen] is stale.
     */
    fun abandon(sid: String, gen: Long): List<GatewayRpc.GatewayEvent> {
        if (gen != generation) return emptyList()
        val waiting = held.remove(sid) ?: return emptyList()
        lastSeen.remove(sid)
        return waiting.onEach(::note)
    }

    private fun note(ev: GatewayRpc.GatewayEvent) {
        val seq = ev.seq ?: return
        if (ev.sessionId.isNotEmpty()) lastSeen[ev.sessionId] = seq
    }

    /** How a catch-up ends: the frames to apply, or the reason the transcript is re-read. */
    sealed interface Plan {
        data class Replay(
            val events: List<GatewayRpc.GatewayEvent>,
            /** Questions the backend is still waiting on, for the request handlers. */
            val openRequests: List<GatewayRpc.ServerRequest>,
        ) : Plan

        data class Reload(val why: String) : Plan
    }

    companion object {
        /** JSON-RPC "method not found": a gateway older than the ring. */
        const val METHOD_NOT_FOUND = -32601

        /**
         * The replay seam. Frames at or below [floor] were applied before the drop; the rest —
         * replayed and held alike — go in seq order, each number once (a held frame is usually
         * also in the replay: the ring records it the moment it is sent). Unstamped held frames,
         * which a session never gets from a ring-aware gateway, keep their arrival order at the end.
         */
        fun merge(
            floor: Long,
            replayed: List<GatewayRpc.GatewayEvent>,
            waiting: List<GatewayRpc.GatewayEvent>,
        ): List<GatewayRpc.GatewayEvent> {
            val bySeq = java.util.TreeMap<Long, GatewayRpc.GatewayEvent>()
            for (ev in replayed + waiting) {
                val seq = ev.seq ?: continue
                if (seq > floor) bySeq.putIfAbsent(seq, ev)
            }
            return bySeq.values.toList() + waiting.filter { it.seq == null }
        }

        /**
         * Decide one catch-up from the `session.events.since` answer ([response]: success, or the
         * failure the request ended in). [readyEpoch] is what this socket's `gateway.ready`
         * announced; [lastSeen] the watermark asked from. Replay only when the ring vouches for
         * the whole gap; every doubt is a reload, which is exactly what this door did before.
         */
        fun plan(readyEpoch: String?, lastSeen: Long?, response: Result<JsonObject>): Plan {
            if (readyEpoch == null) return Plan.Reload("gateway has no replay ring")
            if (lastSeen == null) return Plan.Reload("no frame seen for this session")
            val res = response.getOrElse { e ->
                val code = (e as? GatewayRpc.RpcException)?.code
                return Plan.Reload(
                    if (code == METHOD_NOT_FOUND) "gateway predates session.events.since"
                    else "events.since failed: ${e.message}",
                )
            }
            val epoch = (res["epoch"] as? JsonPrimitive)?.contentOrNull
            if (epoch != readyEpoch) return Plan.Reload("gateway restarted (epoch $epoch, ready $readyEpoch)")
            if ((res["truncated"] as? JsonPrimitive)?.booleanOrNull == true) {
                return Plan.Reload("the replay ring overflowed during the drop")
            }
            // The ring forgot this session (it evicts whole sessions, counter and all): its count
            // restarted below what we saw, and an empty answer would read as "nothing missed".
            val latest = (res["latest_seq"] as? JsonPrimitive)?.longOrNull
            if (latest != null && latest < lastSeen) return Plan.Reload("the ring restarted this session's count")
            val events = (res["events"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(GatewayRpc::eventOf) }
            return Plan.Replay(
                events = merge(lastSeen, events, emptyList()),
                openRequests = openRequests(res["open_requests"]),
            )
        }

        /** `open_requests` entries as the request handlers take them: id, method, params. */
        fun openRequests(el: kotlinx.serialization.json.JsonElement?): List<GatewayRpc.ServerRequest> =
            (el as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val method = (o["method"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val params = o["params"] as? JsonObject ?: return@mapNotNull null
                GatewayRpc.ServerRequest(id, method, params)
            }
    }
}
