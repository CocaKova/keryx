package chat.keryx.app

import chat.keryx.app.presentation.tapin.TapIn
import chat.keryx.app.presentation.ui.components.ChatRenderItem
import chat.keryx.app.presentation.ui.components.GroupedTimeline
import chat.keryx.app.presentation.ui.components.ThoughtPieces
import chat.keryx.app.presentation.ui.components.ToolRunEntry
import chat.keryx.app.presentation.ui.components.deliveryTargetOf
import chat.keryx.app.presentation.ui.components.groupChatItems
import chat.keryx.app.presentation.ui.components.groupChatItemsIncremental
import chat.keryx.app.transport.direct.SessionStore
import chat.keryx.core.model.AgentDeliveryCommand
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.ToolCall
import chat.keryx.core.model.ToolStatus
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long live thought (3.0 perf). Jonny, 10-18, watching the h2h Sonic run (DeepSeek at high
 * effort, ~2 h, 286 model calls): "with the super long reasoning for this task Keryx is lagging
 * now". That run streamed ~1 MB of reasoning as `reasoning.delta`, and the live row carried up
 * to 210 KB of it at once: the store keeps the whole turn's thought on the live row (a
 * `reasoning.available` once replaced it, 39 times in 286 calls; since 10-19 it never does).
 *
 * The store republishes the live row at most every 100 ms, the whole thought on it each time.
 * The reasoning pane drew that string as one Text in a scroll, so each tick handed text layout
 * every line of it. What a tick costs is what reaches text layout: the whole thought before, one
 * piece now. Text layout itself needs Android; this counts what it is given.
 */
class LongThoughtTest {

    /** The run's longest stretch on the live row between resets. */
    private val runThought = 210_000

    /** Prose shaped like a model's thinking: lines of 40–160 characters, paragraph breaks. */
    private fun thought(totalChars: Int, seed: Long = 7L): String {
        val words = listOf(
            "the", "player", "collision", "sprite", "loop", "so", "check", "velocity", "ring",
            "tile", "slope", "wait", "actually", "frame", "camera", "jump", "let", "me", "fix",
            "physics", "gravity", "level", "draw", "that", "should", "work", "now", "test",
        )
        var r = seed
        fun next(n: Int): Int { r = (r * 6364136223846793005L + 1442695040888963407L); return ((r ushr 33) % n).toInt() }
        val sb = StringBuilder()
        var linesInPara = 0
        while (sb.length < totalChars) {
            val lineLen = 40 + next(120)
            val start = sb.length
            while (sb.length - start < lineLen) { sb.append(words[next(words.size)]); sb.append(' ') }
            sb.setLength(sb.length - 1)
            sb.append('\n')
            if (++linesInPara >= 3 + next(4)) { sb.append('\n'); linesInPara = 0 }
        }
        return sb.toString()
    }

    /** [text] as the wire sends it: deltas of 2–12 characters. */
    private fun deltas(text: String, seed: Long = 11L): List<String> {
        var r = seed
        fun next(n: Int): Int { r = (r * 6364136223846793005L + 1442695040888963407L); return ((r ushr 33) % n).toInt() }
        val out = ArrayList<String>()
        var at = 0
        while (at < text.length) {
            val n = (2 + next(11)).coerceAtMost(text.length - at)
            out += text.substring(at, at + n)
            at += n
        }
        return out
    }

    /** Lines as a reader sees them: trailing spaces never show. */
    private fun shownLines(s: String) = s.lines().map { it.trimEnd() }

