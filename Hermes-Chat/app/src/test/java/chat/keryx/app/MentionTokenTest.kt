package chat.keryx.app

import chat.keryx.app.presentation.ui.components.MentionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionTokenTest {
    @Test fun tagUnderCaret() {
        val t = MentionToken.at("ask @the", 8)!!
        assertEquals(4, t.start); assertEquals(8, t.end); assertEquals("the", t.typed)
    }

    @Test fun bareAtOffersEveryone() {
        val t = MentionToken.at("hey @", 5)!!
        assertEquals("", t.typed)
    }

    @Test fun caretInsideTheWordStillCounts() {
        val t = MentionToken.at("@research now", 4)!!
        assertEquals(0, t.start); assertEquals(9, t.end); assertEquals("research", t.typed)
    }

    @Test fun notATag() {
        assertNull(MentionToken.at("mail jonny@example.com", 22))
        assertNull(MentionToken.at("plain words", 5))
        assertNull(MentionToken.at("done @theo, next", 10)) // punctuation ends the tag
        assertNull(MentionToken.at("", 0))
    }
}
