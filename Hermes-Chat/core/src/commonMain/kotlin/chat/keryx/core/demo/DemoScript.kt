package chat.keryx.core.demo

import chat.keryx.core.model.ApprovalRequest
import chat.keryx.core.model.MediaTags
import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
import chat.keryx.core.model.TodoPlan
import chat.keryx.core.model.TodoPlanParser
import chat.keryx.core.protocol.MessageRow
import chat.keryx.core.protocol.RestToolCall
import chat.keryx.core.protocol.TranscriptBuilder

/**
 * The demo gateway's sessions (3.0 herald): what a stranger with no Hermes yet looks around in.
 *
 * Written as the rows a real gateway stores and drawn by [TranscriptBuilder], the same path a
 * real session takes, so the demo can't show anything Keryx doesn't. It shows the lane: an agent
 * on your own machine doing jobs while you're elsewhere, not a chat window.
 *
 * Nothing in it is about a real person, product or price, and no dates: it has to read true on
 * any day it's opened.
 */
object DemoScript {
    const val AGENT = "Hermes"

    const val UNREAD = "demo-groceries"
    const val RUNNING = "demo-importer"

    /** The session the demo opens on: an agent mid-job, its plan up, stopped on the visitor. */
    const val FIRST = RUNNING

    /** A demo session: its drawer row, the rows its transcript is built from, and the approval
     *  its agent is stopped on, if any. */
    class Session(val id: String, val title: String, val minutesAgo: Long, val rows: List<Row>, val ask: Ask? = null) {
        val unread: Boolean get() = id == UNREAD
        val running: Boolean get() = id == RUNNING
    }

    /**
     * Where the agent stopped to ask, in the words Hermes's own approval check uses
     * (`tools/approval_detection.py`), and what plays on each answer: the visitor's tap is what
     * moves the job on.
     */
    class Ask(val command: String, val description: String, val allowed: List<Row>, val denied: List<Row>) {
        /** The card, as a gateway would raise it (Hermes's four choices). */
        val request: ApprovalRequest get() = ApprovalRequest(command, description, listOf("once", "session", "always", "deny"))

        fun after(choice: String): List<Row> = if (choice == "deny") denied else allowed
    }

    /** One stored row, timed in minutes before the session's last activity. */
    sealed interface Row {
        data class User(val text: String) : Row
        data class Agent(val text: String = "", val thought: String? = null, val calls: List<Call> = emptyList()) : Row
    }

    /** A tool call and what it returned, in the shape Hermes stores (`output` + `exit_code`, or `error`). */
    data class Call(val tool: String, val argsJson: String, val result: String)

    private fun terminal(command: String, output: String) = Call(
        "terminal",
        """{"command":${quote(command)}}""",
        """{"output":${quote(output)},"exit_code":0,"error":null}""",
    )

    /** A `todo_list` call: the whole plan, each step with its status, as the tool returns it. */
    private fun plan(vararg steps: Pair<String, String>): Call {
        val todos = steps.mapIndexed { i, (content, status) ->
            """{"content":${quote(content)},"id":"${i + 1}","status":"$status"}"""
        }.joinToString(",", "[", "]")
        return Call("todo_list", """{"todos":$todos}""", """{"todos":$todos}""")
    }

    // The importer's plan, step by step.
    private const val PROFILE = "Profile the importer"
    private const val FIX = "Fix the slow part"
    private const val TESTS = "Run the tests"
    private const val MEASURE = "Measure it on a real card"
    private const val DONE = "completed"
    private const val NOW = "in_progress"
    private const val LATER = "pending"

    /** The importer's last words: what changed, and the page that shows it. */
    private fun importerDone(clearedCache: Boolean) = Row.Agent(
        "Done. Importing your 2,104-photo test card went from **44 s to 7 s**, about six times faster.\n\n" +
            "- EXIF is read for the whole card in one batch instead of once per photo.\n" +
            "- All 38 tests pass.\n" +
            (if (clearedCache) "- I cleared the stale thumbnail cache. It rebuilds itself on the next import.\n\n"
            else "- Your thumbnail cache is untouched. The tests ran against a fresh one.\n\n") +
            "Here's the before and after:\n\n" +
            "MEDIA:${DemoPages.SPEEDUP}",
    )

    private val measured = listOf(
        Row.Agent(
            calls = listOf(
                terminal(
                    "time python importer.py --dry-run ~/cards/sample",
                    "imported 2104 photos\n\nreal    0m6.8s",
                ),
                plan(PROFILE to DONE, FIX to DONE, TESTS to DONE, MEASURE to DONE),
            ),
        ),
    )

