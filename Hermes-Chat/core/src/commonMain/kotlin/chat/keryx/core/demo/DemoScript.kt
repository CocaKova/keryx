package chat.keryx.core.demo

import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RoomType
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

    /** The session the demo opens on. */
    const val FIRST = "demo-backup"

    /** A demo session: its drawer row and the rows its transcript is built from. */
    class Session(val id: String, val title: String, val minutesAgo: Long, val rows: List<Row>) {
        val unread: Boolean get() = id == UNREAD
        val running: Boolean get() = id == RUNNING
    }

    /** One stored row, timed in minutes before the session's last activity. */
    sealed interface Row {
        data class User(val text: String) : Row
        data class Agent(val text: String = "", val thought: String? = null, val calls: List<Call> = emptyList()) : Row
    }

    /** A tool call and what it returned, in the shape Hermes stores (`output` + `exit_code`, or `error`). */
    data class Call(val tool: String, val argsJson: String, val result: String)

    const val UNREAD = "demo-groceries"
    const val RUNNING = "demo-importer"

    private fun terminal(command: String, output: String) = Call(
        "terminal",
        """{"command":${quote(command)}}""",
        """{"output":${quote(output)},"exit_code":0,"error":null}""",
    )

    val sessions: List<Session> = listOf(
        Session(
            FIRST, "Why did last night's backup fail?", minutesAgo = 12,
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
                    thought = "Profile it first so I fix the slow part, not a guess.",
                    calls = listOf(
                        terminal(
                            "python -m cProfile -s cumtime importer.py --dry-run ~/cards/sample | head -6",
                            "ncalls  cumtime  function\n  2104   41.2  importer.py:58(read_exif)\n  2104    3.1  importer.py:91(copy_file)",
                        ),
                    ),
                ),
                Row.Agent("Almost all the time is reading EXIF one file at a time. I'm switching it to a batch read and running the tests now."),
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

    /** [sessionId]'s transcript, built the way a real gateway's is. */
    fun transcript(sessionId: String, now: Long): List<Message> {
        val index = sessions.indexOfFirst { it.id == sessionId }
        val s = sessions.getOrNull(index) ?: return emptyList()
        val end = now - s.minutesAgo * MINUTE
        // A minute between rows, ending on the session's last activity.
        val start = end - (s.rows.size - 1) * MINUTE
        // Row ids unique across the demo: the Archive keys what it remembers by them.
        var nextId = (index + 1) * 1000L
        val stored = buildList {
            s.rows.forEachIndexed { i, row ->
                val at = start + i * MINUTE
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
        return TranscriptBuilder.build(sessionId, stored)
    }

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
