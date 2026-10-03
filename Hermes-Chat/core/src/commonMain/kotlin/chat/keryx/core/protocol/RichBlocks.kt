package chat.keryx.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A fenced block the agent wrote for Keryx to DRAW rather than print: a chart, a diff, a
 * timeline, progress bars, colour swatches, a card, a collapsible section (2.17).
 *
 * The fence's info string picks the block (```chart, ```diff …). [RichBlocks.parse] turns the
 * body into one of these or returns null, and null means "show it as the code block it is" —
 * the agent's words are never lost to a parser that could not read them. Parsers are forgiving
 * about the sloppiness models actually produce (trailing commas, stray bullets, mixed
 * separators) but refuse to guess: data that does not line up is a code block, not a wrong
 * chart.
 *
 * Pure common Kotlin, like the rest of the protocol layer.
 */
sealed interface RichBlock {

    data class Chart(
        val type: ChartType,
        val title: String?,
        /** One per point (category axis / pie slice). */
        val labels: List<String>,
        /** Every series has exactly [labels].size values; null is a gap. */
        val series: List<Series>,
        val unit: String?,
        val stacked: Boolean,
    ) : RichBlock

    data class Series(val name: String?, val values: List<Double?>)

    enum class ChartType { BAR, HBAR, LINE, AREA, PIE, DONUT }

    data class Diff(val lines: List<DiffLine>) : RichBlock
    data class DiffLine(val kind: DiffKind, val text: String)
    enum class DiffKind { ADD, DEL, HUNK, META, CONTEXT }

    data class Timeline(val items: List<TimelineItem>) : RichBlock
    data class TimelineItem(val time: String?, val title: String, val detail: String?, val done: Boolean?)

    data class Progress(val items: List<ProgressItem>) : RichBlock
    /** [fraction] is clamped to 0..1; [display] is what the agent wrote ("60%", "3/5"). */
    data class ProgressItem(val label: String, val fraction: Float, val display: String)

    data class Swatches(val colors: List<Swatch>) : RichBlock
    /** [argb] is a full 0xAARRGGBB value; [hex] is the normalised `#RRGGBB` / `#AARRGGBB`. */
    data class Swatch(val name: String?, val argb: Long, val hex: String)

    data class Card(
        val title: String,
        val subtitle: String?,
        val body: String?,
        /** https only. */
        val image: String?,
        /** http(s) only. */
        val url: String?,
        val fields: List<Pair<String, String>>,
    ) : RichBlock

    /** A collapsible section; [body] is markdown the app renders through its own path. */
    data class Details(val title: String, val body: String) : RichBlock
}

object RichBlocks {

    const val MAX_SERIES = 12
    const val MAX_POINTS = 200
    private const val MAX_ITEMS = 60
    private const val MAX_SWATCHES = 48
    private const val MAX_DIFF_LINES = 2000

    private val CHART = setOf("chart")
    private val DIFF = setOf("diff", "patch")
    private val TIMELINE = setOf("timeline")
    private val PROGRESS = setOf("progress")
    private val SWATCH = setOf("swatch", "swatches", "colors", "colours", "palette")
    private val CARD = setOf("card")
    private val DETAILS = setOf("details", "collapse", "collapsible")

    /** Every fence language this layer claims (lowercase). */
    val LANGS: Set<String> = CHART + DIFF + TIMELINE + PROGRESS + SWATCH + CARD + DETAILS

    /** [lang] is the fence info string's first word; [body] is the code between the fences. */
    fun parse(lang: String, body: String): RichBlock? {
        val l = lang.trim().lowercase()
        return try {
            when (l) {
                in CHART -> parseChart(body)
                in DIFF -> parseDiff(body)
                in TIMELINE -> parseTimeline(body)
                in PROGRESS -> parseProgress(body)
                in SWATCH -> parseSwatches(body)
                in CARD -> parseCard(body)
                in DETAILS -> parseDetails(body)
                else -> null
            }
        } catch (_: Throwable) {
            // A parser surprise degrades to the code block, never to a dead bubble.
            null
        }
    }