    val sessions: List<Session> = listOf(
        Session(
            "demo-backup", "Why did last night's backup fail?", minutesAgo = 12,
            rows = listOf(
                Row.User("The NAS backup failed again last night. Can you find out why and fix it?"),
                Row.Agent(
                    thought = "Start with the backup job's own log, then look at the disk it writes to.",
                    calls = listOf(
                        terminal(
                            "journalctl -u restic-backup --since yesterday -n 20 --no-pager",
                            "restic[2211]: Fatal: unable to save snapshot: write /mnt/backup/data/7f: no space left on device",
                        ),
                    ),
                ),
                Row.Agent(
                    calls = listOf(
                        terminal(
                            "df -h /mnt/backup && restic snapshots --compact | tail -1",
                            "Filesystem  Size  Used Avail Use% Mounted on\n/dev/sdb1   3.6T  3.6T   12G 100% /mnt/backup\n431 snapshots",
                        ),
                    ),
                ),
                Row.Agent(
                    thought = "Nothing has ever been pruned. A normal retention policy frees the room without touching recent history.",
                    calls = listOf(
                        terminal(
                            "restic forget --keep-daily 7 --keep-weekly 8 --keep-monthly 12 --prune",
                            "removed 403 snapshots\nfreed 612.4 GiB",
                        ),
                        terminal(
                            "systemctl start restic-backup && systemctl show restic-backup -p Result",
                            "Result=success",
                        ),
                    ),
                ),
                Row.Agent(
                    "Fixed. The backup disk was **full**, 12 GB left of 3.6 TB, so last night's snapshot couldn't be written.\n\n" +
                        "- Nothing had ever been pruned: 431 snapshots.\n" +
                        "- I applied a retention policy, **7 daily, 8 weekly, 12 monthly**, which freed **612 GB**.\n" +
                        "- I ran the backup again by hand and it finished cleanly.\n\n" +
                        "Want me to add the prune to the nightly job so this can't come back?",
                ),
            ),
        ),
        Session(
            RUNNING, "Speed up the photo importer", minutesAgo = 1,
            rows = listOf(
                Row.User("The photo importer takes forever on a big card. Make it faster, and keep the tests passing."),
                Row.Agent(
                    thought = "Four steps. Profile first, so I fix the slow part and not a guess.",
                    calls = listOf(plan(PROFILE to NOW, FIX to LATER, TESTS to LATER, MEASURE to LATER)),
                ),
                Row.Agent(
                    calls = listOf(
                        terminal(
                            "python -m cProfile -s cumtime importer.py --dry-run ~/cards/sample | head -6",
                            "ncalls  cumtime  function\n  2104   41.2  importer.py:58(read_exif)\n  2104    3.1  importer.py:91(copy_file)",
                        ),
                    ),
                ),
                Row.Agent(
                    thought = "Every photo opens and parses its EXIF on its own. One batched read covers the whole card.",
                    calls = listOf(
                        Call(
                            "patch", """{"path":"importer.py","mode":"replace"}""",
                            """{"success":true,"diff":"-    for path in paths:\n-        tags[path] = read_exif(path)\n+    tags = read_exif_batch(paths)"}""",
                        ),
                        plan(PROFILE to DONE, FIX to DONE, TESTS to NOW, MEASURE to LATER),
                    ),
                ),
                Row.Agent(
                    "The fix is in: EXIF is read for the whole card at once now.\n\n" +
                        "The old thumbnail cache was built by the slow reader and would hide the change from the tests, " +
                        "so I'd like to clear it before I run them.",
                ),
            ),
            ask = Ask(
                command = "rm -rf ~/photos/.thumbcache",
                description = "recursive delete",
                allowed = listOf(
                    Row.Agent(
                        calls = listOf(
                            terminal("rm -rf ~/photos/.thumbcache", ""),
                            terminal("pytest -q", "......................................\n38 passed in 4.21s"),
                            plan(PROFILE to DONE, FIX to DONE, TESTS to DONE, MEASURE to NOW),
                        ),
                    ),
                ) + measured + importerDone(clearedCache = true),
                denied = listOf(
                    Row.Agent(
                        "Understood, the cache stays. I'll run the tests against a fresh, temporary one instead.",
                        calls = listOf(
                            terminal("THUMBCACHE=\$(mktemp -d) pytest -q", "......................................\n38 passed in 4.38s"),
                            plan(PROFILE to DONE, FIX to DONE, TESTS to DONE, MEASURE to NOW),
                        ),
                    ),
                ) + measured + importerDone(clearedCache = false),
            ),
        ),
        Session(
            "demo-brief", "Morning brief", minutesAgo = 6 * 60,
            rows = listOf(
                Row.User("Morning brief: weather, today's calendar, anything that broke overnight."),
                Row.Agent(
                    "**Good morning.**\n\n" +
                        "- **Weather:** clear and mild, rain after 6 pm. Bring the bikes in.\n" +
                        "- **Calendar:** dentist at 10:30, nothing after 3.\n" +
                        "- **Overnight:** the NAS backup failed (disk full). I've opened a session for it.\n" +
                        "- **Updates:** Hermes has a new version. Say the word and I'll update and check it.",
                ),
            ),
        ),
        Session(
            UNREAD, "Groceries for the week", minutesAgo = 26 * 60,
            rows = listOf(
                Row.User("Plan five easy dinners for the week and give me one grocery list, grouped by aisle."),
                Row.Agent(
                    "Five dinners, nothing over 30 minutes: tacos, a sheet-pan chicken, pasta with greens, fried rice, and soup night.\n\n" +
                        "**Produce:** onions, garlic, limes, cilantro, spinach, broccoli, lemons\n" +
                        "**Meat:** ground beef, chicken thighs\n" +
                        "**Dry goods:** tortillas, pasta, rice, stock, canned tomatoes\n" +
                        "**Dairy:** parmesan, sour cream, eggs\n\n" +
                        "I left off what you bought last week. Want it as a checklist in your notes?",
                ),
            ),
        ),
        Session(
            "demo-site", "Dark mode for the recipe site", minutesAgo = 3 * 24 * 60,
            rows = listOf(
                Row.User("Add a dark mode to the recipe site. It should follow the phone's setting."),
                Row.Agent(
                    calls = listOf(
                        Call(
                            "read_file", """{"path":"site/styles.css"}""",
                            """{"content":":root {\n  --bg: #fffdf8;\n  --ink: #2b2620;\n}","total_lines":214}""",
                        ),
                        Call(
                            "patch", """{"path":"site/styles.css","mode":"replace"}""",
                            """{"success":true,"diff":"+@media (prefers-color-scheme: dark) {\n+  :root { --bg: #1c1a17; --ink: #ece6dc; }\n+}"}""",
                        ),
                    ),
                ),
                Row.Agent("Done. The site follows the phone's light or dark setting now. Colors are in one place at the top of `styles.css` if you want to tune them."),
            ),
        ),
    )

