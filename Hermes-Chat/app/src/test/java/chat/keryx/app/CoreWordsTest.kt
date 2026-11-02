package chat.keryx.app

import chat.keryx.core.i18n.CoreWords
import chat.keryx.core.i18n.CountPhrase
import chat.keryx.core.i18n.Phrase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * :core writes its sentences in English and names each one ([Phrase], [CountPhrase]); the app
 * finds each a string resource ([KeryxCoreWords]). The two Englishes must say the same thing,
 * or a phone set to English reads different words than core's tests checked.
 */
class CoreWordsTest {

    @Test
    fun `every core phrase's English resource says what core says`() {
        for (p in Phrase.entries) {
            assertEquals(p.name, p.english, EnglishStrings.get(KeryxCoreWords.res(p)))
        }
    }

    @Test
    fun `every counted phrase's English plurals say what core says`() {
        for (p in CountPhrase.entries) {
            val id = KeryxCoreWords.plural(p)
            assertEquals(p.name, p.one, EnglishStrings.plural(id, 1))
            assertEquals(p.name, p.other, EnglishStrings.plural(id, 2))
        }
    }

    @Test
    fun `blanks fill by position, and a percent sign survives`() {
        assertEquals("b a 50%", CoreWords.fill("%2\$s %1\$s %3\$d%%", arrayOf<Any>("a", "b", 50)))
        assertEquals("x y", CoreWords.fill("%s %s", arrayOf<Any>("x", "y")))
        assertEquals("Room for about 3 more replies. Then.", CoreWords.n(CountPhrase.CTX_ROOM_THEN, 3, "Then."))
    }
}
