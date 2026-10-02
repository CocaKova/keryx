package chat.keryx.core.model

/**
 * The Runs editor's pure half (2.16). Until now a job could only be READ on the Runs page; the
 * one editor lived three taps deep in the Hub and took a raw cron line and a raw delivery string
 * (`matrix:!dAfUtHvg…:silas.local`), so creating a job from the phone meant knowing both
 * grammars by heart. These are the translations the form stands on — schedule shapes ↔ the
 * gateway's schedule text, delivery values ↔ words, and what a save actually sends — kept here,
 * free of Compose, so every rule is a unit test rather than a device walk.
 */

/** The shapes the schedule picker offers. Everything else is [CUSTOM] and kept verbatim. */
enum class ScheduleKind { INTERVAL, DAILY, WEEKDAYS, WEEKLY, MONTHLY, CUSTOM }

/**
 * One schedule as the picker holds it. Only the fields of [kind] mean anything; the rest keep
 * whatever the user last set, so flipping Daily → Weekly → Daily does not lose the time.
 */
data class ScheduleDraft(
    val kind: ScheduleKind,
    /** INTERVAL: minutes between runs. */
    val everyMinutes: Int = 60,
    val hour: Int = 8,
    val minute: Int = 0,
    /** WEEKLY: cron day numbers, 0 = Sunday … 6 = Saturday. */
    val days: Set<Int> = setOf(1),
    /** MONTHLY: day of the month. */
    val dayOfMonth: Int = 1,
    /** CUSTOM: the text as typed — a cron line, `every 2h`, `in 30m`, an ISO time. */
    val raw: String = "",
)

object CronScheduleForm {

    private val INTERVAL = Regex("""^every\s+(\d+)m$""")
    private val CRON_TOKEN = Regex("""^[A-Za-z\d*\-,/]+$""")
    private val DOW_LIST = Regex("""^[0-7](,[0-7])*$""")
    private val CLOCK = Regex("""^(\d{1,2}):(\d{2})$""")

    /** A year of minutes: past it an "interval" is a typo, not a schedule. */
    private const val MAX_INTERVAL_MIN = 366 * 24 * 60

    /**
     * The gateway's schedule text → the picker. Only the exact shapes [build] writes are read
     * back as a shape, so `build(parse(x)) == x` for every one of them and an untouched schedule
     * can never be re-sent changed. Anything else — ranges, steps, months, `once at …` — opens
     * as [ScheduleKind.CUSTOM] holding the original text.
     */
    fun parse(raw: String): ScheduleDraft {
        val s = raw.trim()
        val custom = ScheduleDraft(ScheduleKind.CUSTOM, raw = s)
        INTERVAL.find(s)?.let { m ->
            val mins = m.groupValues[1].toIntOrNull() ?: return custom
            if (mins !in 1..MAX_INTERVAL_MIN || m.groupValues[1] != mins.toString()) return custom
            return ScheduleDraft(ScheduleKind.INTERVAL, everyMinutes = mins)
        }
        val f = s.split(Regex("\\s+"))
        if (f.size != 5) return custom
        val (min, hour, dom, mon, dow) = f
        if (mon != "*") return custom
        val m = canonicalInt(min, 0..59) ?: return custom
        val h = canonicalInt(hour, 0..23) ?: return custom
        val base = ScheduleDraft(ScheduleKind.DAILY, hour = h, minute = m)
        return when {
            dom == "*" && dow == "*" -> base
            dom == "*" && dow == "1-5" -> base.copy(kind = ScheduleKind.WEEKDAYS)
            dom == "*" && DOW_LIST.matches(dow) -> {
                val days = dow.split(",").map { it.toInt() % 7 }
                // Only the order build writes (ascending, no repeats) reads back as a shape.
                if (days != days.distinct().sorted() || dow.contains('7')) custom
                else if (days == listOf(1, 2, 3, 4, 5)) custom // build writes these as 1-5
                else base.copy(kind = ScheduleKind.WEEKLY, days = days.toSet())
            }
            dow == "*" -> canonicalInt(dom, 1..31)?.let { base.copy(kind = ScheduleKind.MONTHLY, dayOfMonth = it) } ?: custom
            else -> custom
        }
    }

    /** The picker → the text the gateway's own parser reads (`cron/jobs.py parse_schedule`). */
    fun build(d: ScheduleDraft): String = when (d.kind) {
        ScheduleKind.INTERVAL -> "every ${d.everyMinutes}m"
        ScheduleKind.DAILY -> "${d.minute} ${d.hour} * * *"
        ScheduleKind.WEEKDAYS -> "${d.minute} ${d.hour} * * 1-5"
        ScheduleKind.WEEKLY -> {
            val days = d.days.map { it % 7 }.distinct().sorted()
            "${d.minute} ${d.hour} * * " + if (days == listOf(1, 2, 3, 4, 5)) "1-5" else days.joinToString(",")
        }
        ScheduleKind.MONTHLY -> "${d.minute} ${d.hour} ${d.dayOfMonth} * *"
        ScheduleKind.CUSTOM -> d.raw.trim()
    }