    /** The drawer rows, newest first, stamped against [now]. */
    fun rooms(now: Long): List<RoomProfile> = sessions.map { s ->
        RoomProfile(
            id = s.id,
            name = s.title,
            type = RoomType.DIRECT_MESSAGE,
            timestamp = now - s.minutesAgo * MINUTE,
            messageCount = s.rows.size.toLong(),
            isActive = s.running,
            unread = s.unread,
            preview = s.rows.firstNotNullOfOrNull { (it as? Row.User)?.text }.orEmpty(),
        )
    }.sortedByDescending { it.timestamp }

    /** One row and when it happened. */
    data class Stamped(val row: Row, val at: Long)

    /** [sessionId]'s stored rows, a minute apart and ending on its last activity before [now]. */
    fun stamped(sessionId: String, now: Long): List<Stamped> {
        val s = sessions.firstOrNull { it.id == sessionId } ?: return emptyList()
        val end = now - s.minutesAgo * MINUTE
        val start = end - (s.rows.size - 1) * MINUTE
        return s.rows.mapIndexed { i, row -> Stamped(row, start + i * MINUTE) }
    }

    /** [sessionId]'s transcript, built the way a real gateway's is. */
    fun transcript(sessionId: String, now: Long): List<Message> = build(sessionId, stamped(sessionId, now))

    /**
     * [rows] drawn as a gateway's would be: stored rows through [TranscriptBuilder], then the
     * `MEDIA:` lines lifted out the way the direct door lifts them. Ids depend only on the
     * session and a row's place, so a transcript replayed one row longer keeps every id it had.
     */
    fun build(sessionId: String, rows: List<Stamped>): List<Message> {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0) return emptyList()
        // Row ids unique across the demo: the Archive keys what it remembers by them.
        var nextId = (index + 1) * 1000L
        val stored = buildList {
            for ((row, at) in rows) {
                when (row) {
                    is Row.User -> add(MessageRow(nextId++, "user", row.text, null, at, null))
                    is Row.Agent -> {
                        val rowId = nextId++
                        val calls = row.calls.mapIndexed { n, c -> RestToolCall("call-$rowId-$n", c.tool, c.argsJson) }
                        add(MessageRow(rowId, "assistant", row.text, null, at, row.thought, toolCalls = calls))
                        row.calls.forEachIndexed { n, c ->
                            add(MessageRow(nextId++, "tool", c.result, c.tool, at, null, toolCallId = calls[n].id))
                        }
                    }
                }
            }
        }
        return MediaTags.expand(TranscriptBuilder.build(sessionId, stored))
    }

    /** The plan the newest `todo_list` call in [rows] left, as the Flight Plan reads it. */
    fun planOf(rows: List<Row>): TodoPlan? = rows.asReversed().asSequence()
        .flatMap { (it as? Row.Agent)?.calls.orEmpty().asReversed() }
        .firstOrNull { TodoPlanParser.isTodoTool(it.tool) }
        ?.let { TodoPlanParser.parse(it.result) }

    /**
     * What the demo agent says to anything typed. It can't do the job, so it says what would:
     * the visitor's own Hermes, one tap away.
     */
    fun replyTo(@Suppress("UNUSED_PARAMETER") text: String): String =
        "This is the demo, so there's no agent on the other end yet. Connect your own Hermes gateway " +
            "and this is where it answers: it can run commands, search the web, write code, and keep " +
            "working after you put your phone away."

    private const val MINUTE = 60_000L

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }
}
