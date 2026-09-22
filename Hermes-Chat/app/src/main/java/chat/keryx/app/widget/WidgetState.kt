package chat.keryx.app.widget

import chat.keryx.app.presentation.StreamHandoff
import chat.keryx.core.model.DelegationState
import chat.keryx.core.model.LinkState
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.RoomProfile
import chat.keryx.core.model.RunActivity
import chat.keryx.core.model.SenderType

/**
 * What the home-screen widget says (2.13 Part B), decided.
 *
 * The widget is a projection, not a producer: everything on it is a pure function of what the
 * process already holds — the link, the roster, one peeked tail, the runs in flight. This is
 * the pure half so the words can be pinned by a JVM test; the Glance side only draws it. A
 * widget is glanced at from across the room, so it says ONE thing per line and nothing that
 * needs a second look.
 */
data class WidgetState(
    val link: Link,
    /** What a tap opens; null opens the app on whatever it was showing. */
    val roomId: String?,
    /** The newest session's title, or the app's own name when there is none. */
    val title: String,
    /** The last reply, one or two lines of prose, or a stand-in when there is no prose. */
    val preview: String,
    /** The turn in flight in [roomId], or null when the agent is at rest. */
    val run: Run? = null,
) {
    /** The link, in the three words the widget knows. */
    enum class Link(val word: String) {
        LIVE("live"),
        RECONNECTING("reconnecting"),
        OFFLINE("offline"),
    }

    /** A turn in flight: how long, and how many helpers it has out. */
    data class Run(val sessionId: String, val elapsedSeconds: Long, val helpers: Int) {
        /** "3 helpers flying" outranks the clock: a fan-out is the more telling fact. */
        val line: String
            get() = when {
                helpers == 1 -> "1 helper flying"
                helpers > 1 -> "$helpers helpers flying"
                else -> "working · ${clock(elapsedSeconds)}"
            }
    }

    /** A tap lands inside the turn when there is one — the run notice's own rule (2.12). */
    val tapIn: Boolean get() = run != null && run.sessionId == roomId

    companion object {
        /** The preview is a glance, not a read; the launcher clips long before this anyway. */
        const val PREVIEW_MAX = 160

        /** Nothing to show yet: a fresh install, a signed-out door, a roster not yet answered. */
        fun empty(link: Link): WidgetState = WidgetState(
            link = link,
            roomId = null,
            title = "Keryx",
            preview = if (link == Link.LIVE) "Nothing said yet" else "Open Keryx to connect",
        )

        /**
         * The session the widget follows: the one with a turn in flight (the oldest run when
         * several are, matching the run notice's clock), else the newest row by activity. The
         * roster puts bot rows at its head regardless of age, so this is a timestamp pick,
         * not a head pick.
         */
        fun pick(rooms: List<RoomProfile>, runs: Map<String, RunActivity>): RoomProfile? {
            val running = runs.values.minByOrNull { it.startedAt }?.sessionId
            if (running != null) rooms.firstOrNull { it.id == running }?.let { return it }
            return rooms.maxByOrNull { it.timestamp }
        }

        /**
         * Project the inputs. [latest] is the peeked tail of [room] (newest last); wings still
         * flying ride its very last row on the direct door, which is where the helper count
         * comes from — a store never opened simply reports none, and the clock speaks instead.
         */
        fun from(
            link: LinkState?,
            room: RoomProfile?,
            latest: List<Message>,
            runs: Map<String, RunActivity>,
            now: Long,
        ): WidgetState {
            val state = when (link) {
                LinkState.CONNECTED -> Link.LIVE
                LinkState.CONNECTING -> Link.RECONNECTING
                else -> Link.OFFLINE
            }
            if (room == null) return empty(state)
            val run = runs[room.id]?.let { a ->
                Run(
                    sessionId = room.id,
                    elapsedSeconds = ((now - a.startedAt) / 1000L).coerceAtLeast(0L),
                    helpers = latest.lastOrNull()?.delegations
                        ?.count { it.state == DelegationState.RUNNING || it.state == DelegationState.SPAWNING }
                        ?: 0,
                )
            }
            // The wings row and a streaming ghost are not replies; the last thing SAID is.
            val spoken = latest.lastOrNull { it.delegations.isEmpty() && !it.isStreaming }
            return WidgetState(
                link = state,
                roomId = room.id,
                title = room.name.ifBlank { "Keryx" },
                preview = spoken?.let(::previewOf) ?: room.preview.ifBlank { "Nothing said yet" },
                run = run,
            )
        }

        /**
         * One line of a message, as the drawer previews it but without the drawer's glyphs:
         * prose only, chrome and markers stripped, "You:" on your own words, plain stand-ins
         * for media and for a turn that only ran tools.
         */
        fun previewOf(m: Message): String {
            val who = if (m.sender == SenderType.ME) "You: " else ""
            val body = when {
                m.mediaKind == MediaKind.IMAGE -> m.content.takeIf { it.isNotBlank() && it != m.fileName } ?: "Photo"
                m.mediaKind != null -> m.fileName.ifBlank { "Attachment" }
                else -> {
                    val prose = StreamHandoff.normalize(m.content).replace(WHITESPACE, " ").trim()
                    when {
                        prose.isNotBlank() -> prose
                        m.toolCalls.isNotEmpty() -> "Ran ${m.toolCalls.last().name}"
                        m.failure != null -> m.failure?.message?.ifBlank { null } ?: "The turn failed"
                        else -> "Thinking"
                    }
                }
            }
            return (who + body).take(PREVIEW_MAX)
        }

        /** "12s" under a minute, "4m" under an hour, "2h" past it — a glance, not a stopwatch. */
        fun clock(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
