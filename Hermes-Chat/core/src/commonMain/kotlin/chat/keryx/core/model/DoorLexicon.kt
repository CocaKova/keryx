package chat.keryx.core.model

import chat.keryx.core.i18n.CoreWords
import chat.keryx.core.i18n.Phrase

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
        // Getters, not stored values (3.0): the words follow the app's language, which can
        // change while the process lives.
        val MATRIX: DoorLexicon get() = DoorLexicon(
            noun = CoreWords.t(Phrase.LEX_ROOM),
            plural = CoreWords.t(Phrase.LEX_ROOMS),
            pinnedHeader = CoreWords.t(Phrase.LEX_QUICK_ROOMS),
            listHeader = CoreWords.t(Phrase.LEX_ROOMS_HEADER),
            pinVerb = CoreWords.t(Phrase.LEX_PIN_QUICK),
            unpinVerb = CoreWords.t(Phrase.LEX_UNPIN_QUICK),
            pinHint = "",
            emptyList = CoreWords.t(Phrase.LEX_NO_ROOMS),
            emptyChat = CoreWords.t(Phrase.LEX_SELECT_ROOM),
            shareTitle = CoreWords.t(Phrase.LEX_SHARE_ROOM),
            openOneFirst = CoreWords.t(Phrase.LEX_OPEN_ROOM_QUICK),
        )

        val DIRECT: DoorLexicon get() = DoorLexicon(
            noun = CoreWords.t(Phrase.LEX_SESSION),
            plural = CoreWords.t(Phrase.LEX_SESSIONS),
            pinnedHeader = CoreWords.t(Phrase.LEX_PINNED),
            listHeader = CoreWords.t(Phrase.LEX_SESSIONS_HEADER),
            pinVerb = CoreWords.t(Phrase.LEX_PIN),
            unpinVerb = CoreWords.t(Phrase.LEX_UNPIN),
            pinHint = CoreWords.t(Phrase.LEX_PIN_HINT),
            emptyList = CoreWords.t(Phrase.LEX_NO_SESSIONS),
            emptyChat = CoreWords.t(Phrase.LEX_OPEN_SESSION),
            shareTitle = CoreWords.t(Phrase.LEX_SHARE_SESSION),
            openOneFirst = CoreWords.t(Phrase.LEX_OPEN_SESSION_QUICK),
        )

        fun forDoor(direct: Boolean): DoorLexicon = if (direct) DIRECT else MATRIX
    }
}
