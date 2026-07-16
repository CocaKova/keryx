package chat.keryx.app

import chat.keryx.app.domain.model.MediaKind
import chat.keryx.app.domain.model.Message
import chat.keryx.app.domain.model.SenderType
import chat.keryx.app.presentation.ui.components.GroupedTimeline
import chat.keryx.app.presentation.ui.components.MessageParser
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.groupChatItemsIncremental
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * groupChatItemsIncremental must be a pure optimization: for ANY input and ANY previous cache
 * (fresh, stale, chained across arbitrary timeline mutations) its items equal a full
 * groupChatItems pass. Plus the perf invariant the fast path exists for: a streaming tick on a
 * long timeline costs O(1) parses, not O(timeline).
 */
class IncrementalGroupingTest {

    private var ts = 0L
    private var idSeq = 0

    private fun msg(
        sender: SenderType,
        content: String,
        media: MediaKind? = null,
        replyTo: String? = null,
    ): Message {
        ts += 1_000L
        return Message(
            id = "e${idSeq++}", sessionId = "!room", sender = sender, content = content,
            timestamp = ts, mediaKind = media, replyToId = replyTo,
        )
    }

    // Realistic bodies covering every grouping branch: tool lines, header-less fences, telemetry
    // heartbeats, runtime footers (merge into the previous message), short asides, real answers.
    private val agentBodies = listOf(
        "⚙️ terminal: \"ls\"",
        "⚙️ terminal: \"make\"\nbuild failed\n⚙️ terminal: \"make\"",
        "💻 terminal\n```\ncd ~/.hermes && python consolidate.py\n```",
        "⏳ Working — 3 min — iteration 1/90, terminal",
        "checking the logs…",
        "Here is the answer you asked for. " + "detail ".repeat(48),
        "```\nraw tool output without a header\n```",
        "Qwen-35B · 37% · ~/workspace",
        "<think>quick thought about the step</think>Short note",
        "🔍 session_search: \"handoff\"",
    )

    private fun randomAgent(rng: Random) = msg(SenderType.HERMES, agentBodies[rng.nextInt(agentBodies.size)])

    private fun randomMessage(rng: Random): Message = when (rng.nextInt(12)) {
        0 -> msg(SenderType.ME, "please do the next thing")
        1 -> msg(SenderType.ME, "/steer keep it focused")
        2 -> msg(SenderType.OTHER, "human interjection from the group")
        3 -> msg(SenderType.HERMES, "", media = MediaKind.IMAGE)
        else -> randomAgent(rng)
    }

    /** Full-pass reference, newest-first input/output — the semantic oracle. */
    private fun reference(chrono: List<Message>) = groupChatItems(chrono.asReversed())

    private fun assertMatchesReference(chrono: List<Message>, cache: GroupedTimeline?): GroupedTimeline {
        val grouped = groupChatItemsIncremental(chrono.asReversed(), cache)
        assertEquals(
            "incremental != full for ${chrono.size} messages (tail=${chrono.lastOrNull()?.content?.take(60)})",
            reference(chrono),
            grouped.items.toList(),
        )
        return grouped
    }

    @Test
    fun chainedMutations_alwaysEqualFullRegroup() {
        val rng = Random(20260716)
        repeat(30) {
            ts = 0L; idSeq = 0
            val chrono = mutableListOf<Message>(msg(SenderType.ME, "kick off the run"))
            var cache: GroupedTimeline? = null
            repeat(40) {
                when (rng.nextInt(10)) {
                    // Grow the last message in place — the m.replace streaming shape.
                    0, 1, 2 -> {
                        val last = chrono.last()
                        if (last.sender == SenderType.HERMES && last.mediaKind == null) {
                            chrono[chrono.lastIndex] =
                                last.copy(content = last.content + " …more streamed words land here.")
                        } else chrono += randomAgent(rng)
                    }
                    // Older history pages in at the chronological front (loadOlderMessages).
                    3 -> {
                        val older = randomMessage(rng).copy(timestamp = (chrono.first().timestamp - 60_000L))
                        chrono.add(0, older)
                    }
                    // An edit lands BEHIND the trailing block — must force the full path, still equal.
                    4 -> if (chrono.size > 4) {
                        val i = rng.nextInt(chrono.size - 2)
                        chrono[i] = chrono[i].copy(content = chrono[i].content + " (edited)")
                    } else chrono += randomMessage(rng)
                    // A day boundary inside the growth region.
                    5 -> { ts += 86_400_000L; chrono += randomAgent(rng) }
                    else -> chrono += randomMessage(rng)
                }
                cache = assertMatchesReference(chrono, cache)
            }
        }
    }

    @Test
    fun staleOrForeignCache_neverCorruptsTheResult() {
        val rng = Random(99)
        ts = 0L; idSeq = 0
        val roomA = mutableListOf(msg(SenderType.ME, "hello"), randomAgent(rng), randomAgent(rng))
        val cacheA = assertMatchesReference(roomA, null)
        // Feed room A's cache to a completely different timeline (the room-switch shape).
        ts = 0L; idSeq = 100
        val roomB = mutableListOf(msg(SenderType.ME, "other room"), randomAgent(rng))
        assertMatchesReference(roomB, cacheA)
        // And an emptied timeline.
        assertMatchesReference(mutableListOf(), cacheA)
    }

    @Test
    fun streamingTick_onLongTimeline_costsO1Parses() {
        ts = 0L; idSeq = 0
        val rng = Random(514)
        // A marathon-shaped room: 400 committed messages, mostly agent work.
        val chrono = mutableListOf<Message>(msg(SenderType.ME, "start the marathon"))
        repeat(399) { chrono += if (it % 23 == 0) msg(SenderType.ME, "checkpoint ${it / 23}") else randomAgent(rng) }
        chrono += msg(SenderType.HERMES, "The final answer begins to stream. ")
        // Warm pass (fills the parse LRU for the whole timeline) — this one may parse everything.
        var cache: GroupedTimeline? = groupChatItemsIncremental(chrono.asReversed(), null)
        // Now 50 growth ticks of the tail message: each tick may re-parse the GROWN message (its
        // content is new text — a legitimate miss) but nothing else. O(1), not O(timeline).
        var worstTick = 0L
        repeat(50) {
            val last = chrono.last()
            chrono[chrono.lastIndex] = last.copy(content = last.content + "More words arrive on this tick. ")
            val before = MessageParser.parseUncachedCount
            cache = groupChatItemsIncremental(chrono.asReversed(), cache)
            val parses = MessageParser.parseUncachedCount - before
            if (parses > worstTick) worstTick = parses
        }
        assertTrue(
            "a streaming tick on a 400-message timeline did $worstTick raw parses — should be O(1), not O(n)",
            worstTick <= 6,
        )
        // And the result is still exactly the full pass.
        assertEquals(reference(chrono), cache!!.items.toList())
    }
}
