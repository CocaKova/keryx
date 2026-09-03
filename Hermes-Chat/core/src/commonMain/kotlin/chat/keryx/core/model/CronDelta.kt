package chat.keryx.core.model

/**
 * What changed since the last run — the diff feed.
 *
 * A daily job produces forty near-identical reports, and the reader's real question on the
 * forty-first is never "what does it say" but "what does it say that yesterday's didn't". This
 * answers that from the two newest reports alone: which lines are new, which are gone, which
 * are the same line with different words in it (a count that moved, a status that flipped),
 * and how much simply carried over.
 *
 * Deterministic and local on purpose. A model could summarise the change, but it would cost a
 * brain call per card per poll, answer differently each time, and be unavailable exactly when
 * the gateway is busy — which is when you are reading reports. A line-level diff is instant,
 * identical every time, and testable; the report's own words are the summary.
 *
 * Lines are compared by a normalised KEY: markdown chrome and inline emphasis stripped, case
 * and whitespace folded, dates and clock times removed — a report that stamps "generated
 * 2026-09-02 07:15" on every run must not read as changed every day. Numbers are KEPT in the
 * key, because "3 PRs open" becoming "5 PRs open" is exactly the change worth a line — and a
 * pair of lines that share most of their words but differ in a number or a status is reported
 * as UPDATED rather than as one gone and one new.
 */
data class CronDelta(
    /** Lines in the latest report with no counterpart in the previous one — the new words. */
    val added: List<String>,
    /** Lines from the previous report the latest no longer carries. */
    val removed: List<String>,
    /** Lines that changed in place — the latest wording, so the reader sees the new number. */
    val updated: List<String>,
    /** Lines that carried over unchanged. */
    val kept: Int,
) {
    val same: Boolean get() = added.isEmpty() && removed.isEmpty() && updated.isEmpty()

    /** How much of the latest report is carried over, 0..1 — the "mostly the same" signal. */
    val carryOver: Float
        get() {
            val total = added.size + updated.size + kept
            return if (total == 0) 1f else kept.toFloat() / total
        }

    /** "same as last run" · "+3 new" · "+3 new · 2 updated · 1 gone" — for the meta line. */
    val summary: String
        get() {
            if (same) return "same as last run"
            return buildList {
                if (added.isNotEmpty()) add("+${added.size} new")
                if (updated.isNotEmpty()) add("${updated.size} updated")
                if (removed.isNotEmpty()) add("${removed.size} gone")
            }.joinToString(" · ")
        }

    /** The short badge a compact row can carry: "+3" for new lines, "~2" when only updates,
     *  "−1" when only removals, null when nothing changed. */
    val badge: String?
        get() = when {
            added.isNotEmpty() -> "+${added.size}"
            updated.isNotEmpty() -> "~${updated.size}"
            removed.isNotEmpty() -> "−${removed.size}"
            else -> null
        }
}

object CronDeltaCalc {

    /** How many substantive lines of a report take part. Past this a report is a document,
     *  and a document's diff belongs to a diff tool, not a card. */
    private const val MAX_LINES = 160

    /** Word-set overlap at or above which two differing lines are the same line, changed. */
    private const val UPDATE_SIMILARITY = 0.6

    /** Lines shorter than this (after stripping) are noise — "Done.", "---", "Notes" — and are
     *  never reported as news, though they still count as kept when they match. */
    private const val MIN_NEWS_CHARS = 12

    fun compute(previous: String, latest: String): CronDelta {
        val old = items(previous)
        val new = items(latest)

        val oldByKey = LinkedHashMap<String, MutableList<Item>>()
        for (it in old) oldByKey.getOrPut(it.key) { mutableListOf() } += it

        val added = ArrayList<Item>()
        var kept = 0
        // Pass 1: exact key matches carry over.
        for (n in new) {
            val bucket = oldByKey[n.key]
            if (bucket != null && bucket.isNotEmpty()) {
                bucket.removeAt(0)
                kept++
            } else {
                added += n
            }
        }
        val removed = oldByKey.values.flatten().toMutableList()

        // Pass 2: among what's left, pair lines that share most of their words — the same
        // line with a number or a status moved. Greedy by best overlap, each side used once.
        val updated = ArrayList<Item>()
        val addedLeft = ArrayList<Item>()
        for (n in added) {
            var best: Item? = null
            var bestScore = 0.0
            for (o in removed) {
                val score = jaccard(n.words, o.words)
                if (score > bestScore) { bestScore = score; best = o }
            }
            if (best != null && bestScore >= UPDATE_SIMILARITY) {
                removed.remove(best)
                updated += n
            } else {
                addedLeft += n
            }
        }

        return CronDelta(
            added = addedLeft.filter { it.newsworthy }.map { it.text },
            removed = removed.filter { it.newsworthy }.map { it.text },
            updated = updated.filter { it.newsworthy }.map { it.text },
            kept = kept,
        )
    }

    private class Item(val text: String, val key: String, val words: Set<String>) {
        val newsworthy: Boolean get() = text.length >= MIN_NEWS_CHARS
    }

    /** A report's substantive lines, in order: fences, blank lines, rules and bracketed machine
     *  lines dropped, markdown chrome stripped — the same shape rules the digest uses. */
    private fun items(text: String): List<Item> {
        val out = ArrayList<Item>()
        var inFence = false
        for (raw in text.lineSequence()) {
            if (out.size >= MAX_LINES) break
            val line = raw.trim()
            if (line.startsWith("```")) { inFence = !inFence; continue }
            if (inFence || line.isEmpty()) continue
            if (line.startsWith("[") || line.all { it == '-' || it == '—' || it == '=' || it == '*' || it == '_' }) continue
            val cleaned = strip(line)
            if (cleaned.isEmpty()) continue
            val key = key(cleaned)
            if (key.isEmpty()) continue
            out += Item(cleaned, key, key.split(' ').filter { it.isNotEmpty() }.toSet())
        }
        return out
    }

    private fun strip(line: String): String = line
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^[-*>•]\\s+"), "")
        .replace(Regex("^\\d+[.)]\\s+"), "")
        .replace(Regex("^\\[[ xX]]\\s*"), "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .trim()

    private val DATE = Regex("""\b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?\b""")
    private val CLOCK = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:[ap]\.?m\.?)?\b""", RegexOption.IGNORE_CASE)
    private val LONG_DATE = Regex(
        """\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*,?\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(?:,?\s+\d{4})?\b|""" +
            """\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(?:,?\s+\d{4})?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TIME_AGO = Regex("""\b\d+\s*(?:s|m|h|d|min|mins|minutes?|hours?|days?)\s+ago\b""", RegexOption.IGNORE_CASE)

    /** The comparison key: lower-cased words, dates/times/"N ago" removed, punctuation folded. */
    private fun key(cleaned: String): String = cleaned
        .lowercase()
        .replace(DATE, " ")
        .replace(LONG_DATE, " ")
        .replace(CLOCK, " ")
        .replace(TIME_AGO, " ")
        .replace(Regex("[^\\p{L}\\p{N}%$#+/.-]+"), " ")
        .replace(Regex("(?<=\\s)[.-]+(?=\\s)|^[.-]+\\s|\\s[.-]+$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }
}
