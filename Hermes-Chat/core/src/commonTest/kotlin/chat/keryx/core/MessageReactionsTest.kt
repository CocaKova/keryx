package chat.keryx.core

import chat.keryx.core.model.MessageReaction
import chat.keryx.core.model.MessageReactions
import chat.keryx.core.model.RawReaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gateway stores reactions per author (`display_metadata.reactions`, Tapback semantics);
 * the bubble renders them per emoji. This fold is the direct door's half of the transport-blind
 * reaction chip row — it must produce exactly the shape the Matrix side aggregates from
 * annotation events.
 */
class MessageReactionsTest {

    @Test
    fun `empty in, empty out`() {
        assertEquals(emptyList(), MessageReactions.aggregate(emptyList()))
    }

    @Test
    fun `one author, one chip, mine when the author is user`() {
        assertEquals(
            listOf(MessageReaction("👍", 1, true)),
            MessageReactions.aggregate(listOf(RawReaction("👍", "user"))),
        )
    }

    @Test
    fun `the agent's reaction is not mine`() {
        assertEquals(
            listOf(MessageReaction("❤️", 1, false)),
            MessageReactions.aggregate(listOf(RawReaction("❤️", "agent"))),
        )
    }

    @Test
    fun `same emoji from both authors folds into one chip that counts two and is mine`() {
        assertEquals(
            listOf(MessageReaction("🔥", 2, true)),
            MessageReactions.aggregate(
                listOf(RawReaction("🔥", "agent"), RawReaction("🔥", "user")),
            ),
        )
    }

    @Test
    fun `distinct emoji keep first-seen order`() {
        assertEquals(
            listOf(MessageReaction("👍", 1, false), MessageReaction("🎉", 1, true)),
            MessageReactions.aggregate(
                listOf(RawReaction("👍", "agent"), RawReaction("🎉", "user")),
            ),
        )
    }

    @Test
    fun `a blank emoji record is malformed data, not a chip`() {
        assertEquals(
            listOf(MessageReaction("👍", 1, true)),
            MessageReactions.aggregate(
                listOf(RawReaction("", "agent"), RawReaction("👍", "user")),
            ),
        )
    }

    @Test
    fun `self is a parameter, not a constant`() {
        assertEquals(
            listOf(MessageReaction("👍", 1, true)),
            MessageReactions.aggregate(listOf(RawReaction("👍", "agent")), self = "agent"),
        )
    }
}