    @Test
    fun `a long live thought lays out one piece per tick, not the whole thought`() {
        val st = SessionStore("s") { it }
        st.streamStart()
        val pane = ThoughtPieces()
        var prev: List<String> = emptyList()
        var ticks = 0
        var before = 0L
        var now = 0L
        var beforeMax = 0
        var nowMax = 0
        var publishNs = 0L
        var piecesNs = 0L
        val sent = deltas(thought(runThought))
        sent.forEachIndexed { i, d ->
            st.streamReasoning(d)
            // ~41 tok/s against the 100 ms throttle: a publish every couple of deltas.
            if (i % 2 == 0) return@forEachIndexed
            val t0 = System.nanoTime()
            st.republish()
            val live = st.messages.value.last().reasoning ?: return@forEachIndexed
            val t1 = System.nanoTime()
            val pieces = pane.of(live)
            val t2 = System.nanoTime()
            publishNs += t1 - t0
            piecesNs += t2 - t1
            ticks++
            // Before: one Text over live.trim(), all of it laid out again.
            val wholeThought = live.trim().length
            // Now: a piece whose String is the one already drawn skips; only new ones lay out.
            val newPieces = pieces.withIndex().sumOf { (k, p) -> if (prev.getOrNull(k) === p) 0 else p.length }
            before += wholeThought
            now += newPieces
            beforeMax = maxOf(beforeMax, wholeThought)
            nowMax = maxOf(nowMax, newPieces)
            if (ticks % 500 == 0) assertEquals(shownLines(live.trim()), shownLines(pieces.joinToString("\n")))
            prev = pieces
        }
        val live = st.messages.value.last().reasoning!!
        println(
            "LongThought: ${live.length} chars in ${sent.size} deltas, $ticks publishes. " +
                "Laid out per publish, before: max $beforeMax, total $before. " +
                "Now: max $nowMax, total $now (${before / now.coerceAtLeast(1)}x less). " +
                "JVM side per publish: store ${publishNs / ticks / 1000} µs, pieces ${piecesNs / ticks / 1000} µs.",
        )
        // Before, a tick's layout grew with the thought, to the whole of it.
        assertEquals(live.trim().length, beforeMax)
        // Now it is bounded by one piece (two at the tick a piece closes), whatever the length.
        assertTrue("a tick laid out $nowMax chars", nowMax <= 2 * ThoughtPieces.MAX_PIECE)
        assertTrue("before $before, now $now", now * 50 < before)
        // And the pane still reads as the one Text did.
        assertEquals(shownLines(live.trim()), shownLines(prev.joinToString("\n")))
    }

    @Test
    fun `a growing thought keeps its earlier pieces`() {
        val text = thought(20_000)
        val pane = ThoughtPieces()
        val early = pane.of(text.substring(0, 12_000))
        val late = pane.of(text)
        assertTrue(early.size > 3)
        // Every piece cut before the thought grew is the very same String: its Text skips.
        for (k in 0 until early.size - 1) assertSame(early[k], late[k])
    }

    @Test
    fun `cuts never depend on what arrives after them`() {
        // Fed delta by delta or all at once, the same thought cuts the same way, including a
        // line with no end that has to be cut at its spaces, and a token with none at all.
        val endless = (1..900).joinToString(" ") { "word$it" }
        val token = "x".repeat(3 * ThoughtPieces.MAX_PIECE + 17)
        val text = thought(9_000) + endless + "\n" + token + "\nfin\n"
        val fed = ThoughtPieces()
        var last: List<String> = emptyList()
        val acc = StringBuilder()
        for (d in deltas(text)) { acc.append(d); last = fed.of(acc.toString()) }
        assertEquals(ThoughtPieces().of(text), last)
        // Stacked, it is the thought: a cut drops only the line end or space it fell on (the
        // break stands in for it), and a token with neither is split, nothing lost.
        fun ink(s: String) = s.filterNot { it.isWhitespace() }
        assertEquals(ink(text), ink(last.joinToString("")))
        assertTrue(last.all { it.length <= ThoughtPieces.MAX_PIECE })
        assertTrue(last.any { it.startsWith("word") && !it.contains('\n') })
        assertTrue(last.any { it == "x".repeat(ThoughtPieces.MAX_PIECE) })
    }

