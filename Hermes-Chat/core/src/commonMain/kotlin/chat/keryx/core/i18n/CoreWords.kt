package chat.keryx.core.i18n

/**
 * The words :core puts on screen (3.0 languages).
 *
 * Core is pure Kotlin with no resources, but it still writes sentences people read: the
 * notifications, the drawer's nouns, a cron schedule in words, the context ring's line. Each such
 * phrase is named here with its English text. English is the default, so core's tests (and any
 * caller that never installs anything) read exactly what they always did; the app installs a
 * [Words] that looks each phrase up in its own string resources, in the app's language.
 *
 * Templates use Android's positional form (`%1$s`, `%2$d`) so a translator sees the same
 * placeholders in the XML as here; [fill] does the substitution on both sides.
 */
enum class Phrase(val english: String) {
    // Notifications: a message landing.
    NOTICE_PHOTO("🖼 Photo"),
    NOTICE_ATTACHMENT("Attachment"),
    NOTICE_NEW_MESSAGE("New message"),

    // Notifications: a request answerable from the shade.
    SHADE_UNLOCK_TO_SEE("Unlock to see it."),
    SHADE_APPROVE("Approve"),
    SHADE_ALWAYS("Always"),
    SHADE_THIS_SESSION("This session"),
    SHADE_DENY("Deny"),
    SHADE_APPROVAL_TITLE("Approval needed"),
    SHADE_APPROVAL_BODY("The agent wants to run a guarded command."),
    SHADE_APPROVAL_LOCKED("The agent wants to run a command. Unlock to see it."),
    SHADE_ASKS_TITLE("The agent asks"),
    SHADE_ASKS_BODY("The agent has a question."),
    SHADE_ASKS_LOCKED("The agent has a question. Unlock to read it."),
    SHADE_SUDO_TITLE("Password needed"),
    SHADE_SUDO_BODY("A command on the host is waiting for your sudo password. Tap to answer."),
    SHADE_SUDO_LOCKED("A command is waiting for a password. Unlock to answer."),
    SHADE_SECRET_TITLE("Secret needed"),
    SHADE_SECRET_FALLBACK("The agent needs a credential."),
    SHADE_SECRET_BODY("%1\$s Tap to answer."),
    SHADE_SECRET_LOCKED("The agent needs a credential. Unlock to answer."),

    // Notifications: an agent at work.
    RUN_WRITING("Writing the reply…"),
    RUN_THINKING("Thinking…"),
    RUN_STEP("Step %1\$d of %2\$d · %3\$s"),
    RUN_AGENT_WORKING("%1\$s is working"),
    RUN_WORKING_IN("Working · %1\$s"),

    // The door's own nouns: Matrix rooms.
    LEX_ROOM("room"),
    LEX_ROOMS("rooms"),
    LEX_QUICK_ROOMS("Quick Rooms"),
    LEX_ROOMS_HEADER("Rooms"),
    LEX_PIN_QUICK("Pin to Quick Rooms"),
    LEX_UNPIN_QUICK("Unpin from Quick Rooms"),
    LEX_NO_ROOMS("No rooms yet"),
    LEX_SELECT_ROOM("Select a room to begin"),
    LEX_SHARE_ROOM("Share to a room"),
    LEX_OPEN_ROOM_QUICK("Open a room to use quick actions"),

    // The door's own nouns: gateway sessions.
    LEX_SESSION("session"),
    LEX_SESSIONS("sessions"),
    LEX_PINNED("Pinned"),
    LEX_SESSIONS_HEADER("Sessions"),
    LEX_PIN("Pin"),
    LEX_UNPIN("Unpin"),
    LEX_PIN_HINT("Kept on the gateway — never auto-archived"),
    LEX_NO_SESSIONS("No sessions yet"),
    LEX_OPEN_SESSION("Open a session to begin"),
    LEX_SHARE_SESSION("Share to a session"),
    LEX_OPEN_SESSION_QUICK("Open a session to use quick actions"),

    // A cron schedule in words.
    CRON_DAILY("daily"),
    CRON_HOURLY("hourly"),
    CRON_EVERY_MIN("every %1\$s min"),
    CRON_DAILY_AT("daily %1\$s"),
    CRON_WEEKDAYS_AT("weekdays %1\$s"),
    CRON_WEEKENDS_AT("weekends %1\$s"),
    CRON_DAYS_AT("%1\$s %2\$s"),
    CRON_MONTHLY_AT("monthly (day %1\$s) %2\$s"),
    CRON_SUN("Sun"),
    CRON_MON("Mon"),
    CRON_TUE("Tue"),
    CRON_WED("Wed"),
    CRON_THU("Thu"),
    CRON_FRI("Fri"),
    CRON_SAT("Sat"),
    CRON_NEXT_IN("next in %1\$s"),
    CRON_UNDER_A_MINUTE("<1m"),
    CRON_MINUTES_SHORT("%1\$sm"),
    CRON_HOURS_SHORT("%1\$sh"),
    CRON_DAYS_SHORT("%1\$sd"),

    // The context ring and its sheet.
    CTX_SUMMARY_NEXT("summary next"),
    CTX_FILLING_UP("filling up"),
    CTX_LEFT("~%1\$d left"),
    CTX_THE_AGENT("the agent"),
    CTX_THEN("Then %1\$s summarizes the older messages so the chat can keep going."),
    CTX_FILLS_WINDOW("The chat fills up as you talk. This shows how much the model can still hold."),
    CTX_NEXT_SUMMARY("The next reply starts a summary of the older messages, so the chat can keep going."),
    CTX_FILLS_THEN("The chat fills up as you talk. %1\$s"),
    CTX_BEFORE_SUMMARY("%1\$s of %2\$s before the summary"),
    CTX_BEFORE_SUMMARY_WINDOW("%1\$s of %2\$s before the summary · window %3\$s"),
    CTX_MODEL_HOLDS("%1\$s of %2\$s the model can hold"),

