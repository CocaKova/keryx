package chat.keryx.app.presentation

import chat.keryx.app.presentation.ui.components.MessageParser

/**
 * Incremental accumulator for a live token stream whose per-tick cost is O(delta + window),
 * never O(turn length).
 *
 * The v1.18.3 marathon fix bounded what the overlay *renders* (MessageParser.streamTailWindow),
 * but the dispatch path still copied and re-scanned the entire accumulated buffer on every
 * ~100 ms tick (`buf.toString()` + `sanitizeStreamingTail`'s full `lines()` split). On a 41 KB
 * turn at ~10 dispatches/s that residual O(n)-per-tick work is the same freeze class the window
 * was built against. This class owns the buffer instead and maintains, incrementally per append:
 *
 *  - code-fence parity and the offsets of blank lines outside fences (window-cut candidates),
 *  - the rightmost ⟦ / ⟧ marker offsets (the unterminated-marker sanitize rule),
 *  - whether any non-whitespace has arrived (cheap isBlank),
 *
 * so [windowText] can answer from the tail alone. Behavior is pinned to the originals:
 * `windowText() == streamTailWindow(sanitizeStreamingTail(raw), max)` (sanitize mode) and
 * `windowText() == streamTailWindow(raw, max)` (raw mode, used for reasoning) — the
 * MessageParser functions stay as the O(n) reference oracle in StreamTailTrackerTest.
 *
 * Contract notes: '\n' is the only recognized line terminator (the gateway SSE stream never
 * emits '\r'; MessageParser's own offset arithmetic assumes the same). Not thread-safe — the
 * stream collector owns it on a single dispatcher.
 */
