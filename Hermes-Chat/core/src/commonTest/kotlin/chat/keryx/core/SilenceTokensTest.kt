package chat.keryx.core

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.SilenceTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Same verdicts as the gateway's `is_intentional_silence_response`. */
class SilenceTokensTest {

    @Test fun exactMarkersAreSilenceWhateverTheCaseOrSpacing() {
        for (t in listOf("[SILENT]", "silent", "  NO_REPLY \n", "no   reply", "[静默]", "沉默")) {
            assertTrue(SilenceTokens.isSilent(t), t)
        }
    }

    @Test fun strayEdgePunctuationIsForgivenButBracketsStayStructural() {
        assertTrue(SilenceTokens.isSilent("*NO_REPLY*"))
        assertTrue(SilenceTokens.isSilent(".NO_REPLY."))
        assertFalse(SilenceTokens.isSilent("[SILENT"))
    }

    @Test fun proseBlankAndOversizedAreNeverSilence() {
        assertFalse(SilenceTokens.isSilent("Use [SILENT] when nothing changed"))
        assertFalse(SilenceTokens.isSilent(""))
        assertFalse(SilenceTokens.isSilent("   "))
        assertFalse(SilenceTokens.isSilent("SILENT ".repeat(20)))
    }

    @Test fun onlyAFinishedAgentReplyIsDropped() {
        fun msg(sender: SenderType, content: String, streaming: Boolean = false) =
            Message(id = "1", roomId = "r", content = content, sender = sender, timestamp = 0L, isStreaming = streaming)
        assertTrue(SilenceTokens.isSilentReply(msg(SenderType.HERMES, "[SILENT]")))
        assertFalse(SilenceTokens.isSilentReply(msg(SenderType.ME, "[SILENT]")))
        assertFalse(SilenceTokens.isSilentReply(msg(SenderType.HERMES, "NO_REPLY", streaming = true)))
    }

    private fun m(id: String, sender: SenderType, content: String) =
        Message(id = id, roomId = "r", content = content, sender = sender, timestamp = 0L)

    @Test fun aMachineTurnMayVanishButYourTurnKeepsItsAnswer() {
        val chrono = listOf(
            m("1", SenderType.ME, "Anything to add?"),
            m("2", SenderType.HERMES, "No reply."),
            m("3", SenderType.SYSTEM, "Message from Milo: status?"),
            m("4", SenderType.HERMES, "[SILENT]"),
            m("5", SenderType.ME, "And now?"),
            m("6", SenderType.HERMES, "Checking."),
            m("7", SenderType.HERMES, "SILENT"),
        )
        assertEquals(setOf("4"), SilenceTokens.vanishingIds(chrono))
    }

    @Test fun anUnknownTriggerKeepsTheWords() {
        val reply = m("9", SenderType.HERMES, "[SILENT]")
        assertTrue(SilenceTokens.vanishingIds(listOf(reply)).isEmpty())
        assertEquals(setOf("9"), SilenceTokens.vanishingIds(listOf(reply), before = m("8", SenderType.SYSTEM, "cron")))
        assertFalse(SilenceTokens.mayVanish(reply, trigger = m("8", SenderType.ME, "hi")))
    }
}
