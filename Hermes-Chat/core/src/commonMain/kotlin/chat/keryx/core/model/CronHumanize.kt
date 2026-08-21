package chat.keryx.core.model

import kotlinx.datetime.Instant

/**
 * Cron output is written for machines — cron expressions, ISO timestamps, reports that
 * open with their own plumbing. These are the translations a human skims by: the schedule
 * in words, the next run as a distance, and a report's OWN headline instead of the prompt
 * that produced it. Pure; every rule is a generic shape (headings, bullets, colon-framing,
 * bracketed machine lines) — never a match on any one job's wording.
 */
object CronHumanize {

    private val DOW = mapOf(
        "0" to "Sun", "1" to "Mon", "2" to "Tue", "3" to "Wed",
        "4" to "Thu", "5" to "Fri", "6" to "Sat", "7" to "Sun",
    )

    /** "0 19 * * 0" → "Sun 19:00" · "15 7 * * 1-5" → "weekdays 07:15" · "every 30m" →
     *  "every 30 min" · unparseable → the raw string (never lie, never hide). */
    fun schedule(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s

        // Interval jobs: "every 30m", "every 20160m".
        Regex("""^every\s+(\d+)m$""").find(s)?.let { m ->
            val mins = m.groupValues[1].toLong()
            return when {
                mins % (60 * 24) == 0L -> {
                    val d = mins / (60 * 24)
                    if (d == 1L) "daily" else "every $d days"
                }
                mins % 60 == 0L -> {
                    val h = mins / 60
                    if (h == 1L) "hourly" else "every $h h"
                }
                else -> "every $mins min"
            }
        }

        // Five-field cron. Only the shapes people actually schedule; anything else
        // falls through to the raw expression.
        val f = s.split(Regex("\\s+"))
        if (f.size != 5) return s
        val (min, hour, dom, mon, dow) = f
        if (mon != "*") return s

        // Step forms: "*/30 * * * *", "0 */2 * * *".
        Regex("""^\*/(\d+)$""").find(min)?.let { m ->
            if (hour == "*" && dom == "*" && dow == "*") return "every ${m.groupValues[1]} min"
        }
        Regex("""^\*/(\d+)$""").find(hour)?.let { m ->
            if (dom == "*" && dow == "*") return "every ${m.groupValues[1]} h"
        }

        val time = clock(hour, min) ?: return s
        return when {
            dom == "*" && dow == "*" -> "daily $time"
            dom == "*" && dow == "1-5" -> "weekdays $time"
            dom == "*" && (dow == "0,6" || dow == "6,0") -> "weekends $time"
            dom == "*" -> {
                val names = dow.split(",").map { DOW[it] ?: return s }
                "${names.joinToString("/")} $time"
            }
            dow == "*" && dom.toIntOrNull() != null -> "monthly (day $dom) $time"
            else -> s
        }
    }

    private fun clock(hour: String, min: String): String? {
        val h = hour.toIntOrNull() ?: return null
        val m = min.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    }

    /** ISO `next_run_at` → "next in 45m" / "next in 11h" / "next in 3d"; past or
     *  unparseable → null (a stale claim is worse than no claim). */
    fun nextIn(nextRunAtIso: String, nowMs: Long): String? {
        val at = runCatching { Instant.parse(nextRunAtIso.trim()) }.getOrNull() ?: return null
        val deltaMin = (at.toEpochMilliseconds() - nowMs) / 60_000
        if (deltaMin < 0) return null
        return "next in " + when {
            deltaMin < 1 -> "<1m"
            deltaMin < 90 -> "${deltaMin}m"
            deltaMin < 36 * 60 -> "${deltaMin / 60}h"
            else -> "${deltaMin / (60 * 24)}d"
        }
    }

    /**
     * A report's tail text → (title, lead) a human can skim. [title] = the report's own
     * first heading (or first substantive line); [lead] = the first content line after it,
     * markdown-stripped. Skipped as framing, by SHAPE not wording: blank lines, rules,
     * bracketed machine lines (`[IMPORTANT: …]`), code fences, and colon-terminated
     * introductions ("…:") when real content follows them.
     */
    fun digest(text: String): CronDigest {
        // Substantive lines, machine shapes dropped, (isHeading, cleaned) — bounded scan.
        val lines = buildList {
            var inFence = false
            for (raw in text.lineSequence()) {
                if (size >= 40) break
                val line = raw.trim()
                if (line.startsWith("```")) { inFence = !inFence; continue }
                if (inFence || line.isEmpty()) continue
                if (line.startsWith("[") || line.all { it == '-' || it == '—' || it == '=' }) continue
                val cleaned = strip(line)
                if (cleaned.isEmpty()) continue
                add(line.startsWith("#") to cleaned)
            }
        }
        if (lines.isEmpty()) return CronDigest(null, null)

        // A HEADING anywhere in the window beats any prose before it: runs routinely open
        // with narration ("Now I have all the data. Let me compile…") and the report — with
        // its own title — follows in the same message (live-caught).
        val headingAt = lines.indexOfFirst { it.first }
        if (headingAt >= 0) {
            val title = lines[headingAt].second
            val lead = lines.drop(headingAt + 1).firstOrNull { !it.first }?.second
            return CronDigest(title, lead)
        }

        // No heading: the first prose line that isn't colon-framing IS the content.
        val title = lines.firstOrNull { !it.second.endsWith(":") }?.second ?: lines.first().second
        return CronDigest(title, null)
    }

    /** Leading markdown chrome + inline emphasis off one line. */
    private fun strip(line: String): String = line
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^[-*>•]\\s+"), "")
        .replace(Regex("^\\d+\\.\\s+"), "")
        .replace("**", "")
        .replace("`", "")
        .trim()

    /** From a run's tail of assistant texts (any order): the REPORT is the longest one.
     *  The last message is often narration ("Now I have all the data. Let me compile…")
     *  or bookkeeping ("written to …/digest.md (1,458 bytes)") — short by nature, while
     *  the delivery itself is long. Ties keep list order. */
    fun pickReport(assistantTexts: List<String>): String? =
        assistantTexts.filter { it.isNotBlank() }.maxByOrNull { it.length }

    /** Stable identity hue index for a job name — humans find "the gold one" faster than
     *  a name in a column of names. [buckets] = the UI palette size. */
    fun tintIndex(name: String, buckets: Int): Int {
        var h = 0
        for (c in name) h = (h * 31 + c.code) and 0x7fffffff
        return if (buckets <= 0) 0 else h % buckets
    }
}

data class CronDigest(val title: String?, val lead: String?)
