package chat.keryx.app

import chat.keryx.app.transport.direct.SessionStore
import chat.keryx.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The words before a call, drawn twice (11-02 on device, first seen 10-26): Hermes saves the
 * assistant row and only THEN emits `message.interim` (already_streamed), so the card that
 * `tool.generating` put up for the call is already under the words when it lands. The
 * interim must adopt the sealed segment where it stands, not add a second copy below the card.
 */
class InterimAfterCallTest {

    private val words = "Alrighty. Building a self-contained three.js flight."

    private fun texts(st: SessionStore) =
        st.messages.value.map { it.content }.filter { it.isNotBlank() }

    @Test
    fun interimAfterTheCardDoesNotRepeatTheWords() {
        val st = SessionStore("s") { it }
        st.streamStart()
        st.streamDelta(words)
        st.toolGenerating("terminal")
        st.interim(words, alreadyStreamed = true)
        st.toolStart(ToolCall(toolId = "call_1", name = "terminal", context = "mkdir -p out"))
        assertEquals(1, texts(st).count { it == words })
    }

    @Test
    fun interimAfterToolStartAlsoAdopts() {
        val st = SessionStore("s") { it }
        st.streamStart()
        st.streamDelta(words)
        st.toolStart(ToolCall(toolId = "call_1", name = "terminal", context = "mkdir -p out"))
        st.interim(words, alreadyStreamed = true)
        assertEquals(1, texts(st).count { it == words })
    }

    @Test
    fun anInterimNotYetStreamedStillShows() {
        val st = SessionStore("s") { it }
        st.streamStart()
        st.toolStart(ToolCall(toolId = "call_1", name = "terminal", context = "ls"))
        st.interim(words, alreadyStreamed = false)
        assertEquals(1, texts(st).count { it == words })
    }
}
