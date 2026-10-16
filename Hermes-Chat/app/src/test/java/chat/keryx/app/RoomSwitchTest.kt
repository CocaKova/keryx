package chat.keryx.app

import chat.keryx.app.presentation.ChatViewModel
import chat.keryx.app.transport.direct.SessionStore
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crossing rooms (3.0, Jonny 10-16: "ghost chats ... it mixes into each other"). The list
 * follows a switch a beat late; in that beat it is the room being left, and a session still
 * on the wire is not an empty one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSwitchTest {

    private fun msg(id: String, room: String) = Message(id, room, SenderType.HERMES, "hi from $room", 1L)

    @Test
    fun `a timeline is shown only under its own room`() {
        val left = ChatViewModel.RoomTimeline("a", listOf(msg("1", "a")))
        assertEquals(left.messages, left.of("a"))
        // The new room is open, the list is still the old one's: nothing to draw yet.
        assertNull(left.of("b"))
        // A known-empty room is empty, not loading.
        assertEquals(emptyList<Message>(), ChatViewModel.RoomTimeline("b", emptyList()).of("b"))
        // Before any room was read.
        assertNull(ChatViewModel.RoomTimeline(null, emptyList()).of("b"))
    }

    @Test
    fun `a session reads as nothing until its transcript is known`() = runTest(UnconfinedTestDispatcher()) {
        val st = SessionStore("s1") { it }
        val seen = mutableListOf<List<Message>>()
        val job = launch { st.transcript().toList(seen) }
        // Opened, nothing painted, nothing from the wire: not an empty session.
        assertTrue(seen.isEmpty())
        assertFalse(st.known.value)
        // A page from the phone's cache counts.
        st.painted = true
        assertTrue(st.known.value)
        assertEquals(listOf(emptyList<Message>()), seen)
        job.cancel()
    }

    @Test
    fun `the wire's page, or its refusal, makes it known`() = runTest(UnconfinedTestDispatcher()) {
        val hydrated = SessionStore("s2") { it }
        hydrated.hydrated = true
        assertTrue(hydrated.known.value)

        // The wire failed: offline opens on what there is, which is an empty list.
        val refused = SessionStore("s3") { it }
        val seen = mutableListOf<List<Message>>()
        val job = launch { refused.transcript().toList(seen) }
        refused.known.value = true
        assertEquals(listOf(emptyList<Message>()), seen)
        job.cancel()
    }
}