    @Test
    fun `a thought replaced behind a cut starts over, and the pane trims as the Text did`() {
        val pane = ThoughtPieces()
        assertTrue(pane.of(thought(10_000)).size > 2)
        // A thought that shrinks (a new turn starts from nothing) starts the pieces over.
        assertEquals(listOf("I'll start with a quick recon."), pane.of("I'll start with a quick recon."))
        assertEquals(emptyList<String>(), pane.of("  \n "))
        assertEquals(listOf("hello\n\nthere"), pane.of("\n\n  hello\n\nthere\n\n"))
        // Trailing line ends past a cut show nothing, as trim() showed nothing.
        val long = thought(5_000)
        assertEquals(shownLines(long.trim()), shownLines(ThoughtPieces().of(long + "\n\n\n").joinToString("\n")))
    }

    @Test
    fun `what the agent said never replaces the live thought`() {
        // Hermes's reasoning.available previews the agent's words, not its thinking (10-19).
        val st = SessionStore("s") { it }
        st.streamStart()
        st.streamReasoning("The importer reads the header first, so check that.")
        st.reasoningAvailable("I'll look at importer.py.")
        st.republish()
        assertEquals("The importer reads the header first, so check that.", st.messages.value.last().reasoning)
        st.streamReasoning(" Then the cache.")
        st.republish()
        assertEquals("The importer reads the header first, so check that. Then the cache.", st.messages.value.last().reasoning)
    }

    @Test
    fun `Tap-In's mind reads the same last lines from the end`() {
        // The split it replaced, kept here as the reference.
        fun before(text: String, n: Int): String {
            if (text.isBlank()) return ""
            return text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList().takeLast(n).joinToString("\n")
        }
        val cases = listOf(
            "", "   ", "one", "one\n", "\n\none\n\n", "a\r\nb\r\n\r\nc", "a\rb\rc", "x  \ny\t\n  z  ",
            (1..10).joinToString("\n\n") { "line $it" }, thought(3_000), thought(3_000) + "\r\n\r\n",
        )
        for (c in cases) for (n in listOf(0, 1, 6, 50)) assertEquals("n=$n on ${c.take(20)}", before(c, n), TapIn.tailLines(c, n))
    }

    /** A terminal call's arguments as the run sent them: JSON, ~700 characters of command
     *  (its 206 terminal calls averaged 657). */
    private fun terminalArgs(k: Int): String {
        val cmd = "cd ~/workspace/h2h/sonic && python3 -c 'import game; game.check($k)' 2>&1 | tail -40; " +
            thought(560, seed = k.toLong()).replace('\n', ' ')
        return kotlinx.serialization.json.buildJsonObject {
            put("command", kotlinx.serialization.json.JsonPrimitive(cmd))
            put("timeout", kotlinx.serialization.json.JsonPrimitive(120))
        }.toString()
    }