    // --- chart ------------------------------------------------------------------------------

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
        allowComments = true
    }

    private fun parseChart(body: String): RichBlock.Chart? {
        val root = json.parseToJsonElement(body.trim()) as? JsonObject ?: return null
        val type = chartType(root.str("type") ?: root.str("kind")) ?: return null
        val title = root.str("title")?.takeIf { it.isNotBlank() }
        val unit = (root.str("unit") ?: root.str("units"))?.takeIf { it.isNotBlank() }
        val stacked = (root["stacked"] as? JsonPrimitive)?.booleanOrNull ?: false

        // Series: "series":[{name, values|data}] | "series":[[…],[…]] | "data":[…] | "values":[…]
        val series = mutableListOf<RichBlock.Series>()
        when (val s = root["series"] ?: root["datasets"]) {
            is JsonArray -> s.forEachIndexed { i, el ->
                when (el) {
                    is JsonObject -> {
                        val vals = numbers(el["values"] ?: el["data"]) ?: return null
                        series += RichBlock.Series(el.str("name") ?: el.str("label"), vals)
                    }
                    is JsonArray -> series += RichBlock.Series(null, numbers(el) ?: return null)
                    else -> if (i == 0) {
                        // "series":[1,2,3] — one unnamed series written flat.
                        series += RichBlock.Series(null, numbers(s) ?: return null)
                        return@forEachIndexed
                    } else Unit
                }
            }
            null, JsonNull -> {}
            else -> return null
        }
        if (series.size == 1 && series[0].values.isEmpty()) series.clear()
        var labels = strings(root["labels"] ?: root["categories"] ?: root["x"])
        if (series.isEmpty()) {
            val data = root["data"] ?: root["values"]
            val flat = numbers(data)
            // "data":[{"label":"a","value":3}, …] — label/value pairs in one list.
            val pairs = (data as? JsonArray)?.takeIf { a -> a.isNotEmpty() && a.all { it is JsonObject } }
                ?.map { it as JsonObject }
            when {
                flat != null -> series += RichBlock.Series(root.str("name"), flat)
                pairs != null -> {
                    if (labels == null) labels = pairs.map { it.str("label") ?: it.str("name") ?: it.str("x") ?: "" }
                    series += RichBlock.Series(root.str("name"), pairs.map { num(it["value"] ?: it["y"] ?: it["count"]) })
                }
                else -> return null
            }
        }

        if (series.isEmpty() || series.size > MAX_SERIES) return null
        val points = series.maxOf { it.values.size }
        if (points == 0 || points > MAX_POINTS) return null
        if (series.any { it.values.size != points }) return null
        val finalLabels = when {
            labels == null -> (1..points).map { it.toString() }
            labels.size == points -> labels
            else -> return null
        }
        if (series.all { s -> s.values.all { it == null } }) return null
        if (type == RichBlock.ChartType.PIE || type == RichBlock.ChartType.DONUT) {
            // A pie is one series of parts of a whole: nothing negative, something positive.
            if (series.size != 1) return null
            val v = series[0].values
            if (v.any { it != null && it < 0 } || v.none { it != null && it > 0 }) return null
        }
        return RichBlock.Chart(type, title, finalLabels, series, unit, stacked)
    }

    private fun chartType(raw: String?): RichBlock.ChartType? = when (raw?.trim()?.lowercase()?.replace('_', '-')) {
        null, "", "bar", "bars", "column", "columns", "col" -> RichBlock.ChartType.BAR
        "hbar", "h-bar", "horizontal-bar", "horizontalbar", "barh", "row", "rows" -> RichBlock.ChartType.HBAR
        "line", "lines", "spline" -> RichBlock.ChartType.LINE
        "area" -> RichBlock.ChartType.AREA
        "pie" -> RichBlock.ChartType.PIE
        "donut", "doughnut", "ring" -> RichBlock.ChartType.DONUT
        else -> null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun num(el: JsonElement?): Double? = when (el) {
        is JsonPrimitive -> el.doubleOrNull ?: el.contentOrNull?.trim()?.removeSuffix("%")?.replace(",", "")?.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    /** A JSON array as numbers; nulls (and unreadable entries) are gaps. Null if not an array. */
    private fun numbers(el: JsonElement?): List<Double?>? {
        val arr = el as? JsonArray ?: return null
        if (arr.any { it is JsonObject || it is JsonArray }) return null
        return arr.map { num(it) }
    }

    private fun strings(el: JsonElement?): List<String>? {
        val arr = el as? JsonArray ?: return null
        return arr.map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
    }

    // --- diff -------------------------------------------------------------------------------

    private val META_PREFIX = listOf("diff ", "index ", "--- ", "+++ ", "new file", "deleted file", "similarity ", "rename ", "old mode", "new mode", "\\ No newline")

    private fun parseDiff(body: String): RichBlock.Diff? {
        val raw = body.trim('\n').lines()
        if (raw.isEmpty() || raw.size > MAX_DIFF_LINES) return null
        val lines = raw.map { line ->
            val kind = when {
                META_PREFIX.any { line.startsWith(it) } || line == "---" || line == "+++" -> RichBlock.DiffKind.META
                line.startsWith("@@") -> RichBlock.DiffKind.HUNK
                line.startsWith("+") -> RichBlock.DiffKind.ADD
                line.startsWith("-") -> RichBlock.DiffKind.DEL
                else -> RichBlock.DiffKind.CONTEXT
            }
            RichBlock.DiffLine(kind, line)
        }
        // A "diff" with no change line is just text someone tagged diff.
        if (lines.none { it.kind == RichBlock.DiffKind.ADD || it.kind == RichBlock.DiffKind.DEL }) return null
        return RichBlock.Diff(lines)
    }

    // --- timeline ---------------------------------------------------------------------------

    /** `- `, `* `, `• `, `1. ` at the start of a line. */
    private val BULLET = Regex("""^\s*(?:[-*•+]|\d{1,3}[.)])\s+""")
    private val CHECK = Regex("""^\[([ xX✓✔])\]\s*""")
    /** Separators between time · title · detail. Spaced dashes only, so 2026-09-01 survives. */
    private val SEP = Regex("""\s+(?:·|•|—|–|-{1,2}|\|)\s+""")
    /** A leading date/time-ish token followed by a colon: `2026-09-01: …`, `Q3: …`, `10:30: …`. */
    private val TIME_COLON = Regex("""^([^:]{1,24}?\d[^:]{0,12}|Q[1-4](?:\s+\d{4})?|\d{1,2}:\d{2}(?:\s*[AaPp][Mm])?)\s*:\s+(.+)$""")

    private fun parseTimeline(body: String): RichBlock.Timeline? {
        val items = mutableListOf<RichBlock.TimelineItem>()
        for (rawLine in body.lines()) {
            var line = rawLine.replace(BULLET, "").trim()
            if (line.isEmpty()) continue
            var done: Boolean? = null
            CHECK.find(line)?.let { m ->
                done = m.groupValues[1].isNotBlank()
                line = line.substring(m.range.last + 1).trim()
            }
            if (line.isEmpty()) continue
            val parts = line.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
            val item = when {
                parts.size >= 3 -> RichBlock.TimelineItem(parts[0], parts[1], parts.drop(2).joinToString(" — "), done)
                parts.size == 2 -> if (looksLikeTime(parts[0])) RichBlock.TimelineItem(parts[0], parts[1], null, done)
                    else RichBlock.TimelineItem(null, parts[0], parts[1], done)
                else -> TIME_COLON.find(parts[0])?.let { m ->
                    RichBlock.TimelineItem(m.groupValues[1].trim(), m.groupValues[2].trim(), null, done)
                } ?: RichBlock.TimelineItem(null, parts[0], null, done)
            }
            items += item
            if (items.size > MAX_ITEMS) return null
        }
        // One line is a sentence, not a timeline.
        if (items.size < 2) return null
        return RichBlock.Timeline(items)
    }

    private fun looksLikeTime(s: String): Boolean =
        s.length <= 24 && (s.any { it.isDigit() } || s.matches(Regex("""(?i)(now|today|tomorrow|yesterday|next|later|soon|then|q[1-4].*|jan.*|feb.*|mar.*|apr.*|may.*|jun.*|jul.*|aug.*|sep.*|oct.*|nov.*|dec.*|mon.*|tue.*|wed.*|thu.*|fri.*|sat.*|sun.*)""")))

    // --- progress ---------------------------------------------------------------------------

    private val PCT = Regex("""^(-?\d+(?:\.\d+)?)\s*%$""")
    private val RATIO = Regex("""^(\d+(?:\.\d+)?)\s*(?:/|of|out of)\s*(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
    private val FRACTION = Regex("""^(-?\d+(?:\.\d+)?)$""")
    /** `label: value`, `label — value`, `label | value`, `label = value`; the LAST separator wins
     *  so a label may hold a colon ("Phase 2: build: 40%"). */
    private val PROGRESS_LINE = Regex("""^(.+?)\s*(?::|=|\||—|–|\s-\s)\s*([^:=|—–]+)$""")

    private fun parseProgress(body: String): RichBlock.Progress? {
        val items = mutableListOf<RichBlock.ProgressItem>()
        for (rawLine in body.lines()) {
            val line = rawLine.replace(BULLET, "").trim()
            if (line.isEmpty()) continue
            val m = PROGRESS_LINE.find(line) ?: return null
            val label = m.groupValues[1].trim().trimEnd(':', '-', '—', '–').trim()
            val value = m.groupValues[2].trim()
            val fraction = PCT.find(value)?.let { it.groupValues[1].toFloat() / 100f }
                ?: RATIO.find(value)?.let { r ->
                    val den = r.groupValues[2].toFloat()
                    if (den <= 0f) return null
                    r.groupValues[1].toFloat() / den
                }
                ?: FRACTION.find(value)?.let { f ->
                    val v = f.groupValues[1].toFloat()
                    // 0.6 is a fraction; 60 is a percentage someone forgot the sign on.
                    if (v > 1f && v <= 100f) v / 100f else v
                }
                ?: return null
            if (label.isEmpty() || fraction.isNaN()) return null
            items += RichBlock.ProgressItem(label, fraction.coerceIn(0f, 1f), value)
            if (items.size > MAX_ITEMS) return null
        }
        return if (items.isEmpty()) null else RichBlock.Progress(items)
    }

    // --- swatches ---------------------------------------------------------------------------

    private val HEX = Regex("""#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-fA-F])""")

    private fun parseSwatches(body: String): RichBlock.Swatches? {
        val out = mutableListOf<RichBlock.Swatch>()
        for (rawLine in body.lines()) {
            val line = rawLine.replace(BULLET, "").trim()
            if (line.isEmpty()) continue
            val hits = HEX.findAll(line).toList()
            if (hits.isEmpty()) return null
            if (hits.size == 1) {
                // `name: #hex`, `name #hex`, `#hex name`, `#hex — name`
                val hit = hits[0]
                val name = (line.removeRange(hit.range))
                    .trim().trim(':', '-', '—', '–', '=', '|', ',', '(', ')').trim()
                    .ifEmpty { null }
                out += swatch(hit.groupValues[1], name) ?: return null
            } else {
                // A run of colours on one line: `#111 #222, #333`. Anything else on it is noise.
                hits.forEach { out += swatch(it.groupValues[1], null) ?: return null }
            }
            if (out.size > MAX_SWATCHES) return null
        }
        return if (out.isEmpty()) null else RichBlock.Swatches(out)
    }

    private fun swatch(hex: String, name: String?): RichBlock.Swatch? {
        val full = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }.uppercase()
        val argb = full.toLongOrNull(16) ?: return null
        val shown = if (full.startsWith("FF")) "#" + full.substring(2) else "#$full"
        return RichBlock.Swatch(name, argb, shown)
    }

    // --- card -------------------------------------------------------------------------------

    private val KV = Regex("""^([A-Za-z][\w .\-]{0,31}?)\s*:\s*(.*)$""")

    private fun parseCard(body: String): RichBlock.Card? {
        var title: String? = null
        var subtitle: String? = null
        var image: String? = null
        var url: String? = null
        val bodyLines = mutableListOf<String>()
        val fields = mutableListOf<Pair<String, String>>()
        var inBody = false
        for (rawLine in body.lines()) {
            val line = rawLine.trimEnd()
            if (inBody) { bodyLines += line; continue }
            if (line.isBlank()) continue
            val m = KV.find(line.trim())
            if (m == null) {
                // Free text after the keys is the body.
                bodyLines += line.trim(); inBody = true; continue
            }
            val key = m.groupValues[1].trim()
            val value = m.groupValues[2].trim()
            when (key.lowercase()) {
                "title", "name", "heading" -> title = value
                "subtitle", "sub", "tagline" -> subtitle = value
                "image", "img", "thumbnail", "picture", "photo" -> image = value.takeIf { it.startsWith("https://") }
                "url", "link", "href" -> url = value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                "body", "description", "desc", "text", "summary" -> { if (value.isNotEmpty()) bodyLines += value; inBody = true }
                else -> if (value.isNotEmpty()) fields += key to value
            }
        }
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        val b = bodyLines.joinToString("\n").trim().ifEmpty { null }
        return RichBlock.Card(t, subtitle?.ifBlank { null }, b, image, url, fields.take(MAX_ITEMS))
    }

    // --- details ----------------------------------------------------------------------------

    private val TITLE_KEY = Regex("""^(?:summary|title)\s*:\s*""", RegexOption.IGNORE_CASE)

    private fun parseDetails(body: String): RichBlock.Details? {
        val lines = body.trim('\n').lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null
        val title = lines[first].trim().replace(TITLE_KEY, "").trim().trimStart('#').trim()
        val rest = lines.drop(first + 1).joinToString("\n").trim('\n')
        if (title.isEmpty() || rest.isBlank()) return null
        return RichBlock.Details(title, rest)
    }
}