    // The drawer's shelves, by when.
    ROSTER_TODAY("Today"),
    ROSTER_YESTERDAY("Yesterday"),
    ROSTER_THIS_WEEK("This week"),
    ROSTER_OLDER("Older"),
    ROSTER_UNDATED("Undated"),

    // Find in chat.
    FIND_NO_MATCH("no match"),
    FIND_POSITION("%1\$d of %2\$d"),

    // A session's standing goal, on its strip.
    GOAL_TURN("turn %1\$d/%2\$d"),
    GOAL_GATES("gates %1\$d/%2\$d"),
    GOAL_DONE("✓ done"),
    GOAL_PAUSED("paused"),
    GOAL_WAITING("waiting"),
    GOAL_BLOCKED("blocked"),
    GOAL_PAUSE("Pause"),
    GOAL_RESUME("Resume"),
    GOAL_STOP_WAITING("Stop waiting"),
    GOAL_CLEAR("Clear"),

    // Updating Hermes from the phone.
    UPDATE_UNREACHABLE("Couldn't reach the update source"),
    UPDATE_BEHIND_UNKNOWN("Behind by an unknown number of commits"),
    UPDATE_UP_TO_DATE("Up to date"),

    // Reasoning effort levels.
    EFFORT_OFF("Off"),
    EFFORT_MINIMAL("Minimal"),
    EFFORT_LOW("Low"),
    EFFORT_MEDIUM("Medium"),
    EFFORT_HIGH("High"),
    EFFORT_XHIGH("X-High"),
    EFFORT_MAX("Max"),
    EFFORT_ULTRA("Ultra"),

    // A transport error in words a person can act on.
    ERR_GENERIC("Something went wrong talking to the gateway."),
    ERR_404("The gateway doesn't offer this (HTTP 404). It needs the keryx-stream plugin; check it is enabled and your Hermes Link URL points at it."),
    ERR_AUTH("The gateway turned the sign-in away (HTTP %1\$d). Sign in again from Settings → Gateways."),
    ERR_SERVER("The gateway hit an error (HTTP %1\$d). Its log says what happened."),
    ERR_NO_HOST("Can't find the gateway. Check your connection (and Tailscale, if you use it)."),
    ERR_REFUSED("The gateway isn't answering. Is Hermes running?"),
    ERR_TIMEOUT("The gateway took too long to answer."),
    ERR_NOT_CONNECTED("Not connected to the gateway right now. Keryx retries on its own."),
}

/** A phrase with a count in it: English needs [one] and [other]; other languages more. */
enum class CountPhrase(val one: String, val other: String) {
    RUN_TOOL_CALLS("%1\$d tool call so far", "%1\$d tool calls so far"),
    RUN_AGENTS_WORKING("%1\$d agent working", "%1\$d agents working"),
    CRON_EVERY_DAYS("every %1\$d day", "every %1\$d days"),
    CRON_EVERY_HOURS("every %1\$d h", "every %1\$d h"),
    CTX_ROOM_BEFORE_FULL("Room for about %1\$d more reply before the chat is full.", "Room for about %1\$d more replies before the chat is full."),
    UPDATE_COMMITS_BEHIND("%1\$d commit behind", "%1\$d commits behind"),
    CTX_ROOM_THEN("Room for about %1\$d more reply. %2\$s", "Room for about %1\$d more replies. %2\$s"),
}

/** Where the templates come from: English here, the app's resources on the phone. */
interface Words {
    /** [p]'s template in the reader's language. */
    fun phrase(p: Phrase): String

    /** [p]'s template for [count], by that language's plural rules. */
    fun count(p: CountPhrase, count: Int): String
}

object EnglishWords : Words {
    override fun phrase(p: Phrase): String = p.english
    override fun count(p: CountPhrase, count: Int): String = if (count == 1) p.one else p.other
}

object CoreWords {
    /** Set once by the app at start; English until then. */
    var words: Words = EnglishWords

    /** [p] in the current language, its blanks filled with [args]. */
    fun t(p: Phrase, vararg args: Any): String = fill(read { words.phrase(p) } ?: p.english, args)

    /** [p] for [n] in the current language; `%1$d` is [n], [args] fill `%2$…` onward. */
    fun n(p: CountPhrase, n: Int, vararg args: Any): String =
        fill(read { words.count(p, n) } ?: EnglishWords.count(p, n), arrayOf<Any>(n, *args))

    // A lookup that fails (the app not ready yet, a resource gone) falls back to English rather
    // than take a notification down with it.
    private inline fun read(block: () -> String): String? = try { block() } catch (e: Exception) { null }

    private val SPEC = Regex("""%(?:(\d+)\$)?([sd%])""")

    /** Fill `%1$s` / `%2$d` / `%s` / `%d` from [args]; `%%` is a percent sign. */
    fun fill(template: String, args: Array<out Any>): String {
        if (args.isEmpty() && '%' !in template) return template
        var next = 0
        return SPEC.replace(template) { m ->
            if (m.groupValues[2] == "%") "%"
            else {
                val i = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
                args.getOrNull(i)?.toString() ?: m.value
            }
        }
    }
}