    @Test
    fun `a long run regroups with its turn, not with its thought`() {
        // The other per-publish pass: the chat regroups the turn's trailing block. The live
        // thought is not copied into it (the streaming row is its own Single; live tool rows
        // carry no reasoning), but every call of the turn is. Measured, not asserted on time.
        val st = SessionStore("s") { it }
        st.localUserMessage("Build me a playable 2D Sonic fan game")
        st.streamStart()
        var cache: GroupedTimeline? = null
        val samples = StringBuilder()
        val words = deltas(thought(800 * 265))
        var w = 0
        for (call in 1..265) {
            repeat(100) { if (w < words.size) st.streamReasoning(words[w++]) }
            val args = terminalArgs(call)
            st.toolStart(ToolCall(toolId = "c$call", name = "terminal", context = "step $call", argsJson = args))
            st.toolComplete(ToolCall(toolId = "c$call", name = "terminal", context = "step $call", argsJson = args, status = ToolStatus.COMPLETED))
            if (call % 66 == 0 || call == 265) {
                // Untimed first: the JIT, not the walk, owns the first passes.
                repeat(5) { st.republish(); cache = groupChatItemsIncremental(st.messages.value.asReversed(), cache) }
                val t0 = System.nanoTime()
                repeat(20) {
                    st.streamReasoning(" more")
                    st.republish()
                    cache = groupChatItemsIncremental(st.messages.value.asReversed(), cache)
                }
                samples.append(" $call tools: ${(System.nanoTime() - t0) / 20 / 1000} µs;")
            }
        }
        val items = cache!!.items
        val newest = items.first() as ChatRenderItem.Single
        assertTrue(newest.message.isStreaming)
        assertTrue(newest.message.reasoning!!.length > 100_000)
        val run = items.filterIsInstance<ChatRenderItem.ToolRun>().single()
        assertEquals(265, run.callCount)
        assertNull(run.reasoning)
        // What the walk asks of every call on every publish: is it a delivery? Each ask parsed
        // the call's arguments as JSON; now a row's answer is kept.
        val calls = run.entries.map { (it as ToolRunEntry.Call).call }
        repeat(3) { calls.forEach { AgentDeliveryCommand.targetOfCall(it.name, it.argsJson) } }
        val t0 = System.nanoTime()
        repeat(20) { calls.forEach { AgentDeliveryCommand.targetOfCall(it.name, it.argsJson) } }
        val t1 = System.nanoTime()
        repeat(20) { calls.forEach { deliveryTargetOf(it) } }
        val t2 = System.nanoTime()
        println(
            "LongThought regroup per publish (store + grouping):$samples " +
                "Delivery checks per publish over ${calls.sumOf { it.argsJson.length }} chars of args: " +
                "parsed ${(t1 - t0) / 20 / 1000} µs, kept ${(t2 - t1) / 20 / 1000} µs.",
        )
        for (c in calls) assertEquals(AgentDeliveryCommand.targetOfCall(c.name, c.argsJson), deliveryTargetOf(c))
    }

    @Test
    fun `a kept delivery answer is the answer`() {
        val send = """{"command":"hermes -p theo chat -Q -q \"Message from 🤖 X: hi\""}"""
        val plain = """{"command":"ls -la"}"""
        repeat(2) {
            assertEquals("theo", deliveryTargetOf(ToolCall(name = "terminal", argsJson = send)))
            assertNull(deliveryTargetOf(ToolCall(name = "terminal", argsJson = plain)))
            // Same arguments, a tool that can't deliver.
            assertNull(deliveryTargetOf(ToolCall(name = "write_file", argsJson = send)))
            // No arguments: the display context is read, as before.
            assertNull(deliveryTargetOf(ToolCall(name = "terminal", context = "ls -la")))
        }
    }