class StreamTailTracker(
    private val windowChars: Int,
    /** true = apply sanitizeStreamingTail semantics before windowing (the answer buffer);
     *  false = window the raw text (the reasoning buffer, which is never tail-sanitized). */
    private val sanitize: Boolean,
) {
    private val sb = StringBuilder()

    /** Offset where the current (not yet newline-terminated) line begins. */
    private var lineStart = 0

    /** Fence parity over completed lines — mirrors streamTailWindow's toggle scan. */
    private var inFence = false

    private var hasNonWhitespace = false

    /** Ascending offsets of completed blank lines seen OUTSIDE a fence: window-cut candidates.
     *  Pruned in [windowText]; entries are skipped (not trusted) below the live minStart, so a
     *  small backward wobble of the sanitized end can never select a stale cut. */
    private val blankCuts = ArrayDeque<Int>()

    /** Rightmost ⟦ / ⟧ offsets, for the unterminated-marker truncation in O(1). */
    private var lastMarkOpen = -1
    private var lastMarkClose = -1

    /** Memo for [sanitizedEnd] — dispatch may ask for the window and the full text in one tick. */
    private var memoLen = -1
    private var memoEnd = -1

    val length: Int get() = sb.length
    fun isEmpty(): Boolean = sb.isEmpty()
    fun isNotEmpty(): Boolean = sb.isNotEmpty()
    fun isBlank(): Boolean = !hasNonWhitespace
    fun endsWith(suffix: String): Boolean = sb.endsWith(suffix)

    fun append(text: String) {
        if (text.isEmpty()) return
        val base = sb.length
        sb.append(text)
        memoLen = -1
        for (i in text.indices) {
            val ch = text[i]
            if (!hasNonWhitespace && !ch.isWhitespace()) hasNonWhitespace = true
            when (ch) {
                '⟦' -> lastMarkOpen = base + i
                '⟧' -> lastMarkClose = base + i
                '\n' -> completeLine(base + i)
            }
        }
    }

    fun clear() {
        sb.setLength(0)
        lineStart = 0
        inFence = false
        hasNonWhitespace = false
        blankCuts.clear()
        lastMarkOpen = -1
        lastMarkClose = -1
        memoLen = -1
    }

    /** The bounded live view: sanitized (in sanitize mode) then tail-windowed, exactly as
     *  streamTailWindow(sanitizeStreamingTail(raw), windowChars) would produce. O(window). */
    fun windowText(): String {
        val c = sanitizedEnd()
        if (c <= windowChars) return sb.substring(0, c)
        val minStart = c - windowChars
        // Free memory for candidates safely behind the window; selection below still re-checks
        // >= minStart so pruning is never load-bearing for correctness.
        while (blankCuts.isNotEmpty() && blankCuts.first() < minStart - PRUNE_MARGIN) {
            blankCuts.removeFirst()
        }
        var cut = -1
        for (o in blankCuts) {
            if (o >= c) break // ascending: nothing later can precede the sanitized end
            if (o >= minStart) { cut = o; break }
        }
        if (cut < 0 && !sanitize) {
            // Raw mode keeps trailing whitespace, so the current incomplete line (or the empty
            // virtual line after a trailing '\n') is a legal cut too — lines() emits it.
            if (lineStart >= minStart && lineStart <= c && !inFence && currentLineBlank(c)) {
                cut = lineStart
            }
        }
        if (cut < 0) return sb.substring(0, c) // no safe boundary: whole (sanitized) text, as before
        var s = cut
        while (s < c && sb[s] == '\n') s++ // mirror substring(offset).trimStart('\n')
        return ELLIPSIS_PREFIX + sb.substring(s, c)
    }

    /** The full sanitized text — what handoff matching compares against the committed Matrix
     *  body. O(n) copy: call on demand (stop / handoff evaluation), never per tick. */
    fun sanitizedFullText(): String = sb.substring(0, sanitizedEnd())

    /** The raw accumulated buffer (the `stop` fallback when the gateway sent no finalText). */
    fun rawText(): String = sb.toString()

    // --- internals -----------------------------------------------------------------------------

    private fun completeLine(newlinePos: Int) {
        // Mirrors streamTailWindow's per-line scan: fence markers toggle, blank lines outside a
        // fence are cut candidates. A fence line has non-whitespace so the branches are exclusive.
        var j = lineStart
        while (j < newlinePos && sb[j].isWhitespace()) j++
        if (j == newlinePos) {
            if (!inFence) blankCuts.addLast(lineStart)
        } else if (matchesAt(j, newlinePos, "```") || matchesAt(j, newlinePos, "~~~")) {
            inFence = !inFence
        }
        lineStart = newlinePos + 1
    }

    private fun matchesAt(start: Int, end: Int, token: String): Boolean {
        if (end - start < token.length) return false
        for (k in token.indices) if (sb[start + k] != token[k]) return false
        return true
    }

    private fun currentLineBlank(end: Int): Boolean {
        for (k in lineStart until end) if (!sb[k].isWhitespace()) return false
        return true
    }

    /** End offset of sanitizeStreamingTail(raw) — every sanitize op is a suffix truncation, so
     *  the sanitized text is always sb[0, end). O(tail). */
    private fun sanitizedEnd(): Int {
        if (!sanitize) return sb.length
        if (memoLen == sb.length) return memoEnd
        var c = sb.length
        // 1) Unterminated ⟦… marker at the end (a complete pair is left alone).
        if (lastMarkOpen >= 0 && lastMarkClose < lastMarkOpen) c = lastMarkOpen
        // 2) A trailing line that is just 1–2 backticks (a fence being typed).
        run {
            var ls = c
            if (c == sb.length) {
                ls = lineStart
            } else {
                while (ls > 0 && sb[ls - 1] != '\n') ls--
            }
            var a = ls
            var b = c
            while (a < b && sb[a].isWhitespace()) a++
            while (b > a && sb[b - 1].isWhitespace()) b--
            if (b - a in 1..2) {
                var allTicks = true
                for (k in a until b) if (sb[k] != '`') { allTicks = false; break }
                if (allTicks) c = if (ls > 0) ls - 1 else 0
            }
        }
        // 3) trimEnd
        while (c > 0 && sb[c - 1].isWhitespace()) c--
        // 4) A half-typed reasoning tag at the very end. Only a '<'/'◁' closer to the end than
        //    the longest tag spelling can qualify, so the backscan is bounded.
        var tagStart = -1
        val lo = maxOf(0, c - MAX_TAG_LEN + 1)
        for (k in c - 1 downTo lo) {
            val ch = sb[k]
            if (ch == '<' || ch == '◁') { tagStart = k; break }
        }
        if (tagStart >= 0) {
            val tail = sb.substring(tagStart, c).lowercase()
            if (MessageParser.TAIL_TAG_CANDIDATES.any { it.startsWith(tail) && it.length > tail.length }) {
                c = tagStart
                while (c > 0 && sb[c - 1].isWhitespace()) c--
            }
        }
        memoLen = sb.length
        memoEnd = c
        return c
    }

    private companion object {
        const val ELLIPSIS_PREFIX = "…\n"
        const val PRUNE_MARGIN = 256
        val MAX_TAG_LEN = MessageParser.TAIL_TAG_CANDIDATES.maxOf { it.length }
    }
}