    /**
     * What is wrong with [d] before it is worth sending, or null. Deliberately lenient on
     * [ScheduleKind.CUSTOM]: the gateway's parser takes natural phrases ("every monday 9am"),
     * durations, cron and ISO times, and is the judge — this only catches what cannot be a
     * schedule at all (nothing typed, a cron line missing fields).
     */
    fun problem(d: ScheduleDraft): String? = when (d.kind) {
        ScheduleKind.INTERVAL ->
            if (d.everyMinutes !in 1..MAX_INTERVAL_MIN) "An interval is between a minute and a year" else null
        ScheduleKind.WEEKLY -> if (d.days.isEmpty()) "Pick at least one day" else clockProblem(d)
        ScheduleKind.MONTHLY ->
            if (d.dayOfMonth !in 1..31) "A day of the month is 1 to 31" else clockProblem(d)
        ScheduleKind.DAILY, ScheduleKind.WEEKDAYS -> clockProblem(d)
        ScheduleKind.CUSTOM -> rawProblem(d.raw)
    }

    private fun clockProblem(d: ScheduleDraft): String? =
        if (d.hour !in 0..23 || d.minute !in 0..59) "Pick a time of day" else null

    private fun rawProblem(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return "Say when it runs"
        val tokens = s.split(Regex("\\s+"))
        // Two to four cron-shaped tokens with a cron operator in them is a cron line with
        // fields missing — the one typo worth catching before the round trip.
        if (tokens.size in 2..4 && tokens.all { CRON_TOKEN.matches(it) } && tokens.any { it == "*" || '/' in it }) {
            return "A cron line has five fields: minute hour day month weekday"
        }
        return null
    }

    /** "7:05" / "07:05" → (7, 5); anything else → null. */
    fun clock(text: String): Pair<Int, Int>? {
        val m = CLOCK.find(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** (7, 5) → "07:05". */
    fun clockText(hour: Int, minute: Int): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    /** A cron field as written by [build]: a bare number with no leading zero, in [range]. */
    private fun canonicalInt(field: String, range: IntRange): Int? {
        val n = field.toIntOrNull() ?: return null
        return if (n in range && field == n.toString()) n else null
    }
}

/** One place a job's report can go, as the gateway lists them (`GET /api/cron/delivery-targets`). */
data class DeliveryTarget(
    /** The deliver value itself: `local`, a platform (`matrix`), or `bot-chat:<profile>`. */
    val id: String,
    val name: String,
    /** False = the platform is connected but has no home channel to deliver into. */
    val homeSet: Boolean,
)

/** One row of the delivery picker. [value] is what the job stores. */
data class DeliveryOption(
    val value: String,
    val label: String,
    val detail: String? = null,
    /** Pickable, but it will not arrive anywhere as things stand (no home channel). */
    val warning: Boolean = false,
)

/**
 * Delivery values ↔ words. A job's `deliver` is a comma list of `local`, `origin`, `all`,
 * a platform name (its home channel), `platform:chat_id[:thread]`, or `bot-chat:<profile>`
 * (`cron/scheduler_delivery.py`). The picker shows the gateway's own list of targets when the
 * gateway serves one, and always keeps a value it does not recognise — an operator's exact
 * room is a choice, not an error to "correct".
 */
object CronDelivery {
    const val LOCAL = "local"
    const val ORIGIN = "origin"
    const val ALL = "all"

    /**
     * A deliver value in words. [roomName] resolves a chat id to the name the drawer shows,
     * when the phone knows it; unknown ids are shortened, never dropped.
     */
    fun label(
        value: String,
        targets: List<DeliveryTarget> = emptyList(),
        roomName: (String) -> String? = { null },
    ): String {
        val v = value.trim()
        if (',' in v) {
            return v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" + ") { label(it, targets, roomName) }
        }
        return when {
            v.isEmpty() || v == LOCAL -> "Save only"
            v == ORIGIN -> "Where it was set up"
            v == ALL -> "Every home channel"
            else -> {
                targets.firstOrNull { it.id == v }?.let { t ->
                    return if (':' in t.id) t.name else "${t.name} home channel"
                }
                val platform = v.substringBefore(':')
                val chat = v.substringAfter(':', "")
                val platformName = targets.firstOrNull { it.id == platform }?.name ?: titleCase(platform)
                // A Matrix room id carries its own colon (`!abc:server`); a thread rides after
                // the chat on other platforms (`telegram:123:45`). Try the whole id, then the
                // chat without its thread.
                if (chat.isEmpty()) "$platformName home channel"
                else "$platformName · " + (roomName(chat) ?: roomName(chat.substringBeforeLast(':')) ?: shortChat(chat))
            }
        }
    }

    /**
     * The picker's rows: save-only first, then the gateway's targets in its own order, then
     * [room] (this chat, where the door can name one), then the job's [current] value when it
     * is none of those — kept verbatim, so opening an edit never silently re-routes a job.
     * [targets] null = the gateway serves no list (older dashboard, or the Matrix door): the
     * picker still offers save-only, the room, and what the job already has.
     */
    fun options(
        targets: List<DeliveryTarget>?,
        current: String?,
        room: DeliveryOption? = null,
        roomName: (String) -> String? = { null },
    ): List<DeliveryOption> {
        val known = targets.orEmpty()
        val rows = LinkedHashMap<String, DeliveryOption>()
        rows[LOCAL] = DeliveryOption(LOCAL, label(LOCAL), "No message — the report stays on the gateway")
        for (t in known) {
            if (t.id == LOCAL || t.id.isBlank()) continue
            rows[t.id] = DeliveryOption(
                value = t.id,
                label = label(t.id, known, roomName),
                detail = when {
                    t.id.startsWith("bot-chat:") -> "That bot's own chat"
                    !t.homeSet -> "No home channel set on the gateway — nothing would arrive"
                    else -> "Its home channel"
                },
                warning = !t.homeSet,
            )
        }
        if (room != null && room.value.isNotBlank() && room.value !in rows) rows[room.value] = room
        val cur = current?.trim().orEmpty()
        if (cur.isNotEmpty() && cur !in rows) {
            rows[cur] = DeliveryOption(
                value = cur,
                label = label(cur, known, roomName),
                detail = if (cur == ORIGIN) "The chat it was set up from, or the home channel" else "As set on the gateway",
            )
        }
        return rows.values.toList()
    }

    private fun titleCase(s: String): String =
        s.replace('_', ' ').replace('-', ' ').split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /** `!dAfUtHvghPuyaprEuB:silas.local` → `!dAfUtHvg…` — enough to tell two rooms apart. */
    private fun shortChat(chat: String): String {
        val head = chat.substringBefore(':')
        return if (head.length > 10) head.take(9) + "…" else head
    }
}

/** A job as the editor holds it — one shape for create and edit. */
data class CronJobDraft(
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "",
    val deliver: String = CronDelivery.LOCAL,
    /** Blank = the gateway's default model. Only the dashboard's cron routes accept a model. */
    val model: String = "",
    val provider: String = "",
    /** A script job runs without the agent, so it may carry no prompt at all. */
    val scriptOnly: Boolean = false,
)

object CronJobForm {
    /** The gateway's own caps (`api_server.py` `_MAX_NAME_LENGTH` / `_MAX_PROMPT_LENGTH`). */
    const val MAX_NAME = 200
    const val MAX_PROMPT = 5000

