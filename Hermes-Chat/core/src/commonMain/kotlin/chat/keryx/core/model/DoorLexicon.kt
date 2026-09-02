package chat.keryx.core.model

/**
 * The words a door uses for its own things.
 *
 * Keryx has two doors and one drawer. On Matrix the rows are rooms — they have members, invites,
 * an avatar on the homeserver, and you leave them. On the direct door the rows are gateway
 * sessions — they are created, renamed, pinned on the gateway, and deleted. The chrome is shared,
 * so for a while it spoke Matrix at both doors ("Pin to Quick Rooms", "Select a room to begin"),
 * which on the direct door named a thing that does not exist.
 *
 * One value object, chosen once per process from the transport, threaded to every surface that
 * says the noun. Kept in :core so the choice is pure and testable, and so a third door someday
 * is one more constructor, not a sweep.
 */
data class DoorLexicon(
    /** "room" / "session". */
    val noun: String,
    /** "rooms" / "sessions". */
    val plural: String,
    /** Header over the pinned deck: Matrix keeps its "Quick Rooms" name; the direct door says what
     *  the gateway says. */
    val pinnedHeader: String,
    /** Header over the main list. */
    val listHeader: String,
    /** The long-press verb pair. */
    val pinVerb: String,
    val unpinVerb: String,
    /** One line under the pin verb on the direct door — the gateway's pin means "keep". Empty
     *  where a pin is only a shortcut. */
    val pinHint: String,
    /** Empty-drawer line. */
    val emptyList: String,
    /** Empty chat pane. */
    val emptyChat: String,
    /** Share-sheet subtitle. */
    val shareTitle: String,
    /** Hub quick actions need an open row. */
    val openOneFirst: String,
) {
    /** "Room" / "Session". */
    val nounTitle: String get() = noun.replaceFirstChar { it.uppercase() }

    companion object {
        val MATRIX = DoorLexicon(
            noun = "room",
            plural = "rooms",
            pinnedHeader = "Quick Rooms",
            listHeader = "Rooms",
            pinVerb = "Pin to Quick Rooms",
            unpinVerb = "Unpin from Quick Rooms",
            pinHint = "",
            emptyList = "No rooms yet",
            emptyChat = "Select a room to begin",
            shareTitle = "Share to a room",
            openOneFirst = "Open a room to use quick actions",
        )

        val DIRECT = DoorLexicon(
            noun = "session",
            plural = "sessions",
            pinnedHeader = "Pinned",
            listHeader = "Sessions",
            pinVerb = "Pin",
            unpinVerb = "Unpin",
            pinHint = "Kept on the gateway — never auto-archived",
            emptyList = "No sessions yet",
            emptyChat = "Open a session to begin",
            shareTitle = "Share to a session",
            openOneFirst = "Open a session to use quick actions",
        )

        fun forDoor(direct: Boolean): DoorLexicon = if (direct) DIRECT else MATRIX
    }
}
