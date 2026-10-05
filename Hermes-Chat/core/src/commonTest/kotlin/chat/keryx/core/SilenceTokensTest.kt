package chat.keryx.core

import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import chat.keryx.core.model.SilenceTokens
import kotlin.test.Test
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
}
