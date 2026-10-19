package chat.keryx.core

import chat.keryx.core.model.AgentDelivery
import chat.keryx.core.model.AgentNotices
import chat.keryx.core.model.Heralds
import chat.keryx.core.model.MediaKind
import chat.keryx.core.model.Message
import chat.keryx.core.model.SenderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentNoticeTest {
    private fun msg(content: String, sender: SenderType = SenderType.HERMES, delivery: AgentDelivery? = null) =
        Message(id = "1", roomId = "r", sender = sender, content = content, timestamp = 1L, senderName = "Hermes", agentDelivery = delivery)

    @Test fun botChatSpeaksAsTheBot() {
        val n = AgentNotices.compose(msg("Done.\nMore"), conversation = "Bot Chat", botLabel = "Atlas", botHandle = "atlas")
        assertEquals("Atlas", n.speaker)
        assertEquals("Atlas", n.conversation)
        assertEquals("bot:atlas", n.speakerKey)
        assertEquals("Done.", n.line)
        assertFalse(n.relayed)
        assertEquals("${Heralds.SIGIL} Atlas", n.title)
    }

    @Test fun plainSessionKeepsTheSessionName() {
        val n = AgentNotices.compose(msg("hi"), conversation = "Inbox")
        assertEquals("Hermes", n.speaker)
        assertEquals("Inbox", n.conversation)
        assertEquals("${Heralds.SIGIL} Hermes · Inbox", n.title)
    }

    @Test fun deliveryMakesTheOtherAgentTheSpeaker() {
        val d = AgentDelivery.parse("Message from 🤖 Vega (@vega): Draft is ready, take a look.")!!
        val n = AgentNotices.compose(msg("", sender = SenderType.SYSTEM, delivery = d), "Bot Chat", botLabel = "Atlas", botHandle = "atlas")
        assertTrue(n.relayed)
        assertEquals("Vega", n.speaker)
        assertEquals("agent:vega", n.speakerKey)
        assertEquals("Draft is ready, take a look.", n.line)
        assertEquals("Vega → Atlas", n.title)
    }

    @Test fun lineStripsMarkersAndHandlesMedia() {
        assertEquals("🖼 Photo", AgentNotices.line("", MediaKind.IMAGE, ""))
        assertEquals("📎 notes.pdf", AgentNotices.line("", MediaKind.FILE, "notes.pdf"))
        assertEquals("New message", AgentNotices.line("   ", null, ""))
        val long = "x".repeat(400)
        assertEquals(160, AgentNotices.line(long, null, "").length)
    }
}
