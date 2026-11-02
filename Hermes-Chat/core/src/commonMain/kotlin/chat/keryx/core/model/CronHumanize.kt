package chat.keryx.core.model

import chat.keryx.core.i18n.CoreWords
import chat.keryx.core.i18n.CountPhrase
import chat.keryx.core.i18n.Phrase
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
        "0" to Phrase.CRON_SUN, "1" to Phrase.CRON_MON, "2" to Phrase.CRON_TUE, "3" to Phrase.CRON_WED,
        "4" to Phrase.CRON_THU, "5" to Phrase.CRON_FRI, "6" to Phrase.CRON_SAT, "7" to Phrase.CRON_SUN,
    )

    /** A count too big for a plural rule is not a schedule anyone wrote; null = say it raw. */
    private fun everyDays(d: Long): String? = d.takeIf { it <= Int.MAX_VALUE }?.let { CoreWords.n(CountPhrase.CRON_EVERY_DAYS, it.toInt()) }
    private fun everyHours(h: Long): String? = h.takeIf { it <= Int.MAX_VALUE }?.let { CoreWords.n(CountPhrase.CRON_EVERY_HOURS, it.toInt()) }

    /** "0 19 * * 0" → "Sun 19:00" · "15 7 * * 1-5" → "weekdays 07:15" · "every 30m" →
     *  "every 30 min" · unparseable → the raw string (never lie, never hide). */
    fun schedule(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s

        // Interval jobs: "every 30m", "every 20160m".
        Regex("""^every\s+(\d+)m$""").find(s)?.let { m ->
            // `(\d+)` is unbounded, so the number is not necessarily a Long: `every
            // 99999999999999999999m` threw NumberFormatException out of a composable that draws
            // one line per job card, taking the whole Runs door with it. "Unparseable → the raw
            // string" is this function's whole contract; a number too big to be a schedule, and a
            // zero-length interval, are unparseable like anything else.
            val mins = m.groupValues[1].toLongOrNull() ?: return s
            if (mins <= 0L) return s
            return when {
                mins % (60 * 24) == 0L -> {
                    val d = mins / (60 * 24)
                    if (d == 1L) CoreWords.t(Phrase.CRON_DAILY) else everyDays(d) ?: s
                }
                mins % 60 == 0L -> {
                    val h = mins / 60
                    if (h == 1L) CoreWords.t(Phrase.CRON_HOURLY) else everyHours(h) ?: s
                }
                else -> CoreWords.t(Phrase.CRON_EVERY_MIN, mins)
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
            if (hour == "*" && dom == "*" && dow == "*") return CoreWords.t(Phrase.CRON_EVERY_MIN, m.groupValues[1])
        }
        Regex("""^\*/(\d+)$""").find(hour)?.let { m ->
            if (dom == "*" && dow == "*") return m.groupValues[1].toLongOrNull()?.let(::everyHours) ?: s
        }

        val time = clock(hour, min) ?: return s
        return when {
            dom == "*" && dow == "*" -> CoreWords.t(Phrase.CRON_DAILY_AT, time)
            dom == "*" && dow == "1-5" -> CoreWords.t(Phrase.CRON_WEEKDAYS_AT, time)
            dom == "*" && (dow == "0,6" || dow == "6,0") -> CoreWords.t(Phrase.CRON_WEEKENDS_AT, time)
            dom == "*" -> {
                val names = dow.split(",").map { DOW[it]?.let { d -> CoreWords.t(d) } ?: return s }
                CoreWords.t(Phrase.CRON_DAYS_AT, names.joinToString("/"), time)
            }
            dow == "*" && dom.toIntOrNull() != null -> CoreWords.t(Phrase.CRON_MONTHLY_AT, dom, time)
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
        return CoreWords.t(Phrase.CRON_NEXT_IN, when {
            deltaMin < 1 -> CoreWords.t(Phrase.CRON_UNDER_A_MINUTE)
            deltaMin < 90 -> CoreWords.t(Phrase.CRON_MINUTES_SHORT, deltaMin)
            deltaMin < 36 * 60 -> CoreWords.t(Phrase.CRON_HOURS_SHORT, deltaMin / 60)
            else -> CoreWords.t(Phrase.CRON_DAYS_SHORT, deltaMin / (60 * 24))
        })
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