    /** The first thing standing between [d] and a save, in the order the form reads, or null. */
    fun problem(d: CronJobDraft): String? = when {
        d.name.isBlank() -> "Give it a name"
        d.name.trim().length > MAX_NAME -> "A name is at most $MAX_NAME characters"
        d.prompt.isBlank() && !d.scriptOnly -> "Say what the agent does each run"
        d.prompt.trim().length > MAX_PROMPT -> "A prompt is at most $MAX_PROMPT characters"
        d.schedule.isBlank() -> "Say when it runs"
        else -> null
    }

    /**
     * What a save sends. A new job ([original] null) sends every field it has (a blank model
     * is left out: the gateway's default). An edit sends only what changed — so a schedule the
     * gateway shows as `once at 2026-10-03 08:00` is never handed back as text its parser
     * cannot read, and a model and its provider always travel together (clearing one clears
     * both). Values are trimmed; a blank deliver is `local`, as the gateway coalesces it.
     */
    fun changes(original: CronJobDraft?, d: CronJobDraft): Map<String, String> {
        val now = normalized(d)
        if (original == null) {
            return buildMap {
                put("name", now.name)
                put("schedule", now.schedule)
                put("prompt", now.prompt)
                put("deliver", now.deliver)
                if (now.model.isNotEmpty()) {
                    put("model", now.model)
                    if (now.provider.isNotEmpty()) put("provider", now.provider)
                }
            }
        }
        val was = normalized(original)
        return buildMap {
            if (now.name != was.name) put("name", now.name)
            if (now.prompt != was.prompt) put("prompt", now.prompt)
            if (now.schedule != was.schedule) put("schedule", now.schedule)
            if (now.deliver != was.deliver) put("deliver", now.deliver)
            if (now.model != was.model || now.provider != was.provider) {
                put("model", now.model)
                put("provider", now.provider)
            }
        }
    }

    private fun normalized(d: CronJobDraft) = d.copy(
        name = d.name.trim(),
        prompt = d.prompt.trim(),
        schedule = d.schedule.trim(),
        deliver = d.deliver.trim().ifEmpty { CronDelivery.LOCAL },
        model = d.model.trim(),
        provider = if (d.model.isBlank()) "" else d.provider.trim(),
    )
}