    @Test
    fun `a run's kept thought is the thought a full regroup gathers`() {
        // Runs whose rows carry their own thoughts, mutated the ways a live direct turn mutates
        // them; the incremental walk keeps a run's joined thought between walks and must still
        // equal a full regroup at every step, and hand back the very same String when the run's
        // thoughts did not move.
        val rng = java.util.Random(1018)
        var n = 0
        fun row(sender: SenderType, content: String = "", thought: String? = null, calls: Int = 0) = Message(
            id = "m${n++}", roomId = "s", sender = sender, content = content, timestamp = 1_000L * n,
            reasoning = thought,
            toolCalls = (0 until calls).map { ToolCall(toolId = "t$n-$it", name = "terminal", context = "step $n.$it", argsJson = terminalArgs(n)) },
        )
        val chrono = mutableListOf(row(SenderType.ME, "Build me a playable 2D Sonic fan game"))
        var cache: GroupedTimeline? = null
        var reused = 0
        fun lastMine() = chrono.indexOfLast { it.sender == SenderType.ME }
        repeat(400) {
            val boundary = lastMine()
            when (rng.nextInt(8)) {
                0, 1, 2 -> chrono += row(SenderType.HERMES, thought = thought(200 + rng.nextInt(2_000), seed = n.toLong()), calls = 1 + rng.nextInt(2))
                // A thought re-read for a row of the live block (same id, other words). Behind the
                // block a timeline reads an edit by its length, as it always has.
                3 -> (boundary + 1 until chrono.size).filter { chrono[it].toolCalls.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { at ->
                    val i = at[rng.nextInt(at.size)]
                    chrono[i] = chrono[i].copy(reasoning = thought(300, seed = rng.nextLong()))
                }
                // Words between tools: a bubble that closes the run.
                4 -> chrono += row(SenderType.HERMES, content = "Now the level + collision engine.")
                // A tool row with no thought of its own.
                5 -> chrono += row(SenderType.HERMES, calls = 1)
                // The user speaks: the block ends.
                6 -> if (rng.nextInt(4) == 0) chrono += row(SenderType.ME, "keep going")
                else -> Unit
            }
            val before = cache?.items?.filterIsInstance<ChatRenderItem.ToolRun>()?.associateBy { it.id }
            cache = groupChatItemsIncremental(chrono.asReversed(), cache)
            assertEquals(groupChatItems(chrono.asReversed()), cache!!.items)
            // The user spoke: the block before it is walked afresh, once.
            if (lastMine() != boundary) return@repeat
            for (r in cache!!.items.filterIsInstance<ChatRenderItem.ToolRun>()) {
                val was = before?.get(r.id) ?: continue
                if (was.reasoning != null && was.reasoning == r.reasoning) { assertSame(was.reasoning, r.reasoning); reused++ }
            }
        }
        assertTrue("no run kept its thought", reused > 50)
    }

    @Test
    fun `a run opened mid-flight regroups its stored thoughts on each publish`() {
        // Opened mid-run (or re-read after a compaction), the turn so far is stored rows, each
        // with its own thought, and they share the trailing block with the live row. Measured:
        // the run's gathered thought is rebuilt per publish.
        fun rows(withThought: Boolean): List<MessageRow> {
            var id = 1L
            val rows = mutableListOf(MessageRow(id++, "user", "Build me a playable 2D Sonic fan game", null, id * 1000, null))
            val one = thought(2_500)
            for (k in 0 until 100) {
                val call = "c$k"
                rows += MessageRow(
                    id++, "assistant", "", null, id * 1000, if (withThought) "$k $one" else null,
                    toolCalls = listOf(RestToolCall(call, "terminal", terminalArgs(k))),
                )
                rows += MessageRow(id++, "tool", "ok", "terminal", id * 1000, null, toolCallId = call)
            }
            return rows
        }
        fun measure(withThought: Boolean): Pair<ChatRenderItem.ToolRun, String> {
            val st = SessionStore("s") { it }
            st.setHistory(rows(withThought), more = false)
            st.streamStart()
            var cache: GroupedTimeline? = null
            repeat(200) { st.republish(); cache = groupChatItemsIncremental(st.messages.value.asReversed(), cache) }
            var storeNs = 0L
            var groupNs = 0L
            repeat(200) {
                st.streamReasoning(" more")
                val t0 = System.nanoTime()
                st.republish()
                val t1 = System.nanoTime()
                cache = groupChatItemsIncremental(st.messages.value.asReversed(), cache)
                groupNs += System.nanoTime() - t1
                storeNs += t1 - t0
            }
            val run = cache!!.items.filterIsInstance<ChatRenderItem.ToolRun>().single()
            return run to "store ${storeNs / 200 / 1000} µs, grouping ${groupNs / 200 / 1000} µs"
        }
        val (bare, bareTime) = measure(withThought = false)
        val (run, time) = measure(withThought = true)
        println(
            "LongThought mid-flight regroup per publish, 100 stored calls: no stored thought: $bareTime; " +
                "with ${run.reasoning?.length} chars of it: $time",
        )
        assertEquals(100, run.callCount)
        assertNull(bare.reasoning)
        assertTrue(run.reasoning!!.length > 240_000)
    }
}
